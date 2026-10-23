package com.personal.voicemail.service

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
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
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.audio.StandaloneTelephonyAudioEngine
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

/**
 * Telecom InCallService implementation holding RoleManager.ROLE_DIALER replacement duties.
 *
 * Telecom Audio Architecture:
 * - Phase A (SCREENING_GREETING):
 *   - Keeps Telecom uplink unmuted (`setMuted(false)`) and physical mic unmuted so greeting reaches caller.
 *   - When digital injection is available (UID 2000 or self-granted digital privilege), mutes STREAM_VOICE_CALL
 *     so the receiver's phone is 100% silent in the room.
 *   - When in unprivileged acoustic mode, sets STREAM_VOICE_CALL to quiet earpiece level (35%).
 * - Phase B (SCREENING_LIVE_VOICEMAIL):
 *   - When digital capture is available (VOICE_DOWNLINK / TYPE_TELEPHONY), physical mic is safely muted (`setMuted(true)`).
 *   - When in unprivileged acoustic fallback, NEVER calls `setMuted(true)` or `isMicrophoneMute = true`,
 *     because Android AudioFlinger zeroes all AudioRecord buffers when microphone mute is set!
 *     Instead keeps `setMuted(false)` and captures caller speech from earpiece via VOICE_RECOGNITION.
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
    private val standaloneEngine = StandaloneTelephonyAudioEngine()

    private var activeCall: Call? = null
    private val allCalls = mutableListOf<Call>()
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var autoAnswerRunnable: Runnable? = null

    private val isScreeningSessionRunning = AtomicBoolean(false)
    private val hasSavedCurrentSession = AtomicBoolean(false)
    private val chunksReceivedCount = AtomicInteger(0)

    @Volatile
    private var isVoicemailScreeningActive = false
    private var greetingTimeoutJob: Job? = null
    private val isGreetingFinished = AtomicBoolean(false)

    private val isUsingPrivilegedShizuku: Boolean
        get() = shizukuAudioController.isBound

    private val hasDigitalCapturePrivilege: Boolean
        get() = isUsingPrivilegedShizuku || standaloneEngine.hasDigitalTelephonyPrivilege(this)

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
                    cancelAutoAnswerTimer()
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
                    if (allCalls.isEmpty()) {
                        handleCallDisconnected()
                    } else if (activeCall == call) {
                        switchToNextActiveCall()
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

                // Boost quiet cellular downlink signal without clipping
                var peak = 0
                for (i in shortArray.indices) {
                    val boosted = (shortArray[i].toInt() * 3).coerceIn(-32768, 32767)
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
            Log.e(TAG, "Privileged audio error: $message. Seamlessly failing over to StandaloneTelephonyAudioEngine.")
            mainHandler.post {
                onGreetingPlaybackFinished()
                standaloneEngine.startCapture(this@VoicemailInCallService) { chunk ->
                    if (isVoicemailScreeningActive && isGreetingFinished.get()) {
                        transcriber.submitPcmChunk(chunk)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoicemailInCallService onCreate")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shizukuAudioController = VoicemailApplication.shizukuController
        transcriber = VoicemailApplication.transcriber
        TelecomCallManager.currentInCallService = this

        // Auto-grant permanent digital permissions if Shizuku / su is present, then pre-bind
        shizukuAudioController.grantPermanentStandalonePermissions {
            shizukuAudioController.bindUserService()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "VoicemailInCallService onDestroy")
        cancelAutoAnswerTimer()
        releaseProximityWakeLock()
        standaloneEngine.stopSession()
        shizukuAudioController.stopVoicemailSession()
        if (!hasSavedCurrentSession.get()) {
            stopAndSaveVoicemailSession(isPickedUp = false)
        }
        TelecomCallManager.currentInCallService = null
        allCalls.clear()
        super.onDestroy()
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
        if (allCalls.isEmpty()) {
            handleCallDisconnected()
        } else if (activeCall == call) {
            switchToNextActiveCall()
        }
    }

    private fun switchToNextActiveCall() {
        activeCall = allCalls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: allCalls.firstOrNull()
        val current = activeCall ?: return
        val rawNumber = current.details?.handle?.schemeSpecificPart?.let { Uri.decode(it) }.orEmpty()
        val displayNumber = if (rawNumber.isNotBlank()) rawNumber else "Unknown Number"
        val callerName = current.details?.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: resolveContactName(displayNumber)
        val (simLabel, simSlot) = resolveSimDetails(current.details?.accountHandle)
        val isOnHold = current.state == Call.STATE_HOLDING
        TelecomCallManager.setIsOnHold(isOnHold)
        TelecomCallManager.updateCall(
            call = current,
            name = callerName,
            number = displayNumber,
            simName = simLabel,
            simSlot = simSlot,
            state = TelecomCallManager.CallScreeningState.ACTIVE_CALL
        )
        updateInCallNotification()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (isVoicemailScreeningActive && audioState != null) {
            val currentState = TelecomCallManager.screeningState.value
            val hasDigital = hasDigitalCapturePrivilege

            // Uplink muting enforcement:
            // During Phase B (SCREENING_LIVE_VOICEMAIL):
            // - If digital capture is active: keep Telecom uplink muted so caller only speaks.
            // - If unprivileged acoustic fallback: NEVER mute Telecom uplink because AudioFlinger zeroes AudioRecord!
            if (currentState == TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL) {
                if (hasDigital) {
                    if (!audioState.isMuted) {
                        setMuted(true)
                    }
                } else {
                    if (audioState.isMuted) {
                        setMuted(false)
                    }
                }
            } else if (currentState == TelecomCallManager.CallScreeningState.SCREENING_GREETING ||
                currentState == TelecomCallManager.CallScreeningState.SCREENING_ANSWERING
            ) {
                // Phase A: Telecom uplink MUST remain unmuted so caller hears greeting!
                if (audioState.isMuted) {
                    setMuted(false)
                }
            }

            // Never switch to Loudspeaker unless the user explicitly enabled "Listen Live"!
            if (!TelecomCallManager.isScreeningSpeakerOn.value && (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0) {
                setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                audioManager.isSpeakerphoneOn = false
            }
        }
        TelecomCallManager.updateAudioState(audioState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_VOICEMAIL -> triggerLiveVoicemailScreening(activeCall)
            ACTION_ANSWER_CALL -> TelecomCallManager.answerCallNormally()
            ACTION_PICK_UP -> takeOverVoicemailCall()
            ACTION_HANG_UP -> TelecomCallManager.endCall()
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
                Log.i(TAG, "Auto-answer 8s timer fired. Initiating silent Live Voicemail.")
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
     * Answers the call silently for Live Voicemail and launches the greeting + downlink capture pipeline.
     */
    fun triggerLiveVoicemailScreening(call: Call? = activeCall) {
        val targetCall = call ?: return
        cancelAutoAnswerTimer()

        if (isScreeningSessionRunning.getAndSet(true)) {
            Log.d(TAG, "Screening session already running for current call.")
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            Log.i(TAG, "Triggering Live Voicemail. hasDigitalPrivilege=$hasDigitalCapturePrivilege")
            isVoicemailScreeningActive = true
            TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_ANSWERING)
            TelecomCallManager.setScreeningSpeakerStateOnly(false)
            updateInCallNotification()

            // Enforce Phase A audio state BEFORE answering call
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

            // Re-enforce Phase A audio state as soon as Telecom activates call audio path
            enforceSilentScreeningAudioState()

            // Wait 600ms for VoLTE / 5G IMS RTP audio bearer setup so greeting is not dropped
            delay(600)
            enforceSilentScreeningAudioState()

            runCatching {
                setMuted(false)
                audioManager.isMicrophoneMute = false
            }

            executeVoicemailPipeline()
        }
    }

    /**
     * Enforces Phase A screening mode:
     * 1. Routes call audio to EARPIECE (never loudspeaker).
     * 2. Keeps Telecom uplink UNMUTED (`setMuted(false)`) and physical mic unmuted so greeting reaches caller.
     * 3. When digital privilege is held, mutes STREAM_VOICE_CALL so the receiver's phone is 100% dead silent.
     * 4. When unprivileged, sets STREAM_VOICE_CALL to earpiece level (70%).
     */
    private fun enforceSilentScreeningAudioState() {
        try {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            audioManager.isSpeakerphoneOn = false

            // Keep Telecom uplink UNMUTED during Phase A so greeting audio reaches caller
            setMuted(false)
            audioManager.isMicrophoneMute = false

            val hasDigital = hasDigitalCapturePrivilege
            if (hasDigital) {
                // With digital telephony privilege (UID 2000 or self-granted CAPTURE_AUDIO_OUTPUT + MODIFY_AUDIO_ROUTING),
                // MUTE STREAM_VOICE_CALL so room is 100% dead silent!
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
            } else {
                // Without digital privilege, earpiece level (70% volume, strictly on earpiece)
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                val maxVoiceVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVoiceVol * 0.70f).toInt().coerceAtLeast(3), 0)
            }

            Log.i(TAG, "enforceSilentScreeningAudioState: ROUTE_EARPIECE, speaker=false, muted=false, hasDigital=$hasDigital")
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

        // Attempt background permanent permission self-granting if Shizuku / root is present
        withContext(Dispatchers.IO) {
            shizukuAudioController.grantPermanentStandalonePermissions()
        }

        val shizukuReady = withContext(Dispatchers.IO) {
            shizukuAudioController.updateShizukuStatus() && shizukuAudioController.ensureBound(2500L)
        }

        if (shizukuReady) {
            Log.i(TAG, "Privileged daemon bound! Dispatching privileged uplink greeting + downlink capture.")
            try {
                val afd = GreetingGenerator.getGreetingAssetFileDescriptor(this)
                shizukuAudioController.startVoicemailSession(afd, audioChunkCallback)

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
                Log.e(TAG, "Failed starting privileged voicemail session, switching to standalone engine", t)
            }
        }

        Log.i(TAG, "Using StandaloneTelephonyAudioEngine for greeting injection & downlink capture.")
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = serviceScope.launch {
            delay(6500L)
            if (!isGreetingFinished.get() && isVoicemailScreeningActive) {
                Log.w(TAG, "Standalone greeting safety timeout (6.5s) reached; transitioning to SCREENING_LIVE_VOICEMAIL.")
                onGreetingPlaybackFinished()
                standaloneEngine.startCapture(this@VoicemailInCallService) { chunk ->
                    if (isVoicemailScreeningActive && isGreetingFinished.get()) {
                        transcriber.submitPcmChunk(chunk)
                    }
                }
            }
        }

        standaloneEngine.playGreeting(this@VoicemailInCallService) {
            mainHandler.post {
                onGreetingPlaybackFinished()
                standaloneEngine.startCapture(this@VoicemailInCallService) { chunk ->
                    if (isVoicemailScreeningActive && isGreetingFinished.get()) {
                        transcriber.submitPcmChunk(chunk)
                    }
                }
            }
        }
    }

    private fun onGreetingPlaybackFinished() {
        if (isGreetingFinished.getAndSet(true)) return
        greetingTimeoutJob?.cancel()
        greetingTimeoutJob = null

        if (!isVoicemailScreeningActive) return

        val hasDigital = hasDigitalCapturePrivilege
        Log.i(TAG, "Greeting finished. Transitioning to SCREENING_LIVE_VOICEMAIL (hasDigitalCapture=$hasDigital).")

        try {
            audioManager.setParameters("incall_music_enabled=false;incall_music_uplink=false")
            audioManager.setParameters("Set_SpeechCall_Music_Uplink=0")
            audioManager.setParameters("g_call_sim_voice_uplink=false")

            if (hasDigital) {
                // We can safely mute the physical mic because VOICE_DOWNLINK (audio source 3)
                // captures the cellular RX leg before the mic mixer!
                setMuted(true)
                audioManager.isMicrophoneMute = true
            } else {
                // Unprivileged acoustic fallback:
                // NEVER call setMuted(true) or audioManager.isMicrophoneMute = true during Phase B!
                // If you call setMuted(true), AudioRecord is muted by the OS and records 0 bytes of audio!
                setMuted(false)
                audioManager.isMicrophoneMute = false
                setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                audioManager.isSpeakerphoneOn = false
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.6f).toInt().coerceAtLeast(2), 0)
            }

            // Respect user's "Listen Live" toggle (default is FALSE = silent earpiece in room)
            val listenLive = TelecomCallManager.isScreeningSpeakerOn.value
            setScreeningSpeakerEnabled(listenLive)
        } catch (t: Throwable) {
            Log.w(TAG, "Error configuring post-greeting audio state: ${t.message}")
        }

        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL)
        updateInCallNotification()
    }

    /**
     * Handles user tapping "Pick Up" to take over the call and talk to the caller live.
     */
    fun takeOverVoicemailCall() {
        Log.i(TAG, "Taking over Live Voicemail session: stopping screening and unmuting user microphone.")
        isVoicemailScreeningActive = false
        standaloneEngine.stopSession()
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
     * When digital capture is active, microphone remains strictly muted (`setMuted(true)`).
     * When unprivileged acoustic fallback is active, mic must stay unmuted (`setMuted(false)`).
     */
    fun setScreeningSpeakerEnabled(enabled: Boolean) {
        try {
            val hasDigital = hasDigitalCapturePrivilege

            if (hasDigital) {
                setMuted(true)
                audioManager.isMicrophoneMute = true
            } else {
                setMuted(false)
                audioManager.isMicrophoneMute = false
            }

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
                if (hasDigital) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0)
                } else {
                    // In unprivileged standalone mode: keep ROUTE_EARPIECE at 60% volume so caller audio is captured
                    audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.6f).toInt().coerceAtLeast(2), 0)
                }
            }
            Log.i(TAG, "setScreeningSpeakerEnabled($enabled): hasDigital=$hasDigital, speaker=$enabled")
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
        standaloneEngine.stopSession()
        shizukuAudioController.stopVoicemailSession()

        val wasScreening = isVoicemailScreeningActive || isScreeningSessionRunning.get()
        val wasGreetingPlaying = !isGreetingFinished.get()
        val targetNumber = TelecomCallManager.phoneNumber.value
        val callCreationTime = activeCall?.details?.creationTimeMillis?.takeIf { it > 0 } ?: System.currentTimeMillis()

        isVoicemailScreeningActive = false
        isScreeningSessionRunning.set(false)

        // Restore normal audio state when session ends
        setMuted(false)
        audioManager.isMicrophoneMute = false
        audioManager.isSpeakerphoneOn = false
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)

        TelecomCallManager.setScreeningState(
            if (isPickedUp) TelecomCallManager.CallScreeningState.ACTIVE_CALL
            else TelecomCallManager.CallScreeningState.DISCONNECTED
        )

        appScope.launch(NonCancellable) {
            try {
                if (wasScreening) {
                    if (!isPickedUp) {
                        markCallAsMissedInCallLog(targetNumber, callCreationTime)
                    }
                    val voicemail = transcriber.stopSession(isPickedUp = isPickedUp, saveToRepository = true)
                    if (voicemail != null) {
                        if (wasGreetingPlaying && !isPickedUp) {
                            VoicemailApplication.voicemailRepository.updateTranscript(voicemail.id, "Caller hung up during greeting")
                            postVoicemailCompletedNotification(voicemail.callerName ?: voicemail.phoneNumber, "Caller hung up during greeting")
                        } else if (!isPickedUp && voicemail.transcript.isNotBlank()) {
                            postVoicemailCompletedNotification(voicemail.callerName ?: voicemail.phoneNumber, voicemail.transcript)
                        }
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
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.DISCONNECTED)
        updateInCallNotification()
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
            if (allCalls.isEmpty()) {
                TelecomCallManager.reset()
                activeCall = null
            }
        }, 1500L)
    }

    private fun updateInCallNotification() {
        val name = TelecomCallManager.callerName.value ?: TelecomCallManager.phoneNumber.value.ifBlank { "Phone Call" }
        val simLabel = TelecomCallManager.simDisplayName.value
        val state = TelecomCallManager.screeningState.value

        val (titleText, contentText) = when (state) {
            TelecomCallManager.CallScreeningState.IDLE ->
                "Live Voicemail" to "Ready"
            TelecomCallManager.CallScreeningState.INCOMING_RINGING ->
                "Incoming Call • $simLabel" to "Ringing from $name"
            TelecomCallManager.CallScreeningState.SCREENING_ANSWERING ->
                "Answering Silently..." to "Connecting Live Voicemail..."
            TelecomCallManager.CallScreeningState.SCREENING_GREETING ->
                "Screening Call • $simLabel" to "Playing greeting to $name..."
            TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL ->
                "Live Voicemail • $simLabel" to "Recording voice message from $name"
            TelecomCallManager.CallScreeningState.ACTIVE_CALL ->
                "Call in Progress • $simLabel" to "Talking with $name"
            TelecomCallManager.CallScreeningState.OUTGOING_DIALING ->
                "Outgoing Call • $simLabel" to "Calling $name..."
            TelecomCallManager.CallScreeningState.DISCONNECTED ->
                "Call Ended" to "Disconnected"
        }

        val activityIntent = Intent(this, InCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, VoicemailApplication.CHANNEL_INCALL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPendingIntent)
            .setOngoing(state != TelecomCallManager.CallScreeningState.DISCONNECTED)
            .setAutoCancel(state == TelecomCallManager.CallScreeningState.DISCONNECTED)

        if (state == TelecomCallManager.CallScreeningState.INCOMING_RINGING) {
            builder.setFullScreenIntent(contentPendingIntent, true)

            val answerIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_ANSWER_CALL
            }
            val answerPendingIntent = android.app.PendingIntent.getService(
                this,
                4,
                answerIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.sym_action_call, "Answer", answerPendingIntent)

            val screenIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_TRIGGER_VOICEMAIL
            }
            val screenPendingIntent = android.app.PendingIntent.getService(
                this,
                5,
                screenIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Screen Call", screenPendingIntent)

            val hangUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_HANG_UP
            }
            val hangUpPendingIntent = android.app.PendingIntent.getService(
                this,
                2,
                hangUpIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", hangUpPendingIntent)
        } else if (state == TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL ||
            state == TelecomCallManager.CallScreeningState.SCREENING_GREETING
        ) {
            val pickUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_PICK_UP
            }
            val pickUpPendingIntent = android.app.PendingIntent.getService(
                this,
                1,
                pickUpIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.sym_call_incoming, "Pick Up", pickUpPendingIntent)

            val hangUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_HANG_UP
            }
            val hangUpPendingIntent = android.app.PendingIntent.getService(
                this,
                2,
                hangUpIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", hangUpPendingIntent)
        } else if (state == TelecomCallManager.CallScreeningState.ACTIVE_CALL ||
            state == TelecomCallManager.CallScreeningState.OUTGOING_DIALING
        ) {
            val hangUpIntent = Intent(this, VoicemailInCallService::class.java).apply {
                action = ACTION_HANG_UP
            }
            val hangUpPendingIntent = android.app.PendingIntent.getService(
                this,
                2,
                hangUpIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", hangUpPendingIntent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    INCOMING_NOTIFICATION_ID,
                    builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(INCOMING_NOTIFICATION_ID, builder.build())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error displaying foreground notification: ${t.message}")
        }
    }

    private fun postVoicemailCompletedNotification(caller: String, transcript: String) {
        val intent = Intent(this, com.personal.voicemail.ui.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, VoicemailApplication.CHANNEL_VOICEMAIL_NOTIFICATIONS_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("New Voicemail from $caller")
            .setContentText(transcript.ifBlank { "New voice message received" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(transcript))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "VoicemailApp:InCallProximityWakeLock"
                )
            }
        }
        proximityWakeLock?.let {
            if (!it.isHeld) {
                it.acquire(60 * 60 * 1000L) // 1 hour safety timeout
            }
        }
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        proximityWakeLock = null
    }

    private fun resolveContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveSimDetails(handle: PhoneAccountHandle?): Pair<String, Int> {
        if (handle == null) return "SIM 1" to 0
        return try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subList = sm?.activeSubscriptionInfoList.orEmpty()
            val matchedInfo = subList.firstOrNull { it.iccId == handle.id || it.subscriptionId.toString() == handle.id }
                ?: subList.getOrNull(0)

            val displayName = matchedInfo?.displayName?.toString()
                ?: matchedInfo?.carrierName?.toString()
                ?: "SIM 1"
            val slotIndex = matchedInfo?.simSlotIndex ?: 0
            displayName to slotIndex
        } catch (t: Throwable) {
            "SIM 1" to 0
        }
    }

    private fun markCallAsMissedInCallLog(phoneNumber: String, callStartTimeMs: Long) {
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot mark call as missed in CallLog: WRITE_CALL_LOG permission not granted")
            return
        }

        appScope.launch(Dispatchers.IO) {
            val cleanTarget = phoneNumber.replace(Regex("[^0-9+]"), "")
            var updated = false

            // Retry over 3 seconds to ensure Android Telecom finishes writing the CallLog row first
            for (attempt in 1..4) {
                delay(if (attempt == 1) 350L else 700L)
                try {
                    val projection = arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE
                    )
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC LIMIT 10"
                    )?.use { cursor ->
                        val colId = cursor.getColumnIndex(CallLog.Calls._ID)
                        val colNumber = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                        val colDate = cursor.getColumnIndex(CallLog.Calls.DATE)
                        val colType = cursor.getColumnIndex(CallLog.Calls.TYPE)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(colId)
                            val num = cursor.getString(colNumber).orEmpty()
                            val date = cursor.getLong(colDate)
                            val type = cursor.getInt(colType)

                            val cleanNum = num.replace(Regex("[^0-9+]"), "")
                            val matchesNumber = (cleanTarget.isNotBlank() && (cleanNum.endsWith(cleanTarget) || cleanTarget.endsWith(cleanNum)))
                                    || cleanTarget.isBlank()
                            val isWithinTime = kotlin.math.abs(date - callStartTimeMs) < 120_000L

                            if (matchesNumber && isWithinTime) {
                                if (type != CallLog.Calls.MISSED_TYPE) {
                                    val values = ContentValues().apply {
                                        put(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
                                        put(CallLog.Calls.IS_READ, 0)
                                        put(CallLog.Calls.NEW, 1)
                                    }
                                    val rows = contentResolver.update(
                                        CallLog.Calls.CONTENT_URI,
                                        values,
                                        "${CallLog.Calls._ID} = ?",
                                        arrayOf(id.toString())
                                    )
                                    Log.i(TAG, "Updated CallLog #$id ($num) to MISSED_TYPE (rows affected=$rows)")
                                    updated = true
                                } else {
                                    Log.d(TAG, "CallLog #$id ($num) already recorded as MISSED_TYPE")
                                    updated = true
                                }
                                break
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Error updating CallLog entry to MISSED_TYPE: ${t.message}")
                }

                if (updated) break
            }

            // Fallback: If Telecom did not insert a CallLog entry within the window, insert a missed call record directly
            if (!updated && cleanTarget.isNotBlank()) {
                try {
                    val values = ContentValues().apply {
                        put(CallLog.Calls.NUMBER, phoneNumber)
                        put(CallLog.Calls.DATE, callStartTimeMs)
                        put(CallLog.Calls.DURATION, 0)
                        put(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
                        put(CallLog.Calls.NEW, 1)
                        put(CallLog.Calls.IS_READ, 0)
                    }
                    contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                    Log.i(TAG, "Inserted fallback MISSED_TYPE call log entry for $phoneNumber")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed inserting fallback CallLog entry: ${t.message}")
                }
            }
        }
    }
}
