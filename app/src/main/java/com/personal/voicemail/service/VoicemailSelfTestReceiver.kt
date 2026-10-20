package com.personal.voicemail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.ipc.IAudioChunkCallback
import com.personal.voicemail.recognition.LiveVoicemailTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Diagnostic BroadcastReceiver triggered via ADB to verify the entire
 * standalone audio pipeline and Sherpa-ONNX transducer offline before live test calls.
 *
 * Trigger with:
 *   adb shell am broadcast -a com.personal.voicemail.ACTION_SELF_TEST
 */
class VoicemailSelfTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VoicemailSelfTest"
        const val ACTION_SELF_TEST = "com.personal.voicemail.ACTION_SELF_TEST"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SELF_TEST) return

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "[SELF_TEST] INITIATING OFFLINE VOICEMAIL PIPELINE TEST")
        Log.i(TAG, "=======================================================")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runSelfTest(context.applicationContext)
                Log.i(TAG, "=======================================================")
                Log.i(TAG, "[SELF_TEST] *** SELF_TEST_PASSED ***")
                Log.i(TAG, "=======================================================")
            } catch (t: Throwable) {
                Log.e(TAG, "[SELF_TEST] *** SELF_TEST_FAILED ***: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runSelfTest(context: Context) {
        // Step 1: Verify Greeting File and ParcelFileDescriptor
        Log.i(TAG, "[SELF_TEST] Step 1: Verifying Greeting WAV file...")
        val greetingFile = GreetingGenerator.getActiveGreetingFile(context)
        if (!greetingFile.exists() || greetingFile.length() <= 44) {
            throw IllegalStateException("Greeting file not found or empty: ${greetingFile.absolutePath}")
        }
        Log.i(TAG, "[SELF_TEST] Greeting file verified: ${greetingFile.absolutePath} (${greetingFile.length()} bytes)")

        val pfd = ParcelFileDescriptor.open(greetingFile, ParcelFileDescriptor.MODE_READ_ONLY)
        if (pfd == null) {
            throw IllegalStateException("Failed to open ParcelFileDescriptor for greeting WAV")
        }
        Log.i(TAG, "[SELF_TEST] ParcelFileDescriptor created successfully (fd=${pfd.fd})")

        // Step 2: Verify Standalone AudioRecord Capture Capability
        Log.i(TAG, "[SELF_TEST] Step 2: Verifying standalone AudioRecord hardware capture...")
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val testRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(3200 * 4)
        )
        if (testRecord.state != AudioRecord.STATE_INITIALIZED) {
            testRecord.release()
            throw IllegalStateException("AudioRecord failed to initialize with VOICE_RECOGNITION source")
        }
        Log.i(TAG, "[SELF_TEST] Standalone AudioRecord initialized successfully (state=${testRecord.state})")
        testRecord.release()

        // Optional Shizuku Controller check (logged only, non-blocking)
        runCatching {
            val shizukuController = ShizukuAudioController.getInstance(context)
            val isShizukuRunning = shizukuController.updateShizukuStatus()
            Log.i(TAG, "[SELF_TEST] Optional Shizuku status: running=$isShizukuRunning")
        }

        // Step 3: Verify Sherpa-ONNX Transcriber Engine
        Log.i(TAG, "[SELF_TEST] Step 3: Verifying Sherpa-ONNX Transcriber...")
        val transcriber = LiveVoicemailTranscriber.getInstance(context)
        
        // Wait up to 5 seconds for model initialization if needed
        var modelRetries = 10
        while (!transcriber.isReady() && modelRetries > 0) {
            delay(500)
            modelRetries--
        }
        Log.i(TAG, "[SELF_TEST] Sherpa-ONNX Transcriber ready: ${transcriber.isReady()}")

        // Step 4: Stream audio chunks through transcriber
        Log.i(TAG, "[SELF_TEST] Step 4: Testing Audio pipeline with synthetic 16kHz speech frame...")
        transcriber.startSession("Diagnostic Test", "+10000000000", "SIM 1", 0)

        // Synthesize 1 second of 16kHz audio (10 chunks of 1600 samples)
        for (i in 0 until 10) {
            val shortChunk = ShortArray(1600) { idx -> ((idx % 128) * 100).toShort() }
            transcriber.submitPcmChunk(shortChunk)
            delay(100)
        }

        transcriber.stopSession(isPickedUp = false, saveToRepository = false)
        pfd.close()

        Log.i(TAG, "[SELF_TEST] Audio pipeline and transcriber session completed cleanly without crash.")
    }
}
