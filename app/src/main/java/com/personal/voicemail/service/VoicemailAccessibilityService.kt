package com.personal.voicemail.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight AccessibilityService that guarantees background microphone access on Vivo FuntouchOS
 * when the screen is locked and the auto-answer screening timer triggers.
 */
class VoicemailAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoicemailAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "VoicemailAccessibilityService connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: Lightweight background guardian for AudioRecord microphone access
    }

    override fun onInterrupt() {
        Log.i(TAG, "VoicemailAccessibilityService interrupted.")
    }
}
