package com.personal.voicemail.service

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import com.personal.voicemail.ipc.IAudioChunkCallback
import com.personal.voicemail.ipc.IVoicemailAudioService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Privileged Audio Routing and Telecom Uplink/Downlink Streaming Service.
 *
 * Runs inside a standalone privileged process spawned by Shizuku under UID 2000 (com.android.shell).
 * UID 2000 holds system privileges including:
 *   - android.permission.CAPTURE_AUDIO_OUTPUT
 *   - android.permission.MODIFY_AUDIO_ROUTING
 *   - android.permission.MODIFY_PHONE_STATE
 *
 * Phase A:Silent Telephony Uplink Greeting Injection (zero room speaker bleed).
 * Phase B: Privileged Telephony Downlink Capture (VOICE_DOWNLINK / TYPE_TELEPHONY).
 */
@Keep
class VoicemailUserService : IVoicemailAudioService.Stub {

    companion object {
        private const val TAG = "VoicemailUserService"
        private const val DEFAULT_SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SIZE_SAMPLES = 1600 // 100ms at 16kHz
        private const val QUALCOMM_PARAM_INCALL_MUSIC_ON = "incall_music_enabled=true;incall_music_uplink=true;music_playback_during_call=true"
        private const val QUALCOMM_PARAM_INCALL_MUSIC_OFF = "incall_music_enabled=false;incall_music_uplink=false;music_playback_during_call=false"
        private const val USAGE_CALL_ASSISTANT_HIDDEN = 17
        private const val AUDIO_SOURCE_VOICE_DOWNLINK = 3
        private const val AUDIO_SOURCE_VOICE_CALL = 4
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val exemptSuccess = HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass exemptions applied in UID 2000 process: $exemptSuccess")
            }.onFailure { t ->
                Log.e(TAG, "Error applying HiddenApiBypass in VoicemailUserService init", t)
            }
        }
    }

    private var rawContext: Context? = null
    private var shellContext: Context? = null
    private var audioManager: AudioManager? = null

    private val isSessionActive = AtomicBoolean(false)
    private val isRecordingActive = AtomicBoolean(false)
    private val isScreeningSpeakerEnabled = AtomicBoolean(false)

    private var savedMusicVolume: Int = -1
    private var savedVoiceCallVolume: Int = -1

    private var currentAudioTrack: AudioTrack? = null
    private var currentAudioRecord: AudioRecord? = null

    private var activeWorkerExecutor: ExecutorService? = null
    private var activeRecordingThread: Thread? = null

    @Keep
    constructor() : super() {
        initializeContextAndAudio(null)
    }

    @Keep
    constructor(context: Context) : this() {
        this.rawContext = context
        initializeContextAndAudio(context)
    }

    private fun initializeContextAndAudio(context: Context?) {
        val base = context ?: runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = runCatching {
                val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
                systemMain.isAccessible = true
                systemMain.invoke(null)
            }.getOrElse {
                val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread")
                currentActivityThread.isAccessible = true
                currentActivityThread.invoke(null)
            }
            val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
            getSystemContextMethod.isAccessible = true
            getSystemContextMethod.invoke(thread) as? Context
        }.onFailure { t ->
            Log.w(TAG, "Could not acquire system context via ActivityThread: ${t.message}")
        }.getOrNull()

        this.rawContext = base
        if (base != null) {
            this.shellContext = ShellAttributionContext(base)
            this.audioManager = base.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }

        Log.i(TAG, "VoicemailUserService initialized. Process UID: ${Process.myUid()}, PID: ${Process.myPid()}")
    }

    private class ShellAttributionContext(base: Context) : ContextWrapper(base) {
        private val shellAttribution: AttributionSource? by lazy {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AttributionSource.Builder(2000)
                        .setPackageName("com.android.shell")
                        .build()
                } else null
            }.getOrNull()
        }

        override fun getOpPackageName(): String = "com.android.shell"
        override fun getPackageName(): String = "com.android.shell"

        override fun getAttributionSource(): AttributionSource {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                shellAttribution ?: super.getAttributionSource()
            } else {
                super.getAttributionSource()
            }
        }
    }

    @Synchronized
    override fun startVoicemailSession(greetingWavFd: AssetFileDescriptor?, callback: IAudioChunkCallback?) {
        if (callback == null) {
            Log.e(TAG, "startVoicemailSession aborted: callback is null")
            return
        }

        if (isSessionActive.get()) {
            Log.w(TAG, "Voicemail session already running. Stopping previous session before restarting.")
            stopVoicemailSession()
        }

        isSessionActive.set(true)
        isScreeningSpeakerEnabled.set(false)

        activeWorkerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LiveVoicemail-AudioPipeline").apply {
                priority = Thread.MAX_PRIORITY
            }
        }

        activeWorkerExecutor?.execute {
            try {
                // Save current volumes so we can restore them cleanly after screening
                audioManager?.let { am ->
                    runCatching {
                        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        savedVoiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    }
                }

                // 1. Guarantee silence in the user's room (earpiece mode + muted voice call stream + muted physical mic)
                configureSilentScreeningMode()

                // 2. Phase A - Inject Greeting WAV directly into Telephony Uplink TX
                try {
                    executePhaseAGreetingInjection(greetingWavFd)
                } catch (t: Throwable) {
                    Log.e(TAG, "Greeting error in Phase A", t)
                } finally {
                    try {
                        callback.onGreetingFinished()
                    } catch (re: RemoteException) {
                        Log.e(TAG, "Error firing onGreetingFinished", re)
                    }
                }

                if (!isSessionActive.get()) {
                    Log.i(TAG, "Session stopped during or immediately after greeting playback.")
                    return@execute
                }

                // 3. Ensure physical microphone stays muted during Phase B so caller NEVER hears user's room
                audioManager?.let { am ->
                    runCatching {
                        am.isMicrophoneMute = true
                        if (!isScreeningSpeakerEnabled.get()) {
                            am.isSpeakerphoneOn = false
                            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
                        }
                    }
                }

                // 4. Phase B - Privileged Downlink Capture (captures ONLY caller's voice from telephony downlink)
                executePhaseBDownlinkCapture(callback)

            } catch (t: Throwable) {
                Log.e(TAG, "Critical error in voicemail audio pipeline execution", t)
                try {
                    callback.onError("Audio pipeline failed: ${t.localizedMessage ?: t.javaClass.simpleName}")
                } catch (re: RemoteException) {
                    Log.e(TAG, "RemoteException sending onError to callback", re)
                }
                stopVoicemailSession()
            } finally {
                try {
                    greetingWavFd?.close()
                } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Mutes local room output and physical microphone so neither the greeting nor caller
     * bleeds out of the phone speaker, and the caller cannot hear the user's room.
     */
    private fun configureSilentScreeningMode() {
        val am = audioManager ?: return
        try {
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = true
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            Log.i(TAG, "Silent screening mode enforced: isSpeakerphoneOn=false, isMicrophoneMute=true, STREAM_VOICE_CALL=MUTED")
        } catch (t: Throwable) {
            Log.w(TAG, "Error configuring silent screening mode: ${t.message}")
        }
    }

    private fun readWavSampleRate(fd: AssetFileDescriptor?): Int {
        if (fd == null) return DEFAULT_SAMPLE_RATE_HZ
        return try {
            val pfd = fd.parcelFileDescriptor
            val isStream = if (pfd != null) {
                val dup = pfd.dup()
                ParcelFileDescriptor.AutoCloseInputStream(dup)
            } else {
                fd.createInputStream()
            }
            val header = ByteArray(44)
            val read = isStream.read(header)
            isStream.close()
            if (read >= 28) {
                val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (rate in 8000..48000) {
                    return rate
                }
            }
            DEFAULT_SAMPLE_RATE_HZ
        } catch (t: Throwable) {
            DEFAULT_SAMPLE_RATE_HZ
        }
    }

    private fun buildCallAssistantAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        runCatching {
            val setSystemUsage = AudioAttributes.Builder::class.java.getDeclaredMethod(
                "setSystemUsage",
                Int::class.javaPrimitiveType
            )
            setSystemUsage.isAccessible = true
            setSystemUsage.invoke(builder, USAGE_CALL_ASSISTANT_HIDDEN) // 17 = USAGE_CALL_ASSISTANT
        }.onFailure {
            builder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        }
        return builder.build()
    }

    /**
     * Phase A: Injects the greeting WAV into the cellular uplink TX while keeping the phone silent.
     * Strategy 1: AOSP Call Assistant / TYPE_TELEPHONY explicit routing (pure digital uplink injection).
     * Strategy 2: Qualcomm incall_music_uplink DSP mixer with earpiece routing so room hears nothing.
     */
    private fun executePhaseAGreetingInjection(greetingWavFd: AssetFileDescriptor?) {
        val am = audioManager ?: return
        Log.i(TAG, "Commencing Phase A: Silent Telephony Uplink Greeting Injection")

        val stagedTmpFile = File("/data/local/tmp/voicemail_greeting.wav")
        if (!stagedTmpFile.exists() || stagedTmpFile.length() < 32000) {
            greetingWavFd?.let { fd ->
                try {
                    val pfd = fd.parcelFileDescriptor
                    val inStream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else fd.createInputStream()
                    FileOutputStream(stagedTmpFile).use { fos ->
                        inStream.copyTo(fos)
                        fos.flush()
                    }
                    stagedTmpFile.setReadable(true, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not cache greeting to /data/local/tmp: ${t.message}")
                }
            }
        }

        val targetSampleRate = if (stagedTmpFile.exists() && stagedTmpFile.length() >= 44) {
            readWavFileSampleRate(stagedTmpFile)
        } else {
            readWavSampleRate(greetingWavFd)
        }

        // Keep hardware mic muted so room noise is never mixed into greeting
        runCatching {
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = true
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_ON)
        }

        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val telephonyOutputDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val earpieceDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (telephonyOutputDevice != null) {
            Log.i(TAG, "Located TYPE_TELEPHONY output device: ID=${telephonyOutputDevice.id}")
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(targetSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            targetSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufferSize = max(minBufferSize, CHUNK_SIZE_SAMPLES * 2 * 4)

        fun createAudioTrack(attrs: AudioAttributes, preferredDevice: AudioDeviceInfo?): AudioTrack? {
            return try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                shellContext?.let { ctx ->
                    runCatching {
                        val m = AudioTrack.Builder::class.java.getDeclaredMethod("setContext", Context::class.java)
                        m.isAccessible = true
                        m.invoke(builder, ctx)
                    }
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
                Log.w(TAG, "Failed creating AudioTrack: ${t.message}")
                null
            }
        }

        val activeTracks = mutableListOf<AudioTrack>()

        // Track 1: USAGE_CALL_ASSISTANT (17) routed directly to TYPE_TELEPHONY
        createAudioTrack(buildCallAssistantAttributes(), telephonyOutputDevice)?.let { t1 ->
            t1.setVolume(1.0f)
            t1.play()
            activeTracks.add(t1)
            Log.i(TAG, "Phase A Track 1 (CALL_ASSISTANT) playing on routedDevice=${t1.routedDevice?.type}")
        }

        // Track 2: Qualcomm incall_music_uplink track (USAGE_MEDIA).
        // Route to TYPE_TELEPHONY if accepted, otherwise route to EARPIECE (never speaker!) so room stays silent while Qualcomm DSP mixes PCM into modem uplink.
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val preferredForMedia = telephonyOutputDevice ?: earpieceDevice
        createAudioTrack(mediaAttrs, preferredForMedia)?.let { t2 ->
            // Set stream music volume to moderate level for Qualcomm DSP uplink mixer, while earpiece keeps it inaudible in room
            runCatching {
                val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.6f).toInt().coerceAtLeast(3), 0)
            }
            t2.setVolume(1.0f)
            t2.play()
            if (t2.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && earpieceDevice != null) {
                runCatching { t2.setPreferredDevice(earpieceDevice) }
            }
            activeTracks.add(t2)
            Log.i(TAG, "Phase A Track 2 (QUALCOMM_UPLINK) playing on routedDevice=${t2.routedDevice?.type}")
        }

        if (activeTracks.isEmpty()) {
            Log.e(TAG, "No AudioTrack could be initialized for greeting injection.")
            return
        }

        val primaryTrack = activeTracks.first()
        currentAudioTrack = primaryTrack

        val pcmPayload: ByteArray = try {
            val rawBytes = if (stagedTmpFile.exists() && stagedTmpFile.length() > 44) {
                stagedTmpFile.readBytes()
            } else if (greetingWavFd != null) {
                val pfd = greetingWavFd.parcelFileDescriptor
                val stream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else greetingWavFd.createInputStream()
                stream.use { it.readBytes() }
            } else {
                ByteArray(0)
            }
            if (rawBytes.size > 44) rawBytes.copyOfRange(44, rawBytes.size) else ByteArray(targetSampleRate * 2)
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading greeting WAV payload", t)
            ByteArray(targetSampleRate * 2)
        }

        var offset = 0
        val step = 3200 // 100ms chunks
        while (isSessionActive.get() && offset < pcmPayload.size) {
            val len = minOf(step, pcmPayload.size - offset)
            for (t in activeTracks) {
                runCatching { t.write(pcmPayload, offset, len) }
            }
            offset += len
        }

        val totalFrames = pcmPayload.size / 2
        val expectedDurationMs = (totalFrames * 1000L / targetSampleRate)
        val startWait = System.currentTimeMillis()
        while (isSessionActive.get() &&
            primaryTrack.playbackHeadPosition < totalFrames &&
            (System.currentTimeMillis() - startWait) < (expectedDurationMs + 1500L)
        ) {
            try {
                Thread.sleep(50)
            } catch (ignored: InterruptedException) {
                break
            }
        }

        for (t in activeTracks) {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        currentAudioTrack = null

        // Restore music volume and turn off incall_music_uplink before starting Phase B downlink capture
        runCatching {
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
            if (savedMusicVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            }
        }

        Log.i(TAG, "Phase A: Greeting injection complete (${pcmPayload.size} bytes).")
    }

    private fun readWavFileSampleRate(file: File): Int {
        var sampleRate = DEFAULT_SAMPLE_RATE_HZ
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                val read = fis.read(header)
                if (read >= 28) {
                    val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (rate in 8000..48000) {
                        sampleRate = rate
                    }
                }
            }
        } catch (t: Throwable) {
            sampleRate = DEFAULT_SAMPLE_RATE_HZ
        }
        return sampleRate
    }

    /**
     * Phase B: Privileged Downlink Capture.
     * Prioritizes VOICE_DOWNLINK (3) and TYPE_TELEPHONY input so ONLY the remote caller's voice
     * is captured and transcribed, never the local user's microphone.
     */
    private fun executePhaseBDownlinkCapture(callback: IAudioChunkCallback) {
        val am = audioManager ?: run {
            Log.e(TAG, "AudioManager not available during Phase B downlink capture")
            return
        }

        Log.i(TAG, "Commencing Phase B: Privileged Telephony Downlink Capture")

        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val telephonyInputDevice = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        if (telephonyInputDevice != null) {
            Log.i(TAG, "Telephony input device located: ID=${telephonyInputDevice.id}")
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            DEFAULT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recordBufferSize = max(minBufSize, CHUNK_SIZE_SAMPLES * 2 * 4)

        fun createAndStartRecord(source: Int, routeToTelephonyDevice: Boolean): AudioRecord? {
            return try {
                val attrBuilder = AudioAttributes.Builder()
                val presetApplied = runCatching {
                    val m = AudioAttributes.Builder::class.java.getDeclaredMethod(
                        "setInternalCapturePreset",
                        Int::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    m.invoke(attrBuilder, source)
                    true
                }.getOrDefault(false)

                val recBuilder = AudioRecord.Builder()
                shellContext?.let { ctx ->
                    runCatching {
                        val mCtx = AudioRecord.Builder::class.java.getDeclaredMethod(
                            "setContext",
                            Context::class.java
                        )
                        mCtx.isAccessible = true
                        mCtx.invoke(recBuilder, ctx)
                    }
                }
                if (presetApplied) {
                    runCatching {
                        val mAttr = AudioRecord.Builder::class.java.getDeclaredMethod(
                            "setAudioAttributes",
                            AudioAttributes::class.java
                        )
                        mAttr.isAccessible = true
                        mAttr.invoke(recBuilder, attrBuilder.build())
                    }.onFailure {
                        recBuilder.setAudioSource(source)
                    }
                } else {
                    recBuilder.setAudioSource(source)
                }

                recBuilder.setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                ).setBufferSizeInBytes(recordBufferSize)

                val rec = recBuilder.build()
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return null
                }
                if (routeToTelephonyDevice && telephonyInputDevice != null) {
                    runCatching { rec.setPreferredDevice(telephonyInputDevice) }
                }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.release()
                    return null
                }
                Log.i(TAG, "Phase B AudioRecord started: source=$source, presetApplied=$presetApplied, routeTelephony=$routeToTelephonyDevice, routedDevice=${rec.routedDevice?.type}")
                rec
            } catch (t: Throwable) {
                Log.w(TAG, "Failed creating Phase B AudioRecord(source=$source, route=$routeToTelephonyDevice): ${t.message}")
                null
            }
        }

        // Strictly prioritize telephony downlink sources (VOICE_DOWNLINK=3, VOICE_CALL=4, or explicit TYPE_TELEPHONY device)
        // Because physical mic is muted (isMicrophoneMute = true), VOICE_CALL only contains the downlink leg!
        val candidates = mutableListOf(
            Pair(AUDIO_SOURCE_VOICE_DOWNLINK, true),
            Pair(AUDIO_SOURCE_VOICE_DOWNLINK, false),
            Pair(AUDIO_SOURCE_VOICE_CALL, true),
            Pair(AUDIO_SOURCE_VOICE_CALL, false)
        )
        if (telephonyInputDevice != null) {
            candidates.add(Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, true))
            candidates.add(Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true))
        }

        var candidateIndex = 0
        var activeRecord: AudioRecord? = null

        while (candidateIndex < candidates.size) {
            val cand = candidates[candidateIndex]
            val rec = createAndStartRecord(cand.first, cand.second)
            if (rec != null) {
                activeRecord = rec
                break
            }
            candidateIndex++
        }

        if (activeRecord == null) {
            val errorMsg = "Unable to initialize privileged downlink AudioRecord in UID 2000."
            Log.e(TAG, errorMsg)
            callback.onError(errorMsg)
            return
        }

        currentAudioRecord = activeRecord
        isRecordingActive.set(true)

        val recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val pcmByteBuffer = ByteArray(CHUNK_SIZE_SAMPLES * 2)
            var chunkIndex = 0
            var consecutiveZeroChunks = 0

            Log.i(TAG, "Phase B downlink capture loop active (candidate=${candidates[candidateIndex]})")

            while (isSessionActive.get() && isRecordingActive.get()) {
                val record = activeRecord ?: break
                val currentCandidate = candidates[candidateIndex]
                val bytesRead = record.read(pcmByteBuffer, 0, pcmByteBuffer.size)

                if (bytesRead > 0) {
                    var peakAmp = 0
                    var i = 0
                    while (i < bytesRead - 1) {
                        val sample = (pcmByteBuffer[i].toInt() and 0xFF or (pcmByteBuffer[i + 1].toInt() shl 8)).toShort()
                        val absVal = kotlin.math.abs(sample.toInt())
                        if (absVal > peakAmp) peakAmp = absVal
                        i += 2
                    }

                    chunkIndex++
                    if (chunkIndex <= 20 || chunkIndex % 15 == 0) {
                        Log.d(TAG, "Downlink RX chunk #$chunkIndex source=${currentCandidate.first} route=${currentCandidate.second} peak=$peakAmp")
                    }

                    if (peakAmp == 0) {
                        consecutiveZeroChunks++
                    } else {
                        consecutiveZeroChunks = 0
                    }

                    // Only switch candidate if we get 25 consecutive exact-zero chunks (2.5 seconds of dead hardware stream)
                    if (consecutiveZeroChunks >= 25 && candidateIndex < candidates.lastIndex) {
                        Log.w(TAG, "Downlink source $currentCandidate returned 25 zero chunks; switching to next downlink candidate...")
                        runCatching {
                            record.stop()
                            record.release()
                        }
                        var switched = false
                        while (candidateIndex < candidates.lastIndex) {
                            candidateIndex++
                            val nextCand = candidates[candidateIndex]
                            val newRecord = createAndStartRecord(nextCand.first, nextCand.second)
                            if (newRecord != null) {
                                activeRecord = newRecord
                                currentAudioRecord = newRecord
                                consecutiveZeroChunks = 0
                                switched = true
                                break
                            }
                        }
                        if (!switched) break
                        continue
                    }

                    val chunk = if (bytesRead == pcmByteBuffer.size) {
                        pcmByteBuffer.clone()
                    } else {
                        pcmByteBuffer.copyOf(bytesRead)
                    }

                    try {
                        callback.onPcmChunkReceived(chunk)
                    } catch (re: RemoteException) {
                        Log.e(TAG, "RemoteException in onPcmChunkReceived; stopping capture", re)
                        break
                    }
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read error $bytesRead on $currentCandidate")
                    break
                }
            }

            Log.i(TAG, "Phase B downlink capture loop terminated. Total chunks: $chunkIndex")
        }, "LiveVoicemail-DownlinkLoop")

        activeRecordingThread = recordingThread
        recordingThread.start()
    }

    @Synchronized
    override fun setScreeningSpeakerEnabled(enabled: Boolean) {
        isScreeningSpeakerEnabled.set(enabled)
        val am = audioManager ?: return

        try {
            // Always keep the microphone muted during screening even if speaker listening is toggled on!
            am.isMicrophoneMute = true
            if (enabled) {
                Log.i(TAG, "Live listening speaker enabled: unmuting STREAM_VOICE_CALL")
                am.isSpeakerphoneOn = true
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.7f).toInt().coerceAtLeast(2), 0)
            } else {
                Log.i(TAG, "Live listening speaker disabled: muting STREAM_VOICE_CALL")
                am.isSpeakerphoneOn = false
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error toggling screening speaker state (enabled=$enabled)", t)
        }
    }

    @Synchronized
    override fun stopVoicemailSession() {
        Log.i(TAG, "stopVoicemailSession requested.")
        isSessionActive.set(false)
        isRecordingActive.set(false)

        try {
            activeRecordingThread?.interrupt()
            activeRecordingThread = null
        } catch (ignored: Throwable) {}

        try {
            currentAudioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping AudioRecord", t)
        } finally {
            currentAudioRecord = null
        }

        try {
            currentAudioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping AudioTrack", t)
        } finally {
            currentAudioTrack = null
        }

        audioManager?.let { am ->
            runCatching {
                am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
                am.isMicrophoneMute = false
                am.isSpeakerphoneOn = false
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                if (savedVoiceCallVolume > 0) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0)
                }
                if (savedMusicVolume >= 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                }
            }
        }

        try {
            activeWorkerExecutor?.shutdownNow()
            activeWorkerExecutor = null
        } catch (ignored: Throwable) {}

        Log.i(TAG, "stopVoicemailSession completed cleanly.")
    }

    override fun destroy() {
        Log.i(TAG, "destroy() invoked on Shizuku UserService.")
        stopVoicemailSession()
        Process.killProcess(Process.myPid())
    }
}
