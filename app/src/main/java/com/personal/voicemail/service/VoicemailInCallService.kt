package com.personal.voicemail.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personal.voicemail.R
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.ipc.IAudioChunkCallback
import com.personal.voicemail.recognition.LiveVoicemailTranscriber
import com.personal.voicemail.ui.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Telecom InCallService implementation holding RoleManager.ROLE_DIALER replacement duties.
 *
 * Intercepts incoming cellular calls across Dual-SIM slots, coordinates the 8-second
 * auto-screening timer, silently answers without room audio bleed, connects to the
 * privileged Shizuku VoicemailUserService, streams caller speech to Sherpa-ONNX, and
 * supports zero-latency mid-call Pick Up takeover with proximity screen-off management.
 */
class VoicemailInCallService : InCallService() {

    companion object {
        private const val TAG = "VoicemailInCallService"
        private const val INCOMING_NOTIFICATION_ID = 2001
        private const val AUTO_ANSWER_DELAY_MS = 8000L // 8-second unanswered countdown

        const val ACTION_TRIGGER_VOICEMAIL = "com.personal.voicemail.ACTION_TRIGGER_VOICEMAIL"
        const val ACTION_ANSWER_CALL = "com.personal.voicemail.ACTION_ANSWER_CALL"
        const val ACTION_PICK_UP = "com.personal.voicemail.ACTION_PICK_UP"
        const val ACTION_HANG_UP = "com.personal.voicemail.ACTION_HANG_UP"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private lateinit var shizukuAudioController: ShizukuAudioController
    private lateinit var transcriber: LiveVoicemailTranscriber

    private var activeCall: Call? = null
    private val allCalls = mutableListOf<Call>()
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var autoAnswerRunnable: Runnable? = null

    private val isScreeningSessionRunning = AtomicBoolean(false)
    private val hasSavedCurrentSession = AtomicBoolean(false)
    private val chunksReceivedCount = AtomicInteger(0)

    private var isVoicemailScreeningActive = false
    private var inProcessGreetingPlayer: MediaPlayer? = null
    private var greetingTimeoutJob: Job? = null
    private val isGreetingFinished = AtomicBoolean(false)

    private var inProcessAudioRecord: AudioRecord? = null
    private var inProcessRecordJob: Job? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            super.onStateChanged(call, state)
            Log.i(TAG, "Call onStateChanged: state=$state, screeningRunning=${isScreeningSessionRunning.get()}, screeningActive=$isVoicemailScreeningActive")
            when (state) {
                Call.STATE_RINGING -> {
                    TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.INCOMING_RINGING)
                    updateInCallNotification()
                }
                Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                    TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.OUTGOING_DIALING)
                    updateInCallNotification()
                }
                Call.STATE_HOLDING -> {
                    TelecomCallManager.setIsOnHold(true)
                }
                Call.STATE_ACTIVE -> {
                    TelecomCallManager.setIsOnHold(false)
                    if (!isScreeningSessionRunning.get() && !isVoicemailScreeningActive) {
                        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.ACTIVE_CALL)
                        acquireProximityWakeLock()
                        updateInCallNotification()
                    }
                }
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    if (call != null) {
                        allCalls.remove(call)
                        TelecomCallManager.updateCallsList(allCalls)
                    }
                    if (activeCall == call || allCalls.isEmpty()) {
                        handleCallDisconnected()
                    }
                }
            }
        }

        override fun onDetailsChanged(call: Call?, details: Call.Details?) {
            super.onDetailsChanged(call, details)
        }
    }

    private val audioChunkCallback = object : IAudioChunkCallback.Stub() {
        override fun onPcmChunkReceived(pcm16kHzBytes: ByteArray?) {
            if (pcm16kHzBytes != null && isVoicemailScreeningActive) {
                chunksReceivedCount.incrementAndGet()
                val shortCount = pcm16kHzBytes.size / 2
                val shortArray = ShortArray(shortCount)
                ByteBuffer.wrap(pcm16kHzBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
                transcriber.submitPcmChunk(shortArray)
            }
        }

        override fun onGreetingFinished() {
            Log.i(TAG, "Privileged service reported onGreetingFinished (ignored: in-process greeting master controls playback duration).")
        }

        override fun onError(message: String?) {
            Log.e(TAG, "Privileged audio service reported error: $message")
            // Safety net: in-process audio recording
            startInProcessAudioRecord()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shizukuAudioController = VoicemailApplication.shizukuController
        transcriber = VoicemailApplication.transcriber
        TelecomCallManager.currentInCallService = this
        Log.i(TAG, "VoicemailInCallService created with shared singletons.")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoAnswerTimer()
        releaseProximityWakeLock()
        stopInProcessGreetingFallback()
        stopInProcessAudioRecord()
        if (!hasSavedCurrentSession.get()) {
            stopAndSaveVoicemailSession(isPickedUp = false)
        }
        TelecomCallManager.currentInCallService = null
        allCalls.clear()
        Log.i(TAG, "VoicemailInCallService destroyed.")
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callCallback)
        super.onCallAdded(call)

        activeCall = call
        if (!allCalls.contains(call)) {
            allCalls.add(call)
        }
        TelecomCallManager.updateCallsList(allCalls)

        hasSavedCurrentSession.set(false)
        isScreeningSessionRunning.set(false)
        isVoicemailScreeningActive = false

        val rawNumber = call.details?.handle?.schemeSpecificPart?.let { Uri.decode(it) }.orEmpty()
        val displayNumber = if (rawNumber.isNotBlank()) rawNumber else "Unknown Number"
        val callerName = call.details?.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: resolveContactName(displayNumber)

        val (simLabel, simSlot) = resolveSimDetails(call.details?.accountHandle)

        val isOutgoing = call.state == Call.STATE_DIALING || call.state == Call.STATE_CONNECTING ||
                (call.details?.callDirection == Call.Details.DIRECTION_OUTGOING)

        Log.i(TAG, "onCallAdded: number=$displayNumber, caller=$callerName, SIM=$simLabel (slot=$simSlot), isOutgoing=$isOutgoing")

        val initialState = when {
            call.state == Call.STATE_RINGING -> TelecomCallManager.CallScreeningState.INCOMING_RINGING
            isOutgoing -> TelecomCallManager.CallScreeningState.OUTGOING_DIALING
            else -> TelecomCallManager.CallScreeningState.ACTIVE_CALL
        }

        TelecomCallManager.updateCall(
            call = call,
            name = callerName,
            number = displayNumber,
            simName = simLabel,
            simSlot = simSlot,
            state = initialState
        )

        updateInCallNotification()

        // Immediate launch of InCallActivity for Lockscreen & Heads-up presentation on Vivo FuntouchOS
        try {
            val activityIntent = Intent(this, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(activityIntent)
            Log.i(TAG, "Launched InCallActivity directly from onCallAdded")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed launching InCallActivity directly from onCallAdded", t)
        }

        // If call is incoming ringing, schedule 8-second auto-screening countdown
        if (call.state == Call.STATE_RINGING && !isOutgoing) {
            startAutoAnswerTimer()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "onCallRemoved.")
        call.unregisterCallback(callCallback)
        allCalls.remove(call)
        TelecomCallManager.updateCallsList(allCalls)
        if (activeCall == call || allCalls.isEmpty()) {
            handleCallDisconnected()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        TelecomCallManager.updateAudioState(audioState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_VOICEMAIL -> triggerLiveVoicemailScreening(activeCall)
            ACTION_ANSWER_CALL -> TelecomCallManager.answerCallNormally()
            ACTION_PICK_UP -> takeOverVoicemailCall()
            ACTION_HANG_UP -> activeCall?.disconnect()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    fun placeSecondCall(number: String) {
        try {
            val uri = Uri.fromParts("tel", number, null)
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            tm?.placeCall(uri, null)
            Log.i(TAG, "Placed second call to $number for multi-call conference.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error placing second call: ${t.message}")
        }
    }

    private fun startAutoAnswerTimer() {
        cancelAutoAnswerTimer()
        val runnable = Runnable {
            val call = activeCall
            if (call != null && call.state == Call.STATE_RINGING) {
                Log.i(TAG, "Auto-answer 8s timer fired. Initiating silent Live Voicemail screening.")
                triggerLiveVoicemailScreening(call)
            }
        }
        autoAnswerRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_ANSWER_DELAY_MS)
    }

    private fun cancelAutoAnswerTimer() {
        autoAnswerRunnable?.let { mainHandler.removeCallbacks(it) }
        autoAnswerRunnable = null
    }

    /**
     * Answers call and initiates Live Voicemail screening with deterministic polling.
     */
    fun triggerLiveVoicemailScreening(call: Call? = activeCall) {
        val targetCall = call ?: return
        cancelAutoAnswerTimer()

        if (isScreeningSessionRunning.getAndSet(true)) {
            Log.d(TAG, "Screening session already running for current call.")
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            Log.i(TAG, "Triggering Live Voicemail: Requesting answer and setting screening active.")
            TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_ANSWERING)
            updateInCallNotification()

            if (targetCall.state == Call.STATE_RINGING || targetCall.state == Call.STATE_CONNECTING) {
                targetCall.answer(VideoProfile.STATE_AUDIO_ONLY)
            }

            // Actively poll until call reaches STATE_ACTIVE (max 5 seconds) instead of relying solely on callback
            var waitedMs = 0
            while (targetCall.state != Call.STATE_ACTIVE && targetCall.state != Call.STATE_DISCONNECTED && waitedMs < 5000) {
                delay(100)
                waitedMs += 100
            }

            if (targetCall.state == Call.STATE_DISCONNECTED) {
                Log.w(TAG, "Call disconnected before reaching STATE_ACTIVE")
                handleCallDisconnected()
                return@launch
            }

            // Wait 600ms for Vivo VoLTE modem to stabilize audio path
            delay(600)
            executeVoicemailPipeline(targetCall)
        }
    }

    /**
     * Executes the authoritative greeting playback and caller transcription session.
     */
    private fun executeVoicemailPipeline(call: Call) {
        isVoicemailScreeningActive = true
        isGreetingFinished.set(false)

        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_GREETING)
        updateInCallNotification()

        // 1. Switch call audio route to ROUTE_SPEAKER so bottom speaker is loud and active
        try {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            Log.i(TAG, "Audio route set to ROUTE_SPEAKER for greeting acoustic playback.")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed setting ROUTE_SPEAKER: ${t.message}")
        }

        // 2. Unmute microphone and maximize volume during greeting
        try {
            audioManager.isMicrophoneMute = false
            audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
            Log.i(TAG, "Microphone unmuted and streams set to MAX volume for greeting transmission.")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed setting greeting volume: ${t.message}")
        }

        // 3. Ensure InCallActivity is visible in foreground
        try {
            val activityIntent = Intent(this, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(activityIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed ensuring InCallActivity visibility in executeVoicemailPipeline", t)
        }

        // 4. Start transcriber session
        val number = TelecomCallManager.phoneNumber.value
        val name = TelecomCallManager.callerName.value
        val simName = TelecomCallManager.simDisplayName.value
        val simSlot = TelecomCallManager.simSlotIndex.value

        transcriber.startSession(
            callerName = name,
            phoneNumber = number,
            simDisplayName = simName,
            simSlotIndex = simSlot
        )

        // 5. In-process authoritative greeting playback (plays full ~5.6s file with beep)
        playAuthoritativeGreeting()

        // 6. Connect to Shizuku privileged audio service for Qualcomm hardware DSP assistance
        val greetingFd = GreetingGenerator.getGreetingAssetFileDescriptor(this)
        serviceScope.launch(Dispatchers.IO) {
            val isBound = shizukuAudioController.ensureBound(3000L)
            Log.i(TAG, "Shizuku UserService connection verified: $isBound")
            if (isBound) {
                shizukuAudioController.startVoicemailSession(greetingFd, audioChunkCallback)
            } else {
                Log.w(TAG, "Shizuku not bound. Starting immediate in-process AudioRecord safety net.")
                withContext(Dispatchers.Main) {
                    startInProcessAudioRecord()
                }
            }
        }
    }

    private fun playAuthoritativeGreeting() {
        try {
            val greetingFile = GreetingGenerator.getActiveGreetingFile(this)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(greetingFile.absolutePath)
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            inProcessGreetingPlayer = mp
            val dur = mp.duration
            Log.i(TAG, "Authoritative Greeting MediaPlayer started on ROUTE_SPEAKER (${greetingFile.length()} bytes, duration=${dur}ms)")

            mp.setOnCompletionListener {
                Log.i(TAG, "Greeting MediaPlayer completed naturally. Initiating caller voice capture.")
                onGreetingPlaybackFinished()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Greeting MediaPlayer error what=$what extra=$extra")
                onGreetingPlaybackFinished()
                true
            }

            // Safety timeout safeguard in case onCompletionListener fails to trigger (dur + 1000ms or 6500ms)
            val timeoutMs = if (dur > 0) (dur + 1000L) else 6500L
            greetingTimeoutJob?.cancel()
            greetingTimeoutJob = serviceScope.launch {
                delay(timeoutMs)
                if (!isGreetingFinished.get() && isVoicemailScreeningActive) {
                    Log.i(TAG, "Greeting safety timeout fired ($timeoutMs ms). Transitioning to caller capture.")
                    onGreetingPlaybackFinished()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Authoritative greeting playback failed: ${t.message}", t)
            onGreetingPlaybackFinished()
        }
    }

    private fun onGreetingPlaybackFinished() {
        if (isGreetingFinished.getAndSet(true)) return
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = null
        stopInProcessGreetingFallback()

        if (!isVoicemailScreeningActive) return

        Log.i(TAG, "Greeting fully finished. Transitioning to SCREENING_LIVE_VOICEMAIL.")

        // Silence room audio: switch to earpiece and mute STREAM_VOICE_CALL
        try {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Error silencing audio after greeting: ${t.message}")
        }

        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL)
        updateInCallNotification()

        // Start caller voice recording & live transcription
        startInProcessAudioRecord()
        startChunkWatchdog()
    }

    private fun stopInProcessGreetingFallback() {
        try {
            inProcessGreetingPlayer?.stop()
            inProcessGreetingPlayer?.release()
        } catch (ignored: Throwable) {}
        inProcessGreetingPlayer = null
    }

    private fun startChunkWatchdog() {
        chunksReceivedCount.set(0)
        serviceScope.launch {
            delay(2500L)
            if (isVoicemailScreeningActive && chunksReceivedCount.get() == 0) {
                Log.w(TAG, "No PCM chunks received from Shizuku after 2.5s. Engaging in-process AudioRecord safety net...")
                startInProcessAudioRecord()
            }
        }
    }

    private fun startInProcessAudioRecord() {
        if (inProcessRecordJob != null || !isVoicemailScreeningActive) return
        inProcessRecordJob = serviceScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, 3200 * 4)
                )
            } catch (t: Throwable) {
                null
            } ?: try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, 3200 * 4)
                )
            } catch (t: Throwable) {
                null
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed initializing in-process fallback AudioRecord")
                return@launch
            }

            inProcessAudioRecord = record
            try {
                record.startRecording()
                Log.i(TAG, "In-process fallback AudioRecord started successfully")
                val buf = ShortArray(1600)
                while (isVoicemailScreeningActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) {
                        chunksReceivedCount.incrementAndGet()
                        transcriber.submitPcmChunk(buf.copyOf(read))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "In-process AudioRecord loop error", t)
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (ignored: Throwable) {}
                if (inProcessAudioRecord == record) inProcessAudioRecord = null
            }
        }
    }

    private fun stopInProcessAudioRecord() {
        inProcessRecordJob?.cancel()
        inProcessRecordJob = null
        try {
            inProcessAudioRecord?.stop()
            inProcessAudioRecord?.release()
        } catch (ignored: Throwable) {}
        inProcessAudioRecord = null
    }

    /**
     * Handles user tapping "Pick Up" to talk to the caller directly.
     */
    fun takeOverVoicemailCall() {
        Log.i(TAG, "Taking over Live Voicemail session: transitioning to active conversation.")
        stopInProcessGreetingFallback()
        stopInProcessAudioRecord()

        // 1. Stop privileged screening audio session
        shizukuAudioController.stopVoicemailSession()

        // 2. Unmute hardware microphone & voice call stream
        audioManager.isMicrophoneMute = false
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)

        // 3. Acquire proximity lock so ear proximity blanks display
        acquireProximityWakeLock()

        // 4. Update UI state to ActiveCall
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.ACTIVE_CALL)
        updateInCallNotification()

        // 5. Finalize voicemail record as picked up
        stopAndSaveVoicemailSession(isPickedUp = true)
    }

    fun setScreeningSpeakerEnabled(enabled: Boolean) {
        shizukuAudioController.setScreeningSpeakerEnabled(enabled)
    }

    /**
     * Guarantees that every screened call is saved to repository exactly once.
     */
    fun stopAndSaveVoicemailSession(isPickedUp: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (hasSavedCurrentSession.getAndSet(true)) {
            Log.d(TAG, "Session already saved for current call.")
            onComplete?.invoke()
            return
        }

        cancelAutoAnswerTimer()
        releaseProximityWakeLock()
        stopInProcessGreetingFallback()
        stopInProcessAudioRecord()

        val wasScreening = isVoicemailScreeningActive || isScreeningSessionRunning.get()
        isVoicemailScreeningActive = false
        isScreeningSessionRunning.set(false)

        shizukuAudioController.stopVoicemailSession()
        audioManager.isMicrophoneMute = false

        TelecomCallManager.setScreeningState(
            if (isPickedUp) TelecomCallManager.CallScreeningState.ACTIVE_CALL
            else TelecomCallManager.CallScreeningState.DISCONNECTED
        )

        appScope.launch(NonCancellable) {
            Log.i(TAG, "NonCancellable job saving voicemail on session completion...")
            try {
                val voicemail = transcriber.stopSession(isPickedUp = isPickedUp, saveToRepository = true)
                if (voicemail != null) {
                    Log.i(TAG, "SAVED VOICEMAIL ENTRY: id=${voicemail.id}, number=${voicemail.phoneNumber}, duration=${voicemail.durationSeconds}")
                    if (wasScreening && !isPickedUp && voicemail.transcript.isNotBlank()) {
                        postVoicemailCompletedNotification(voicemail.callerName ?: voicemail.phoneNumber, voicemail.transcript)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in stopSession / save", t)
            } finally {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun handleCallDisconnected() {
        cancelAutoAnswerTimer()
        releaseProximityWakeLock()
        stopAndSaveVoicemailSession(isPickedUp = false) {
            // Dismiss in-call notification ONLY AFTER session has been written to disk and saved to Room
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(INCOMING_NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "Error dismissing in-call notification: ${t.message}")
            }
        }

        // Reset state after brief delay
        mainHandler.postDelayed({
            TelecomCallManager.reset()
            activeCall = null
        }, 1500L)
    }

    private fun updateInCallNotification() {
        val name = TelecomCallManager.callerName.value ?: TelecomCallManager.phoneNumber.value.ifBlank { "Phone Call" }
        val number = TelecomCallManager.phoneNumber.value
        val simLabel = TelecomCallManager.simDisplayName.value
        val state = TelecomCallManager.screeningState.value

        val (titleText, contentText) = when (state) {
            TelecomCallManager.CallScreeningState.INCOMING_RINGING ->
                Pair(name, "$simLabel • Incoming Call")
            TelecomCallManager.CallScreeningState.OUTGOING_DIALING ->
                Pair(name, "$simLabel • Calling…")
            TelecomCallManager.CallScreeningState.SCREENING_ANSWERING ->
                Pair(name, "$simLabel • Answering for Voicemail…")
            TelecomCallManager.CallScreeningState.SCREENING_GREETING ->
                Pair(name, "$simLabel • Playing Greeting…")
            TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL ->
                Pair(name, "$simLabel • Live Voicemail Screening")
            TelecomCallManager.CallScreeningState.ACTIVE_CALL ->
                Pair(name, "$simLabel • Call in Progress")
            TelecomCallManager.CallScreeningState.DISCONNECTED ->
                Pair(name, "Call Ended")
            else ->
                Pair(name, simLabel)
        }

        val openIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
            action = ACTION_HANG_UP
        }
        val pendingHangUp = PendingIntent.getService(
            this,
            2,
            hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, VoicemailApplication.CHANNEL_INCALL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(state != TelecomCallManager.CallScreeningState.DISCONNECTED)
            .setAutoCancel(false)
            .setContentIntent(pendingOpen)

        if (state == TelecomCallManager.CallScreeningState.INCOMING_RINGING) {
            builder.setFullScreenIntent(pendingOpen, true)

            val answerIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_ANSWER_CALL
            }
            val pendingAnswer = PendingIntent.getService(
                this,
                4,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val voicemailIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_TRIGGER_VOICEMAIL
            }
            val pendingVoicemail = PendingIntent.getService(
                this,
                1,
                voicemailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(android.R.drawable.sym_action_call, getString(R.string.button_answer), pendingAnswer)
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.button_screen_call), pendingVoicemail)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.button_end_call), pendingHangUp)
        } else if (state == TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL ||
            state == TelecomCallManager.CallScreeningState.SCREENING_GREETING
        ) {
            val pickUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_PICK_UP
            }
            val pendingPickUp = PendingIntent.getService(
                this,
                3,
                pickUpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.sym_call_incoming, getString(R.string.button_pickup), pendingPickUp)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.button_end_call), pendingHangUp)
        } else if (state == TelecomCallManager.CallScreeningState.OUTGOING_DIALING) {
            // Outgoing call dialing: Only End Call
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.button_end_call), pendingHangUp)
        } else if (state == TelecomCallManager.CallScreeningState.ACTIVE_CALL) {
            // Normal active call: Screen Call and End Call
            val voicemailIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_TRIGGER_VOICEMAIL
            }
            val pendingVoicemail = PendingIntent.getService(
                this,
                1,
                voicemailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.button_screen_call), pendingVoicemail)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.button_end_call), pendingHangUp)
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.button_end_call), pendingHangUp)
        }

        val notification = builder.build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    INCOMING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(INCOMING_NOTIFICATION_ID, notification)
            }
        }.onFailure {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(INCOMING_NOTIFICATION_ID, notification)
        }
    }

    private fun postVoicemailCompletedNotification(caller: String, transcript: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(this, VoicemailApplication.CHANNEL_VOICEMAIL_NOTIFICATIONS_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("New Voicemail from $caller")
            .setContentText(transcript.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(transcript))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        nm.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
    }

    private fun acquireProximityWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (proximityWakeLock == null) {
                proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "PersonalVoicemail:ProximityScreenOff"
                )
            }
            if (proximityWakeLock?.isHeld == false) {
                proximityWakeLock?.acquire(2 * 60 * 60 * 1000L)
                Log.i(TAG, "Proximity wake lock acquired.")
            }
        }
    }

    private fun releaseProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
            Log.i(TAG, "Proximity wake lock released.")
        }
        proximityWakeLock = null
    }

    private fun parsePhoneNumber(uri: Uri?): String {
        if (uri == null) return "Unknown"
        val schemeSpecific = uri.schemeSpecificPart
        return schemeSpecific ?: uri.toString()
    }

    private fun resolveContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown Number") return "Unknown Caller"
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return phoneNumber
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
            phoneNumber
        } catch (e: Exception) {
            Log.w(TAG, "Error looking up contact name for $phoneNumber: ${e.message}")
            phoneNumber
        }
    }

    private fun resolveSimDetails(accountHandle: PhoneAccountHandle?): Pair<String, Int> {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return Pair("SIM 1", 0)
        }
        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val subs = sm?.activeSubscriptionInfoList ?: return Pair("SIM 1", 0)

        val targetId = accountHandle?.id
        val matched = subs.firstOrNull { sub ->
            sub.subscriptionId.toString() == targetId || sub.iccId == targetId
        } ?: subs.firstOrNull()

        return if (matched != null) {
            val slot = matched.simSlotIndex
            val carrier = matched.displayName?.toString()?.ifBlank { matched.carrierName?.toString() } ?: "Cellular"
            Pair("SIM ${slot + 1} - $carrier", slot)
        } else {
            Pair("SIM 1", 0)
        }
    }
}
