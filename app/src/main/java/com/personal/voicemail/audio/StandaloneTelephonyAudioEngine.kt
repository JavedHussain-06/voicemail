package com.personal.voicemail.audio

import android.content.Context
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
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Production-grade Standalone Telephony Audio Engine.
 *
 * Coordinates 100% in-process telephony audio routing and streaming without requiring
 * an external Shizuku application, PC ADB connection, or root privileges:
 *
 * Phase A: Standalone Cellular Uplink Greeting Injection
 *   - Configures Qualcomm & MediaTek DSP HAL parameters for incall music uplink.
 *   - Concurrently drives Call Assistant / Voice Communication and companion USAGE_MEDIA AudioTracks
 *     routed to TYPE_TELEPHONY or Earpiece so the greeting streams to the caller while the room speaker stays silent.
 *
 * Phase B: Standalone Cellular Downlink Capture
 *   - Iterates through priority capture sources (internal preset 3, VOICE_DOWNLINK, VOICE_CALL,
 *     VOICE_RECOGNITION, VOICE_COMMUNICATION) routed to TYPE_TELEPHONY.
 *   - Reads 100ms 16kHz PCM chunks on a THREAD_PRIORITY_URGENT_AUDIO worker thread.
 *   - Applies 2x software gain clamping and submits chunks to Sherpa-ONNX live transcriber.
 */
class StandaloneTelephonyAudioEngine {

    companion object {
        private const val TAG = "StandaloneAudioEngine"
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val CHUNK_BYTES = CHUNK_SAMPLES * 2 // 3200 bytes

        private const val USAGE_CALL_ASSISTANT_HIDDEN = 17
        private const val AUDIO_SOURCE_VOICE_DOWNLINK = 3
        private const val AUDIO_SOURCE_VOICE_CALL = 4
        private const val AUDIO_SOURCE_VOICE_RECOGNITION = 6
        private const val AUDIO_SOURCE_VOICE_COMMUNICATION = 7

        private const val QUALCOMM_PARAM_INCALL_MUSIC_ON =
            "incall_music_enabled=true;incall_music_uplink=true;music_playback_during_call=true"
        private const val QUALCOMM_PARAM_INCALL_MUSIC_OFF =
            "incall_music_enabled=false;incall_music_uplink=false;music_playback_during_call=false"
        private const val MEDIATEK_PARAM_UPLINK_ON = "Set_SpeechCall_Music_Uplink=1"
        private const val MEDIATEK_PARAM_UPLINK_OFF = "Set_SpeechCall_Music_Uplink=0"
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val exemptSuccess = HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass exemptions applied in StandaloneTelephonyAudioEngine: $exemptSuccess")
            }.onFailure { t ->
                Log.e(TAG, "Error applying HiddenApiBypass in StandaloneTelephonyAudioEngine init", t)
            }
        }
    }

    private val isSessionActive = AtomicBoolean(false)
    private val isGreetingPlaying = AtomicBoolean(false)
    private val isCaptureActive = AtomicBoolean(false)

    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private var activePlaybackTracks = mutableListOf<AudioTrack>()

    private var activeAudioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private var savedMusicVolume = -1
    private var savedVoiceCallVolume = -1

    /**
     * Phase A: Standalone Uplink Greeting Injection.
     * Streams the active greeting WAV file to the cellular uplink while keeping room speaker silent.
     */
    fun playGreeting(context: Context, onFinished: () -> Unit) {
        if (isSessionActive.getAndSet(true)) {
            Log.w(TAG, "playGreeting called while session is already active. Stopping previous session.")
            stopSession()
            isSessionActive.set(true)
        }

        isGreetingPlaying.set(true)

        playbackExecutor.execute {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.i(TAG, "Starting Standalone Phase A Greeting Injection")

            // 1. Save volumes and configure hardware HAL parameters
            runCatching {
                savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                savedVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                audioManager.isSpeakerphoneOn = false
                audioManager.isMicrophoneMute = true
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)

                // Qualcomm & MediaTek in-call music uplink HAL activation
                audioManager.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_ON)
                audioManager.setParameters(MEDIATEK_PARAM_UPLINK_ON)
            }.onFailure { t ->
                Log.w(TAG, "Failed setting audio HAL parameters: ${t.message}")
            }

            // 2. Discover telephony and earpiece output devices
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephonyOutputDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            val earpieceDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val preferredTargetDevice = telephonyOutputDevice ?: earpieceDevice

            Log.i(TAG, "Phase A Routing: telephonyDevice=${telephonyOutputDevice?.id}, earpieceDevice=${earpieceDevice?.id}")

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
            val trackBufferSize = max(minBufferSize, CHUNK_BYTES * 4)

            fun createAudioTrack(attrs: AudioAttributes, preferredDevice: AudioDeviceInfo?): AudioTrack? {
                return try {
                    val builder = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(trackBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)

                    val track = builder.build()
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        if (preferredDevice != null) {
                            runCatching { track.setPreferredDevice(preferredDevice) }
                        }
                        track
                    } else {
                        track.release()
                        null
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed creating AudioTrack: ${t.message}")
                    null
                }
            }

            synchronized(activePlaybackTracks) {
                activePlaybackTracks.clear()

                // Primary Track: USAGE_CALL_ASSISTANT (17) or USAGE_VOICE_COMMUNICATION routed to TYPE_TELEPHONY / Earpiece
                val callAssistantAttrs = buildCallAssistantAttributes()
                createAudioTrack(callAssistantAttrs, preferredTargetDevice)?.let { track1 ->
                    track1.setVolume(1.0f)
                    track1.play()
                    activePlaybackTracks.add(track1)
                    Log.i(TAG, "Phase A Track 1 (CALL_ASSISTANT/VOICE_COMM) active on device=${track1.routedDevice?.type}")
                }

                // Companion Track: USAGE_MEDIA routed to TYPE_TELEPHONY / Earpiece for Qualcomm DSP uplink mixer
                val mediaAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                createAudioTrack(mediaAttrs, preferredTargetDevice)?.let { track2 ->
                    runCatching {
                        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.7f).toInt().coerceAtLeast(3), 0)
                    }
                    track2.setVolume(1.0f)
                    track2.play()
                    if (track2.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && earpieceDevice != null) {
                        runCatching { track2.setPreferredDevice(earpieceDevice) }
                    }
                    activePlaybackTracks.add(track2)
                    Log.i(TAG, "Phase A Track 2 (MEDIA/QUALCOMM_UPLINK) active on device=${track2.routedDevice?.type}")
                }
            }

            // 3. Load active greeting WAV payload (skipping 44-byte RIFF header)
            val greetingFile = GreetingGenerator.getActiveGreetingFile(context)
            val pcmPayload = readWavPayload(greetingFile)

            Log.i(TAG, "Streaming greeting PCM payload: ${pcmPayload.size} bytes (${pcmPayload.size / (SAMPLE_RATE_HZ * 2)}s)")

            // 4. Stream PCM payload in 100ms chunks (3200 bytes)
            var offset = 0
            while (isSessionActive.get() && isGreetingPlaying.get() && offset < pcmPayload.size) {
                val len = minOf(CHUNK_BYTES, pcmPayload.size - offset)
                synchronized(activePlaybackTracks) {
                    for (track in activePlaybackTracks) {
                        runCatching { track.write(pcmPayload, offset, len) }
                    }
                }
                offset += len
            }

            // 5. Wait for audio pipeline completion
            val primaryTrack = synchronized(activePlaybackTracks) { activePlaybackTracks.firstOrNull() }
            val totalFrames = pcmPayload.size / 2
            val expectedDurationMs = (totalFrames * 1000L / SAMPLE_RATE_HZ)
            val startWait = System.currentTimeMillis()

            while (isSessionActive.get() && isGreetingPlaying.get() &&
                primaryTrack != null && primaryTrack.playbackHeadPosition < totalFrames &&
                (System.currentTimeMillis() - startWait) < (expectedDurationMs + 1200L)
            ) {
                try {
                    Thread.sleep(40)
                } catch (e: InterruptedException) {
                    break
                }
            }

            // 6. Release playback tracks and reset HAL parameters
            cleanupPlaybackTracks()

            runCatching {
                audioManager.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
                audioManager.setParameters(MEDIATEK_PARAM_UPLINK_OFF)
                if (savedMusicVolume >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                }
            }

            isGreetingPlaying.set(false)
            Log.i(TAG, "Standalone Phase A Greeting Playback completed successfully.")

            if (isSessionActive.get()) {
                onFinished()
            }
        }
    }

    /**
     * Phase B: Standalone Downlink Capture.
     * Captures remote caller's audio while keeping the local microphone muted.
     */
    fun startCapture(context: Context, onPcmChunk: (ShortArray) -> Unit) {
        if (!isSessionActive.get()) {
            isSessionActive.set(true)
        }

        if (isCaptureActive.getAndSet(true)) {
            Log.d(TAG, "startCapture called but capture is already active.")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Starting Standalone Phase B Downlink Capture Thread")

            // Keep physical microphone strictly muted so caller never hears user's room
            runCatching {
                audioManager.isSpeakerphoneOn = false
                audioManager.isMicrophoneMute = true
            }

            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val telephonyInput = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            Log.i(TAG, "Telephony input device detected: id=${telephonyInput?.id}")

            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recordBufferSize = max(minBufSize, CHUNK_SAMPLES * 2 * 4)

            // Ordered candidate sources per architectural requirements
            data class SourceCandidate(
                val name: String,
                val sourceId: Int,
                val useInternalPreset: Boolean,
                val routeToTelephony: Boolean
            )

            val candidates = listOf(
                SourceCandidate("Hidden setInternalCapturePreset(3) -> TYPE_TELEPHONY", AUDIO_SOURCE_VOICE_DOWNLINK, true, true),
                SourceCandidate("MediaRecorder.AudioSource.VOICE_DOWNLINK (3)", AUDIO_SOURCE_VOICE_DOWNLINK, false, false),
                SourceCandidate("MediaRecorder.AudioSource.VOICE_CALL (4) -> TYPE_TELEPHONY", AUDIO_SOURCE_VOICE_CALL, false, true),
                SourceCandidate("MediaRecorder.AudioSource.VOICE_RECOGNITION (6) -> TYPE_TELEPHONY", AUDIO_SOURCE_VOICE_RECOGNITION, false, true),
                SourceCandidate("MediaRecorder.AudioSource.VOICE_COMMUNICATION (7) -> TYPE_TELEPHONY", AUDIO_SOURCE_VOICE_COMMUNICATION, false, true)
            )

            var candidateIndex = 0
            var currentRecord: AudioRecord? = null

            fun tryInitializeCandidate(index: Int): AudioRecord? {
                if (index !in candidates.indices) return null
                val candidate = candidates[index]
                return try {
                    val record = if (candidate.useInternalPreset) {
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

                        if (presetApplied) {
                            runCatching {
                                HiddenApiBypass.invoke(
                                    AudioRecord.Builder::class.java,
                                    recBuilder,
                                    "setAudioAttributes",
                                    attrBuilder.build()
                                )
                            }.onFailure {
                                recBuilder.setAudioSource(candidate.sourceId)
                            }
                        } else {
                            recBuilder.setAudioSource(candidate.sourceId)
                        }

                        recBuilder.build()
                    } else {
                        AudioRecord(
                            candidate.sourceId,
                            SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            recordBufferSize
                        )
                    }

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        if (candidate.routeToTelephony && telephonyInput != null) {
                            runCatching { record.setPreferredDevice(telephonyInput) }
                        }
                        record.startRecording()
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            Log.i(TAG, "Phase B AudioRecord initialized and recording with candidate: ${candidate.name}")
                            record
                        } else {
                            record.release()
                            null
                        }
                    } else {
                        record.release()
                        null
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Candidate ${candidate.name} failed: ${t.message}")
                    null
                }
            }

            // Find first candidate that successfully initializes and starts recording
            while (candidateIndex < candidates.size) {
                currentRecord = tryInitializeCandidate(candidateIndex)
                if (currentRecord != null) break
                candidateIndex++
            }

            if (currentRecord == null) {
                Log.e(TAG, "Fatal: No AudioRecord candidate could be initialized for Phase B downlink capture.")
                isCaptureActive.set(false)
                return@Thread
            }

            activeAudioRecord = currentRecord
            val rawBuffer = ShortArray(CHUNK_SAMPLES)
            var consecutiveZeroChunks = 0

            while (isSessionActive.get() && isCaptureActive.get()) {
                val record = activeAudioRecord ?: break

                // Keep physical microphone strictly muted
                if (!audioManager.isMicrophoneMute) {
                    audioManager.isMicrophoneMute = true
                }

                val read = record.read(rawBuffer, 0, rawBuffer.size)
                if (read > 0) {
                    var peak = 0
                    for (i in 0 until read) {
                        val absVal = abs(rawBuffer[i].toInt())
                        if (absVal > peak) peak = absVal
                    }

                    // If unprivileged source returns silent zeroes (SELinux block on downlink), dynamically failover
                    if (peak == 0) {
                        consecutiveZeroChunks++
                        if (consecutiveZeroChunks >= 15 && candidateIndex + 1 < candidates.size) {
                            Log.w(TAG, "Candidate ${candidates[candidateIndex].name} returned 15 zero chunks. Switching to fallback candidate.")
                            runCatching {
                                record.stop()
                                record.release()
                            }
                            candidateIndex++
                            val nextRecord = tryInitializeCandidate(candidateIndex)
                            if (nextRecord != null) {
                                activeAudioRecord = nextRecord
                                currentRecord = nextRecord
                                consecutiveZeroChunks = 0
                                continue
                            }
                        }
                    } else {
                        consecutiveZeroChunks = 0
                    }

                    // Apply 2x software gain clamp: (sample * 2).coerceIn(-32768, 32767)
                    val clamped = ShortArray(read)
                    for (i in 0 until read) {
                        clamped[i] = (rawBuffer[i].toInt() * 2).coerceIn(-32768, 32767).toShort()
                    }

                    onPcmChunk(clamped)
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read returned error code $read. Attempting candidate rollover.")
                    runCatching {
                        record.stop()
                        record.release()
                    }
                    candidateIndex++
                    val nextRecord = tryInitializeCandidate(candidateIndex)
                    if (nextRecord != null) {
                        activeAudioRecord = nextRecord
                        currentRecord = nextRecord
                    } else {
                        break
                    }
                }
            }

            // Cleanup recording resources
            runCatching {
                currentRecord?.stop()
                currentRecord?.release()
            }
            activeAudioRecord = null
            isCaptureActive.set(false)
            Log.i(TAG, "Standalone Phase B Downlink Capture Thread terminated.")
        }, "StandaloneAudioCaptureThread").apply { start() }
    }

    /**
     * Stops the active session (both Phase A greeting injection and Phase B capture),
     * releases all AudioTrack/AudioRecord instances, and resets AudioManager states.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping Standalone Telephony Audio Engine session...")
        isSessionActive.set(false)
        isGreetingPlaying.set(false)
        isCaptureActive.set(false)

        cleanupPlaybackTracks()

        try {
            activeAudioRecord?.let { record ->
                record.stop()
                record.release()
            }
        } catch (ignored: Throwable) {}
        activeAudioRecord = null

        captureThread?.interrupt()
        captureThread = null

        Log.i(TAG, "Standalone Telephony Audio Engine session successfully stopped.")
    }

    private fun cleanupPlaybackTracks() {
        synchronized(activePlaybackTracks) {
            for (track in activePlaybackTracks) {
                runCatching {
                    track.stop()
                    track.release()
                }
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
            builder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        }
        return builder.build()
    }

    private fun readWavPayload(file: File): ByteArray {
        if (!file.exists() || file.length() <= 44) {
            return ByteArray(SAMPLE_RATE_HZ * 2 * 3) // 3 seconds silent fallback
        }
        return try {
            FileInputStream(file).use { fis ->
                val fullBytes = fis.readBytes()
                if (fullBytes.size > 44) {
                    fullBytes.copyOfRange(44, fullBytes.size)
                } else {
                    ByteArray(SAMPLE_RATE_HZ * 2 * 3)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed reading WAV payload: ${t.message}", t)
            ByteArray(SAMPLE_RATE_HZ * 2 * 3)
        }
    }
}
