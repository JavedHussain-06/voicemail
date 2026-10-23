package com.personal.voicemail.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import com.personal.voicemail.service.VoicemailAccessibilityService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Production-grade Standalone Telephony Audio Engine.
 * Manages unprivileged local fallback audio injection and downlink capture.
 */
class StandaloneTelephonyAudioEngine {

    companion object {
        private const val TAG = "StandaloneAudioEngine"
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SAMPLES = 1600
        private const val CHUNK_BYTES = CHUNK_SAMPLES * 2

        private const val USAGE_CALL_ASSISTANT_HIDDEN = 17
        private const val AUDIO_SOURCE_VOICE_DOWNLINK = 3
        private const val AUDIO_SOURCE_VOICE_CALL = 4
        private const val AUDIO_SOURCE_VOICE_RECOGNITION = 6
        private const val AUDIO_SOURCE_VOICE_COMMUNICATION = 7

        private const val QUALCOMM_PARAM_UPLINK_ON = "incall_music_enabled=true;incall_music_uplink=true"
        private const val QUALCOMM_PARAM_UPLINK_OFF = "incall_music_enabled=false;incall_music_uplink=false"
        private const val MEDIATEK_PARAM_UPLINK_ON = "Set_SpeechCall_Music_Uplink=1"
        private const val MEDIATEK_PARAM_UPLINK_OFF = "Set_SpeechCall_Music_Uplink=0"
        private const val SAMSUNG_PARAM_UPLINK_ON = "g_call_sim_voice_uplink=true"
        private const val SAMSUNG_PARAM_UPLINK_OFF = "g_call_sim_voice_uplink=false"
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    private val isSessionActive = AtomicBoolean(false)
    private val isGreetingPlaying = AtomicBoolean(false)
    private val isCaptureActive = AtomicBoolean(false)

    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private val activePlaybackTracks = mutableListOf<AudioTrack>()

    private var activeAudioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private var savedMusicVolume = -1
    private var savedVoiceCallVolume = -1

    fun hasDigitalTelephonyPrivilege(context: Context): Boolean {
        val hasCapture = context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
        val hasRouting = context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED
        return hasCapture && hasRouting
    }

    fun playGreeting(context: Context, onFinished: () -> Unit) {
        if (isSessionActive.getAndSet(true)) {
            stopSession()
            isSessionActive.set(true)
        }

        isGreetingPlaying.set(true)

        playbackExecutor.execute {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val hasDigitalPrivilege = hasDigitalTelephonyPrivilege(context)
            Log.i(TAG, "Starting Standalone Phase A Greeting Injection (hasDigitalPrivilege=$hasDigitalPrivilege)")

            runCatching {
                savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                savedVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                if (audioManager.mode != AudioManager.MODE_IN_CALL) {
                    audioManager.mode = AudioManager.MODE_IN_CALL
                }
                audioManager.isSpeakerphoneOn = false
                audioManager.isMicrophoneMute = false

                audioManager.setParameters(QUALCOMM_PARAM_UPLINK_ON)
                audioManager.setParameters(MEDIATEK_PARAM_UPLINK_ON)
                audioManager.setParameters(SAMSUNG_PARAM_UPLINK_ON)

                val maxVoiceVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val earpieceVol = (maxVoiceVol * 0.75f).toInt().coerceAtLeast(4)
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, earpieceVol, 0)
                audioManager.setParameters("ec_supported=false;ns_supported=false;fluence=none")
            }

            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephonyOutputDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            val earpieceDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val targetDevice = telephonyOutputDevice ?: earpieceDevice

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBufferSize = max(minBufferSize, CHUNK_BYTES * 8)
            val trackContext = VoicemailAccessibilityService.instance ?: context

            fun buildTrack(attrs: AudioAttributes, preferredDevice: AudioDeviceInfo?): AudioTrack? {
                return try {
                    val builder = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(trackBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)

                    runCatching {
                        HiddenApiBypass.invoke(
                            AudioTrack.Builder::class.java,
                            builder,
                            "setContext",
                            trackContext
                        )
                    }

                    val candidate = builder.build()
                    if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                        if (preferredDevice != null) {
                            runCatching { candidate.setPreferredDevice(preferredDevice) }
                        }
                        candidate
                    } else {
                        candidate.release()
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
            }

            synchronized(activePlaybackTracks) {
                activePlaybackTracks.clear()

                val signallingAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                buildTrack(signallingAttrs, targetDevice)?.let { tSig ->
                    tSig.setVolume(1.0f)
                    tSig.play()
                    activePlaybackTracks.add(tSig)
                }

                buildTrack(buildCallAssistantAttributes(), targetDevice)?.let { tAssist ->
                    tAssist.setVolume(1.0f)
                    tAssist.play()
                    activePlaybackTracks.add(tAssist)
                }

                val voiceAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                buildTrack(voiceAttrs, earpieceDevice ?: targetDevice)?.let { tVoice ->
                    tVoice.setVolume(1.0f)
                    tVoice.play()
                    activePlaybackTracks.add(tVoice)
                }
            }

            val greetingFile = GreetingGenerator.getActiveGreetingFile(context)
            val rawPcm = readWavPayload(greetingFile)
            val pcmPayload = ByteArray(rawPcm.size)
            for (i in 0 until rawPcm.size - 1 step 2) {
                val sample = (rawPcm[i].toInt() and 0xFF or (rawPcm[i + 1].toInt() shl 8)).toShort()
                val boosted = (sample * 1.5f).toInt().coerceIn(-32768, 32767).toShort()
                pcmPayload[i] = (boosted.toInt() and 0xFF).toByte()
                pcmPayload[i + 1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
            }

            // Pre-fill buffer before playback loop
            synchronized(activePlaybackTracks) {
                for (t in activePlaybackTracks) {
                    runCatching { t.write(pcmPayload, 0, minOf(trackBufferSize, pcmPayload.size)) }
                }
            }

            var offset = minOf(trackBufferSize, pcmPayload.size)
            while (isSessionActive.get() && isGreetingPlaying.get() && offset < pcmPayload.size) {
                val len = minOf(CHUNK_BYTES, pcmPayload.size - offset)
                if (audioManager.isSpeakerphoneOn) {
                    audioManager.isSpeakerphoneOn = false
                }
                synchronized(activePlaybackTracks) {
                    val iterator = activePlaybackTracks.iterator()
                    while (iterator.hasNext()) {
                        val t = iterator.next()
                        if (t.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            runCatching { t.pause(); t.flush(); t.stop(); t.release() }
                            iterator.remove()
                            continue
                        }
                        runCatching { t.write(pcmPayload, offset, len, AudioTrack.WRITE_NON_BLOCKING) }
                    }
                }
                offset += len
                try {
                    Thread.sleep(85)
                } catch (e: InterruptedException) {
                    break
                }
            }

            if (isSessionActive.get() && isGreetingPlaying.get()) {
                try { Thread.sleep(400) } catch (ignored: InterruptedException) {}
            }

            cleanupPlaybackTracks()

            runCatching {
                audioManager.setParameters(QUALCOMM_PARAM_UPLINK_OFF)
                audioManager.setParameters(MEDIATEK_PARAM_UPLINK_OFF)
                audioManager.setParameters(SAMSUNG_PARAM_UPLINK_OFF)
                if (savedMusicVolume >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                }
            }

            isGreetingPlaying.set(false)
            if (isSessionActive.get()) {
                onFinished()
            }
        }
    }

    fun startCapture(context: Context, onPcmChunk: (ShortArray) -> Unit) {
        if (!isSessionActive.get()) {
            isSessionActive.set(true)
        }

        if (isCaptureActive.getAndSet(true)) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val captureContext = VoicemailAccessibilityService.instance ?: context
            val hasPrivilege = hasDigitalTelephonyPrivilege(context)

            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val telephonyInput = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recordBufferSize = max(minBufSize, CHUNK_SAMPLES * 2 * 4)

            data class SourceCandidate(
                val name: String,
                val sourceId: Int,
                val useInternalPreset: Boolean,
                val routeToTelephony: Boolean
            )

            val candidates = mutableListOf<SourceCandidate>()
            if (hasPrivilege) {
                candidates.add(SourceCandidate("VOICE_DOWNLINK (3)", AUDIO_SOURCE_VOICE_DOWNLINK, true, true))
                candidates.add(SourceCandidate("VOICE_CALL (4)", AUDIO_SOURCE_VOICE_CALL, true, true))
            }
            candidates.add(SourceCandidate("VOICE_RECOGNITION (6)", AUDIO_SOURCE_VOICE_RECOGNITION, false, false))
            candidates.add(SourceCandidate("VOICE_COMMUNICATION (7)", AUDIO_SOURCE_VOICE_COMMUNICATION, false, false))
            candidates.add(SourceCandidate("MIC (1)", MediaRecorder.AudioSource.MIC, false, false))

            fun createAudioRecord(candidate: SourceCandidate): AudioRecord? {
                return try {
                    var record: AudioRecord? = null
                    try {
                        val attrBuilder = AudioAttributes.Builder()
                        val presetApplied = runCatching {
                            HiddenApiBypass.invoke(
                                AudioAttributes.Builder::class.java,
                                attrBuilder,
                                "setInternalCapturePreset",
                                candidate.sourceId
                            )
                            true
                        }.getOrDefault(false)

                        val recBuilder = AudioRecord.Builder()
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE_HZ)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(recordBufferSize)

                        runCatching {
                            HiddenApiBypass.invoke(
                                AudioRecord.Builder::class.java,
                                recBuilder,
                                "setContext",
                                captureContext
                            )
                        }

                        if (presetApplied) {
                            runCatching {
                                HiddenApiBypass.invoke(
                                    AudioRecord.Builder::class.java,
                                    recBuilder,
                                    "setAudioAttributes",
                                    attrBuilder.build()
                                )
                            }
                        } else if (candidate.sourceId != AUDIO_SOURCE_VOICE_DOWNLINK && candidate.sourceId != AUDIO_SOURCE_VOICE_CALL) {
                            recBuilder.setAudioSource(candidate.sourceId)
                        }

                        val candidateRec = recBuilder.build()
                        if (candidateRec.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidateRec
                        } else {
                            candidateRec.release()
                        }
                    } catch (ignored: Throwable) {}

                    if (record == null) {
                        try {
                            @Suppress("DEPRECATION")
                            val fallbackRec = AudioRecord(
                                candidate.sourceId,
                                SAMPLE_RATE_HZ,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                recordBufferSize
                            )
                            if (fallbackRec.state == AudioRecord.STATE_INITIALIZED) {
                                record = fallbackRec
                            } else {
                                fallbackRec.release()
                            }
                        } catch (ignored: Throwable) {}
                    }

                    val finalRec = record ?: return null
                    if (candidate.routeToTelephony && telephonyInput != null && hasPrivilege) {
                        runCatching { finalRec.setPreferredDevice(telephonyInput) }
                    }
                    finalRec.startRecording()
                    if (finalRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) finalRec else {
                        finalRec.release()
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
            }

            var currentCandidateIndex = 0
            var currentRecord: AudioRecord? = null

            while (currentCandidateIndex < candidates.size) {
                currentRecord = createAudioRecord(candidates[currentCandidateIndex])
                if (currentRecord != null) break
                currentCandidateIndex++
            }

            if (currentRecord == null) {
                isCaptureActive.set(false)
                return@Thread
            }

            activeAudioRecord = currentRecord
            val rawBuffer = ShortArray(CHUNK_SAMPLES)
            var currentGain = 4.0f

            while (isSessionActive.get() && isCaptureActive.get()) {
                val record = activeAudioRecord ?: break
                if (!hasPrivilege && audioManager.isMicrophoneMute) {
                    audioManager.isMicrophoneMute = false
                }

                val read = record.read(rawBuffer, 0, rawBuffer.size)
                if (read > 0) {
                    var peak = 0
                    for (i in 0 until read) {
                        val absVal = abs(rawBuffer[i].toInt())
                        if (absVal > peak) peak = absVal
                    }

                    if (peak in 50..12000) {
                        val targetGain = (20000.0f / peak).coerceIn(1.0f, 15.0f)
                        currentGain = (currentGain * 0.7f) + (targetGain * 0.3f)
                    }

                    val boosted = ShortArray(read)
                    for (i in 0 until read) {
                        boosted[i] = (rawBuffer[i].toFloat() * currentGain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    onPcmChunk(boosted)
                } else if (read < 0) {
                    try { Thread.sleep(50) } catch (ignored: Throwable) {}
                }
            }

            runCatching {
                currentRecord?.stop()
                currentRecord?.release()
            }
            activeAudioRecord = null
            isCaptureActive.set(false)
        }, "StandaloneAudioCaptureThread").apply { start() }
    }

    fun stopSession() {
        isSessionActive.set(false)
        isGreetingPlaying.set(false)
        isCaptureActive.set(false)
        cleanupPlaybackTracks()
        runCatching {
            activeAudioRecord?.stop()
            activeAudioRecord?.release()
        }
        activeAudioRecord = null
        captureThread?.interrupt()
        captureThread = null
    }

    private fun cleanupPlaybackTracks() {
        synchronized(activePlaybackTracks) {
            for (track in activePlaybackTracks) {
                runCatching { track.stop(); track.release() }
            }
            activePlaybackTracks.clear()
        }
    }

    private fun buildCallAssistantAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        val applied = runCatching {
            HiddenApiBypass.invoke(
                AudioAttributes.Builder::class.java,
                builder,
                "setSystemUsage",
                USAGE_CALL_ASSISTANT_HIDDEN
            )
            true
        }.getOrDefault(false)

        if (!applied) {
            builder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        }
        return builder.build()
    }

    private fun readWavPayload(file: File): ByteArray {
        if (!file.exists() || file.length() <= 44) {
            return ByteArray(SAMPLE_RATE_HZ * 2 * 3)
        }
        return try {
            FileInputStream(file).use { fis ->
                val fullBytes = fis.readBytes()
                if (fullBytes.size > 44) fullBytes.copyOfRange(44, fullBytes.size) else ByteArray(SAMPLE_RATE_HZ * 2 * 3)
            }
        } catch (t: Throwable) {
            ByteArray(SAMPLE_RATE_HZ * 2 * 3)
        }
    }
}
