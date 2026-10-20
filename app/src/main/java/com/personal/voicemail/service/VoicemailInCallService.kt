package com.personal.voicemail.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
 * auto-screening timer, silently answers without room audio bleed, mutes the local microphone
 * so the caller cannot hear the user, injects the greeting via Shizuku UID 2000 into the
 * cellular uplink, and streams caller downlink audio to Sherpa-ONNX.
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

    @Volatile
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
    }

    /**
     * Receives 16kHz caller downlink PCM chunks from the privileged Shizuku VoicemailUserService.
     */
    private val audioChunkCallback = object : IAudioChunkCallback.Stub() {
        override fun onPcmChunkReceived(pcm16kHzBytes: ByteArray?) {
            if (pcm16kHzBytes != null && isVoicemailScreeningActive && isGreetingFinished.get()) {
                val count = chunksReceivedCount.incrementAndGet()
                val shortCount = pcm16kHzBytes.size / 2
                val shortArray = ShortArray(shortCount)
                ByteBuffer.wrap(pcm16kHzBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)

                // Boost quiet cellular downlink signal moderately without clipping
                var peak = 0
                for (i in shortArray.indices) {
                    val boosted = (shortArray[i].toInt() * 2).coerceIn(-32768, 32767)
                    val absVal = kotlin.math.abs(boosted)
                    if (absVal > peak) peak = absVal
                    shortArray[i] = boosted.toShort()
                }
                if (count <= 15 || count % 20 == 0) {
                    Log.d(TAG, "Privileged downlink chunk #$count received (${pcm16kHzBytes.size}B, boostedPeak=$peak)")
                }
                transcriber.submitPcmChunk(shortArray)
            }
        }

        override fun onGreetingFinished() {
            Log.i(TAG, "Privileged VoicemailUserService reported onGreetingFinished.")
            mainHandler.post {
                onGreetingPlaybackFinished()
            }
        }

        override fun onError(message: String?) {
            Log.e(TAG, "Privileged VoicemailUserService reported error: $message. Falling back to in-process downlink capture.")
            mainHandler.post {
                onGreetingPlaybackFinished()
                startInProcessDownlinkFallback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shizukuAudioController = VoicemailApplication.shizukuController
        transcriber = VoicemailApplication.transcriber
        TelecomCallManager.currentInCallService = this
        // Pre-bind Shizuku UserService so it is hot when a call arrives
        shizukuAudioController.bindUserService()
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

        try {
            val activityIntent = Intent(this, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(activityIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed launching InCallActivity from onCallAdded", t)
        }

        if (call.state == Call.STATE_RINGING && !isOutgoing) {
            startAutoAnswerTimer()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        allCalls.remove(call)
        TelecomCallManager.updateCallsList(allCalls)
        if (activeCall == call || allCalls.isEmpty()) {
            handleCallDisconnected()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        // If voicemail screening is active, enforce hardware mute so the caller NEVER hears the user
        if (isVoicemailScreeningActive && audioState != null && !audioState.isMuted) {
            setMuted(true)
            audioManager.isMicrophoneMute = true
        }
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
     * Answers the call silently, mutes the user's microphone and room speaker immediately,
     * and launches the privileged Shizuku voicemail greeting + downlink capture pipeline.
     */
    fun triggerLiveVoicemailScreening(call: Call? = activeCall) {
        val targetCall = call ?: return
        cancelAutoAnswerTimer()

        if (isScreeningSessionRunning.getAndSet(true)) {
            Log.d(TAG, "Screening session already running for current call.")
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            Log.i(TAG, "Triggering Live Voicemail: Muting room audio & mic BEFORE answering.")
            isVoicemailScreeningActive = true
            TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_ANSWERING)
            TelecomCallManager.setScreeningSpeakerStateOnly(false)
            updateInCallNotification()

            // Pre-mute call stream & microphone before answering so there is zero audio pop/bleed
            enforceSilentScreeningAudioState()

            if (targetCall.state == Call.STATE_RINGING || targetCall.state == Call.STATE_CONNECTING) {
                targetCall.answer(VideoProfile.STATE_AUDIO_ONLY)
            }

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

            // Re-enforce silent screening state as soon as Telecom activates the call audio path
            enforceSilentScreeningAudioState()

            // Wait 500ms for VoLTE modem path setup
            delay(500)
            enforceSilentScreeningAudioState()
            executeVoicemailPipeline()
        }
    }

    /**
     * Forces Earpiece route, mutes STREAM_VOICE_CALL (silent in room), and mutes the phone's
     * physical microphone so the caller cannot hear the user during voicemail screening.
     */
    private fun enforceSilentScreeningAudioState() {
        try {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            setMuted(true)
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = true
            audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed enforcing silent screening audio state: ${t.message}")
        }
    }

    private suspend fun executeVoicemailPipeline() {
        isVoicemailScreeningActive = true
        isGreetingFinished.set(false)
        chunksReceivedCount.set(0)

        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_GREETING)
        updateInCallNotification()

        // Start transcriber session now so it is ready the instant the greeting finishes
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

        val shizukuReady = withContext(Dispatchers.IO) {
            shizukuAudioController.updateShizukuStatus() && shizukuAudioController.ensureBound(2500L)
        }

        if (shizukuReady) {
            Log.i(TAG, "Shizuku VoicemailUserService is bound! Dispatching privileged uplink greeting + downlink capture.")
            try {
                val afd = GreetingGenerator.getGreetingAssetFileDescriptor(this)
                shizukuAudioController.startVoicemailSession(afd, audioChunkCallback)

                // Safety timeout in case binder callback is delayed
                greetingTimeoutJob?.cancel()
                greetingTimeoutJob = serviceScope.launch {
                    delay(8000L)
                    if (!isGreetingFinished.get() && isVoicemailScreeningActive) {
                        Log.w(TAG, "Privileged greeting timeout (8s) reached; ensuring SCREENING_LIVE_VOICEMAIL state.")
                        onGreetingPlaybackFinished()
                    }
                }
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Failed starting Shizuku voicemail session, falling back", t)
            }
        }

        Log.w(TAG, "Shizuku unavailable or unbound! Using silent in-process telephony fallback.")
        playFallbackGreetingToTelephony()
    }

    /**
     * Fallback used ONLY when Shizuku is not running: routes greeting to TYPE_TELEPHONY / Earpiece
     * (never loudspeaker!) so it does not blast loudly in the room.
     */
    private fun playFallbackGreetingToTelephony() {
        try {
            enforceSilentScreeningAudioState()
            audioManager.setParameters("incall_music_enabled=true;incall_music_uplink=true;music_playback_during_call=true")

            val greetingFile = GreetingGenerator.getActiveGreetingFile(this)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val mp = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(greetingFile.absolutePath)
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val telephonyDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                    ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (telephonyDevice != null) {
                    setPreferredDevice(telephonyDevice)
                }
                setVolume(1.0f, 1.0f)
                prepare()
            }
            inProcessGreetingPlayer = mp
            val dur = mp.duration

            mp.setOnCompletionListener {
                onGreetingPlaybackFinished()
                startInProcessDownlinkFallback()
            }
            mp.setOnErrorListener { _, _, _ ->
                onGreetingPlaybackFinished()
                startInProcessDownlinkFallback()
                true
            }

            val timeoutMs = if (dur > 0) (dur + 1000L) else 6500L
            greetingTimeoutJob?.cancel()
            greetingTimeoutJob = serviceScope.launch {
                delay(timeoutMs)
                if (!isGreetingFinished.get() && isVoicemailScreeningActive) {
                    onGreetingPlaybackFinished()
                    startInProcessDownlinkFallback()
                }
            }
            mp.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Fallback greeting failed: ${t.message}", t)
            onGreetingPlaybackFinished()
            startInProcessDownlinkFallback()
        }
    }

    private fun onGreetingPlaybackFinished() {
        if (isGreetingFinished.getAndSet(true)) return
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = null
        stopInProcessGreetingFallback()

        if (!isVoicemailScreeningActive) return

        Log.i(TAG, "Greeting finished. Transitioning UI to SCREENING_LIVE_VOICEMAIL (mic muted, speaker off by default).")

        try {
            audioManager.setParameters("incall_music_enabled=false;incall_music_uplink=false")
            // Keep physical microphone strictly muted so caller cannot hear user!
            setMuted(true)
            audioManager.isMicrophoneMute = true

            // Respect user's "Listen Live" toggle (default is FALSE = completely silent in room)
            val listenLive = TelecomCallManager.isScreeningSpeakerOn.value
            setScreeningSpeakerEnabled(listenLive)
        } catch (t: Throwable) {
            Log.w(TAG, "Error configuring post-greeting audio state: ${t.message}")
        }

        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL)
        updateInCallNotification()
    }

    /**
     * Fallback downlink recorder used ONLY if Shizuku UID 2000 capture fails.
     * Attempts VOICE_DOWNLINK (3) and VOICE_CALL (4) with TYPE_TELEPHONY input while keeping the mic muted.
     */
    private fun startInProcessDownlinkFallback() {
        if (inProcessRecordJob != null || !isVoicemailScreeningActive) return
        inProcessRecordJob = serviceScope.launch(Dispatchers.IO) {
            val candidateSources = intArrayOf(
                3, // MediaRecorder.AudioSource.VOICE_DOWNLINK
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val telephonyInput = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

            var record: AudioRecord? = null
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = max(minBuf, 3200 * 4)

            for (src in candidateSources) {
                try {
                    val candidate = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        if (telephonyInput != null) {
                            runCatching { candidate.setPreferredDevice(telephonyInput) }
                        }
                        candidate.startRecording()
                        if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            record = candidate
                            Log.i(TAG, "Fallback AudioRecord started with source=$src")
                            break
                        }
                    }
                    candidate.release()
                } catch (ignored: Throwable) {}
            }

            if (record == null) {
                Log.e(TAG, "Fallback AudioRecord could not open downlink source (Shizuku permission required).")
                return@launch
            }

            inProcessAudioRecord = record
            val buf = ShortArray(1600)
            try {
                while (isVoicemailScreeningActive) {
                    // Keep physical microphone muted so local voice is never recorded!
                    if (!audioManager.isMicrophoneMute) {
                        audioManager.isMicrophoneMute = true
                    }
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0 && isGreetingFinished.get()) {
                        val copy = ShortArray(read)
                        for (i in 0 until read) {
                            copy[i] = (buf[i].toInt() * 2).coerceIn(-32768, 32767).toShort()
                        }
                        transcriber.submitPcmChunk(copy)
                    } else if (read < 0) {
                        break
                    }
                }
            } finally {
                runCatching {
                    record.stop()
                    record.release()
                }
                if (inProcessAudioRecord == record) inProcessAudioRecord = null
            }
        }
    }

    private fun stopInProcessGreetingFallback() {
        try {
            inProcessGreetingPlayer?.stop()
            inProcessGreetingPlayer?.release()
        } catch (ignored: Throwable) {}
        inProcessGreetingPlayer = null
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
     * Handles user tapping "Pick Up" to take over the call and talk to the caller live.
     */
    fun takeOverVoicemailCall() {
        Log.i(TAG, "Taking over Live Voicemail session: stopping screening and unmuting user microphone.")
        isVoicemailScreeningActive = false
        stopInProcessGreetingFallback()
        stopInProcessAudioRecord()
        shizukuAudioController.stopVoicemailSession()

        // 1. Unmute hardware microphone & restore normal earpiece/call volume for live conversation
        setMuted(false)
        audioManager.isMicrophoneMute = false
        audioManager.isSpeakerphoneOn = false
        setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.8f).toInt().coerceAtLeast(3), 0)

        // 2. Acquire proximity lock so ear proximity blanks display
        acquireProximityWakeLock()

        // 3. Update UI state to ActiveCall
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.ACTIVE_CALL)
        updateInCallNotification()

        // 4. Finalize voicemail record as picked up
        stopAndSaveVoicemailSession(isPickedUp = true)
    }

    /**
     * Toggles whether the user wants to hear the caller's voice out loud in the room while screening.
     * Microphone remains strictly muted either way.
     */
    fun setScreeningSpeakerEnabled(enabled: Boolean) {
        try {
            setMuted(true)
            audioManager.isMicrophoneMute = true
            shizukuAudioController.setScreeningSpeakerEnabled(enabled)

            if (enabled) {
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                audioManager.isSpeakerphoneOn = true
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.7f).toInt().coerceAtLeast(2), 0)
            } else {
                setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                audioManager.isSpeakerphoneOn = false
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            }
            Log.i(TAG, "setScreeningSpeakerEnabled($enabled): micMuted=true, speaker=$enabled")
        } catch (t: Throwable) {
            Log.e(TAG, "Error toggling screening speaker: ${t.message}")
        }
    }

    fun stopAndSaveVoicemailSession(isPickedUp: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (hasSavedCurrentSession.getAndSet(true)) {
            onComplete?.invoke()
            return
        }

        cancelAutoAnswerTimer()
        releaseProximityWakeLock()
        stopInProcessGreetingFallback()
        stopInProcessAudioRecord()
        shizukuAudioController.stopVoicemailSession()

        val wasScreening = isVoicemailScreeningActive || isScreeningSessionRunning.get()
        val wasGreetingPlaying = !isGreetingFinished.get()
        isVoicemailScreeningActive = false
        isScreeningSessionRunning.set(false)

        // Restore normal audio state when session ends
        setMuted(false)
        audioManager.isMicrophoneMute = false
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)

        TelecomCallManager.setScreeningState(
            if (isPickedUp) TelecomCallManager.CallScreeningState.ACTIVE_CALL
            else TelecomCallManager.CallScreeningState.DISCONNECTED
        )

        appScope.launch(NonCancellable) {
            try {
                if (wasGreetingPlaying && wasScreening && !isPickedUp) {
                    val number = TelecomCallManager.phoneNumber.value
                    val name = TelecomCallManager.callerName.value
                    val simName = TelecomCallManager.simDisplayName.value
                    val simSlot = TelecomCallManager.simSlotIndex.value

                    transcriber.stopSession(isPickedUp = false, saveToRepository = false)

                    val recordingsDir = java.io.File(filesDir, "recordings").apply { mkdirs() }
                    val cleanNumber = number.replace(Regex("[^0-9+]"), "").ifEmpty { "unknown" }
                    val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val finalWavFile = java.io.File(recordingsDir, "voicemail_${cleanNumber}_$timeStamp.wav")
                    try {
                        val silenceBytes = ByteArray(16000 * 2)
                        java.io.FileOutputStream(finalWavFile).use { fos ->
                            GreetingGenerator.writeWavHeader(
                                out = fos,
                                totalAudioLen = silenceBytes.size.toLong(),
                                sampleRate = 16000,
                                channels = 1,
                                bitsPerSample = 16
                            )
                            fos.write(silenceBytes)
                            fos.flush()
                        }
                    } catch (ignored: Throwable) {}

                    val entity = com.personal.voicemail.data.VoicemailEntity(
                        callerName = name,
                        phoneNumber = number,
                        simSlotIndex = simSlot,
                        simDisplayName = simName,
                        timestamp = System.currentTimeMillis(),
                        durationSeconds = 1,
                        transcript = "Caller hung up during greeting",
                        audioFilePath = finalWavFile.absolutePath,
                        isRead = false,
                        isPickedUp = false
                    )
                    VoicemailApplication.voicemailRepository.addVoicemail(entity)
                    postVoicemailCompletedNotification(name ?: number, "Caller hung up during greeting")
                } else if (wasScreening) {
                    val voicemail = transcriber.stopSession(isPickedUp = isPickedUp, saveToRepository = true)
                    if (voicemail != null && !isPickedUp && voicemail.transcript.isNotBlank()) {
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
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(INCOMING_NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "Error dismissing in-call notification: ${t.message}")
            }
        }

        mainHandler.postDelayed({
            TelecomCallManager.reset()
            activeCall = null
        }, 1500L)
    }

    private fun updateInCallNotification() {
        val name = TelecomCallManager.callerName.value ?: TelecomCallManager.phoneNumber.value.ifBlank { "Phone Call" }
        val simLabel = TelecomCallManager.simDisplayName.value
        val state = TelecomCallManager.screeningState.value

        val (titleText, contentText) = when (state) {
            TelecomCallManager.CallScreeningState.INCOMING_RINGING ->
                Pair(name, "$simLabel • Incoming Call")
            TelecomCallManager.CallScreeningState.OUTGOING_DIALING ->
                Pair(name, "$simLabel • Calling…")
            TelecomCallManager.CallScreeningState.SCREENING_ANSWERING ->
                Pair(name, "$simLabel • Answering Silently…")
            TelecomCallManager.CallScreeningState.SCREENING_GREETING ->
                Pair(name, "$simLabel • Playing Greeting to Caller…")
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
        } else if (state == TelecomCallManager.CallScreeningState.ACTIVE_CALL) {
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
            }
        }
    }

    private fun releaseProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        proximityWakeLock = null
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
