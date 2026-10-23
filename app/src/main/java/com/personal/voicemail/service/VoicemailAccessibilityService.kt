package com.personal.voicemail.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.util.SetupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lightweight AccessibilityService that guarantees background microphone and telephony audio access
 * on Android 12–16 (including Vivo FuntouchOS) when the screen is locked and the auto-answer
 * screening timer triggers.
 *
 * Exposes its active Context [instance] so [com.personal.voicemail.audio.StandaloneTelephonyAudioEngine]
 * can bind AudioRecord to an active AccessibilityService context (`isAccessibilityTool="true"`),
 * preventing Android's AudioFlinger from silencing the capture stream.
 */
class VoicemailAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoicemailAccessibility"

        @Volatile
        var instance: VoicemailAccessibilityService? = null
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        SetupPreferences.setAccessibilityConfirmed(this, true)
        Log.i(TAG, "VoicemailAccessibilityService onStartCommand (START_STICKY).")
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SetupPreferences.setAccessibilityConfirmed(this, true)
        Log.i(TAG, "VoicemailAccessibilityService connected and active (instance bound).")

        // Pre-warm speech recognition models and greeting file on connect / reboot
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val greeting = GreetingGenerator.getActiveGreetingFile(this@VoicemailAccessibilityService)
                Log.i(TAG, "Accessibility guardian pre-warmed greeting: ${greeting.absolutePath} (${greeting.length()} bytes)")
                GreetingGenerator.generateVoiceGreetingTts(this@VoicemailAccessibilityService)
                VoicemailApplication.transcriber.initializeRecognizer()
                Log.i(TAG, "LiveVoicemailTranscriber pre-warmed via AccessibilityService.")
            }.onFailure { t ->
                Log.w(TAG, "Error pre-warming services in onServiceConnected: ${t.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: Lightweight background guardian for AudioRecord telephony/microphone access
    }

    override fun onInterrupt() {
        Log.i(TAG, "VoicemailAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "VoicemailAccessibilityService unbound (instance cleared).")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "VoicemailAccessibilityService destroyed.")
        super.onDestroy()
    }
}
