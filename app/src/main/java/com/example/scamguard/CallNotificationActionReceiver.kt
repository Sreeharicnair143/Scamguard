@file:Suppress("SpellCheckingInspection")

package com.example.scamguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallNotifReceiver"

        const val ACTION_ANSWER_CALL = "com.example.scamguard.ACTION_ANSWER_CALL"
        const val ACTION_REJECT_CALL = "com.example.scamguard.ACTION_REJECT_CALL"
        const val ACTION_END_CALL = "com.example.scamguard.ACTION_END_CALL"
        const val ACTION_TOGGLE_SPEAKER = "com.example.scamguard.ACTION_TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_MUTE = "com.example.scamguard.ACTION_TOGGLE_MUTE"
        const val ACTION_HOLD_CALL = "com.example.scamguard.ACTION_HOLD_CALL"
        const val ACTION_RESUME_CALL = "com.example.scamguard.ACTION_RESUME_CALL"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Notification action received: $action")

        when (action) {
            ACTION_ANSWER_CALL -> ScamGuardInCallService.answerCall(fromNotification = true)
            ACTION_REJECT_CALL -> ScamGuardInCallService.rejectCall(fromNotification = true)
            ACTION_END_CALL -> ScamGuardInCallService.endCall()
            ACTION_TOGGLE_SPEAKER -> ScamGuardInCallService.toggleSpeakerphone()
            ACTION_TOGGLE_MUTE -> ScamGuardInCallService.toggleMute()
            ACTION_HOLD_CALL -> ScamGuardInCallService.toggleHold(true)
            ACTION_RESUME_CALL -> ScamGuardInCallService.toggleHold(false)
        }
    }
}