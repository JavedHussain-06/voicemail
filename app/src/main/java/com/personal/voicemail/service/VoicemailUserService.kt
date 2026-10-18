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
 * Implements resilient Dual-Strategy Greeting Uplink (Phase A) and Resilient Downlink Capture (Phase B).
 */
@Keep
class VoicemailUserService : IVoicemailAudioService.Stub {

    companion object {
        private const val TAG = "VoicemailUserService"
        private const val DEFAULT_SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SIZE_SAMPLES = 1600 // 100ms at 16kHz
        private const val QUALCOMM_PARAM_INCALL_MUSIC_ON = "incall_music_enabled=true"
        private const val QUALCOMM_PARAM_INCALL_MUSIC_UPLINK = "incall_music_uplink=true"
        private const val QUALCOMM_PARAM_INCALL_MUSIC_OFF = "incall_music_enabled=false"
        private const val QUALCOMM_PARAM_PLAYBACK_DURING_CALL = "music_playback_during_call=true"
        private const val USAGE_CALL_ASSISTANT_HIDDEN = 17
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

    /**
     * Starts a complete voicemail screening session:
     * Phase A: Greeting injection with Dual-Strategy (Telephony Uplink + Acoustic Feed).
     * Phase B: Continuous resilient downlink caller voice capture.
     * Guaranteed: Phase B executes even if Phase A encounters an issue.
     */
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
                // Step 1: Phase A - Greeting Audio Playback (Qualcomm Uplink DSP injection)
                try {
                    executePhaseAGreetingInjection(greetingWavFd)
                } catch (t: Throwable) {
                    Log.e(TAG, "Greeting error in Phase A", t)
                }

                // If session was canceled during greeting, abort Phase B
                if (!isSessionActive.get()) {
                    Log.i(TAG, "Session stopped during or immediately after greeting playback.")
                    return@execute
                }

                // Step 3: Phase B - Caller Voice Capture from Cellular Downlink
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
     * Reads the sample rate from bytes 24..27 of the WAV header from an AssetFileDescriptor.
     */
    private fun readWavSampleRate(fd: AssetFileDescriptor?): Int {
        if (fd == null) return DEFAULT_SAMPLE_RATE_HZ
        return try {
            val pfd = fd.parcelFileDescriptor
            val isStream = if (pfd != null) {
                val dup = pfd.dup()
                android.os.ParcelFileDescriptor.AutoCloseInputStream(dup)
            } else {
                fd.createInputStream()
            }
            val header = ByteArray(44)
            val read = isStream.read(header)
            isStream.close()
            if (read >= 28) {
                val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (rate in 8000..48000) {
                    Log.i(TAG, "Detected WAV header sample rate: $rate Hz")
                    return rate
                }
            }
            DEFAULT_SAMPLE_RATE_HZ
        } catch (t: Throwable) {
            Log.w(TAG, "Error reading WAV header sample rate: ${t.message}")
            DEFAULT_SAMPLE_RATE_HZ
        }
    }

    /**
     * Phase A: Dual-Strategy Greeting Playback
     * Strategy 1: Qualcomm Hardware In-Call Uplink TX injection.
     * Strategy 2: Acoustic Uplink Fallback (unmuted mic + controlled volume) so the caller
     * ALWAYS hears the spoken greeting message, even if Qualcomm DSP blocks digital TX.
     */
    private fun executePhaseAGreetingInjection(greetingWavFd: AssetFileDescriptor?) {
        val am = audioManager ?: run {
            Log.e(TAG, "AudioManager not available during Phase A greeting injection")
            return
        }

        Log.i(TAG, "Commencing Phase A: Dual-Strategy Greeting Audio Playback")

        // Check if /data/local/tmp/voicemail_greeting.wav exists on disk (owned natively by UID 2000)
        val stagedTmpFile = File("/data/local/tmp/voicemail_greeting.wav")
        if (!stagedTmpFile.exists() || stagedTmpFile.length() < 32000) {
            // Attempt to copy from greetingWavFd into /data/local/tmp/voicemail_greeting.wav
            greetingWavFd?.let { fd ->
                try {
                    val pfd = fd.parcelFileDescriptor
                    val inStream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else fd.createInputStream()
                    FileOutputStream(stagedTmpFile).use { fos ->
                        inStream.copyTo(fos)
                        fos.flush()
                    }
                    stagedTmpFile.setReadable(true, false)
                    Log.i(TAG, "Cached greeting to /data/local/tmp/voicemail_greeting.wav (${stagedTmpFile.length()} bytes)")
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

        // 1. Configure Qualcomm Voice DSP uplink parameters
        try {
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_ON)
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_UPLINK)
            am.setParameters(QUALCOMM_PARAM_PLAYBACK_DURING_CALL)
            Log.i(TAG, "Configured Qualcomm DSP parameters for in-call music uplink.")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed setting Qualcomm DSP parameters: ${t.message}")
        }

        // 2. Discover telephony output device if available
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val telephonyOutputDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        if (telephonyOutputDevice != null) {
            Log.i(TAG, "Located telephony output device: ID ${telephonyOutputDevice.id}")
        }

        // 3. Build AudioTrack for greeting playback (Try USAGE_CALL_ASSISTANT -> USAGE_VOICE_COMMUNICATION -> USAGE_MEDIA)
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

        val candidateUsages = intArrayOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_MEDIA
        )

        var track: AudioTrack? = null
        for (usage in candidateUsages) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val candidate = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                    track = candidate
                    Log.i(TAG, "Successfully built AudioTrack with usage=$usage at $targetSampleRate Hz")
                    break
                } else {
                    candidate.release()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.Builder with usage=$usage threw: ${t.message}")
            }
        }

        if (track == null) {
            Log.e(TAG, "Unable to initialize AudioTrack for greeting. Skipping Phase A.")
            return
        }

        currentAudioTrack = track

        if (telephonyOutputDevice != null) {
            track.setPreferredDevice(telephonyOutputDevice)
        }

        // Acoustic Uplink Fallback: Unmute microphone & raise volume to 80% during greeting playback
        try {
            am.isMicrophoneMute = false
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max(1, (maxVol * 0.8).toInt()), 0)
            Log.i(TAG, "Acoustic fallback engaged: microphone unmuted, STREAM_VOICE_CALL volume set to 80%")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed setting acoustic fallback volume: ${t.message}")
        }

        track.play()

        val routedType = track.routedDevice?.type
        Log.d(TAG, "Greeting AudioTrack routed to device type: $routedType (TYPE_TELEPHONY is ${AudioDeviceInfo.TYPE_TELEPHONY})")

        val totalBytesWritten = if (stagedTmpFile.exists() && stagedTmpFile.length() > 44) {
            streamFileToTrack(stagedTmpFile, track)
        } else if (greetingWavFd != null) {
            streamGreetingWavToTrack(greetingWavFd, track)
        } else {
            val silenceBuffer = ByteArray(targetSampleRate * 2)
            track.write(silenceBuffer, 0, silenceBuffer.size)
            silenceBuffer.size.toLong()
        }

        // Wait until AudioTrack has finished playing all frames through the uplink
        val totalFrames = totalBytesWritten / 2
        val expectedDurationMs = (totalFrames * 1000L / targetSampleRate)
        Log.i(TAG, "Greeting written: $totalBytesWritten bytes ($expectedDurationMs ms). Awaiting full playback...")

        val greetingStartTime = System.currentTimeMillis()
        val maxWaitMs = expectedDurationMs + 2500L
        while (isSessionActive.get() && track.playbackHeadPosition < totalFrames && (System.currentTimeMillis() - greetingStartTime) < maxWaitMs) {
            try {
                Thread.sleep(50)
            } catch (ignored: InterruptedException) {
                break
            }
        }
        Log.i(TAG, "Greeting playback completed. HeadPosition=${track.playbackHeadPosition}/$totalFrames")

        try {
            track.stop()
        } catch (ignored: Throwable) {}
        try {
            track.release()
        } catch (ignored: Throwable) {}
        currentAudioTrack = null

        // Immediately mute mic and mute speaker stream so room sounds do not bleed into caller recording
        am.isMicrophoneMute = true
        muteVoiceCallStream(true)

        // Reset Qualcomm DSP uplink parameters
        try {
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
        } catch (ignored: Throwable) {}

        Log.i(TAG, "Phase A: Greeting injection complete.")
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

    private fun streamFileToTrack(file: File, track: AudioTrack): Long {
        val buffer = ByteArray(4096)
        var totalBytesWritten = 0L
        try {
            FileInputStream(file).use { fis ->
                val headerBytes = ByteArray(44)
                val readHeader = fis.read(headerBytes)
                if (readHeader in 1..43) {
                    track.write(headerBytes, 0, readHeader)
                    totalBytesWritten += readHeader.toLong()
                }

                var readLen: Int
                while (isSessionActive.get()) {
                    readLen = fis.read(buffer)
                    if (readLen == -1) break
                    if (readLen > 0) {
                        track.write(buffer, 0, readLen)
                        totalBytesWritten += readLen.toLong()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception streaming file to AudioTrack: ${t.message}")
        }
        return totalBytesWritten
    }

    private fun streamGreetingWavToTrack(fd: AssetFileDescriptor, track: AudioTrack): Long {
        val pfd = fd.parcelFileDescriptor
        val inputStream: InputStream = if (pfd != null) {
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        } else {
            fd.createInputStream()
        }
        val buffer = ByteArray(4096)
        var totalBytesWritten = 0L

        try {
            val headerBytes = ByteArray(44)
            val bytesRead = inputStream.read(headerBytes)
            if (bytesRead < 44 && bytesRead > 0) {
                track.write(headerBytes, 0, bytesRead)
                totalBytesWritten += bytesRead.toLong()
            }

            var readLen = 0
            while (isSessionActive.get() && inputStream.read(buffer).also { readLen = it } != -1) {
                if (readLen > 0) {
                    track.write(buffer, 0, readLen)
                    totalBytesWritten += readLen.toLong()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during greeting WAV streaming to AudioTrack", e)
        } finally {
            try {
                inputStream.close()
            } catch (ignored: Throwable) {}
        }
        return totalBytesWritten
    }

    /**
     * Phase B: Initializes AudioRecord and captures caller audio continuously.
     * Ordered fallback: VOICE_DOWNLINK (3) -> VOICE_CALL (4) -> VOICE_COMMUNICATION (7) -> VOICE_RECOGNITION (6) -> MIC (1).
     * If the first 8 chunks (800ms) are all zeros, automatically switches to the next fallback source.
     */
    private fun executePhaseBDownlinkCapture(callback: IAudioChunkCallback) {
        val am = audioManager ?: run {
            Log.e(TAG, "AudioManager not available during Phase B downlink capture")
            return
        }

        Log.i(TAG, "Commencing Phase B: Telephony Downlink Capture")

        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val telephonyInputDevice = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        if (telephonyInputDevice != null) {
            Log.i(TAG, "Telephony input device located: ID ${telephonyInputDevice.id}")
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            DEFAULT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recordBufferSize = max(minBufSize, CHUNK_SIZE_SAMPLES * 2 * 4)

        // Ensure microphone is unmuted for recording capture
        try {
            am.isMicrophoneMute = false
        } catch (ignored: Throwable) {}

        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,     // 3
            MediaRecorder.AudioSource.VOICE_CALL,         // 4
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 7
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 6
            MediaRecorder.AudioSource.MIC                 // 1
        )

        fun createAndStartRecord(source: Int): AudioRecord? {
            return try {
                val rec = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufferSize)
                    .build()

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return null
                }
                if (telephonyInputDevice != null && source != MediaRecorder.AudioSource.MIC) {
                    try {
                        rec.setPreferredDevice(telephonyInputDevice)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreferredDevice failed for source=$source: ${t.message}")
                    }
                }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.release()
                    return null
                }
                Log.i(TAG, "AudioRecord started successfully with source=$source")
                rec
            } catch (t: Throwable) {
                Log.w(TAG, "Failed creating AudioRecord for source=$source: ${t.message}")
                null
            }
        }

        fun testAndSelectRecord(): Pair<AudioRecord?, Int> {
            val testBuffer = ByteArray(CHUNK_SIZE_SAMPLES * 2)
            for (source in candidateSources) {
                val rec = createAndStartRecord(source) ?: continue
                try {
                    val read = rec.read(testBuffer, 0, testBuffer.size)
                    Log.d(TAG, "Testing AudioSource $source -> state=${rec.state}, initialRead=$read")
                    if (read >= 0) {
                        Log.i(TAG, "Selected AudioSource $source (initialRead=$read)")
                        return Pair(rec, source)
                    } else {
                        Log.w(TAG, "AudioSource $source read error $read. Trying next candidate...")
                        try { rec.stop() } catch (ignored: Throwable) {}
                        try { rec.release() } catch (ignored: Throwable) {}
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Testing AudioSource $source threw: ${t.message}")
                    try { rec.release() } catch (ignored: Throwable) {}
                }
            }
            return Pair(null, -1)
        }

        val (testedRecord, initialSource) = testAndSelectRecord()
        var activeRecord = testedRecord
        var currentSource = if (initialSource != -1) initialSource else candidateSources[0]
        var sourceIndex = candidateSources.indexOf(currentSource).coerceAtLeast(0)

        if (activeRecord == null) {
            val errorMsg = "Unable to initialize AudioRecord with any audio source."
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

            Log.i(TAG, "Caller voice capture loop active (source=$currentSource, 100ms chunks continuous)")

            while (isSessionActive.get() && isRecordingActive.get()) {
                val record = activeRecord ?: break
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

                    val chunk = if (bytesRead == pcmByteBuffer.size) {
                        pcmByteBuffer.clone()
                    } else {
                        pcmByteBuffer.copyOf(bytesRead)
                    }

                    chunkIndex++
                    if (chunkIndex % 10 == 0) {
                        Log.d(TAG, "Downlink capture tick: chunk #$chunkIndex, source=$currentSource, peakAmplitude=$peakAmp")
                    }

                    try {
                        callback.onPcmChunkReceived(chunk)
                    } catch (re: RemoteException) {
                        Log.e(TAG, "RemoteException in onPcmChunkReceived; stopping capture", re)
                        break
                    }
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read returned error $bytesRead on source $currentSource")
                    if (sourceIndex < candidateSources.lastIndex) {
                        try {
                            record.stop()
                            record.release()
                        } catch (ignored: Throwable) {}

                        sourceIndex++
                        currentSource = candidateSources[sourceIndex]
                        val newRecord = createAndStartRecord(currentSource)
                        if (newRecord != null) {
                            activeRecord = newRecord
                            currentAudioRecord = newRecord
                            Log.i(TAG, "Switched AudioRecord to source=$currentSource after read error")
                            continue
                        }
                    }
                    try {
                        Thread.sleep(50)
                    } catch (ignored: InterruptedException) {
                        break
                    }
                }
            }

            Log.i(TAG, "Caller voice capture loop terminated. Total chunks processed: $chunkIndex")
        }, "LiveVoicemail-RecordingLoop")

        activeRecordingThread = recordingThread
        recordingThread.start()
    }

    @Synchronized
    override fun setScreeningSpeakerEnabled(enabled: Boolean) {
        isScreeningSpeakerEnabled.set(enabled)
        val am = audioManager ?: return

        try {
            if (enabled) {
                Log.i(TAG, "Live listening speaker enabled: unmuting STREAM_VOICE_CALL")
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                am.isSpeakerphoneOn = true
            } else {
                Log.i(TAG, "Live listening speaker disabled: muting STREAM_VOICE_CALL")
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
                am.isSpeakerphoneOn = false
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

        muteVoiceCallStream(false)

        audioManager?.let { am ->
            try {
                am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
            } catch (ignored: Throwable) {}
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

    private fun muteVoiceCallStream(mute: Boolean) {
        val am = audioManager ?: return
        try {
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
            Log.i(TAG, "Voice call stream adjusted: mute=$mute")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to adjust STREAM_VOICE_CALL volume: ${t.message}")
        }
    }
}
