@file:Suppress("SpellCheckingInspection")

package com.example.scamguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.TelecomManager
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.scamguard.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class DialerRowItem(
    val title: String,
    val number: String,
    val subtitle: String,
    val badgeText: String,
    val badgeBgColor: Int,
    val badgeTextColor: Int,
    val avatarColor: Int,
    val isScamAlert: Boolean = false,
    val isFavorite: Boolean = false,
    val recordingItem: CallRecordingItem? = null
)

enum class RecentFilterType {
    ALL, MISSED, CONTACTS, NON_SPAM, SPAM, RECORDINGS
}

@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var dialedDigits = StringBuilder()
    private var dtmfDigitsTyped = StringBuilder()

    // Active tab: 1 = Keypad, 2 = Recents, 3 = Contacts, 4 = Scam ID Hub
    private var currentTab = 1
    private var currentRecentFilter = RecentFilterType.ALL
    private var selectedGoogleAccount: String? = null
    private var hasPromptedOnLaunch = false

    // True when user selected "Add Call" during an active call
    private var isAddingSecondCall = false

    // In-Call state flags synchronized with ScamGuardInCallService
    private var isSpeakerOn = false
    private var isMuted = false
    private var isOnHold = false
    private var isDtmfPadVisible = false
    private var isMoreButtonEnabled = false

    // Live Call & Recording Duration Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartTimeMs = 0L
    private var isCallTimerRunning = false

    // Interactive Audio Player for saved Call Recordings
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingPath: String? = null
    private var isPlaybackPaused: Boolean = false

    private val currentRowItems = mutableListOf<DialerRowItem>()
    private val shieldRowItems = mutableListOf<DialerRowItem>()

    private val avatarPalette = intArrayOf(
        Color.parseColor("#2563EB"),
        Color.parseColor("#7C3AED"),
        Color.parseColor("#0D9488"),
        Color.parseColor("#DB2777"),
        Color.parseColor("#D97706"),
        Color.parseColor("#0284C7"),
        Color.parseColor("#4F46E5")
    )

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        updateSetupBannerVisibility()
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "ScamGuard AI set as Default Phone App!", Toast.LENGTH_SHORT).show()
        }
        requestRuntimePermissionsIfNeeded()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateSetupBannerVisibility()
        refreshFavoritesStrip()
        if (currentTab == 2) loadRecentCallLogs()
        if (currentTab == 3) loadSavedContacts()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLockScreenCallPresentation()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure Reply with SMS never displays a ^ arrow even if XML cached
        binding.btnReplyWithSms.text = "💬   Reply with SMS"

        applyMaterialYouShapes()
        setupDialpadButtons()
        setupGlobalSearchAndFilter()
        setupRecentFilterChips()
        setupFavoritesStripActions()
        setupBottomNavigationTabs()
        setupShieldHubTab()
        setupInCallControls()

        binding.btnSetupPermissions.setOnClickListener {
            requestDefaultDialerRole(forcePrompt = true)
        }

        binding.bannerReturnToCall.setOnClickListener {
            isAddingSecondCall = false
            binding.bannerReturnToCall.visibility = View.GONE
            syncWithActiveCallIfPresent()
        }

        if (!hasPromptedOnLaunch) {
            hasPromptedOnLaunch = true
            autoCheckDefaultDialerOnLaunch()
        }

        intent?.data?.schemeSpecificPart?.let { incomingNumber ->
            dialedDigits.clear()
            dialedDigits.append(incomingNumber)
            updateDialedNumberDisplay()
        }

        ScamGuardInCallService.onCallUiUpdate = {
                state,
                number,
                contactName,
                reputation,
                isIncoming,
                speakerActive,
                micMuted,
                callOnHold,
                connectTimeMs,
                callCount,
                canMergeConference,
                isRecording ->
            runOnUiThread {
                if (state == Call.STATE_RINGING || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
                    isAddingSecondCall = false
                    binding.bannerReturnToCall.visibility = View.GONE
                }
                updateCallScreenUi(
                    state = state,
                    phoneNumber = number,
                    contactName = contactName,
                    reputation = reputation,
                    isIncoming = isIncoming,
                    speakerActive = speakerActive,
                    micMuted = micMuted,
                    callOnHold = callOnHold,
                    connectTimeMs = connectTimeMs,
                    callCount = callCount,
                    canMergeConference = canMergeConference,
                    isRecording = isRecording
                )
            }
        }

        refreshFavoritesStrip()
        syncWithActiveCallIfPresent()
    }

    private fun enableLockScreenCallPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        enableLockScreenCallPresentation()
        setIntent(intent)
        if (intent.getBooleanExtra(ScamGuardInCallService.EXTRA_OPEN_CALL_SCREEN, false)) {
            isAddingSecondCall = false
            binding.bannerReturnToCall.visibility = View.GONE
        }
        intent.data?.schemeSpecificPart?.let { incomingNumber ->
            dialedDigits.clear()
            dialedDigits.append(incomingNumber)
            updateDialedNumberDisplay()
        }
        ScamGuardInCallService.setMainActivityVisibility(true)
        syncWithActiveCallIfPresent()
    }

    override fun onStart() {
        super.onStart()
        ScamGuardInCallService.setMainActivityVisibility(true)
    }

    override fun onResume() {
        super.onResume()
        ScamGuardInCallService.setMainActivityVisibility(true)
        updateSetupBannerVisibility()
        refreshFavoritesStrip()
        syncWithActiveCallIfPresent()
    }

    override fun onStop() {
        super.onStop()
        ScamGuardInCallService.setMainActivityVisibility(false)
    }

    // =========================================================================
    // 1. MATERIAL YOU PROGRAMMATIC STYLING ENGINE
    // =========================================================================
    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun makeRoundedDrawable(
        fillColor: Int,
        radiusDp: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun makeRippleButtonDrawable(
        fillColor: Int,
        radiusDp: Int,
        rippleColor: Int = Color.parseColor("#33FFFFFF"),
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Int = 0
    ): RippleDrawable {
        val content = makeRoundedDrawable(fillColor, radiusDp, strokeColor, strokeWidthDp)
        val mask = makeRoundedDrawable(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun applyMaterialYouShapes() {
        binding.searchBarContainer.background = makeRoundedDrawable(
            Color.parseColor("#182032"),
            radiusDp = 28,
            strokeColor = Color.parseColor("#28354E"),
            strokeWidthDp = 1
        )
        binding.btnAccountBadge.background = makeRippleButtonDrawable(
            Color.parseColor("#1E293B"),
            radiusDp = 999,
            strokeColor = Color.parseColor("#334155"),
            strokeWidthDp = 1
        )

        binding.bannerPermissionsNeeded.background = makeRoundedDrawable(
            Color.parseColor("#13233F"),
            radiusDp = 16,
            strokeColor = Color.parseColor("#1D4ED8"),
            strokeWidthDp = 1
        )
        binding.btnSetupPermissions.background = makeRippleButtonDrawable(
            Color.parseColor("#38BDF8"),
            radiusDp = 999
        )

        binding.btnAddFavoriteQuick.background = makeRippleButtonDrawable(
            Color.parseColor("#172554"),
            radiusDp = 999
        )
        binding.btnFilterAccount.background = makeRippleButtonDrawable(
            Color.parseColor("#172554"),
            radiusDp = 999
        )
        binding.btnAddNewContact.background = makeRippleButtonDrawable(
            Color.parseColor("#064E3B"),
            radiusDp = 999
        )

        binding.cardCallerIdPreview.background = makeRoundedDrawable(
            Color.parseColor("#141C2B"),
            radiusDp = 18,
            strokeColor = Color.parseColor("#23304A"),
            strokeWidthDp = 1
        )
        binding.btnAddDialedToContact.background = makeRippleButtonDrawable(
            Color.parseColor("#1E293B"),
            radiusDp = 999
        )

        val keySpecs = listOf(
            Triple(binding.key1, "1", "➿"),
            Triple(binding.key2, "2", "ABC"),
            Triple(binding.key3, "3", "DEF"),
            Triple(binding.key4, "4", "GHI"),
            Triple(binding.key5, "5", "JKL"),
            Triple(binding.key6, "6", "MNO"),
            Triple(binding.key7, "7", "PQRS"),
            Triple(binding.key8, "8", "TUV"),
            Triple(binding.key9, "9", "WXYZ"),
            Triple(binding.keyStar, "＊", ""),
            Triple(binding.key0, "0", "＋"),
            Triple(binding.keyHash, "＃", "")
        )

        for ((keyView, mainDigit, subLetters) in keySpecs) {
            keyView.background = makeRippleButtonDrawable(
                Color.parseColor("#161E2E"),
                radiusDp = 32,
                strokeColor = Color.parseColor("#222E45"),
                strokeWidthDp = 1
            )
            if (subLetters.isNotEmpty()) {
                val full = "$mainDigit\n$subLetters"
                val spannable = SpannableString(full)
                spannable.setSpan(
                    RelativeSizeSpan(0.46f),
                    2,
                    full.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#8E9BAE")),
                    2,
                    full.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                keyView.text = spannable
            } else {
                keyView.text = mainDigit
            }
        }

        binding.btnQuickLookup.background = makeRippleButtonDrawable(
            Color.parseColor("#172554"),
            radiusDp = 999
        )
        binding.btnMakeCall.background = makeRippleButtonDrawable(
            Color.parseColor("#16A34A"),
            radiusDp = 999
        )
        binding.btnBackspace.background = makeRippleButtonDrawable(
            Color.parseColor("#182032"),
            radiusDp = 999
        )

        binding.tvShieldLookupResult.background = makeRoundedDrawable(
            Color.parseColor("#151D2C"),
            radiusDp = 18,
            strokeColor = Color.parseColor("#26344D"),
            strokeWidthDp = 1
        )
        binding.btnOpenReportDialog.background = makeRippleButtonDrawable(
            Color.parseColor("#DC2626"),
            radiusDp = 999
        )

        binding.tvCallerBadge.background = makeRoundedDrawable(
            Color.parseColor("#162238"),
            radiusDp = 999
        )
        binding.tvRecordingIndicator.background = makeRoundedDrawable(
            Color.parseColor("#450A0A"),
            radiusDp = 999,
            strokeColor = Color.parseColor("#EF4444"),
            strokeWidthDp = 1
        )
        binding.btnMergeCalls.background = makeRippleButtonDrawable(
            Color.parseColor("#2563EB"),
            radiusDp = 999
        )
        binding.layoutInCallDtmfPad.background = makeRoundedDrawable(
            Color.parseColor("#131B2B"),
            radiusDp = 22,
            strokeColor = Color.parseColor("#283652"),
            strokeWidthDp = 1
        )

        // Incoming-Call & Active-Call Vector Handset Buttons
        binding.btnReplyWithSms.background = makeRippleButtonDrawable(
            Color.parseColor("#182235"),
            radiusDp = 999,
            strokeColor = Color.parseColor("#2E3E5C"),
            strokeWidthDp = 1
        )
        binding.btnDeclineIncoming.background = makeRippleButtonDrawable(
            Color.parseColor("#DC2626"),
            radiusDp = 999
        )
        binding.btnAnswerIncoming.background = makeRippleButtonDrawable(
            Color.parseColor("#16A34A"),
            radiusDp = 999
        )
        binding.btnEndCall.background = makeRippleButtonDrawable(
            Color.parseColor("#DC2626"),
            radiusDp = 999
        )

        highlightActiveNavPill(1)
        updateRecentFilterChipStyles()
    }

    // =========================================================================
    // 2. STARTUP DEFAULT DIALER & PERMISSION ONBOARDING
    // =========================================================================
    private fun isDefaultDialerHeld(): Boolean {
        val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
        return roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    private fun getMissingRuntimePermissions(): List<String> {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateSetupBannerVisibility() {
        val isReady = isDefaultDialerHeld() && getMissingRuntimePermissions().isEmpty()
        binding.bannerPermissionsNeeded.visibility = if (isReady) View.GONE else View.VISIBLE
    }

    private fun autoCheckDefaultDialerOnLaunch() {
        updateSetupBannerVisibility()
        if (!isDefaultDialerHeld()) {
            requestDefaultDialerRole(forcePrompt = false)
        } else {
            requestRuntimePermissionsIfNeeded()
        }
    }

    private fun requestDefaultDialerRole(forcePrompt: Boolean) {
        val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else {
                requestRuntimePermissionsIfNeeded()
                if (forcePrompt && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri()
                    )
                    startActivity(intent)
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = getMissingRuntimePermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            updateSetupBannerVisibility()
        }
    }

    // =========================================================================
    // 3. FAVORITES STRIP & SEARCHABLE 'MANAGE FAVORITES' SHEET
    // =========================================================================
    private fun setupFavoritesStripActions() {
        binding.btnAddFavoriteQuick.setOnClickListener {
            showAddFavoritePickerDialog()
        }
    }

    private fun getCombinedFavorites(): List<FavoriteContact> {
        val combined = mutableListOf<FavoriteContact>()
        val seenNorm = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val excludedSystemSet = ScamIntelligenceRepo.getExcludedSystemStarred(this)

        for (fav in ScamIntelligenceRepo.getFavorites(this)) {
            val nameKey = fav.name.trim().lowercase()
            if (fav.normalizedNumber.isNotEmpty() &&
                seenNorm.add(fav.normalizedNumber) &&
                seenNames.add(nameKey)
            ) {
                combined.add(fav)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.STARRED
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1",
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0)?.trim() ?: continue
                        val num = it.getString(1)?.trim() ?: continue
                        val norm = ScamIntelligenceRepo.normalizeNumber(num)
                        val nameKey = name.lowercase()
                        if (norm.length >= 3 &&
                            !excludedSystemSet.contains(norm) &&
                            seenNorm.add(norm) &&
                            seenNames.add(nameKey)
                        ) {
                            combined.add(FavoriteContact(name, num, norm))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return combined
    }

    private fun isContactFavorited(rawNumber: String): Boolean {
        val norm = ScamIntelligenceRepo.normalizeNumber(rawNumber)
        if (norm.isEmpty()) return false
        return getCombinedFavorites().any { it.normalizedNumber == norm }
    }

    private fun toggleContactFavorite(name: String, rawNumber: String): Boolean {
        val currentlyFav = isContactFavorited(rawNumber)
        val newFavState = !currentlyFav
        ScamIntelligenceRepo.setFavoriteState(this, name, rawNumber, newFavState)
        return newFavState
    }

    private fun refreshFavoritesStrip() {
        val container = binding.containerFavoritesHorizontal
        container.removeAllViews()

        val favorites = getCombinedFavorites()
        if (favorites.isEmpty()) {
            val emptyHint = TextView(this).apply {
                text = "Tap '＋ Add' to pin favorite contacts for quick calling"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                setPadding(dp(4), dp(8), dp(8), dp(8))
            }
            container.addView(emptyHint)
            return
        }

        for (fav in favorites) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val initial = fav.name.trim()
                .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "★"
            val avatar = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                gravity = Gravity.CENTER
                text = initial
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                background = makeRippleButtonDrawable(
                    pickAvatarColor(fav.name),
                    radiusDp = 999,
                    strokeColor = Color.parseColor("#33FFFFFF"),
                    strokeWidthDp = 1
                )
            }

            val nameTv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
                gravity = Gravity.CENTER
                text = fav.name
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            itemLayout.addView(avatar)
            itemLayout.addView(nameTv)

            itemLayout.setOnClickListener {
                placeOutgoingCall(fav.number)
            }
            itemLayout.setOnLongClickListener {
                showNumberActionSheet(fav.number, fav.name)
                true
            }

            container.addView(itemLayout)
        }
    }

    private fun showAddFavoritePickerDialog() {
        val allContacts = mutableListOf<FavoriteContact>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val seenNorm = mutableSetOf<String>()
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val num = it.getString(1) ?: continue
                    val norm = ScamIntelligenceRepo.normalizeNumber(num)
                    if (norm.length >= 3 && seenNorm.add(norm)) {
                        allContacts.add(FavoriteContact(name, num, norm))
                    }
                }
            }
        }

        if (allContacts.isEmpty()) {
            Toast.makeText(this, "No contacts available.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#111726"),
                radiusDp = 28,
                strokeColor = Color.parseColor("#24324B"),
                strokeWidthDp = 1
            )
        }

        val dragHandle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = makeRoundedDrawable(Color.parseColor("#334155"), radiusDp = 999)
        }
        rootSheet.addView(dragHandle)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        val tvSheetTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Manage Favorites"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
        }
        val btnDone = TextView(this).apply {
            text = "Done"
            setTextColor(Color.parseColor("#60A5FA"))
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = makeRippleButtonDrawable(Color.parseColor("#172554"), radiusDp = 999)
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(tvSheetTitle)
        headerRow.addView(btnDone)
        rootSheet.addView(headerRow)

        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(14), 0, dp(10), 0)
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#182135"),
                radiusDp = 24,
                strokeColor = Color.parseColor("#2A3957"),
                strokeWidthDp = 1
            )
        }
        val searchIcon = TextView(this).apply {
            text = "🔍"
            textSize = 14f
            setPadding(0, 0, dp(8), 0)
        }
        val etSearchFav = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            background = null
            hint = "Search contacts by name or number"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14.5f
            isSingleLine = true
        }
        val btnClearFavSearch = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            gravity = Gravity.CENTER
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            visibility = View.GONE
            setOnClickListener { etSearchFav.setText("") }
        }
        searchPill.addView(searchIcon)
        searchPill.addView(etSearchFav)
        searchPill.addView(btnClearFavSearch)
        rootSheet.addView(searchPill)

        val tvEmptyState = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(36), dp(16), dp(36))
            text = "No contacts match your search"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 14.5f
            visibility = View.GONE
        }
        rootSheet.addView(tvEmptyState)

        val filteredContacts = mutableListOf<FavoriteContact>().apply { addAll(allContacts) }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.56).toInt()
            )
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6)
            isVerticalScrollBarEnabled = false
        }

        val favPickerAdapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredContacts.size
            override fun getItem(position: Int): Any = filteredContacts[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val contact = filteredContacts[position]
                val isFav = isContactFavorited(contact.number)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = makeRippleButtonDrawable(
                        fillColor = if (isFav) Color.parseColor("#16233B") else Color.parseColor("#151C2B"),
                        radiusDp = 18,
                        strokeColor = if (isFav) Color.parseColor("#2563EB") else Color.parseColor("#1E293B"),
                        strokeWidthDp = 1
                    )
                }

                val initial = contact.name.trim()
                    .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
                val avatar = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        marginEnd = dp(12)
                    }
                    gravity = Gravity.CENTER
                    text = initial
                    setTextColor(Color.WHITE)
                    textSize = 16.5f
                    setTypeface(null, Typeface.BOLD)
                    background = makeRoundedDrawable(pickAvatarColor(contact.name), radiusDp = 999)
                }

                val infoCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvName = TextView(this@MainActivity).apply {
                    text = contact.name
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val tvPhone = TextView(this@MainActivity).apply {
                    text = contact.number
                    setTextColor(Color.parseColor("#8E9BAE"))
                    textSize = 12.5f
                    setPadding(0, dp(2), 0, 0)
                }
                infoCol.addView(tvName)
                infoCol.addView(tvPhone)

                val starAction = TextView(this@MainActivity).apply {
                    text = if (isFav) "★ Favorited" else "☆ Favorite"
                    setTextColor(Color.parseColor(if (isFav) "#FACC15" else "#93C5FD"))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = makeRoundedDrawable(
                        fillColor = Color.parseColor(if (isFav) "#1E3A8A" else "#1E293B"),
                        radiusDp = 999
                    )
                }

                row.addView(avatar)
                row.addView(infoCol)
                row.addView(starAction)

                row.setOnClickListener {
                    val nowFav = toggleContactFavorite(contact.name, contact.number)
                    notifyDataSetChanged()
                    refreshFavoritesStrip()
                    if (currentTab == 2) loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
                    if (currentTab == 3) loadSavedContacts(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
                    val feedback = if (nowFav) "Added ${contact.name} to Favorites" else "Removed ${contact.name} from Favorites"
                    Toast.makeText(this@MainActivity, feedback, Toast.LENGTH_SHORT).show()
                }

                return row
            }
        }

        listView.adapter = favPickerAdapter
        rootSheet.addView(listView)

        etSearchFav.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                btnClearFavSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                filteredContacts.clear()
                if (q.isEmpty()) {
                    filteredContacts.addAll(allContacts)
                } else {
                    for (c in allContacts) {
                        if (c.name.contains(q, ignoreCase = true) || c.number.contains(q)) {
                            filteredContacts.add(c)
                        }
                    }
                }
                tvEmptyState.visibility = if (filteredContacts.isEmpty()) View.VISIBLE else View.GONE
                listView.visibility = if (filteredContacts.isEmpty()) View.GONE else View.VISIBLE
                favPickerAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.setContentView(rootSheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(14) }
        }
        dialog.show()
    }

    // =========================================================================
    // 4. RECENT CALL FILTERS (All | Missed | Contacts | Non-Spam | Spam | Recordings)
    // =========================================================================
    private fun setupRecentFilterChips() {
        val chipMap = mapOf(
            binding.chipFilterAll to RecentFilterType.ALL,
            binding.chipFilterMissed to RecentFilterType.MISSED,
            binding.chipFilterContacts to RecentFilterType.CONTACTS,
            binding.chipFilterNonSpam to RecentFilterType.NON_SPAM,
            binding.chipFilterSpam to RecentFilterType.SPAM,
            binding.chipFilterRecordings to RecentFilterType.RECORDINGS
        )

        for ((chipView, filterType) in chipMap) {
            chipView.setOnClickListener {
                currentRecentFilter = filterType
                updateRecentFilterChipStyles()
                loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
            }
        }
    }

    private fun updateRecentFilterChipStyles() {
        val chipMap = listOf(
            binding.chipFilterAll to RecentFilterType.ALL,
            binding.chipFilterMissed to RecentFilterType.MISSED,
            binding.chipFilterContacts to RecentFilterType.CONTACTS,
            binding.chipFilterNonSpam to RecentFilterType.NON_SPAM,
            binding.chipFilterSpam to RecentFilterType.SPAM,
            binding.chipFilterRecordings to RecentFilterType.RECORDINGS
        )

        for ((chip, type) in chipMap) {
            val isSelected = (currentRecentFilter == type)
            chip.background = makeRippleButtonDrawable(
                fillColor = Color.parseColor(if (isSelected) "#2563EB" else "#161F30"),
                radiusDp = 999,
                strokeColor = Color.parseColor(if (isSelected) "#60A5FA" else "#26344D"),
                strokeWidthDp = 1
            )
            chip.setTextColor(Color.parseColor(if (isSelected) "#FFFFFF" else "#94A3B8"))
        }
    }

    // =========================================================================
    // 5. T9 KEYPAD & OUTGOING CALL LOGIC
    // =========================================================================
    private fun setupDialpadButtons() {
        val keyMap = mapOf(
            binding.key0 to "0", binding.key1 to "1", binding.key2 to "2",
            binding.key3 to "3", binding.key4 to "4", binding.key5 to "5",
            binding.key6 to "6", binding.key7 to "7", binding.key8 to "8",
            binding.key9 to "9", binding.keyStar to "*", binding.keyHash to "#"
        )

        for ((view, digit) in keyMap) {
            view.setOnClickListener {
                dialedDigits.append(digit)
                updateDialedNumberDisplay()
            }
        }

        binding.key0.setOnLongClickListener {
            dialedDigits.append("+")
            updateDialedNumberDisplay()
            true
        }

        binding.btnBackspace.setOnClickListener {
            if (dialedDigits.isNotEmpty()) {
                dialedDigits.deleteCharAt(dialedDigits.length - 1)
                updateDialedNumberDisplay()
            }
        }
        binding.btnBackspace.setOnLongClickListener {
            dialedDigits.clear()
            updateDialedNumberDisplay()
            true
        }

        binding.btnAddDialedToContact.setOnClickListener {
            val num = dialedDigits.toString().trim()
            if (num.isNotEmpty()) {
                openSystemAddContactIntent(num)
            }
        }

        binding.btnQuickLookup.setOnClickListener {
            val num = dialedDigits.toString().trim()
            if (num.isEmpty()) {
                switchToTab(4)
            } else {
                showNumberActionSheet(num, ScamGuardInCallService.getContactName(this, num))
            }
        }

        binding.btnMakeCall.setOnClickListener {
            val numberToCall = dialedDigits.toString().trim()
            if (numberToCall.isEmpty()) {
                Toast.makeText(this, "Enter a phone number to call", Toast.LENGTH_SHORT).show()
            } else {
                placeOutgoingCall(numberToCall)
            }
        }
    }

    private fun updateDialedNumberDisplay() {
        val numberStr = dialedDigits.toString()
        binding.tvDialedNumber.text = numberStr

        binding.btnAddDialedToContact.visibility =
            if (numberStr.isNotEmpty()) View.VISIBLE else View.GONE

        if (numberStr.length >= 3) {
            val savedName = ScamGuardInCallService.getContactName(this, numberStr)
            if (savedName != null) {
                binding.tvMatchedContact.text = "$savedName  •  Saved Contact"
                binding.tvMatchedContact.setTextColor(Color.parseColor("#4ADE80"))
                binding.btnAddDialedToContact.visibility = View.GONE
            } else {
                val rep = ScamIntelligenceRepo.lookupNumber(this, numberStr)
                if (rep.reportCount > 0) {
                    binding.tvMatchedContact.text =
                        "Reported Spam: ${rep.identifiedName} (${rep.reportCount} reports)"
                    binding.tvMatchedContact.setTextColor(Color.parseColor("#FCA5A5"))
                } else {
                    binding.tvMatchedContact.text = "Tap ID to inspect number or Call"
                    binding.tvMatchedContact.setTextColor(Color.parseColor("#A8C7FA"))
                }
            }
        } else {
            binding.tvMatchedContact.text = "Enter a number to call or check Caller ID"
            binding.tvMatchedContact.setTextColor(Color.parseColor("#A8C7FA"))
        }
    }

    private fun placeOutgoingCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissionsIfNeeded()
            return
        }
        try {
            isAddingSecondCall = false
            binding.bannerReturnToCall.visibility = View.GONE
            val uri = "tel:${Uri.encode(phoneNumber)}".toUri()
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecomManager.placeCall(uri, Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to place call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // 6. GLOBAL SEARCH PILL & ACCOUNT FILTERING
    // =========================================================================
    private fun setupGlobalSearchAndFilter() {
        binding.etGlobalSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                if (query.isNotEmpty()) {
                    when (currentTab) {
                        1 -> switchToTab(3)
                        2 -> loadRecentCallLogs(query)
                        3 -> loadSavedContacts(query)
                        4 -> {
                            val rep = ScamIntelligenceRepo.lookupNumber(this@MainActivity, query)
                            val statusStr = if (rep.isBlocked) "Blocked" else "Active"
                            binding.tvShieldLookupResult.text =
                                "Caller ID Lookup for $query\n" +
                                        "• Identity: ${rep.identifiedName}\n" +
                                        "• Category: ${rep.category}\n" +
                                        "• Community Reports: ${rep.reportCount}\n" +
                                        "• Status: $statusStr"
                        }
                    }
                } else {
                    if (currentTab == 2) loadRecentCallLogs("")
                    if (currentTab == 3) loadSavedContacts("")
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etGlobalSearch.setText("")
        }

        binding.btnAccountBadge.setOnClickListener {
            switchToTab(3)
            showGoogleAccountFilterDialog()
        }

        binding.btnFilterAccount.setOnClickListener {
            showGoogleAccountFilterDialog()
        }

        binding.btnAddNewContact.setOnClickListener {
            openSystemAddContactIntent("")
        }
    }

    private fun showGoogleAccountFilterDialog() {
        val accounts = getSyncedContactAccounts()
        val options = mutableListOf("All Contacts")
        options.addAll(accounts)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#131B2E"),
                radiusDp = 24,
                strokeColor = Color.parseColor("#263554"),
                strokeWidthDp = 1
            )
        }

        val tvTitle = TextView(this).apply {
            text = "Filter Contacts by Account"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        sheet.addView(tvTitle)

        for (index in options.indices) {
            val accLabel = options[index]
            val isCurrent = (index == 0 && selectedGoogleAccount == null) ||
                    (accLabel.equals(selectedGoogleAccount, ignoreCase = true))

            val row = TextView(this).apply {
                text = if (isCurrent) "✓   $accLabel" else "      $accLabel"
                setTextColor(Color.parseColor(if (isCurrent) "#60A5FA" else "#E2E8F0"))
                textSize = 15f
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 14)
                setOnClickListener {
                    dialog.dismiss()
                    if (index == 0) {
                        selectedGoogleAccount = null
                        binding.btnFilterAccount.text = "All Contacts ▾"
                        binding.btnAccountBadge.text = "All"
                    } else {
                        selectedGoogleAccount = accLabel
                        binding.btnFilterAccount.text = "$accLabel ▾"
                        binding.btnAccountBadge.text = accLabel.first().uppercaseChar().toString()
                    }
                    loadSavedContacts(binding.etGlobalSearch.text.toString().trim())
                }
            }
            sheet.addView(row)
        }

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun getSyncedContactAccounts(): List<String> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val accountSet = linkedSetOf<String>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            ),
            null,
            null,
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val accName = it.getString(0) ?: continue
                val accType = it.getString(1) ?: ""
                if (accType.contains("google", ignoreCase = true) || accName.contains("@")) {
                    accountSet.add(accName)
                }
            }
        }
        return accountSet.toList()
    }

    private fun openSystemAddContactIntent(prefillNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                if (prefillNumber.isNotEmpty()) {
                    putExtra(ContactsContract.Intents.Insert.PHONE, prefillNumber)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Contacts app is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // Opens the existing saved contact's Edit screen without creating duplicate contacts
    private fun openEditContactIntent(contactUri: Uri) {
        try {
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                data = contactUri
                putExtra("finishActivityOnSaveCompleted", true)
            }
            startActivity(editIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to open Contact Editor on this device.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =========================================================================
    // 7. BOTTOM NAVIGATION TABS & CLEAN CONTACTS / RECENTS LISTS
    // =========================================================================
    private fun setupBottomNavigationTabs() {
        binding.navTabKeypad.setOnClickListener { switchToTab(1) }
        binding.navTabRecents.setOnClickListener { switchToTab(2) }
        binding.navTabContacts.setOnClickListener { switchToTab(3) }
        binding.navTabShield.setOnClickListener { switchToTab(4) }

        binding.listViewItems.setOnItemClickListener { _, _, position, _ ->
            val item = currentRowItems.getOrNull(position) ?: return@setOnItemClickListener
            if (item.recordingItem != null) {
                showRecordingPlayerSheet(item)
            } else if (item.number.isNotBlank()) {
                showNumberActionSheet(item.number, item.title)
            }
        }
    }

    private fun switchToTab(tabIndex: Int) {
        currentTab = tabIndex
        binding.layoutKeypadContainer.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.layoutListContainer.visibility = if (tabIndex == 2 || tabIndex == 3) View.VISIBLE else View.GONE
        binding.layoutShieldContainer.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE

        binding.scrollRecentsFilters.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.layoutGoogleFilterBar.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        binding.layoutFavoritesStripSection.visibility =
            if (tabIndex == 2 || tabIndex == 3) View.VISIBLE else View.GONE

        highlightActiveNavPill(tabIndex)
        refreshFavoritesStrip()

        val query = binding.etGlobalSearch.text?.toString()?.trim().orEmpty()
        when (tabIndex) {
            2 -> loadRecentCallLogs(query)
            3 -> loadSavedContacts(query)
            4 -> refreshShieldDatabaseList()
        }
    }

    private fun highlightActiveNavPill(activeTab: Int) {
        val pills = listOf(
            binding.pillTabKeypad,
            binding.pillTabRecents,
            binding.pillTabContacts,
            binding.pillTabShield
        )
        val labels = listOf(
            binding.tvTabKeypad,
            binding.tvTabRecents,
            binding.tvTabContacts,
            binding.tvTabShield
        )

        for (i in pills.indices) {
            val isSelected = (i + 1 == activeTab)
            pills[i].background = if (isSelected) {
                makeRoundedDrawable(Color.parseColor("#1E3A8A"), radiusDp = 999)
            } else {
                null
            }
            labels[i].setTextColor(
                Color.parseColor(if (isSelected) "#A8C7FA" else "#8E9BAE")
            )
            labels[i].setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun pickAvatarColor(seed: String): Int {
        return avatarPalette[abs(seed.hashCode()) % avatarPalette.size]
    }

    private fun loadRecentCallLogs(searchQuery: String = "") {
        currentRowItems.clear()
        val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

        if (currentRecentFilter == RecentFilterType.RECORDINGS) {
            val recordings = ScamIntelligenceRepo.getSavedRecordings(this)
            for (rec in recordings) {
                if (searchQuery.isNotEmpty() &&
                    !rec.callerName.contains(searchQuery, ignoreCase = true) &&
                    !rec.phoneNumber.contains(searchQuery)
                ) {
                    continue
                }
                currentRowItems.add(
                    DialerRowItem(
                        title = rec.callerName,
                        number = rec.phoneNumber,
                        subtitle = "Duration: ${rec.durationSec}s  •  ${dateFormat.format(Date(rec.timestampMs))}",
                        badgeText = "Recording",
                        badgeBgColor = Color.parseColor("#450A0A"),
                        badgeTextColor = Color.parseColor("#FCA5A5"),
                        avatarColor = Color.parseColor("#DC2626"),
                        recordingItem = rec
                    )
                )
            }
            binding.tvListHeader.text = "SAVED CALL RECORDINGS (${currentRowItems.size})"
            binding.listViewItems.adapter = createPremiumCardAdapter(currentRowItems)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissionsIfNeeded()
            return
        }

        val favNormSet = getCombinedFavorites().map { it.normalizedNumber }.toSet()

        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < 100) {
                val number = it.getString(0) ?: "Unknown"
                val savedName = it.getString(1)?.takeIf { n -> n.isNotBlank() }
                    ?: ScamGuardInCallService.getContactName(this, number)
                val type = it.getInt(2)
                val dateMs = it.getLong(3)
                val durationSec = it.getLong(4)

                val reputation = ScamIntelligenceRepo.lookupNumber(this, number)
                val isSpamCall = (reputation.reportCount > 0 || reputation.isBlocked) && savedName == null
                val isContactCall = (savedName != null)
                val isMissedCall = (type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE)

                val passesFilter = when (currentRecentFilter) {
                    RecentFilterType.ALL -> true
                    RecentFilterType.MISSED -> isMissedCall
                    RecentFilterType.CONTACTS -> isContactCall
                    RecentFilterType.NON_SPAM -> !isSpamCall
                    RecentFilterType.SPAM -> isSpamCall
                    RecentFilterType.RECORDINGS -> false
                }
                if (!passesFilter) continue

                val title = when {
                    savedName != null -> savedName
                    reputation.identifiedName != "Unknown" -> reputation.identifiedName
                    else -> number
                }

                if (searchQuery.isNotEmpty() &&
                    !title.contains(searchQuery, ignoreCase = true) &&
                    !number.contains(searchQuery)
                ) {
                    continue
                }

                val typeLabel = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "↙ Incoming (${durationSec}s)"
                    CallLog.Calls.OUTGOING_TYPE -> "↗ Outgoing (${durationSec}s)"
                    CallLog.Calls.MISSED_TYPE -> "↙ Missed call"
                    CallLog.Calls.REJECTED_TYPE -> "Declined"
                    else -> "Call"
                }

                val isFav = favNormSet.contains(ScamIntelligenceRepo.normalizeNumber(number))
                val badgeText = when {
                    isSpamCall -> "Spam (${reputation.reportCount})"
                    isFav -> "★"
                    else -> ""
                }
                val badgeBg = when {
                    isSpamCall -> Color.parseColor("#7F1D1D")
                    isFav -> Color.parseColor("#1E3A8A")
                    else -> Color.TRANSPARENT
                }
                val badgeFg = when {
                    isSpamCall -> Color.parseColor("#FCA5A5")
                    isFav -> Color.parseColor("#FACC15")
                    else -> Color.TRANSPARENT
                }
                val avatarCol = if (isSpamCall) Color.parseColor("#DC2626") else pickAvatarColor(title)

                currentRowItems.add(
                    DialerRowItem(
                        title = title,
                        number = number,
                        subtitle = "$typeLabel  •  ${dateFormat.format(Date(dateMs))}",
                        badgeText = badgeText,
                        badgeBgColor = badgeBg,
                        badgeTextColor = badgeFg,
                        avatarColor = avatarCol,
                        isScamAlert = isSpamCall,
                        isFavorite = isFav
                    )
                )
                count++
            }
        }

        val filterLabel = when (currentRecentFilter) {
            RecentFilterType.ALL -> "RECENT CALLS"
            RecentFilterType.MISSED -> "MISSED CALLS"
            RecentFilterType.CONTACTS -> "CALLS FROM CONTACTS"
            RecentFilterType.NON_SPAM -> "NON-SPAM CALLS"
            RecentFilterType.SPAM -> "SUSPECTED SPAM CALLS"
            RecentFilterType.RECORDINGS -> "CALL RECORDINGS"
        }
        binding.tvListHeader.text = "$filterLabel (${currentRowItems.size})"
        binding.listViewItems.adapter = createPremiumCardAdapter(currentRowItems)
    }

    private fun loadSavedContacts(searchQuery: String = "") {
        currentRowItems.clear()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissionsIfNeeded()
            return
        }

        val favNormSet = getCombinedFavorites().map { it.normalizedNumber }.toSet()

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.RawContacts.ACCOUNT_NAME
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        val seenNormalizedNumbers = mutableSetOf<String>()
        val favoriteRows = mutableListOf<DialerRowItem>()
        val regularRows = mutableListOf<DialerRowItem>()

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: "Unnamed"
                val rawNumber = it.getString(1) ?: continue
                val accountName = it.getString(2) ?: ""

                if (selectedGoogleAccount != null &&
                    !accountName.equals(selectedGoogleAccount, ignoreCase = true)
                ) {
                    continue
                }

                if (searchQuery.isNotEmpty() &&
                    !name.contains(searchQuery, ignoreCase = true) &&
                    !rawNumber.contains(searchQuery)
                ) {
                    continue
                }

                val normalizedKey = ScamIntelligenceRepo.normalizeNumber(rawNumber)
                if (normalizedKey.length >= 3 && seenNormalizedNumbers.add(normalizedKey)) {
                    val isFav = favNormSet.contains(normalizedKey)

                    val row = DialerRowItem(
                        title = name,
                        number = rawNumber,
                        subtitle = rawNumber,
                        badgeText = if (isFav) "★" else "",
                        badgeBgColor = if (isFav) Color.parseColor("#1E3A8A") else Color.TRANSPARENT,
                        badgeTextColor = if (isFav) Color.parseColor("#FACC15") else Color.TRANSPARENT,
                        avatarColor = pickAvatarColor(name),
                        isFavorite = isFav
                    )

                    if (isFav) favoriteRows.add(row) else regularRows.add(row)
                }
            }
        }

        currentRowItems.addAll(favoriteRows)
        currentRowItems.addAll(regularRows)

        if (currentRowItems.isEmpty() && searchQuery.isNotEmpty()) {
            val rep = ScamIntelligenceRepo.lookupNumber(this, searchQuery)
            val isScam = rep.reportCount > 0
            currentRowItems.add(
                DialerRowItem(
                    title = if (rep.identifiedName != "Unknown") rep.identifiedName else searchQuery,
                    number = searchQuery,
                    subtitle = "$searchQuery  •  ${rep.category}",
                    badgeText = if (isScam) "Spam (${rep.reportCount})" else "",
                    badgeBgColor = if (isScam) Color.parseColor("#7F1D1D") else Color.TRANSPARENT,
                    badgeTextColor = if (isScam) Color.parseColor("#FCA5A5") else Color.TRANSPARENT,
                    avatarColor = Color.parseColor(if (isScam) "#DC2626" else "#2563EB"),
                    isScamAlert = isScam
                )
            )
        }

        binding.tvListHeader.text = "ALL CONTACTS (${currentRowItems.size})"
        binding.listViewItems.adapter = createPremiumCardAdapter(currentRowItems)
    }

    private fun createPremiumCardAdapter(items: List<DialerRowItem>): BaseAdapter {
        return object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]

                val cardRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = makeRoundedDrawable(
                        fillColor = if (item.isScamAlert) Color.parseColor("#1F1218") else Color.parseColor("#141B29"),
                        radiusDp = 20,
                        strokeColor = if (item.isScamAlert) Color.parseColor("#7F1D1D") else Color.parseColor("#1E293B"),
                        strokeWidthDp = 1
                    )
                }

                val initials = item.title.trim()
                    .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
                val avatarView = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        marginEnd = dp(14)
                    }
                    gravity = Gravity.CENTER
                    text = when {
                        item.recordingItem != null -> "▶"
                        item.isScamAlert -> "!"
                        else -> initials
                    }
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    background = makeRoundedDrawable(item.avatarColor, radiusDp = 999)
                }

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleLine = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val tvTitle = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    text = item.title
                    setTextColor(Color.parseColor(if (item.isScamAlert) "#FCA5A5" else "#F8FAFC"))
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                titleLine.addView(tvTitle)

                if (item.badgeText.isNotEmpty()) {
                    val tvBadge = TextView(this@MainActivity).apply {
                        text = item.badgeText
                        setTextColor(item.badgeTextColor)
                        textSize = 10.5f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(7), dp(2), dp(7), dp(2))
                        background = makeRoundedDrawable(item.badgeBgColor, radiusDp = 999)
                    }
                    titleLine.addView(tvBadge)
                }

                val tvSub = TextView(this@MainActivity).apply {
                    text = item.subtitle
                    setTextColor(Color.parseColor("#8E9BAE"))
                    textSize = 13f
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                textCol.addView(titleLine)
                textCol.addView(tvSub)

                if (item.recordingItem != null) {
                    val btnPlayRec = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            marginStart = dp(10)
                        }
                        gravity = Gravity.CENTER
                        text = "▶"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        background = makeRippleButtonDrawable(
                            Color.parseColor("#1E293B"),
                            radiusDp = 999
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnClickListener {
                            showRecordingPlayerSheet(item)
                        }
                    }
                    cardRow.addView(avatarView)
                    cardRow.addView(textCol)
                    cardRow.addView(btnPlayRec)

                    cardRow.setOnClickListener {
                        showRecordingPlayerSheet(item)
                    }
                } else {
                    val btnCallIcon = ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            marginStart = dp(10)
                        }
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setImageResource(R.drawable.ic_call_answer)
                        imageTintList = ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
                        background = makeRippleButtonDrawable(
                            Color.parseColor("#1A2438"),
                            radiusDp = 999
                        )
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnClickListener {
                            placeOutgoingCall(item.number)
                        }
                    }
                    cardRow.addView(avatarView)
                    cardRow.addView(textCol)
                    cardRow.addView(btnCallIcon)

                    cardRow.setOnClickListener {
                        showNumberActionSheet(item.number, item.title)
                    }
                }

                return cardRow
            }
        }
    }

    // =========================================================================
    // 8. INTERACTIVE CALL RECORDING PLAYER BOTTOM SHEET (PLAY/PAUSE + SEEKBAR)
    // =========================================================================
    private fun formatMillisToMmSs(ms: Long): String {
        val totalSec = max(0L, ms / 1000L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
    }

    private fun showRecordingPlayerSheet(item: DialerRowItem) {
        val rec = item.recordingItem ?: return
        val file = rec.file

        if (!file.exists() || !file.canRead() || file.length() <= 256L) {
            Toast.makeText(
                this,
                "Recording audio file is missing or corrupted. Cleaning up entry.",
                Toast.LENGTH_LONG
            ).show()
            ScamIntelligenceRepo.deleteRecording(this, file)
            loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(22))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#111726"),
                radiusDp = 28,
                strokeColor = Color.parseColor("#25344F"),
                strokeWidthDp = 1
            )
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = makeRoundedDrawable(Color.parseColor("#334155"), radiusDp = 999)
        }
        sheet.addView(handle)

        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        val tvTitle = TextView(this).apply {
            text = rec.callerName
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val tvSub = TextView(this).apply {
            text = "${rec.phoneNumber}  •  ${dateFormat.format(Date(rec.timestampMs))}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        sheet.addView(tvTitle)
        sheet.addView(tvSub)

        // Player Controls Card
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#161F31"),
                radiusDp = 20,
                strokeColor = Color.parseColor("#24324B"),
                strokeWidthDp = 1
            )
        }

        val seekBar = SeekBar(this).apply {
            max = max(1000, rec.durationMs.toInt())
            progress = 0
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val tvElapsed = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "00:00"
            setTextColor(Color.parseColor("#A8C7FA"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
        }
        val tvTotal = TextView(this).apply {
            text = formatMillisToMmSs(rec.durationMs)
            setTextColor(Color.parseColor("#8E9BAE"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
        }
        timeRow.addView(tvElapsed)
        timeRow.addView(tvTotal)

        val btnPlayPause = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            gravity = Gravity.CENTER
            text = "⏸   Pause"
            setTextColor(Color.WHITE)
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            background = makeRippleButtonDrawable(Color.parseColor("#2563EB"), radiusDp = 999)
        }

        playerCard.addView(seekBar)
        playerCard.addView(timeRow)
        playerCard.addView(btnPlayPause)
        sheet.addView(playerCard)

        // Bottom Action Row: Call & Delete Recording
        val bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        val btnCallCaller = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            gravity = Gravity.CENTER
            text = "Call ${rec.callerName}"
            setTextColor(Color.parseColor("#86EFAC"))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            background = makeRippleButtonDrawable(Color.parseColor("#064E3B"), radiusDp = 16)
            setOnClickListener {
                dialog.dismiss()
                placeOutgoingCall(rec.phoneNumber)
            }
        }

        val btnDeleteRec = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
            gravity = Gravity.CENTER
            text = "Delete Recording"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            background = makeRippleButtonDrawable(Color.parseColor("#450A0A"), radiusDp = 16)
            setOnClickListener {
                stopRecordingPlayback()
                ScamIntelligenceRepo.deleteRecording(this@MainActivity, file)
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
            }
        }

        bottomActions.addView(btnCallCaller)
        bottomActions.addView(btnDeleteRec)
        sheet.addView(bottomActions)

        var userSeeking = false

        val progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && currentlyPlayingPath == file.absolutePath) {
                    try {
                        if (mp.isPlaying && !userSeeking) {
                            val pos = mp.currentPosition
                            seekBar.progress = pos
                            tvElapsed.text = formatMillisToMmSs(pos.toLong())
                        }
                        playbackHandler.postDelayed(this, 100L)
                    } catch (_: Exception) {}
                }
            }
        }

        fun startOrResumeAudio() {
            val mp = mediaPlayer
            if (mp != null && currentlyPlayingPath == file.absolutePath && isPlaybackPaused) {
                try {
                    mp.start()
                    isPlaybackPaused = false
                    btnPlayPause.text = "⏸   Pause"
                    playbackHandler.post(progressRunnable)
                    return
                } catch (_: Exception) {}
            }

            stopRecordingPlayback()
            try {
                // Ensure Android audio mode is normal media playback (not stuck in call earpiece mode)
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    prepare()
                }

                val actualDuration = max(1000, player.duration)
                seekBar.max = actualDuration
                tvTotal.text = formatMillisToMmSs(actualDuration.toLong())

                player.setOnCompletionListener {
                    isPlaybackPaused = true
                    seekBar.progress = seekBar.max
                    tvElapsed.text = formatMillisToMmSs(actualDuration.toLong())
                    btnPlayPause.text = "▶   Replay Recording"
                    try {
                        player.seekTo(0)
                    } catch (_: Exception) {}
                }

                player.start()
                mediaPlayer = player
                currentlyPlayingPath = file.absolutePath
                isPlaybackPaused = false
                btnPlayPause.text = "⏸   Pause"
                playbackHandler.post(progressRunnable)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Unable to play audio file: ${e.localizedMessage ?: "Corrupted format"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnPlayPause.setOnClickListener {
            val mp = mediaPlayer
            if (mp != null && currentlyPlayingPath == file.absolutePath && mp.isPlaying) {
                mp.pause()
                isPlaybackPaused = true
                btnPlayPause.text = "▶   Play"
            } else {
                startOrResumeAudio()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvElapsed.text = formatMillisToMmSs(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                userSeeking = false
                val targetPos = bar?.progress ?: 0
                try {
                    mediaPlayer?.seekTo(targetPos)
                } catch (_: Exception) {}
            }
        })

        dialog.setOnDismissListener {
            stopRecordingPlayback()
        }

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(14) }
        }
        dialog.show()

        // Automatically start playing as soon as the user opens the recording
        startOrResumeAudio()
    }

    private fun stopRecordingPlayback() {
        playbackHandler.removeCallbacksAndMessages(null)
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentlyPlayingPath = null
        isPlaybackPaused = false
    }

    // =========================================================================
    // 9. REDESIGNED GOOGLE DIALER-STYLE CONTACT ACTION BOTTOM SHEET
    // =========================================================================
    private fun showNumberActionSheet(phoneNumber: String, existingName: String?) {
        val rep = ScamIntelligenceRepo.lookupNumber(this, phoneNumber)
        val isFav = isContactFavorited(phoneNumber)
        val contactEditUri = ScamGuardInCallService.getContactEditUri(this, phoneNumber)
        val isSavedContact = (contactEditUri != null)

        val displayTitle = existingName?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: ScamGuardInCallService.getContactName(this, phoneNumber)
            ?: if (rep.identifiedName != "Unknown") rep.identifiedName else phoneNumber

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#111726"),
                radiusDp = 28,
                strokeColor = Color.parseColor("#25344F"),
                strokeWidthDp = 1
            )
        }

        // Top Drag Handle
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = makeRoundedDrawable(Color.parseColor("#334155"), radiusDp = 999)
        }
        sheet.addView(handle)

        // Contact Identity Header
        val identityHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(18))
        }

        val initial = displayTitle.trim()
            .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        val isScam = rep.reportCount > 0 && !isSavedContact

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                bottomMargin = dp(10)
            }
            gravity = Gravity.CENTER
            text = if (isScam) "!" else initial
            setTextColor(Color.WHITE)
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            background = makeRoundedDrawable(
                fillColor = if (isScam) Color.parseColor("#DC2626") else pickAvatarColor(displayTitle),
                radiusDp = 999
            )
        }

        val tvName = TextView(this).apply {
            text = displayTitle
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val tvNumber = TextView(this).apply {
            text = phoneNumber
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }

        identityHeader.addView(avatar)
        identityHeader.addView(tvName)
        if (displayTitle != phoneNumber) {
            identityHeader.addView(tvNumber)
        }

        if (rep.reportCount > 0) {
            val tvSpamPill = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                text = "Reported by ${rep.reportCount} users  •  ${rep.category}"
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = makeRoundedDrawable(Color.parseColor("#450A0A"), radiusDp = 999)
            }
            identityHeader.addView(tvSpamPill)
        }

        sheet.addView(identityHeader)

        // Uniformly Aligned 3-Button Quick Action Row (Call | Send SMS | Favorite)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        fun buildAlignedQuickButton(
            title: String,
            symbol: String,
            bgColor: Int,
            strokeColor: Int,
            textColor: Int,
            useVectorCallIcon: Boolean = false,
            onClick: () -> Unit
        ): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = makeRippleButtonDrawable(
                    fillColor = bgColor,
                    radiusDp = 20,
                    strokeColor = strokeColor,
                    strokeWidthDp = 1
                )

                // Fixed 28dp x 28dp centered icon container so all 3 icons align on the exact same baseline
                val iconBox = FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }

                if (useVectorCallIcon) {
                    val icon = ImageView(this@MainActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                        setImageResource(R.drawable.ic_call_answer)
                        imageTintList = ColorStateList.valueOf(textColor)
                    }
                    iconBox.addView(icon)
                } else {
                    val tvIcon = TextView(this@MainActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        text = symbol
                        setTextColor(textColor)
                        textSize = 18f
                        setTypeface(null, Typeface.BOLD)
                    }
                    iconBox.addView(tvIcon)
                }

                val tvLabel = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    text = title
                    setTextColor(textColor)
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                }

                addView(iconBox)
                addView(tvLabel)

                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        }

        quickRow.addView(
            buildAlignedQuickButton(
                title = "Call",
                symbol = "",
                bgColor = Color.parseColor("#16A34A"),
                strokeColor = Color.parseColor("#22C55E"),
                textColor = Color.WHITE,
                useVectorCallIcon = true
            ) {
                placeOutgoingCall(phoneNumber)
            }
        )

        quickRow.addView(
            buildAlignedQuickButton(
                title = "Send SMS",
                symbol = "💬",
                bgColor = Color.parseColor("#182235"),
                strokeColor = Color.parseColor("#283652"),
                textColor = Color.parseColor("#A8C7FA")
            ) {
                val smsIntent = Intent(Intent.ACTION_VIEW, "smsto:$phoneNumber".toUri())
                startActivity(smsIntent)
            }
        )

        quickRow.addView(
            buildAlignedQuickButton(
                title = if (isFav) "Favorited" else "Favorite",
                symbol = if (isFav) "★" else "☆",
                bgColor = Color.parseColor(if (isFav) "#1E3A8A" else "#182235"),
                strokeColor = Color.parseColor(if (isFav) "#3B82F6" else "#283652"),
                textColor = Color.parseColor(if (isFav) "#FACC15" else "#A8C7FA")
            ) {
                val added = toggleContactFavorite(displayTitle, phoneNumber)
                val msg = if (added) "Added $displayTitle to Favorites" else "Removed from Favorites"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshFavoritesStrip()
                if (currentTab == 2) loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
                if (currentTab == 3) loadSavedContacts(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
            }
        )

        sheet.addView(quickRow)

        // Detailed Action List Card
        val actionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#161F31"),
                radiusDp = 20,
                strokeColor = Color.parseColor("#23304A"),
                strokeWidthDp = 1
            )
        }

        fun addSheetOptionRow(
            iconText: String,
            labelText: String,
            textColor: Int = Color.parseColor("#F1F5F9"),
            showDivider: Boolean = true,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 16)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            val tvIcon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                text = iconText
                setTextColor(textColor)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }
            val tvLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
                text = labelText
                setTextColor(textColor)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(tvIcon)
            row.addView(tvLabel)
            actionsCard.addView(row)

            if (showDivider) {
                val div = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(Color.parseColor("#222E46"))
                }
                actionsCard.addView(div)
            }
        }

        addSheetOptionRow(
            iconText = if (isFav) "★" else "☆",
            labelText = if (isFav) "Remove from Favorites" else "Add to Favorites"
        ) {
            val added = toggleContactFavorite(displayTitle, phoneNumber)
            val msg = if (added) "Added $displayTitle to Favorites" else "Removed from Favorites"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refreshFavoritesStrip()
            if (currentTab == 2) loadRecentCallLogs(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
            if (currentTab == 3) loadSavedContacts(binding.etGlobalSearch.text?.toString()?.trim().orEmpty())
        }

        // If contact is already saved on the phone, show "Edit Contact" and open the existing contact's editor
        if (contactEditUri != null) {
            addSheetOptionRow(
                iconText = "✎",
                labelText = "Edit Contact"
            ) {
                openEditContactIntent(contactEditUri)
            }
        } else {
            addSheetOptionRow(
                iconText = "＋",
                labelText = "Add to Contacts"
            ) {
                openSystemAddContactIntent(phoneNumber)
            }
        }

        addSheetOptionRow(
            iconText = "⚑",
            labelText = "Report Scam / Tag Caller Name",
            textColor = Color.parseColor("#FDE047")
        ) {
            showReportScamDialog(phoneNumber)
        }

        addSheetOptionRow(
            iconText = "⊘",
            labelText = if (rep.isBlocked) "Unblock Number" else "Block Number (Auto-Reject Calls)",
            textColor = Color.parseColor(if (rep.isBlocked) "#4ADE80" else "#FCA5A5"),
            showDivider = false
        ) {
            ScamIntelligenceRepo.setBlockedState(this, phoneNumber, !rep.isBlocked)
            val msg = if (!rep.isBlocked) "Blocked $phoneNumber" else "Unblocked $phoneNumber"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (currentTab == 2) loadRecentCallLogs()
            if (currentTab == 4) refreshShieldDatabaseList()
        }

        sheet.addView(actionsCard)

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(14) }
        }
        dialog.show()
    }

    private fun setupShieldHubTab() {
        binding.btnOpenReportDialog.setOnClickListener {
            showReportScamDialog("")
        }

        binding.listShieldDatabase.setOnItemClickListener { _, _, position, _ ->
            val item = shieldRowItems.getOrNull(position) ?: return@setOnItemClickListener
            showNumberActionSheet(item.number, item.title)
        }
    }

    private fun refreshShieldDatabaseList() {
        shieldRowItems.clear()
        val sampleNumbers = mutableListOf("9876543210", "7935417825", "9999888877", "8888777766")
        for (blocked in ScamIntelligenceRepo.getBlockedNumbers(this)) {
            if (!sampleNumbers.contains(blocked)) sampleNumbers.add(0, blocked)
        }

        for (num in sampleNumbers) {
            val rep = ScamIntelligenceRepo.lookupNumber(this, num)
            shieldRowItems.add(
                DialerRowItem(
                    title = rep.identifiedName,
                    number = num,
                    subtitle = "$num  •  ${rep.category}",
                    badgeText = if (rep.isBlocked) "BLOCKED" else "${rep.reportCount} Reports",
                    badgeBgColor = Color.parseColor("#7F1D1D"),
                    badgeTextColor = Color.parseColor("#FCA5A5"),
                    avatarColor = Color.parseColor("#DC2626"),
                    isScamAlert = true
                )
            )
        }

        binding.listShieldDatabase.adapter = createPremiumCardAdapter(shieldRowItems)
    }

    private fun showReportScamDialog(prefillNumber: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#111726"),
                radiusDp = 26,
                strokeColor = Color.parseColor("#283652"),
                strokeWidthDp = 1
            )
        }

        val tvTitle = TextView(this).apply {
            text = "Report & Tag Scam Number"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = "Updates Caller ID and spam protection across ScamGuard."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        }
        card.addView(tvTitle)
        card.addView(tvSub)

        fun styleInput(hintStr: String, prefill: String = ""): EditText {
            return EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply { bottomMargin = dp(10) }
                hint = hintStr
                setText(prefill)
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 14.5f
                setPadding(dp(14), 0, dp(14), 0)
                background = makeRoundedDrawable(
                    Color.parseColor("#182235"),
                    radiusDp = 14,
                    strokeColor = Color.parseColor("#2A3B59"),
                    strokeWidthDp = 1
                )
                isSingleLine = true
            }
        }

        val etNumber = styleInput("Phone Number (e.g. +919876543210)", prefillNumber)
        val etNameTag = styleInput("Caller Name Tag (e.g. Fake CBI Officer)")
        val etCategory = styleInput("Scam Category", "Authority Impersonation / Fraud")

        card.addView(etNumber)
        card.addView(etNameTag)
        card.addView(etCategory)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }

        val btnCancel = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { dialog.dismiss() }
        }

        val btnReportOnly = TextView(this).apply {
            text = "Save Tag"
            setTextColor(Color.parseColor("#60A5FA"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                val num = etNumber.text.toString().trim()
                val name = etNameTag.text.toString().trim()
                val cat = etCategory.text.toString().trim()
                if (num.isNotEmpty()) {
                    ScamIntelligenceRepo.reportAndTagNumber(this@MainActivity, num, name, cat, alsoBlock = false)
                    Toast.makeText(this@MainActivity, "Caller ID Tag saved!", Toast.LENGTH_SHORT).show()
                    if (currentTab == 2) loadRecentCallLogs()
                    if (currentTab == 4) refreshShieldDatabaseList()
                    dialog.dismiss()
                }
            }
        }

        val btnReportBlock = TextView(this).apply {
            text = "Report & Block"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = makeRippleButtonDrawable(Color.parseColor("#DC2626"), radiusDp = 999)
            setOnClickListener {
                val num = etNumber.text.toString().trim()
                val name = etNameTag.text.toString().trim()
                val cat = etCategory.text.toString().trim()
                if (num.isNotEmpty()) {
                    ScamIntelligenceRepo.reportAndTagNumber(this@MainActivity, num, name, cat, alsoBlock = true)
                    Toast.makeText(this@MainActivity, "Number reported & blocked!", Toast.LENGTH_LONG).show()
                    if (currentTab == 2) loadRecentCallLogs()
                    if (currentTab == 4) refreshShieldDatabaseList()
                    dialog.dismiss()
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnReportOnly)
        btnRow.addView(btnReportBlock)
        card.addView(btnRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    // =========================================================================
    // 10. INCOMING CALL CONTROLS, SWIPE-UP SMS REPLY & ACTIVE CALL 'MORE' MENU
    // =========================================================================
    private fun setupInCallControls() {
        binding.btnAnswerIncoming.setOnClickListener {
            ScamGuardInCallService.answerCall()
        }

        binding.btnDeclineIncoming.setOnClickListener {
            ScamGuardInCallService.rejectCall()
        }

        var touchStartY = 0f
        var smsSheetTriggered = false
        binding.btnReplyWithSms.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    smsSheetTriggered = false
                    v.animate().translationY(-dp(6).toFloat()).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = touchStartY - event.rawY
                    if (deltaY > dp(24) && !smsSheetTriggered) {
                        smsSheetTriggered = true
                        v.animate().translationY(0f).setDuration(100).start()
                        showGoogleStyleSmsReplyDialog()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationY(0f).setDuration(120).start()
                    if (!smsSheetTriggered && event.actionMasked == MotionEvent.ACTION_UP) {
                        showGoogleStyleSmsReplyDialog()
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnEndCall.setOnClickListener {
            ScamGuardInCallService.endCall()
        }

        binding.btnToggleSpeaker.setOnClickListener {
            ScamGuardInCallService.toggleSpeakerphone()
        }

        binding.btnToggleMute.setOnClickListener {
            ScamGuardInCallService.toggleMute()
        }

        binding.btnToggleHold.setOnClickListener {
            ScamGuardInCallService.toggleHold(!isOnHold)
        }

        binding.btnInCallKeypad.setOnClickListener {
            isDtmfPadVisible = !isDtmfPadVisible
            binding.layoutInCallDtmfPad.visibility = if (isDtmfPadVisible) View.VISIBLE else View.GONE
            applyAudioAndHoldButtonsState(isSpeakerOn, isMuted, isOnHold, isMoreButtonEnabled)
        }

        binding.btnCloseDtmfPad.setOnClickListener {
            isDtmfPadVisible = false
            binding.layoutInCallDtmfPad.visibility = View.GONE
            applyAudioAndHoldButtonsState(isSpeakerOn, isMuted, isOnHold, isMoreButtonEnabled)
        }

        binding.btnInCallMore.setOnClickListener {
            if (!isMoreButtonEnabled) {
                Toast.makeText(
                    this,
                    "More options become available once the call is answered.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            showActiveCallMoreMenu()
        }

        binding.btnMergeCalls.setOnClickListener {
            ScamGuardInCallService.mergeActiveCalls { _, message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        val dtmfMap = mapOf(
            binding.dtmf0 to '0', binding.dtmf1 to '1', binding.dtmf2 to '2',
            binding.dtmf3 to '3', binding.dtmf4 to '4', binding.dtmf5 to '5',
            binding.dtmf6 to '6', binding.dtmf7 to '7', binding.dtmf8 to '8',
            binding.dtmf9 to '9', binding.dtmfStar to '*', binding.dtmfHash to '#'
        )

        for ((dtmfView, toneChar) in dtmfMap) {
            dtmfView.background = makeRippleButtonDrawable(
                Color.parseColor("#1E293B"),
                radiusDp = 16
            )
            dtmfView.setOnClickListener {
                dtmfDigitsTyped.append(toneChar)
                binding.tvDtmfTyped.text = dtmfDigitsTyped.toString()
                ScamGuardInCallService.sendDtmfKey(toneChar)
            }
        }
    }

    private fun showGoogleStyleSmsReplyDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#1C1D1F"),
                radiusDp = 28
            )
        }

        val titleTv = TextView(this).apply {
            text = "SMS reply"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(titleTv)

        val quickMessages = listOf(
            "I'll call you back later.",
            "Can't talk now. What's up?",
            "Can't talk now. Call me back later.",
            "I'll be there soon."
        )

        fun addDivider() {
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(Color.parseColor("#2E3136"))
            }
            card.addView(div)
        }

        for (msg in quickMessages) {
            val row = TextView(this).apply {
                text = msg
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 16.5f
                setPadding(dp(4), dp(16), dp(4), dp(16))
                background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 12)
                setOnClickListener {
                    dialog.dismiss()
                    sendSmsAndDeclineIncomingCall(msg)
                }
            }
            card.addView(row)
            addDivider()
        }

        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(16))
            background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 12)
            setOnClickListener {
                dialog.dismiss()
                showCustomSmsInputDialog()
            }
        }
        val tvCustomLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Custom"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16.5f
        }
        val tvChevron = TextView(this).apply {
            text = "›"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 22f
        }
        customRow.addView(tvCustomLabel)
        customRow.addView(tvChevron)
        card.addView(customRow)

        val btnCancel = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(6))
            setOnClickListener {
                dialog.dismiss()
            }
        }
        card.addView(btnCancel)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply {
                y = dp(24)
            }
        }
        dialog.show()
    }

    private fun showCustomSmsInputDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#1C1D1F"),
                radiusDp = 26
            )
        }

        val tvTitle = TextView(this).apply {
            text = "Custom SMS reply"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 18.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(tvTitle)

        val input = EditText(this).apply {
            hint = "Type your message..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedDrawable(
                Color.parseColor("#27292D"),
                radiusDp = 14
            )
        }
        card.addView(input)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        val btnCancel = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        val btnSend = TextView(this).apply {
            text = "Send & Decline"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val customText = input.text.toString().trim()
                if (customText.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Message cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    sendSmsAndDeclineIncomingCall(customText)
                }
            }
        }
        actionRow.addView(btnCancel)
        actionRow.addView(btnSend)
        card.addView(actionRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun sendSmsAndDeclineIncomingCall(message: String) {
        ScamGuardInCallService.rejectCallWithSms(this, message) { success, feedback ->
            if (!success && feedback == "SEND_SMS_PERMISSION_REQUIRED") {
                Toast.makeText(
                    this,
                    "Please allow SMS permission to send quick replies.",
                    Toast.LENGTH_LONG
                ).show()
                requestRuntimePermissionsIfNeeded()
            } else {
                Toast.makeText(this, feedback, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Three-Dot 'More' Menu with Record and Add Call
    private fun showActiveCallMoreMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = makeRoundedDrawable(
                fillColor = Color.parseColor("#161E2E"),
                radiusDp = 24,
                strokeColor = Color.parseColor("#2B3B59"),
                strokeWidthDp = 1
            )
        }

        val isRec = ScamGuardInCallService.isRecordingCall
        val recordLabel = if (isRec) "⏹   Stop Recording" else "⏺   Record"

        val btnRecordOption = TextView(this).apply {
            text = recordLabel
            setTextColor(Color.parseColor(if (isRec) "#FCA5A5" else "#F8FAFC"))
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 14)
            setOnClickListener {
                dialog.dismiss()
                handleRecordOptionClicked()
            }
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#26344D"))
        }

        val btnAddCallOption = TextView(this).apply {
            text = "＋   Add Call"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = makeRippleButtonDrawable(Color.TRANSPARENT, radiusDp = 14)
            setOnClickListener {
                dialog.dismiss()
                handleAddCallOptionClicked()
            }
        }

        menuCard.addView(btnRecordOption)
        menuCard.addView(divider)
        menuCard.addView(btnAddCallOption)

        dialog.setContentView(menuCard)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.78).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun handleRecordOptionClicked() {
        if (ScamGuardInCallService.isRecordingCall) {
            ScamGuardInCallService.stopCallRecording(this) { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (currentTab == 2 && currentRecentFilter == RecentFilterType.RECORDINGS) {
                    loadRecentCallLogs()
                }
            }
        } else {
            ScamGuardInCallService.startCallRecording(this) { ok, msg ->
                if (!ok && msg == "RECORD_AUDIO_PERMISSION_REQUIRED") {
                    Toast.makeText(this, "Grant Microphone permission to record calls.", Toast.LENGTH_SHORT).show()
                    requestRuntimePermissionsIfNeeded()
                } else {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleAddCallOptionClicked() {
        isAddingSecondCall = true
        binding.layoutActiveCallScreen.visibility = View.GONE
        binding.layoutDialerHome.visibility = View.VISIBLE
        binding.bannerReturnToCall.visibility = View.VISIBLE
        switchToTab(1)
        Toast.makeText(
            this,
            "Dial a second number. Your current call will be placed on hold.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyAudioAndHoldButtonsState(
        speakerActive: Boolean,
        micMuted: Boolean,
        callOnHold: Boolean,
        moreEnabled: Boolean
    ) {
        isSpeakerOn = speakerActive
        isMuted = micMuted
        isOnHold = callOnHold
        isMoreButtonEnabled = moreEnabled

        val activeBg = Color.parseColor("#2563EB")
        val inactiveBg = Color.parseColor("#182235")

        binding.btnToggleMute.background = makeRippleButtonDrawable(
            if (isMuted) activeBg else inactiveBg,
            radiusDp = 999
        )
        binding.tvLabelMute.text = if (isMuted) "Muted" else "Mute"
        binding.tvLabelMute.setTextColor(Color.parseColor(if (isMuted) "#93C5FD" else "#CBD5E1"))

        binding.btnInCallKeypad.background = makeRippleButtonDrawable(
            if (isDtmfPadVisible) activeBg else inactiveBg,
            radiusDp = 999
        )
        binding.tvLabelKeypad.setTextColor(
            Color.parseColor(if (isDtmfPadVisible) "#93C5FD" else "#CBD5E1")
        )

        binding.btnToggleSpeaker.background = makeRippleButtonDrawable(
            if (isSpeakerOn) activeBg else inactiveBg,
            radiusDp = 999
        )
        binding.tvLabelSpeaker.text = if (isSpeakerOn) "Speaker ON" else "Speaker"
        binding.tvLabelSpeaker.setTextColor(
            Color.parseColor(if (isSpeakerOn) "#93C5FD" else "#CBD5E1")
        )

        binding.btnToggleHold.background = makeRippleButtonDrawable(
            if (isOnHold) activeBg else inactiveBg,
            radiusDp = 999
        )
        binding.tvLabelHold.text = if (isOnHold) "Resume" else "Hold"
        binding.tvLabelHold.setTextColor(Color.parseColor(if (isOnHold) "#93C5FD" else "#CBD5E1"))

        binding.btnInCallMore.background = makeRippleButtonDrawable(
            if (ScamGuardInCallService.isRecordingCall) Color.parseColor("#DC2626") else inactiveBg,
            radiusDp = 999
        )
        binding.btnInCallMore.isEnabled = moreEnabled
        binding.btnInCallMore.alpha = if (moreEnabled) 1.0f else 0.38f
        binding.tvLabelMore.alpha = if (moreEnabled) 1.0f else 0.38f
    }

    private fun syncWithActiveCallIfPresent() {
        val activeCall = ScamGuardInCallService.currentCall
        if (activeCall != null && activeCall.details.state != Call.STATE_DISCONNECTED) {
            val number = activeCall.details?.handle?.schemeSpecificPart ?: "Unknown"
            val contactName = ScamGuardInCallService.getContactName(this, number)
            val reputation = ScamIntelligenceRepo.lookupNumber(this, number)
            val isIncoming = (activeCall.details?.callDirection == Call.Details.DIRECTION_INCOMING) ||
                    (activeCall.details.state == Call.STATE_RINGING)
            val state = activeCall.details.state
            updateCallScreenUi(
                state = state,
                phoneNumber = number,
                contactName = contactName,
                reputation = reputation,
                isIncoming = isIncoming,
                speakerActive = ScamGuardInCallService.isSpeakerOn,
                micMuted = ScamGuardInCallService.isMutedState,
                callOnHold = (state == Call.STATE_HOLDING),
                connectTimeMs = ScamGuardInCallService.getActiveCallConnectTimeMs(),
                callCount = ScamGuardInCallService.getCallCount(),
                canMergeConference = ScamGuardInCallService.canMergeCalls(),
                isRecording = ScamGuardInCallService.isRecordingCall
            )
        } else {
            isAddingSecondCall = false
            binding.bannerReturnToCall.visibility = View.GONE
            binding.layoutActiveCallScreen.visibility = View.GONE
            binding.layoutDialerHome.visibility = View.VISIBLE
        }
    }

    private fun updateCallScreenUi(
        state: Int,
        phoneNumber: String,
        contactName: String?,
        reputation: CallerReputation,
        isIncoming: Boolean,
        speakerActive: Boolean,
        micMuted: Boolean,
        callOnHold: Boolean,
        connectTimeMs: Long,
        callCount: Int,
        canMergeConference: Boolean,
        isRecording: Boolean
    ) {
        if (state == Call.STATE_DISCONNECTED) {
            stopCallTimer()
            isDtmfPadVisible = false
            isAddingSecondCall = false
            dtmfDigitsTyped.clear()
            binding.bannerReturnToCall.visibility = View.GONE
            binding.layoutInCallDtmfPad.visibility = View.GONE
            binding.layoutActiveCallScreen.visibility = View.GONE
            binding.layoutDialerHome.visibility = View.VISIBLE
            applyAudioAndHoldButtonsState(
                speakerActive = false,
                micMuted = false,
                callOnHold = false,
                moreEnabled = false
            )
            if (currentTab == 2) loadRecentCallLogs()
            return
        }

        if (isAddingSecondCall) {
            binding.bannerReturnToCall.visibility = View.VISIBLE
            return
        }

        binding.layoutDialerHome.visibility = View.GONE
        binding.layoutActiveCallScreen.visibility = View.VISIBLE

        val isAnsweredOrHeld = (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING)
        applyAudioAndHoldButtonsState(
            speakerActive = speakerActive,
            micMuted = micMuted,
            callOnHold = callOnHold,
            moreEnabled = isAnsweredOrHeld
        )

        val hasKnownCallerId = (reputation.identifiedName != "Unknown")
        val primaryDisplayName = when {
            !contactName.isNullOrBlank() -> contactName
            hasKnownCallerId -> reputation.identifiedName
            else -> phoneNumber
        }

        val initialChar = primaryDisplayName.trim()
            .firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
        val isKnownSpam = reputation.reportCount > 0 && contactName == null

        binding.tvInCallAvatar.text = if (isKnownSpam) "!" else initialChar
        binding.tvInCallAvatar.background = makeRoundedDrawable(
            fillColor = when {
                isKnownSpam -> Color.parseColor("#DC2626")
                contactName != null -> Color.parseColor("#16A34A")
                else -> Color.parseColor("#2563EB")
            },
            radiusDp = 999,
            strokeColor = Color.parseColor("#33FFFFFF"),
            strokeWidthDp = 3
        )

        binding.tvCallerName.text = primaryDisplayName
        if (primaryDisplayName != phoneNumber) {
            binding.tvCallerNumber.visibility = View.VISIBLE
            binding.tvCallerNumber.text = phoneNumber
        } else {
            binding.tvCallerNumber.visibility = View.VISIBLE
            binding.tvCallerNumber.text = if (isIncoming) "Incoming call" else "Mobile"
        }

        if (contactName != null) {
            binding.tvCallerBadge.visibility = View.VISIBLE
            binding.tvCallerBadge.text = "Saved Contact"
            binding.tvCallerBadge.setTextColor(Color.parseColor("#4ADE80"))
        } else if (isKnownSpam) {
            binding.tvCallerBadge.visibility = View.VISIBLE
            binding.tvCallerBadge.text = "Reported Spam (${reputation.reportCount} reports)"
            binding.tvCallerBadge.setTextColor(Color.parseColor("#FCA5A5"))
        } else {
            binding.tvCallerBadge.visibility = View.GONE
        }

        binding.tvRecordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
        binding.btnMergeCalls.visibility =
            if (callCount >= 2 && canMergeConference) View.VISIBLE else View.GONE

        if (state == Call.STATE_RINGING) {
            binding.layoutIncomingCallActions.visibility = View.VISIBLE
            binding.layoutActiveCallControlsGroup.visibility = View.GONE
            binding.layoutInCallDtmfPad.visibility = View.GONE
            isDtmfPadVisible = false
        } else {
            binding.layoutIncomingCallActions.visibility = View.GONE
            binding.layoutActiveCallControlsGroup.visibility = View.VISIBLE
        }

        when (state) {
            Call.STATE_RINGING -> {
                stopCallTimer()
                binding.tvCallStatus.text = "Incoming call"
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                stopCallTimer()
                binding.tvCallStatus.text = "Calling..."
            }
            Call.STATE_HOLDING -> {
                stopCallTimer()
                binding.tvCallStatus.text =
                    if (callCount >= 2) "$callCount calls • On hold" else "Call on hold"
            }
            Call.STATE_ACTIVE -> {
                startCallTimer(connectTimeMs, callCount)
            }
        }
    }

    private fun startCallTimer(authoritativeStartMs: Long, callCount: Int) {
        callStartTimeMs =
            if (authoritativeStartMs > 0L) authoritativeStartMs else System.currentTimeMillis()
        if (isCallTimerRunning) return
        isCallTimerRunning = true
        timerHandler.post(object : Runnable {
            override fun run() {
                if (!isCallTimerRunning) return
                val elapsedSec =
                    ((System.currentTimeMillis() - callStartTimeMs) / 1000).coerceAtLeast(0L)
                val mins = elapsedSec / 60
                val secs = elapsedSec % 60
                val prefix = if (callCount >= 2) "$callCount Calls • " else ""
                binding.tvCallStatus.text =
                    String.format(Locale.getDefault(), "%s%02d:%02d", prefix, mins, secs)

                if (ScamGuardInCallService.isRecordingCall) {
                    val recSec =
                        ((System.currentTimeMillis() - ScamGuardInCallService.recordingStartTimeMs) / 1000)
                            .coerceAtLeast(0L)
                    binding.tvRecordingIndicator.visibility = View.VISIBLE
                    binding.tvRecordingIndicator.text = String.format(
                        Locale.getDefault(),
                        "● Recording %02d:%02d",
                        recSec / 60,
                        recSec % 60
                    )
                } else {
                    binding.tvRecordingIndicator.visibility = View.GONE
                }

                timerHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopCallTimer() {
        isCallTimerRunning = false
        timerHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        ScamGuardInCallService.setMainActivityVisibility(false)
        stopCallTimer()
        stopRecordingPlayback()
        ScamGuardInCallService.onCallUiUpdate = null
    }

    external fun stringFromJNI(): String
}