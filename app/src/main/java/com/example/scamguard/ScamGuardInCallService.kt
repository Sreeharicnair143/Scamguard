@file:Suppress("SpellCheckingInspection")

package com.example.scamguard

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

class ScamGuardInCallService : InCallService() {

    companion object {
        private const val TAG = "ScamGuardCallService"

        // High-importance channel for heads-up incoming call banner when user is in another app
        private const val CHANNEL_ID_INCOMING_HEADSUP = "scamguard_incoming_headsup_v4"
        // Silent, non-peeking channel used when full-screen call UI is already visible or during ongoing calls
        private const val CHANNEL_ID_ONGOING_SILENT = "scamguard_ongoing_silent_v4"
        private const val NOTIFICATION_ID_CALL = 41501

        const val EXTRA_OPEN_CALL_SCREEN = "com.example.scamguard.EXTRA_OPEN_CALL_SCREEN"

        private val trackedCalls = CopyOnWriteArrayList<Call>()
        private val callConnectTimestamps = ConcurrentHashMap<Call, Long>()
        private val smsRepliedCalls = ConcurrentHashMap<Call, Boolean>()
        private val answeredCallGuards = ConcurrentHashMap<Call, Boolean>()

        var currentCall: Call? = null
            private set

        var instance: ScamGuardInCallService? = null
            private set

        @Volatile
        var isMainActivityVisible: Boolean = false
            private set

        var isSpeakerOn: Boolean = false
            private set

        var isMutedState: Boolean = false
            private set

        var currentAudioRoute: Int = CallAudioState.ROUTE_EARPIECE
            private set

        // Call Recording State
        var isRecordingCall: Boolean = false
            private set

        var recordingStartTimeMs: Long = 0L
            private set

        private var activeRecorder: MediaRecorder? = null
        private var currentRecordingFile: File? = null
        private var currentRecordingPhone: String = ""
        private var currentRecordingCallerName: String = ""

        var onCallUiUpdate: ((
            state: Int,
            phoneNumber: String,
            contactName: String?,
            reputation: CallerReputation,
            isIncoming: Boolean,
            isSpeakerActive: Boolean,
            isMicMuted: Boolean,
            isCallOnHold: Boolean,
            connectTimeMillis: Long,
            callCount: Int,
            canMergeConference: Boolean,
            isRecording: Boolean
        ) -> Unit)? = null

        fun getCallCount(): Int = trackedCalls.size

        fun setMainActivityVisibility(visible: Boolean) {
            val changed = (isMainActivityVisible != visible)
            isMainActivityVisible = visible
            if (changed) {
                instance?.refreshCurrentCallPresentation()
            }
        }

        // Answers the incoming call exactly once
        fun answerCall(fromNotification: Boolean = false) {
            val targetCall = trackedCalls.firstOrNull { it.details.state == Call.STATE_RINGING } ?: currentCall
            if (targetCall != null && targetCall.details.state == Call.STATE_RINGING) {
                if (answeredCallGuards.putIfAbsent(targetCall, true) == null) {
                    targetCall.answer(VideoProfile.STATE_AUDIO_ONLY)
                }
            }
        }

        // Helper that forces the cellular/VoLTE modem to decline and disconnect the call
        private fun forceTerminateCall(context: Context?, targetCall: Call) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    targetCall.reject(Call.REJECT_REASON_DECLINED)
                }
            } catch (_: Exception) {}

            try {
                targetCall.reject(false, null)
            } catch (_: Exception) {}

            try {
                targetCall.disconnect()
            } catch (_: Exception) {}

            // Backup OS-level Telecom hangup if the modem ignored the first InCallService signal
            if (context != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    @Suppress("DEPRECATION")
                    tm?.endCall()
                } catch (_: Exception) {}
            }
        }

        // Rejects the incoming call or disconnects active call reliably
        fun rejectCall(fromNotification: Boolean = false) {
            val targetCall = trackedCalls.firstOrNull { it.details.state == Call.STATE_RINGING }
                ?: currentCall
                ?: trackedCalls.firstOrNull()

            if (targetCall != null) {
                forceTerminateCall(instance, targetCall)
            }
        }

        // Sends quick-reply SMS once and reliably terminates the incoming call
        fun rejectCallWithSms(
            context: Context,
            rawMessage: String,
            onResult: (Boolean, String) -> Unit
        ) {
            val message = rawMessage.trim()
            if (message.isEmpty()) {
                onResult(false, "Message cannot be empty.")
                return
            }

            val ringingCall = trackedCalls.firstOrNull { it.details.state == Call.STATE_RINGING }
                ?: currentCall
                ?: trackedCalls.firstOrNull()

            if (ringingCall == null) {
                onResult(false, "No incoming call to reply to.")
                return
            }

            if (smsRepliedCalls.putIfAbsent(ringingCall, true) == true) {
                forceTerminateCall(context, ringingCall)
                onResult(false, "Reply message already sent for this call.")
                return
            }

            val phoneNumber = ringingCall.details?.handle?.schemeSpecificPart.orEmpty()
            if (phoneNumber.isBlank()) {
                smsRepliedCalls.remove(ringingCall)
                onResult(false, "Caller phone number is unavailable.")
                return
            }

            val hasSmsPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasSmsPermission) {
                smsRepliedCalls.remove(ringingCall)
                onResult(false, "SEND_SMS_PERMISSION_REQUIRED")
                return
            }

            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                if (smsManager == null) {
                    smsRepliedCalls.remove(ringingCall)
                    onResult(false, "SMS service is unavailable on this device.")
                    return
                }

                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                }

                // 1. Terminate immediately
                forceTerminateCall(context, ringingCall)

                // 2. Retry at 200ms and 500ms in case the VoLTE/IMS modem was busy queueing the SMS
                val mainHandler = Handler(Looper.getMainLooper())
                val appCtx = context.applicationContext
                mainHandler.postDelayed({
                    if (trackedCalls.contains(ringingCall) &&
                        ringingCall.details.state != Call.STATE_DISCONNECTED
                    ) {
                        forceTerminateCall(appCtx, ringingCall)
                    }
                }, 200L)

                mainHandler.postDelayed({
                    if (trackedCalls.contains(ringingCall) &&
                        ringingCall.details.state != Call.STATE_DISCONNECTED
                    ) {
                        forceTerminateCall(appCtx, ringingCall)
                    }
                }, 500L)

                onResult(true, "SMS reply sent to $phoneNumber")
            } catch (e: Exception) {
                smsRepliedCalls.remove(ringingCall)
                Log.e(TAG, "Failed to send SMS reply: ${e.message}")
                onResult(false, "Failed to send SMS: ${e.localizedMessage ?: "Check network/SIM"}")
            }
        }

        fun endCall() {
            val targetCall = currentCall ?: trackedCalls.firstOrNull()
            if (targetCall != null) {
                forceTerminateCall(instance, targetCall)
            }
        }

        fun toggleSpeakerphone() {
            setSpeakerphone(!isSpeakerOn)
        }

        fun setSpeakerphone(enable: Boolean) {
            val svc = instance ?: return
            isSpeakerOn = enable
            val targetRoute = if (enable) {
                CallAudioState.ROUTE_SPEAKER
            } else {
                val supported = svc.callAudioState?.supportedRouteMask ?: 0
                if ((supported and CallAudioState.ROUTE_BLUETOOTH) != 0) {
                    CallAudioState.ROUTE_BLUETOOTH
                } else {
                    CallAudioState.ROUTE_WIRED_OR_EARPIECE
                }
            }
            @Suppress("DEPRECATION")
            svc.setAudioRoute(targetRoute)
            svc.refreshCurrentCallPresentation()
        }

        fun toggleMute() {
            setMutedState(!isMutedState)
        }

        fun setMutedState(mute: Boolean) {
            val svc = instance ?: return
            isMutedState = mute
            svc.setMuted(mute)
            svc.refreshCurrentCallPresentation()
        }

        fun toggleHold(hold: Boolean) {
            val call = currentCall ?: return
            if (hold && call.details.state == Call.STATE_ACTIVE) {
                call.hold()
            } else if (!hold && call.details.state == Call.STATE_HOLDING) {
                call.unhold()
            }
            instance?.refreshCurrentCallPresentation()
        }

        fun sendDtmfKey(digit: Char) {
            currentCall?.playDtmfTone(digit)
            currentCall?.stopDtmfTone()
        }

        fun getActiveCallConnectTimeMs(): Long {
            val call = currentCall ?: return 0L
            return callConnectTimestamps[call] ?: 0L
        }

        // =====================================================================
        // CONFERENCE CALL & MULTI-CALL SUPPORT
        // =====================================================================
        fun canMergeCalls(): Boolean {
            if (trackedCalls.size < 2) return false
            val hasUnanswered = trackedCalls.any {
                it.details.state == Call.STATE_RINGING ||
                        it.details.state == Call.STATE_DIALING ||
                        it.details.state == Call.STATE_CONNECTING
            }
            if (hasUnanswered) return false

            for (call in trackedCalls) {
                val caps = call.details.callCapabilities
                if ((caps and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) return true
                if (call.conferenceableCalls.isNotEmpty()) return true
            }
            return true
        }

        fun mergeActiveCalls(onResult: (Boolean, String) -> Unit) {
            if (trackedCalls.size < 2) {
                onResult(false, "Add a second call first before merging.")
                return
            }

            val ringingOrDialing = trackedCalls.any {
                it.details.state == Call.STATE_RINGING ||
                        it.details.state == Call.STATE_DIALING ||
                        it.details.state == Call.STATE_CONNECTING
            }
            if (ringingOrDialing) {
                onResult(false, "Wait for the second call to be answered before merging.")
                return
            }

            try {
                val primary = currentCall ?: trackedCalls[0]
                val secondary = trackedCalls.firstOrNull { it != primary }

                val caps = primary.details.callCapabilities
                when {
                    (caps and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0 -> {
                        primary.mergeConference()
                        onResult(true, "Merging calls into conference...")
                    }
                    primary.conferenceableCalls.isNotEmpty() -> {
                        primary.conference(primary.conferenceableCalls.first())
                        onResult(true, "Merging calls into conference...")
                    }
                    secondary != null -> {
                        primary.conference(secondary)
                        onResult(true, "Requested carrier conference merge...")
                    }
                    else -> {
                        onResult(false, "Conference calling is not supported by your carrier.")
                    }
                }
                instance?.refreshCurrentCallPresentation()
            } catch (e: Exception) {
                onResult(false, "Conference merge unavailable: ${e.localizedMessage ?: "Carrier restriction"}")
            }
        }

        // =====================================================================
        // VERIFIED CALL RECORDING ENGINE (SAVES ACTUAL M4A AUDIO + METADATA)
        // =====================================================================
        fun startCallRecording(context: Context, onResult: (Boolean, String) -> Unit) {
            if (isRecordingCall) {
                onResult(false, "Call recording is already active.")
                return
            }

            val active = currentCall
            if (active == null || active.details.state != Call.STATE_ACTIVE) {
                onResult(false, "Call must be active and answered before recording.")
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                onResult(false, "RECORD_AUDIO_PERMISSION_REQUIRED")
                return
            }

            val rawNumber = active.details?.handle?.schemeSpecificPart ?: "Unknown"
            val contactName = getContactName(context, rawNumber) ?: rawNumber
            val cleanNumber = rawNumber.filter { it.isLetterOrDigit() }.ifBlank { "Unknown" }
            val startTs = System.currentTimeMillis()
            val tempFile = File(
                ScamIntelligenceRepo.getRecordingsDir(context),
                "REC_${startTs}_0_${cleanNumber}.m4a"
            )

            val candidateSources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            var startedSuccess = false
            var lastError = ""

            for (source in candidateSources) {
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                try {
                    recorder.setAudioSource(source)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioChannels(1)
                    recorder.setAudioSamplingRate(44100)
                    recorder.setAudioEncodingBitRate(96000)
                    recorder.setOutputFile(tempFile.absolutePath)
                    recorder.prepare()
                    recorder.start()

                    activeRecorder = recorder
                    currentRecordingFile = tempFile
                    currentRecordingPhone = rawNumber
                    currentRecordingCallerName = contactName
                    recordingStartTimeMs = startTs
                    isRecordingCall = true
                    startedSuccess = true
                    break
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: e.javaClass.simpleName
                    try {
                        recorder.reset()
                        recorder.release()
                    } catch (_: Exception) {}
                }
            }

            if (startedSuccess) {
                instance?.refreshCurrentCallPresentation()
                onResult(true, "Recording started. Audio will be saved to Recents → Recordings.")
            } else {
                if (tempFile.exists()) tempFile.delete()
                onResult(
                    false,
                    "Call recording is restricted by this device/OS during cellular calls ($lastError)."
                )
            }
        }

        fun stopCallRecording(context: Context?, onResult: ((Boolean, String) -> Unit)? = null) {
            if (!isRecordingCall) return
            val recorder = activeRecorder
            val file = currentRecordingFile
            val elapsedMs = max(1000L, System.currentTimeMillis() - recordingStartTimeMs)
            val elapsedSec = max(1L, (elapsedMs + 500L) / 1000L)
            val cleanNumber = currentRecordingPhone.filter { it.isLetterOrDigit() }.ifBlank { "Unknown" }

            isRecordingCall = false
            activeRecorder = null
            currentRecordingFile = null

            var stoppedCleanly = false
            try {
                recorder?.stop()
                stoppedCleanly = true
            } catch (e: Exception) {
                Log.w(TAG, "Recorder stop error: ${e.message}")
            } finally {
                try {
                    recorder?.reset()
                    recorder?.release()
                } catch (_: Exception) {}
            }

            val appCtx = context ?: instance
            if (file != null && file.exists()) {
                if (stoppedCleanly && file.length() > 512L) {
                    val targetFile = File(
                        file.parentFile,
                        "REC_${recordingStartTimeMs}_${elapsedSec}_${cleanNumber}.m4a"
                    )
                    val finalFile = if (file.renameTo(targetFile)) targetFile else file

                    val probedMs = if (appCtx != null) {
                        ScamIntelligenceRepo.extractAudioDurationMs(finalFile)
                    } else {
                        0L
                    }
                    val finalDurationMs = if (probedMs > 0L) probedMs else elapsedMs

                    if (appCtx != null) {
                        ScamIntelligenceRepo.saveRecordingMetadata(
                            context = appCtx,
                            file = finalFile,
                            callerName = currentRecordingCallerName,
                            phoneNumber = currentRecordingPhone,
                            timestampMs = recordingStartTimeMs,
                            durationMs = finalDurationMs
                        )
                    }
                    onResult?.invoke(
                        true,
                        "Call recording saved (${max(1L, finalDurationMs / 1000L)}s) in Recents → Recordings."
                    )
                } else {
                    file.delete()
                    onResult?.invoke(
                        false,
                        "Device blocked audio stream during call; empty recording was discarded."
                    )
                }
            } else {
                onResult?.invoke(false, "Recording file could not be created.")
            }

            instance?.refreshCurrentCallPresentation()
        }

        fun getContactName(context: Context, phoneNumber: String): String? {
            if (phoneNumber.isBlank()) return null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun getContactEditUri(context: Context, phoneNumber: String): Uri? {
            if (phoneNumber.isBlank()) return null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(
                        ContactsContract.PhoneLookup.CONTACT_ID,
                        ContactsContract.PhoneLookup.LOOKUP_KEY
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val contactId = cursor.getLong(0)
                        val lookupKey = cursor.getString(1)
                        if (!lookupKey.isNullOrBlank()) {
                            ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                        } else {
                            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                        }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createCallNotificationChannels()
    }

    private fun isDeviceLockedOrScreenOff(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val isScreenOff = !pm.isInteractive
        val isLocked = km.isKeyguardLocked
        return isScreenOff || isLocked
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val rawNumber = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val reputation = ScamIntelligenceRepo.lookupNumber(this, rawNumber)
        val isIncoming = (call.details?.callDirection == Call.Details.DIRECTION_INCOMING) ||
                (call.details.state == Call.STATE_RINGING)

        if (reputation.isBlocked && isIncoming) {
            Log.w(TAG, "Auto-blocking blacklisted number: $rawNumber")
            call.reject(false, null)
            return
        }

        if (!trackedCalls.contains(call)) {
            trackedCalls.add(call)
        }
        call.registerCallback(callCallback)

        currentCall = selectPrimaryCall()
        if (call.details.state == Call.STATE_ACTIVE) {
            recordCallConnectTimeIfNeeded(call)
        }

        callAudioState?.let { syncAudioStateFromSystem(it) }

        val primary = currentCall ?: call

        if (!isIncoming) {
            launchInCallScreen(wakeScreen = false)
        } else if (isDeviceLockedOrScreenOff() || isMainActivityVisible) {
            launchInCallScreen(wakeScreen = true)
        }

        dispatchCallUpdate(primary, primary.details.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        trackedCalls.remove(call)
        callConnectTimestamps.remove(call)
        smsRepliedCalls.remove(call)
        answeredCallGuards.remove(call)

        if (trackedCalls.isNotEmpty()) {
            val nextCall = selectPrimaryCall()
            currentCall = nextCall
            if (nextCall != null) {
                dispatchCallUpdate(nextCall, nextCall.details.state)
                return
            }
        }

        if (isRecordingCall) {
            stopCallRecording(this)
        }
        currentCall = null
        isSpeakerOn = false
        isMutedState = false

        clearCallNotification()
        dispatchCallUpdate(call, Call.STATE_DISCONNECTED)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            syncAudioStateFromSystem(audioState)
            refreshCurrentCallPresentation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecordingCall) {
            stopCallRecording(this)
        }
        trackedCalls.clear()
        callConnectTimestamps.clear()
        smsRepliedCalls.clear()
        answeredCallGuards.clear()
        clearCallNotification()
        instance = null
    }

    private fun syncAudioStateFromSystem(audioState: CallAudioState) {
        isMutedState = audioState.isMuted
        currentAudioRoute = audioState.route
        isSpeakerOn = (audioState.route == CallAudioState.ROUTE_SPEAKER)
    }

    private fun selectPrimaryCall(): Call? {
        if (trackedCalls.isEmpty()) return null
        return trackedCalls.firstOrNull { it.details.state == Call.STATE_RINGING }
            ?: trackedCalls.firstOrNull {
                it.details.state == Call.STATE_DIALING || it.details.state == Call.STATE_CONNECTING
            }
            ?: trackedCalls.firstOrNull { it.details.state == Call.STATE_ACTIVE }
            ?: trackedCalls.firstOrNull { it.details.state == Call.STATE_HOLDING }
            ?: trackedCalls.firstOrNull()
    }

    private fun recordCallConnectTimeIfNeeded(call: Call) {
        if (!callConnectTimestamps.containsKey(call)) {
            val telecomConnectMs = call.details.connectTimeMillis
            val timestamp = if (telecomConnectMs > 0L) telecomConnectMs else System.currentTimeMillis()
            callConnectTimestamps[call] = timestamp
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            currentCall = selectPrimaryCall() ?: call

            if (state == Call.STATE_ACTIVE) {
                recordCallConnectTimeIfNeeded(call)
            } else if (state == Call.STATE_DISCONNECTED && trackedCalls.isEmpty()) {
                if (isRecordingCall) {
                    stopCallRecording(this@ScamGuardInCallService)
                }
            }

            val primary = currentCall ?: call
            dispatchCallUpdate(primary, primary.details.state)
        }
    }

    fun refreshCurrentCallPresentation() {
        val call = currentCall ?: selectPrimaryCall() ?: return
        dispatchCallUpdate(call, call.details.state)
    }

    private fun createCallNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val incomingHeadsUpChannel = NotificationChannel(
            CHANNEL_ID_INCOMING_HEADSUP,
            "Incoming Call Banner",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Heads-up incoming call banner when using other apps"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }

        val ongoingSilentChannel = NotificationChannel(
            CHANNEL_ID_ONGOING_SILENT,
            "Ongoing Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent ongoing call status in the notification shade"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        nm.createNotificationChannel(incomingHeadsUpChannel)
        nm.createNotificationChannel(ongoingSilentChannel)
    }

    private fun updateOrPublishCallNotification(
        call: Call,
        state: Int,
        phoneNumber: String,
        contactName: String?,
        reputation: CallerReputation
    ) {
        if (state == Call.STATE_DISCONNECTED && trackedCalls.isEmpty()) {
            clearCallNotification()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val displayName = when {
            !contactName.isNullOrBlank() -> contactName
            reputation.reportCount > 0 -> reputation.identifiedName
            phoneNumber.isNotBlank() && phoneNumber != "Unknown" -> phoneNumber
            else -> "Unknown Caller"
        }

        val stateLabel = when (state) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING -> "Calling..."
            Call.STATE_CONNECTING -> "Connecting..."
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING -> "Ending call..."
            Call.STATE_ACTIVE -> if (trackedCalls.size >= 2) "Active (${trackedCalls.size} calls)" else "Ongoing call"
            else -> "Ongoing call"
        }

        val contentText = if (displayName != phoneNumber && phoneNumber != "Unknown") {
            "$stateLabel • $phoneNumber"
        } else {
            stateLabel
        }

        val isKnownSpam = reputation.reportCount > 0 && contactName == null
        val avatarBitmap = generateCircularAvatar(
            displayName = contactName ?: displayName,
            isTrusted = contactName != null,
            isDanger = isKnownSpam
        )

        val callerPerson = Person.Builder()
            .setName(displayName)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap))
            .setImportant(true)
            .build()

        val openAppPendingIntent = createOpenCallScreenPendingIntent()
        val answerPendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_ANSWER_CALL, 101)
        val rejectPendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_REJECT_CALL, 102)
        val endCallPendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_END_CALL, 103)
        val speakerPendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_TOGGLE_SPEAKER, 104)
        val mutePendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_TOGGLE_MUTE, 105)
        val resumePendingIntent = createActionPendingIntent(CallNotificationActionReceiver.ACTION_RESUME_CALL, 107)

        val isIncomingRinging = (state == Call.STATE_RINGING)

        val shouldUseHeadsUpChannel = isIncomingRinging && !isMainActivityVisible
        val channelId = if (shouldUseHeadsUpChannel) CHANNEL_ID_INCOMING_HEADSUP else CHANNEL_ID_ONGOING_SILENT

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setLargeIcon(avatarBitmap)
            .setContentTitle(displayName)
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(
                if (shouldUseHeadsUpChannel) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW
            )

        val connectTimeMs = callConnectTimestamps[call] ?: 0L
        if (state == Call.STATE_ACTIVE && connectTimeMs > 0L) {
            builder.setWhen(connectTimeMs)
            builder.setUsesChronometer(true)
            builder.setShowWhen(true)
        } else {
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
        }

        when (state) {
            Call.STATE_RINGING -> {
                builder.setFullScreenIntent(openAppPendingIntent, true)
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        callerPerson,
                        rejectPendingIntent,
                        answerPendingIntent
                    )
                )
            }

            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                val speakerLabel = if (isSpeakerOn) "Speaker" else "Speaker"
                val speakerIcon = if (isSpeakerOn) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off

                builder.setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        callerPerson,
                        endCallPendingIntent
                    )
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        speakerIcon, speakerLabel, speakerPendingIntent
                    ).build()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_star_rate, "Open Call", openAppPendingIntent
                    ).build()
                )
            }

            Call.STATE_HOLDING -> {
                builder.setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        callerPerson,
                        endCallPendingIntent
                    )
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_call_answer, "Resume", resumePendingIntent
                    ).build()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_star_rate, "Open Call", openAppPendingIntent
                    ).build()
                )
            }

            else -> {
                val speakerLabel = if (isSpeakerOn) "Speaker" else "Speaker"
                val muteLabel = if (isMutedState) "Unmute" else "Mute"

                val speakerIcon = if (isSpeakerOn) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off
                val muteIcon = if (isMutedState) R.drawable.ic_mic_off else R.drawable.ic_mic_on

                builder.setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        callerPerson,
                        endCallPendingIntent
                    )
                )
                
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        speakerIcon, speakerLabel, speakerPendingIntent
                    ).build()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        muteIcon, muteLabel, mutePendingIntent
                    ).build()
                )
            }
        }

        val notification = builder.build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (isRecordingCall && hasMicPerm) {
                    fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID_CALL, notification, fgType)
            } else {
                startForeground(NOTIFICATION_ID_CALL, notification)
            }
        } catch (e: Exception) {
            try {
                nm.notify(NOTIFICATION_ID_CALL, notification)
            } catch (_: Exception) {}
        }
    }

    private fun clearCallNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID_CALL)
        } catch (_: Exception) {}
    }

    private fun createOpenCallScreenPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CALL_SCREEN, true)
        }
        return PendingIntent.getActivity(
            this,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallNotificationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun launchInCallScreen(wakeScreen: Boolean) {
        if (wakeScreen) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                        "scamguard:incall_wakelock"
                    )
                    wl.acquire(5000L)
                }
            } catch (_: Exception) {}
        }

        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(EXTRA_OPEN_CALL_SCREEN, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen launch fallback: ${e.message}")
        }
    }

    private fun generateCircularAvatar(
        displayName: String,
        isTrusted: Boolean,
        isDanger: Boolean
    ): Bitmap {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                isDanger -> Color.parseColor("#DC2626")
                isTrusted -> Color.parseColor("#16A34A")
                else -> Color.parseColor("#2563EB")
            }
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val initials = when {
            isDanger -> "!"
            else -> {
                val cleaned = displayName.trim().filter { it.isLetter() }.trim()
                if (cleaned.isNotEmpty()) cleaned.first().uppercaseChar().toString() else "#"
            }
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 58f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(initials, 0, initials.length, textBounds)
        val exactCenterY = radius - textBounds.exactCenterY()
        canvas.drawText(initials, radius, exactCenterY, textPaint)

        return bitmap
    }

    private fun dispatchCallUpdate(call: Call, state: Int) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val contactName = getContactName(this, number)
        val reputation = ScamIntelligenceRepo.lookupNumber(this, number)
        val isIncoming = (call.details?.callDirection == Call.Details.DIRECTION_INCOMING) ||
                (state == Call.STATE_RINGING)
        val isOnHold = (state == Call.STATE_HOLDING)
        val connectTimeMs = callConnectTimestamps[call] ?: 0L

        updateOrPublishCallNotification(
            call = call,
            state = state,
            phoneNumber = number,
            contactName = contactName,
            reputation = reputation
        )

        onCallUiUpdate?.invoke(
            state,
            number,
            contactName,
            reputation,
            isIncoming,
            isSpeakerOn,
            isMutedState,
            isOnHold,
            connectTimeMs,
            trackedCalls.size,
            canMergeCalls(),
            isRecordingCall
        )
    }
}