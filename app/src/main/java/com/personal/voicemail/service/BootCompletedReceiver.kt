package com.personal.voicemail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.audio.GreetingGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered when the device boots or the app package is updated/replaced.
 *
 * Pre-warms the Sherpa-ONNX speech recognition model and verifies that the telephony greeting
 * WAV exists in RAM/disk cache so Live Voicemail screening is instantly ready for incoming calls
 * without requiring the user to manually launch the app interface.
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
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 1. Pre-warm greeting audio file & trigger TTS if missing
                    val greeting = GreetingGenerator.getActiveGreetingFile(context)
                    Log.i(TAG, "Greeting audio pre-warmed: ${greeting.absolutePath} (${greeting.length()} bytes)")
                    GreetingGenerator.generateVoiceGreetingTts(context)

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
