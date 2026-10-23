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
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Generates and manages peak-normalized (28,000 peak amplitude) 16kHz Mono WAV greetings.
 */
object GreetingGenerator {

    private const val TAG = "GreetingGenerator"
    private const val GREETING_DIR_NAME = "greetings"
    private const val GREETING_VERSION_MARKER = "greeting_v2_normalized.marker"
    const val DEFAULT_GREETING_FILE_NAME = "default_greeting.wav"
    const val CUSTOM_GREETING_FILE_NAME = "custom_greeting.wav"
    const val GREETING_SPOKEN_TEXT = "Hello, I cannot take your call right now. Please leave your message after the beep."

    const val SAMPLE_RATE_HZ = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    @Volatile
    private var isGeneratingTts = false

    @Synchronized
    fun getActiveGreetingFile(context: Context): File {
        val rootGreeting = File(context.filesDir, DEFAULT_GREETING_FILE_NAME)
        val dir = File(context.filesDir, GREETING_DIR_NAME).apply { mkdirs() }
        val versionMarker = File(dir, GREETING_VERSION_MARKER)
        val customGreeting = File(dir, CUSTOM_GREETING_FILE_NAME)

        if (customGreeting.exists() && customGreeting.length() > 44) {
            return customGreeting
        }

        val dirGreeting = File(dir, DEFAULT_GREETING_FILE_NAME)

        if (!versionMarker.exists()) {
            runCatching { rootGreeting.delete() }
            runCatching { dirGreeting.delete() }
        }

        if (rootGreeting.exists() && rootGreeting.length() >= 64000 && versionMarker.exists()) {
            if (!dirGreeting.exists() || dirGreeting.length() != rootGreeting.length()) {
                runCatching { rootGreeting.copyTo(dirGreeting, overwrite = true) }
            }
            stageToTmpIfPossible(rootGreeting)
            return rootGreeting
        }

        if (dirGreeting.exists() && dirGreeting.length() >= 64000 && versionMarker.exists()) {
            runCatching { dirGreeting.copyTo(rootGreeting, overwrite = true) }
            stageToTmpIfPossible(rootGreeting)
            return rootGreeting
        }

        generateImmediateFallbackGreeting(rootGreeting)
        runCatching { rootGreeting.copyTo(dirGreeting, overwrite = true) }
        stageToTmpIfPossible(rootGreeting)
        generateVoiceGreetingTts(context)

        return rootGreeting
    }

    fun stageToTmpIfPossible(sourceFile: File) {
        try {
            val tmpFile = File("/data/local/tmp/voicemail_greeting.wav")
            sourceFile.copyTo(tmpFile, overwrite = true)
            tmpFile.setReadable(true, false)
        } catch (t: Throwable) {}
    }

    fun getGreetingAssetFileDescriptor(context: Context): AssetFileDescriptor {
        val file = getActiveGreetingFile(context)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    fun generateVoiceGreetingTts(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        if (isGeneratingTts) {
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
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) {
                                try {
                                    val success = processTtsWavTo16kWithBeep(tempTtsFile, appContext)
                                    isGeneratingTts = false
                                    engine.shutdown()
                                    onComplete?.invoke(success)
                                } catch (t: Throwable) {
                                    isGeneratingTts = false
                                    engine.shutdown()
                                    onComplete?.invoke(false)
                                } finally {
                                    tempTtsFile.delete()
                                }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) {
                                isGeneratingTts = false
                                engine.shutdown()
                                onComplete?.invoke(false)
                            }
                            override fun onError(id: String?, errorCode: Int) {
                                isGeneratingTts = false
                                engine.shutdown()
                                onComplete?.invoke(false)
                            }
                        })

                        val params = Bundle().apply {
                            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
                        }

                        engine.synthesizeToFile(GREETING_SPOKEN_TEXT, params, tempTtsFile, utteranceId)
                    } catch (t: Throwable) {
                        isGeneratingTts = false
                        engine.shutdown()
                        onComplete?.invoke(false)
                    }
                }
            }
        }
    }

    private fun processTtsWavTo16kWithBeep(sourceWav: File, context: Context): Boolean {
        if (!sourceWav.exists() || sourceWav.length() < 44) return false
        val fileBytes = sourceWav.readBytes()
        if (fileBytes.size < 44) return false

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
                sourceChannels = ByteBuffer.wrap(fileBytes, offset + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                sourceSampleRate = ByteBuffer.wrap(fileBytes, offset + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                bitsPerSample = ByteBuffer.wrap(fileBytes, offset + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
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

        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * sourceChannels
        val totalFrames = dataLen / frameSize

        val sourcePcm = ShortArray(totalFrames)
        var byteIdx = dataOffset
        for (i in 0 until totalFrames) {
            if (byteIdx + frameSize > fileBytes.size) break
            var monoSample = 0
            for (ch in 0 until sourceChannels) {
                val s = (fileBytes[byteIdx + ch * 2].toInt() and 0xFF or (fileBytes[byteIdx + ch * 2 + 1].toInt() shl 8)).toShort()
                monoSample += s
            }
            sourcePcm[i] = (monoSample / sourceChannels).toShort()
            byteIdx += frameSize
        }

        val resampledCount = ((sourcePcm.size.toLong() * SAMPLE_RATE_HZ) / sourceSampleRate).toInt()
        val resampledPcm = ShortArray(resampledCount)
        val ratio = sourceSampleRate.toDouble() / SAMPLE_RATE_HZ.toDouble()

        var rawSpeechPeak = 1
        for (i in 0 until resampledCount) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s0 = if (srcIdx < sourcePcm.size) sourcePcm[srcIdx] else 0
            val s1 = if (srcIdx + 1 < sourcePcm.size) sourcePcm[srcIdx + 1] else s0
            val interpolated = (s0 * (1.0 - frac) + s1 * frac).toInt().coerceIn(-32768, 32767)
            if (abs(interpolated) > rawSpeechPeak) rawSpeechPeak = abs(interpolated)
            resampledPcm[i] = interpolated.toShort()
        }

        val normGain = (28000.0 / rawSpeechPeak.toDouble()).coerceIn(1.0, 8.0)
        for (i in 0 until resampledCount) {
            val boosted = (resampledPcm[i].toInt() * normGain).toInt().coerceIn(-32768, 32767)
            resampledPcm[i] = boosted.toShort()
        }

        val leadInSamples = ShortArray((SAMPLE_RATE_HZ * 0.200).toInt())
        val beepDurationMs = 500
        val beepSampleCount = (SAMPLE_RATE_HZ * beepDurationMs) / 1000
        val beepSamples = ShortArray(beepSampleCount)
        val beepAmp = 26000.0

        for (i in 0 until beepSampleCount) {
            val attack = if (i < 160) (i / 160.0) else 1.0
            val release = if (i > beepSampleCount - 160) ((beepSampleCount - i) / 160.0) else 1.0
            val angle = 2.0 * PI * i * 800.0 / SAMPLE_RATE_HZ
            val s = (sin(angle) * beepAmp * attack * release).toInt().coerceIn(-32768, 32767)
            beepSamples[i] = s.toShort()
        }

        val pauseSamples = ShortArray((SAMPLE_RATE_HZ * 0.120).toInt())
        val tailSamples = ShortArray((SAMPLE_RATE_HZ * 0.150).toInt())

        val totalFinalSamples = leadInSamples.size + resampledPcm.size + pauseSamples.size + beepSamples.size + tailSamples.size
        val finalPcmBytes = ByteArray(totalFinalSamples * 2)
        val buf = ByteBuffer.wrap(finalPcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (s in leadInSamples) buf.putShort(0)
        for (s in resampledPcm) buf.putShort(s)
        for (s in pauseSamples) buf.putShort(0)
        for (s in beepSamples) buf.putShort(s)
        for (s in tailSamples) buf.putShort(0)

        val targetFile = File(context.filesDir, DEFAULT_GREETING_FILE_NAME)
        val dir = File(context.filesDir, GREETING_DIR_NAME).apply { mkdirs() }
        val dirGreeting = File(dir, DEFAULT_GREETING_FILE_NAME)
        val versionMarker = File(dir, GREETING_VERSION_MARKER)

        FileOutputStream(targetFile).use { fos ->
            writeWavHeader(fos, finalPcmBytes.size.toLong(), SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE)
            fos.write(finalPcmBytes)
            fos.flush()
        }

        runCatching { targetFile.copyTo(dirGreeting, overwrite = true) }
        runCatching { versionMarker.writeText("v2_normalized") }
        stageToTmpIfPossible(targetFile)
        return targetFile.length() > 64000
    }

    fun generateImmediateFallbackGreeting(outputFile: File) {
        val pcmData = ArrayList<Short>()
        appendSilence(pcmData, 200)
        appendChimeNote(pcmData, 523.25, 180, 0.80)
        appendChimeNote(pcmData, 659.25, 180, 0.80)
        appendChimeNote(pcmData, 783.99, 220, 0.85)
        appendChimeNote(pcmData, 1046.50, 380, 0.85)
        appendSilence(pcmData, 250)
        appendSineTone(pcmData, 800.0, 450, 0.80)
        appendSilence(pcmData, 150)

        val pcmBytes = ByteArray(pcmData.size * 2)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmData) byteBuffer.putShort(sample)

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(fos, pcmBytes.size.toLong(), SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE)
            fos.write(pcmBytes)
            fos.flush()
        }
    }

    private fun appendSilence(buffer: ArrayList<Short>, durationMs: Int) {
        val count = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        for (i in 0 until count) buffer.add(0)
    }

    private fun appendSineTone(buffer: ArrayList<Short>, frequencyHz: Double, durationMs: Int, amplitude: Double) {
        val totalSamples = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        val maxAmp = 32767.0 * amplitude
        for (i in 0 until totalSamples) {
            val angle = 2.0 * PI * i * frequencyHz / SAMPLE_RATE_HZ
            buffer.add((sin(angle) * maxAmp).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun appendChimeNote(buffer: ArrayList<Short>, frequencyHz: Double, durationMs: Int, peakAmplitude: Double) {
        val totalSamples = (SAMPLE_RATE_HZ * (durationMs / 1000.0)).toInt()
        val maxAmp = 32767.0 * peakAmplitude
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val decay = kotlin.math.exp(-3.5 * (i.toDouble() / totalSamples))
            buffer.add((sin(2.0 * PI * frequencyHz * t) * maxAmp * decay).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    fun writeWavHeader(out: OutputStream, totalAudioLen: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put('R'.code.toByte()); buffer.put('I'.code.toByte()); buffer.put('F'.code.toByte()); buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen.toInt())
        buffer.put('W'.code.toByte()); buffer.put('A'.code.toByte()); buffer.put('V'.code.toByte()); buffer.put('E'.code.toByte())
        buffer.put('f'.code.toByte()); buffer.put('m'.code.toByte()); buffer.put('t'.code.toByte()); buffer.put(' '.code.toByte())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(channels.toShort()); buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt()); buffer.putShort(blockAlign); buffer.putShort(bitsPerSample.toShort())
        buffer.put('d'.code.toByte()); buffer.put('a'.code.toByte()); buffer.put('t'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.putInt(totalAudioLen.toInt())
        out.write(header, 0, 44)
    }
}
