package com.personal.voicemail.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates and manages the telephony greeting WAV audio files.
 *
 * Guarantees a valid 16,000 Hz, 16-bit Mono RIFF/WAVE greeting exists on disk at files/default_greeting.wav.
 * Uses Android TextToSpeech to synthesize spoken voice audio and resamples to 16kHz with an 800Hz cue beep.
 */
object GreetingGenerator {

    private const val TAG = "GreetingGenerator"
    private const val GREETING_DIR_NAME = "greetings"
    const val DEFAULT_GREETING_FILE_NAME = "default_greeting.wav"
    const val CUSTOM_GREETING_FILE_NAME = "custom_greeting.wav"
    const val GREETING_SPOKEN_TEXT = "Hello, I cannot take your call right now. Please leave your message after the beep."

    const val SAMPLE_RATE_HZ = 16000
    const val CHANNELS = 1 // Mono for cellular uplink
    const val BITS_PER_SAMPLE = 16

    @Volatile
    private var isGeneratingTts = false

    /**
     * Retrieves or automatically synthesizes the active greeting WAV file.
     * Prefers custom greeting if created; otherwise ensures default_greeting.wav exists.
     */
    @Synchronized
    fun getActiveGreetingFile(context: Context): File {
        val rootGreeting = File(context.filesDir, DEFAULT_GREETING_FILE_NAME)
        val dir = File(context.filesDir, GREETING_DIR_NAME).apply { mkdirs() }
        val customGreeting = File(dir, CUSTOM_GREETING_FILE_NAME)
        if (customGreeting.exists() && customGreeting.length() > 44) {
            return customGreeting
        }

        val dirGreeting = File(dir, DEFAULT_GREETING_FILE_NAME)

        // Check if root greeting exists and is valid
        if (rootGreeting.exists() && rootGreeting.length() >= 64000) {
            // Keep dirGreeting in sync
            if (!dirGreeting.exists() || dirGreeting.length() != rootGreeting.length()) {
                runCatching { rootGreeting.copyTo(dirGreeting, overwrite = true) }
            }
            stageToTmpIfPossible(rootGreeting)
            return rootGreeting
        }

        // Check if dirGreeting exists and is valid
        if (dirGreeting.exists() && dirGreeting.length() >= 64000) {
            runCatching { dirGreeting.copyTo(rootGreeting, overwrite = true) }
            stageToTmpIfPossible(rootGreeting)
            return rootGreeting
        }

        // Generate immediate fallback chime first so an active file is guaranteed to exist right now
        generateImmediateFallbackGreeting(rootGreeting)
        runCatching { rootGreeting.copyTo(dirGreeting, overwrite = true) }
        stageToTmpIfPossible(rootGreeting)

        // Trigger asynchronous high-fidelity TTS spoken voice generation
        generateVoiceGreetingTts(context)

        return rootGreeting
    }

    /**
     * Attempts to stage greeting directly into /data/local/tmp/voicemail_greeting.wav
     */
    fun stageToTmpIfPossible(sourceFile: File) {
        try {
            val tmpFile = File("/data/local/tmp/voicemail_greeting.wav")
            sourceFile.copyTo(tmpFile, overwrite = true)
            tmpFile.setReadable(true, false)
            Log.i(TAG, "Staged greeting directly to ${tmpFile.absolutePath} (${tmpFile.length()} bytes)")
        } catch (t: Throwable) {
            Log.d(TAG, "Direct copy to /data/local/tmp deferred to Shizuku/ADB: ${t.message}")
        }
    }

    /**
     * Creates an AssetFileDescriptor wrapping the active greeting WAV file.
     */
    fun getGreetingAssetFileDescriptor(context: Context): AssetFileDescriptor {
        val file = getActiveGreetingFile(context)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    /**
     * Synthesizes spoken voice greeting using Android TextToSpeech, resamples to 16,000 Hz,
     * appends an 800Hz beep tone, and saves to files/default_greeting.wav.
     */
    fun generateVoiceGreetingTts(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (isGeneratingTts) {
            Log.d(TAG, "TTS voice generation already in progress, skipping duplicate call.")
            onComplete?.invoke(false)
            return
        }
        isGeneratingTts = true

        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TextToSpeech initialization failed with status $status")
                    isGeneratingTts = false
                    tts?.shutdown()
                    onComplete?.invoke(false)
                    return@TextToSpeech
                }

                tts?.let { engine ->
                    try {
                        engine.language = Locale.US

                        val tempTtsFile = File(appContext.cacheDir, "temp_tts_greeting.wav")
                        if (tempTtsFile.exists()) tempTtsFile.delete()

                        val utteranceId = "voicemail_greeting_tts"
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                Log.i(TAG, "TextToSpeech started synthesizing spoken voice greeting...")
                            }

                            override fun onDone(id: String?) {
                                Log.i(TAG, "TextToSpeech finished synthesis. Processing WAV audio (${tempTtsFile.length()} bytes)...")
                                try {
                                    val success = processTtsWavTo16kWithBeep(tempTtsFile, appContext)
                                    isGeneratingTts = false
                                    engine.shutdown()
                                    onComplete?.invoke(success)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Error processing TTS WAV output", t)
                                    isGeneratingTts = false
                                    engine.shutdown()
                                    onComplete?.invoke(false)
                                } finally {
                                    tempTtsFile.delete()
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) {
                                Log.e(TAG, "TextToSpeech synthesis error for utterance: $id")
                                isGeneratingTts = false
                                engine.shutdown()
                                onComplete?.invoke(false)
                            }

                            override fun onError(id: String?, errorCode: Int) {
                                Log.e(TAG, "TextToSpeech synthesis error $errorCode for utterance: $id")
                                isGeneratingTts = false
                                engine.shutdown()
                                onComplete?.invoke(false)
                            }
                        })

                        val params = Bundle().apply {
                            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                        }

                        val result = engine.synthesizeToFile(
                            GREETING_SPOKEN_TEXT,
                            params,
                            tempTtsFile,
                            utteranceId
                        )
                        Log.i(TAG, "TextToSpeech.synthesizeToFile returned $result for '$GREETING_SPOKEN_TEXT'")
                        if (result != TextToSpeech.SUCCESS) {
                            isGeneratingTts = false
                            engine.shutdown()
                            onComplete?.invoke(false)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception during TTS setup", t)
                        isGeneratingTts = false
                        engine.shutdown()
                        onComplete?.invoke(false)
                    }
                }
            }
        }
    }

    /**
     * Reads the raw TTS output WAV, parses its source sample rate (e.g. 22050 or 24000 Hz),
     * resamples to 16,000 Hz Mono 16-bit PCM, appends a 500ms 800Hz beep (amplitude = 20000),
     * and saves to files/default_greeting.wav.
     */
    private fun processTtsWavTo16kWithBeep(sourceWav: File, context: Context): Boolean {
        if (!sourceWav.exists() || sourceWav.length() < 44) {
            Log.e(TAG, "TTS source WAV missing or invalid: ${sourceWav.length()} bytes")
            return false
        }

        val fileBytes = sourceWav.readBytes()
        if (fileBytes.size < 44) return false

        // Parse WAV Header
        val headerBuf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(fileBytes, 0, 4)
        val wave = String(fileBytes, 8, 4)
        if (riff != "RIFF" || wave != "WAVE") {
            Log.w(TAG, "TTS output is not standard RIFF/WAVE ($riff/$wave)")
        }

        // Find "data" chunk
        var dataOffset = 44
        var dataLen = fileBytes.size - 44
        var sourceSampleRate = 22050
        var sourceChannels = 1
        var bitsPerSample = 16

        var offset = 12
        while (offset < fileBytes.size - 8) {
            val chunkId = String(fileBytes, offset, 4)
            val chunkSize = ByteBuffer.wrap(fileBytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "fmt ") {
                val format = ByteBuffer.wrap(fileBytes, offset + 8, 2).order(ByteOrder.LITTLE_ENDIAN).short
                sourceChannels = ByteBuffer.wrap(fileBytes, offset + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                sourceSampleRate = ByteBuffer.wrap(fileBytes, offset + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                bitsPerSample = ByteBuffer.wrap(fileBytes, offset + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                Log.i(TAG, "Parsed TTS WAV fmt: format=$format, channels=$sourceChannels, sampleRate=$sourceSampleRate, bits=$bitsPerSample")
            } else if (chunkId == "data") {
                dataOffset = offset + 8
                dataLen = chunkSize
                break
            }
            offset += 8 + chunkSize
        }

        if (sourceSampleRate <= 0) sourceSampleRate = 22050
        if (sourceChannels <= 0) sourceChannels = 1
        if (bitsPerSample <= 0) bitsPerSample = 16

        // Extract PCM 16-bit mono samples from source
        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * sourceChannels
        val totalFrames = dataLen / frameSize

        val sourcePcm = ShortArray(totalFrames)
        var byteIdx = dataOffset
        for (i in 0 until totalFrames) {
            if (byteIdx + frameSize > fileBytes.size) break
            var monoSample = 0
            for (ch in 0 until sourceChannels) {
                val s = if (bitsPerSample == 16) {
                    (fileBytes[byteIdx + ch * 2].toInt() and 0xFF or
                            (fileBytes[byteIdx + ch * 2 + 1].toInt() shl 8)).toShort()
                } else {
                    ((fileBytes[byteIdx + ch].toInt() and 0xFF - 128) shl 8).toShort()
                }
                monoSample += s
            }
            sourcePcm[i] = (monoSample / sourceChannels).toShort()
            byteIdx += frameSize
        }

        Log.i(TAG, "Extracted ${sourcePcm.size} source samples at $sourceSampleRate Hz. Resampling to $SAMPLE_RATE_HZ Hz...")

        // Resample from sourceSampleRate to 16,000 Hz using linear interpolation
        val resampledCount = ((sourcePcm.size.toLong() * SAMPLE_RATE_HZ) / sourceSampleRate).toInt()
        val resampledPcm = ShortArray(resampledCount)
        val ratio = sourceSampleRate.toDouble() / SAMPLE_RATE_HZ.toDouble()

        for (i in 0 until resampledCount) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s0 = if (srcIdx < sourcePcm.size) sourcePcm[srcIdx] else 0
            val s1 = if (srcIdx + 1 < sourcePcm.size) sourcePcm[srcIdx + 1] else s0
            val interpolated = (s0 * (1.0 - frac) + s1 * frac).toInt().coerceIn(-32768, 32767)
            resampledPcm[i] = interpolated.toShort()
        }

        // Generate 500ms 800Hz sine-wave beep tone (amplitude = 20000)
        val beepDurationMs = 500
        val beepSampleCount = (SAMPLE_RATE_HZ * beepDurationMs) / 1000
        val beepSamples = ShortArray(beepSampleCount)
        val beepAmp = 20000.0

        for (i in 0 until beepSampleCount) {
            val attack = if (i < 160) (i / 160.0) else 1.0
            val release = if (i > beepSampleCount - 160) ((beepSampleCount - i) / 160.0) else 1.0
            val angle = 2.0 * PI * i * 800.0 / SAMPLE_RATE_HZ
            val s = (sin(angle) * beepAmp * attack * release).toInt().coerceIn(-32768, 32767)
            beepSamples[i] = s.toShort()
        }

        // 100ms silence between voice and beep, and 150ms tail silence
        val pauseSamples = ShortArray((SAMPLE_RATE_HZ * 0.100).toInt())
        val tailSamples = ShortArray((SAMPLE_RATE_HZ * 0.150).toInt())

        val totalFinalSamples = resampledPcm.size + pauseSamples.size + beepSamples.size + tailSamples.size
        val finalPcmBytes = ByteArray(totalFinalSamples * 2)
        val buf = ByteBuffer.wrap(finalPcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (s in resampledPcm) buf.putShort(s)
        for (s in pauseSamples) buf.putShort(0)
        for (s in beepSamples) buf.putShort(s)
        for (s in tailSamples) buf.putShort(0)

        // Write final 16kHz mono WAV file
        val targetFile = File(context.filesDir, DEFAULT_GREETING_FILE_NAME)
        val dirGreeting = File(File(context.filesDir, GREETING_DIR_NAME), DEFAULT_GREETING_FILE_NAME)

        FileOutputStream(targetFile).use { fos ->
            writeWavHeader(
                out = fos,
                totalAudioLen = finalPcmBytes.size.toLong(),
                sampleRate = SAMPLE_RATE_HZ,
                channels = CHANNELS,
                bitsPerSample = BITS_PER_SAMPLE
            )
            fos.write(finalPcmBytes)
            fos.flush()
        }

        runCatching { targetFile.copyTo(dirGreeting, overwrite = true) }
        stageToTmpIfPossible(targetFile)

        // Read back PCM samples and verify peak amplitude
        var maxSample = 0
        for (i in 0 until totalFinalSamples) {
            val s = kotlin.math.abs(buf.getShort(i * 2).toInt())
            if (s > maxSample) maxSample = s
        }

        val durationSec = totalFinalSamples.toFloat() / SAMPLE_RATE_HZ
        Log.d(TAG, "WAV ready: size=${targetFile.length()} peak=$maxSample")
        Log.i(TAG, "Spoken voice greeting generated with 800Hz beep! File: ${targetFile.absolutePath}, Size: ${targetFile.length()} bytes, Duration: ${durationSec}s, Peak: $maxSample")
        return targetFile.length() > 64000 && maxSample > 5000
    }

    /**
     * Programmatically synthesizes an immediate 4-second 16kHz Mono WAV greeting
     * (warm telecom chime + speech cadence pulses + 800Hz beep) so the system is NEVER empty.
     */
    fun generateImmediateFallbackGreeting(outputFile: File) {
        Log.i(TAG, "Synthesizing immediate fallback 16kHz mono WAV greeting at: ${outputFile.absolutePath}")
        val pcmData = ArrayList<Short>()

        // 1. Initial comfort silence
        appendSilence(pcmData, 150)

        // 2. Warm opening chime (C5 -> E5 -> G5 -> C6)
        appendChimeNote(pcmData, frequencyHz = 523.25, durationMs = 180, peakAmplitude = 0.55)
        appendChimeNote(pcmData, frequencyHz = 659.25, durationMs = 180, peakAmplitude = 0.60)
        appendChimeNote(pcmData, frequencyHz = 783.99, durationMs = 220, peakAmplitude = 0.65)
        appendChimeNote(pcmData, frequencyHz = 1046.50, durationMs = 380, peakAmplitude = 0.70)

        appendSilence(pcmData, 250)

        // 3. Cadence pulses
        appendSpeechPulse(pcmData, durationMs = 220, baseFreq = 300.0) // "Please"
        appendSilence(pcmData, 80)
        appendSpeechPulse(pcmData, durationMs = 200, baseFreq = 340.0) // "leave"
        appendSilence(pcmData, 60)
        appendSpeechPulse(pcmData, durationMs = 160, baseFreq = 320.0) // "your"
        appendSilence(pcmData, 60)
        appendSpeechPulse(pcmData, durationMs = 300, baseFreq = 280.0) // "message"
        appendSilence(pcmData, 120)
        appendSpeechPulse(pcmData, durationMs = 180, baseFreq = 320.0) // "after"
        appendSilence(pcmData, 60)
        appendSpeechPulse(pcmData, durationMs = 160, baseFreq = 350.0) // "the"
        appendSilence(pcmData, 60)
        appendSpeechPulse(pcmData, durationMs = 350, baseFreq = 400.0) // "tone."

        appendSilence(pcmData, 300)

        // 4. Standard 800 Hz beep tone (400ms, amplitude 16000)
        appendSineTone(pcmData, frequencyHz = 800.0, durationMs = 400, amplitude = 0.60)

        appendSilence(pcmData, 150)

        val pcmBytes = ByteArray(pcmData.size * 2)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmData) {
            byteBuffer.putShort(sample)
        }

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(
                out = fos,
                totalAudioLen = pcmBytes.size.toLong(),
                sampleRate = SAMPLE_RATE_HZ,
                channels = CHANNELS,
                bitsPerSample = BITS_PER_SAMPLE
            )
            fos.write(pcmBytes)
            fos.flush()
        }
        Log.i(TAG, "Immediate fallback greeting synthesized: ${outputFile.length()} bytes")
    }

    private fun appendSilence(buffer: ArrayList<Short>, durationMs: Int) {
        val count = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        for (i in 0 until count) {
            buffer.add(0)
        }
    }

    private fun appendSineTone(buffer: ArrayList<Short>, frequencyHz: Double, durationMs: Int, amplitude: Double) {
        val totalSamples = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        val maxAmp = 32767.0 * amplitude
        val attackSamples = (SAMPLE_RATE_HZ * 0.01).toInt()
        val releaseSamples = (SAMPLE_RATE_HZ * 0.02).toInt()

        for (i in 0 until totalSamples) {
            var env = 1.0
            if (i < attackSamples) {
                env = i.toDouble() / attackSamples
            } else if (i > totalSamples - releaseSamples) {
                env = (totalSamples - i).toDouble() / releaseSamples
            }
            val angle = 2.0 * PI * i * frequencyHz / SAMPLE_RATE_HZ
            val sampleVal = (sin(angle) * maxAmp * env).toInt().coerceIn(-32768, 32767)
            buffer.add(sampleVal.toShort())
        }
    }

    private fun appendChimeNote(buffer: ArrayList<Short>, frequencyHz: Double, durationMs: Int, peakAmplitude: Double) {
        val totalSamples = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        val maxAmp = 32767.0 * peakAmplitude

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val progress = i.toDouble() / totalSamples
            val decay = kotlin.math.exp(-3.5 * progress)
            val attack = if (i < (SAMPLE_RATE_HZ * 0.015)) (i / (SAMPLE_RATE_HZ * 0.015)) else 1.0

            val fundamental = sin(2.0 * PI * frequencyHz * t)
            val overtone2 = 0.25 * sin(2.0 * PI * (frequencyHz * 2.0) * t)
            val overtone3 = 0.10 * sin(2.0 * PI * (frequencyHz * 3.0) * t)

            val sampleVal = ((fundamental + overtone2 + overtone3) * maxAmp * decay * attack)
                .toInt()
                .coerceIn(-32768, 32767)

            buffer.add(sampleVal.toShort())
        }
    }

    private fun appendSpeechPulse(buffer: ArrayList<Short>, durationMs: Int, baseFreq: Double) {
        val totalSamples = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        val maxAmp = 32767.0 * 0.40

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val progress = i.toDouble() / totalSamples
            val env = sin(PI * progress)

            val f0 = sin(2.0 * PI * baseFreq * t)
            val f1 = 0.4 * sin(2.0 * PI * (baseFreq * 2.8) * t)
            val f2 = 0.2 * sin(2.0 * PI * (baseFreq * 5.2) * t)

            val sampleVal = ((f0 + f1 + f2) * maxAmp * env).toInt().coerceIn(-32768, 32767)
            buffer.add(sampleVal.toShort())
        }
    }

    /**
     * Writes standard 44-byte RIFF/WAVE header to an OutputStream.
     */
    fun writeWavHeader(
        out: OutputStream,
        totalAudioLen: Long,
        sampleRate: Int = SAMPLE_RATE_HZ,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen.toInt())
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // PCM subchunk1 size
        buffer.putShort(1) // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt())
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())

        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(totalAudioLen.toInt())

        out.write(header, 0, 44)
    }

    /**
     * Updates an existing WAV file's 44-byte header in place.
     */
    fun finalizeWavHeaderInPlace(file: File) {
        if (!file.exists()) return
        val totalFileLen = file.length()
        if (totalFileLen < 44) return

        val audioPayloadLen = totalFileLen - 44
        val totalDataLen = audioPayloadLen + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLittleEndian(totalDataLen.toInt()))
            raf.seek(40)
            raf.write(intToLittleEndian(audioPayloadLen.toInt()))
        }
        Log.i(TAG, "Finalized WAV header for ${file.name}: payload=${audioPayloadLen}B, total=${totalFileLen}B")
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
