package com.personal.voicemail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.util.SetupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Direct-Boot aware BroadcastReceiver triggered when the device boots (including before user unlock)
 * or when the app package is updated/replaced.
 *
 * Pre-warms the Sherpa-ONNX speech recognition model and verifies that the telephony greeting
 * WAV exists in RAM/disk cache so Live Voicemail screening is instantly ready for incoming calls
 * without requiring the user to unlock the device or manually launch the app interface.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        Log.i(TAG, "BootCompletedReceiver triggered with action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            val targetContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = context.getSystemService(UserManager::class.java)
                if (userManager != null && !userManager.isUserUnlocked) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
            } else {
                context
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Verify persisted setup state
                    val batteryConfirmed = SetupPreferences.isBatteryExemptConfirmed(targetContext)
                    val accessConfirmed = SetupPreferences.isAccessibilityConfirmed(targetContext)
                    Log.i(TAG, "Device boot: batteryConfirmed=$batteryConfirmed, accessConfirmed=$accessConfirmed")

                    // 1. Pre-warm greeting audio file & trigger TTS if missing
                    val greeting = GreetingGenerator.getActiveGreetingFile(targetContext)
                    Log.i(TAG, "Greeting audio pre-warmed: ${greeting.absolutePath} (${greeting.length()} bytes)")
                    GreetingGenerator.generateVoiceGreetingTts(targetContext)

                    // 2. Pre-warm offline Sherpa-ONNX streaming ASR engine
                    runCatching {
                        VoicemailApplication.transcriber.initializeRecognizer()
                        Log.i(TAG, "Sherpa-ONNX streaming ASR engine pre-warmed in RAM.")
                    }

                    // 3. Trigger background auto-bootstrap if root or privileged binder is available
                    runCatching {
                        VoicemailApplication.shizukuController.tryAutoBootstrapInternalService()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error pre-warming voicemail services on boot: ${t.message}", t)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
