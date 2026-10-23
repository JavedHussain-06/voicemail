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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Privileged Audio Routing and Telecom Uplink/Downlink Streaming Service (UID 2000).
 */
@Keep
class VoicemailUserService : IVoicemailAudioService.Stub {

    companion object {
        private const val TAG = "VoicemailUserService"
        private const val DEFAULT_SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SIZE_SAMPLES = 1600
        private const val QUALCOMM_PARAM_INCALL_MUSIC_ON = "incall_music_enabled=true;incall_music_uplink=true"
        private const val QUALCOMM_PARAM_INCALL_MUSIC_OFF = "incall_music_enabled=false;incall_music_uplink=false"
        private const val MEDIATEK_PARAM_UPLINK_ON = "Set_SpeechCall_Music_Uplink=1"
        private const val MEDIATEK_PARAM_UPLINK_OFF = "Set_SpeechCall_Music_Uplink=0"
        private const val SAMSUNG_PARAM_UPLINK_ON = "g_call_sim_voice_uplink=true"
        private const val SAMSUNG_PARAM_UPLINK_OFF = "g_call_sim_voice_uplink=false"
        private const val USAGE_CALL_ASSISTANT_HIDDEN = 17
        private const val AUDIO_SOURCE_VOICE_DOWNLINK = 3
        private const val AUDIO_SOURCE_VOICE_CALL = 4
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
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
        }.getOrNull()

        this.rawContext = base
        if (base != null) {
            this.shellContext = ShellAttributionContext(base)
            this.audioManager = base.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }

        runCatching {
            val myUid = Process.myUid()
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "cmd appops set com.android.shell RECORD_AUDIO allow ; " +
                "cmd appops set com.android.shell PLAY_AUDIO allow ; " +
                "cmd appops set com.android.shell CAPTURE_AUDIO_OUTPUT allow ; " +
                "cmd appops set com.android.shell MODIFY_AUDIO_ROUTING allow ; " +
                "appops set $myUid RECORD_AUDIO allow ; " +
                "appops set $myUid CAPTURE_AUDIO_OUTPUT allow"
            )).waitFor()
        }
    }

    private class ShellAttributionContext(base: Context) : ContextWrapper(base) {
        private val shellAttribution: AttributionSource? by lazy {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AttributionSource.Builder(Process.myUid())
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
        if (callback == null) return
        if (isSessionActive.get()) stopVoicemailSession()

        isSessionActive.set(true)
        isScreeningSpeakerEnabled.set(false)

        activeWorkerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LiveVoicemail-PrivilegedPipeline").apply {
                priority = Thread.MAX_PRIORITY
            }
        }

        activeWorkerExecutor?.execute {
            try {
                audioManager?.let { am ->
                    runCatching {
                        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        savedVoiceCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    }
                }

                configurePhaseAGreetingMode()

                try {
                    executePhaseAGreetingInjection(greetingWavFd)
                } catch (t: Throwable) {
                    Log.e(TAG, "Greeting error in Phase A", t)
                } finally {
                    try { callback.onGreetingFinished() } catch (re: RemoteException) {}
                }

                if (!isSessionActive.get()) return@execute

                audioManager?.let { am ->
                    runCatching {
                        am.isMicrophoneMute = true
                        if (!isScreeningSpeakerEnabled.get()) {
                            am.isSpeakerphoneOn = false
                            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
                        }
                    }
                }

                executePhaseBDownlinkCapture(callback)
            } catch (t: Throwable) {
                try { callback.onError("Audio pipeline failed: ${t.localizedMessage}") } catch (ignored: RemoteException) {}
                stopVoicemailSession()
            } finally {
                try { greetingWavFd?.close() } catch (ignored: Throwable) {}
            }
        }
    }

    private fun configurePhaseAGreetingMode() {
        val am = audioManager ?: return
        try {
            if (am.mode != AudioManager.MODE_IN_CALL) am.mode = AudioManager.MODE_IN_CALL
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.70f).toInt().coerceAtLeast(3), 0)
        } catch (t: Throwable) {}
    }

    private fun readWavSampleRate(fd: AssetFileDescriptor?): Int {
        if (fd == null) return DEFAULT_SAMPLE_RATE_HZ
        return try {
            val pfd = fd.parcelFileDescriptor
            val isStream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else fd.createInputStream()
            val header = ByteArray(44)
            val read = isStream.read(header)
            isStream.close()
            if (read >= 28) {
                val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (rate in 8000..48000) return rate
            }
            DEFAULT_SAMPLE_RATE_HZ
        } catch (t: Throwable) {
            DEFAULT_SAMPLE_RATE_HZ
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

    private fun executePhaseAGreetingInjection(greetingWavFd: AssetFileDescriptor?) {
        val am = audioManager ?: return
        val stagedTmpFile = File("/data/local/tmp/voicemail_greeting.wav")
        greetingWavFd?.let { fd ->
            runCatching {
                val pfd = fd.parcelFileDescriptor
                val inStream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else fd.createInputStream()
                FileOutputStream(stagedTmpFile).use { fos ->
                    inStream.copyTo(fos)
                    fos.flush()
                }
                stagedTmpFile.setReadable(true, false)
            }
        }

        val targetSampleRate = if (stagedTmpFile.exists() && stagedTmpFile.length() >= 44) {
            readWavFileSampleRate(stagedTmpFile)
        } else {
            readWavSampleRate(greetingWavFd)
        }

        runCatching {
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_ON)
            am.setParameters(MEDIATEK_PARAM_UPLINK_ON)
            am.setParameters(SAMSUNG_PARAM_UPLINK_ON)
        }

        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val telephonyOutputDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val earpieceDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val targetDevice = telephonyOutputDevice ?: earpieceDevice

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(targetSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(targetSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val trackBufferSize = max(minBufferSize, CHUNK_SIZE_SAMPLES * 2 * 8)

        fun createAudioTrack(attrs: AudioAttributes, preferredDevice: AudioDeviceInfo?): AudioTrack? {
            return try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                shellContext?.let { ctx ->
                    runCatching {
                        HiddenApiBypass.invoke(AudioTrack.Builder::class.java, builder, "setContext", ctx)
                    }
                }
                val candidate = builder.build()
                if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                    if (preferredDevice != null) runCatching { candidate.setPreferredDevice(preferredDevice) }
                    candidate
                } else {
                    candidate.release()
                    null
                }
            } catch (t: Throwable) {
                null
            }
        }

        val activeTracks = mutableListOf<AudioTrack>()
        val signallingAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        createAudioTrack(signallingAttrs, targetDevice)?.let { activeTracks.add(it) }
        createAudioTrack(buildCallAssistantAttributes(), targetDevice)?.let { activeTracks.add(it) }

        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        createAudioTrack(mediaAttrs, targetDevice)?.let { activeTracks.add(it) }

        if (activeTracks.isEmpty()) return
        val primaryTrack = activeTracks.first()
        currentAudioTrack = primaryTrack

        for (t in activeTracks) { t.setVolume(1.0f); t.play() }

        val pcmPayload: ByteArray = try {
            val rawBytes = if (greetingWavFd != null) {
                val pfd = greetingWavFd.parcelFileDescriptor
                val stream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()) else greetingWavFd.createInputStream()
                stream.use { it.readBytes() }
            } else if (stagedTmpFile.exists() && stagedTmpFile.length() > 44) {
                stagedTmpFile.readBytes()
            } else ByteArray(0)
            if (rawBytes.size > 44) rawBytes.copyOfRange(44, rawBytes.size) else ByteArray(targetSampleRate * 2)
        } catch (t: Throwable) {
            ByteArray(targetSampleRate * 2)
        }

        val boostedPayload = ByteArray(pcmPayload.size)
        for (i in 0 until pcmPayload.size - 1 step 2) {
            val sample = (pcmPayload[i].toInt() and 0xFF or (pcmPayload[i + 1].toInt() shl 8)).toShort()
            val boosted = (sample * 1.5f).toInt().coerceIn(-32768, 32767).toShort()
            boostedPayload[i] = (boosted.toInt() and 0xFF).toByte()
            boostedPayload[i + 1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
        }

        for (t in activeTracks) {
            runCatching { t.write(boostedPayload, 0, minOf(trackBufferSize, boostedPayload.size)) }
        }

        var offset = minOf(trackBufferSize, boostedPayload.size)
        val step = 3200
        while (isSessionActive.get() && offset < boostedPayload.size) {
            val len = minOf(step, boostedPayload.size - offset)
            if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false
            val iterator = activeTracks.iterator()
            while (iterator.hasNext()) {
                val t = iterator.next()
                if (t.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    runCatching { t.pause(); t.flush(); t.stop(); t.release() }
                    iterator.remove()
                    continue
                }
                runCatching { t.write(boostedPayload, offset, len, AudioTrack.WRITE_NON_BLOCKING) }
            }
            offset += len
            try { Thread.sleep(85) } catch (e: InterruptedException) { break }
        }

        try { Thread.sleep(400) } catch (ignored: InterruptedException) {}

        for (t in activeTracks) {
            runCatching { t.stop(); t.release() }
        }
        currentAudioTrack = null

        runCatching {
            am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
            am.setParameters(MEDIATEK_PARAM_UPLINK_OFF)
            am.setParameters(SAMSUNG_PARAM_UPLINK_OFF)
            if (savedMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        }
    }

    private fun readWavFileSampleRate(file: File): Int {
        var sampleRate = DEFAULT_SAMPLE_RATE_HZ
        runCatching {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) >= 28) {
                    val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (rate in 8000..48000) sampleRate = rate
                }
            }
        }
        return sampleRate
    }

    private fun executePhaseBDownlinkCapture(callback: IAudioChunkCallback) {
        val am = audioManager ?: return
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val telephonyInputDevice = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

        val minBufSize = AudioRecord.getMinBufferSize(DEFAULT_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recordBufferSize = max(minBufSize, CHUNK_SIZE_SAMPLES * 2 * 4)

        fun createAndStartRecord(source: Int, routeToTelephonyDevice: Boolean): AudioRecord? {
            return try {
                var record: AudioRecord? = null
                runCatching {
                    val attrBuilder = AudioAttributes.Builder()
                    val presetApplied = runCatching {
                        HiddenApiBypass.invoke(AudioAttributes.Builder::class.java, attrBuilder, "setInternalCapturePreset", source)
                        true
                    }.getOrDefault(false)

                    val recBuilder = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(recordBufferSize)

                    shellContext?.let { ctx ->
                        runCatching { HiddenApiBypass.invoke(AudioRecord.Builder::class.java, recBuilder, "setContext", ctx) }
                    }

                    if (presetApplied) {
                        runCatching { HiddenApiBypass.invoke(AudioRecord.Builder::class.java, recBuilder, "setAudioAttributes", attrBuilder.build()) }
                    } else if (source != AUDIO_SOURCE_VOICE_DOWNLINK && source != AUDIO_SOURCE_VOICE_CALL) {
                        recBuilder.setAudioSource(source)
                    }

                    val candidateRec = recBuilder.build()
                    if (candidateRec.state == AudioRecord.STATE_INITIALIZED) record = candidateRec else candidateRec.release()
                }

                if (record == null) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val fallbackRec = AudioRecord(source, DEFAULT_SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferSize)
                        if (fallbackRec.state == AudioRecord.STATE_INITIALIZED) record = fallbackRec else fallbackRec.release()
                    }
                }

                val finalRec = record ?: return null
                if (routeToTelephonyDevice && telephonyInputDevice != null) {
                    runCatching { finalRec.setPreferredDevice(telephonyInputDevice) }
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

        val candidates = mutableListOf(
            Pair(AUDIO_SOURCE_VOICE_DOWNLINK, true),
            Pair(AUDIO_SOURCE_VOICE_DOWNLINK, false),
            Pair(AUDIO_SOURCE_VOICE_CALL, true),
            Pair(AUDIO_SOURCE_VOICE_CALL, false)
        )
        candidates.add(Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, false))
        candidates.add(Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, false))

        var candidateIndex = 0
        var activeRecord: AudioRecord? = null
        while (candidateIndex < candidates.size) {
            val cand = candidates[candidateIndex]
            val rec = createAndStartRecord(cand.first, cand.second)
            if (rec != null) { activeRecord = rec; break }
            candidateIndex++
        }

        if (activeRecord == null) {
            callback.onError("Unable to initialize privileged downlink AudioRecord.")
            return
        }

        currentAudioRecord = activeRecord
        isRecordingActive.set(true)

        val recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcmByteBuffer = ByteArray(CHUNK_SIZE_SAMPLES * 2)
            while (isSessionActive.get() && isRecordingActive.get()) {
                val record = activeRecord ?: break
                val bytesRead = record.read(pcmByteBuffer, 0, pcmByteBuffer.size)
                if (bytesRead > 0) {
                    val chunk = if (bytesRead == pcmByteBuffer.size) pcmByteBuffer.clone() else pcmByteBuffer.copyOf(bytesRead)
                    runCatching { callback.onPcmChunkReceived(chunk) }
                } else if (bytesRead < 0) {
                    break
                }
            }
        }, "LiveVoicemail-DownlinkLoop")

        activeRecordingThread = recordingThread
        recordingThread.start()
    }

    @Synchronized
    override fun setScreeningSpeakerEnabled(enabled: Boolean) {
        isScreeningSpeakerEnabled.set(enabled)
        val am = audioManager ?: return
        runCatching {
            am.isMicrophoneMute = true
            if (enabled) {
                am.isSpeakerphoneOn = true
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.7f).toInt().coerceAtLeast(2), 0)
            } else {
                am.isSpeakerphoneOn = false
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            }
        }
    }

    @Synchronized
    override fun stopVoicemailSession() {
        isSessionActive.set(false)
        isRecordingActive.set(false)
        runCatching { activeRecordingThread?.interrupt(); activeRecordingThread = null }
        runCatching { currentAudioRecord?.stop(); currentAudioRecord?.release(); currentAudioRecord = null }
        runCatching { currentAudioTrack?.stop(); currentAudioTrack?.release(); currentAudioTrack = null }
        audioManager?.let { am ->
            runCatching {
                am.setParameters(QUALCOMM_PARAM_INCALL_MUSIC_OFF)
                am.setParameters(MEDIATEK_PARAM_UPLINK_OFF)
                am.setParameters(SAMSUNG_PARAM_UPLINK_OFF)
                am.isMicrophoneMute = false
                am.isSpeakerphoneOn = false
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                if (savedVoiceCallVolume > 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0)
                if (savedMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            }
        }
        runCatching { activeWorkerExecutor?.shutdownNow(); activeWorkerExecutor = null }
    }

    override fun destroy() {
        stopVoicemailSession()
        Process.killProcess(Process.myPid())
    }
}
