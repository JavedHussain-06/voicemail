package com.personal.voicemail.service

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observable UI state and call control dispatcher bridging VoicemailInCallService
 * with InCallScreen.kt and Jetpack Compose.
 */
object TelecomCallManager {

    private const val TAG = "TelecomCallManager"

    enum class CallScreeningState {
        IDLE,
        INCOMING_RINGING,
        OUTGOING_DIALING,
        SCREENING_ANSWERING,
        SCREENING_GREETING,
        SCREENING_LIVE_VOICEMAIL,
        ACTIVE_CALL,
        DISCONNECTED
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var durationTimerJob: Job? = null

    var currentInCallService: VoicemailInCallService? = null
    var currentCall: Call? = null
    private val allCalls = mutableListOf<Call>()

    private val _screeningState = MutableStateFlow(CallScreeningState.IDLE)
    val screeningState: StateFlow<CallScreeningState> = _screeningState.asStateFlow()

    private val _callerName = MutableStateFlow<String?>(null)
    val callerName: StateFlow<String?> = _callerName.asStateFlow()

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    private val _simDisplayName = MutableStateFlow("SIM 1")
    val simDisplayName: StateFlow<String> = _simDisplayName.asStateFlow()

    private val _simSlotIndex = MutableStateFlow(0)
    val simSlotIndex: StateFlow<Int> = _simSlotIndex.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0)
    val callDurationSeconds: StateFlow<Int> = _callDurationSeconds.asStateFlow()

    private val _isMicrophoneMuted = MutableStateFlow(false)
    val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted.asStateFlow()

    private val _isOnHold = MutableStateFlow(false)
    val isOnHold: StateFlow<Boolean> = _isOnHold.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isScreeningSpeakerOn = MutableStateFlow(false)
    val isScreeningSpeakerOn: StateFlow<Boolean> = _isScreeningSpeakerOn.asStateFlow()

    private val _currentAudioRoute = MutableStateFlow(CallAudioState.ROUTE_EARPIECE)
    val currentAudioRoute: StateFlow<Int> = _currentAudioRoute.asStateFlow()

    private val _canConference = MutableStateFlow(false)
    val canConference: StateFlow<Boolean> = _canConference.asStateFlow()

    private val _hasMultipleCalls = MutableStateFlow(false)
    val hasMultipleCalls: StateFlow<Boolean> = _hasMultipleCalls.asStateFlow()

    fun updateCall(
        call: Call?,
        name: String?,
        number: String,
        simName: String,
        simSlot: Int,
        state: CallScreeningState
    ) {
        currentCall = call
        _callerName.value = name
        _phoneNumber.value = number
        _simDisplayName.value = simName
        _simSlotIndex.value = simSlot
        setScreeningState(state)
    }

    fun updateCallsList(calls: List<Call>) {
        allCalls.clear()
        allCalls.addAll(calls)
        _hasMultipleCalls.value = calls.size > 1
        _canConference.value = calls.size >= 2
    }

    fun setScreeningState(state: CallScreeningState) {
        val previousState = _screeningState.value
        _screeningState.value = state
        // Default to SILENT screening when entering greeting or live voicemail from ringing/answering
        if ((state == CallScreeningState.SCREENING_ANSWERING ||
                    state == CallScreeningState.SCREENING_GREETING) &&
            previousState != CallScreeningState.SCREENING_GREETING &&
            previousState != CallScreeningState.SCREENING_LIVE_VOICEMAIL
        ) {
            _isScreeningSpeakerOn.value = false
        }
        if (state == CallScreeningState.ACTIVE_CALL) {
            startDurationTimer()
        } else if (state == CallScreeningState.IDLE || state == CallScreeningState.DISCONNECTED) {
            stopDurationTimer()
        }
    }

    fun setScreeningSpeakerStateOnly(enabled: Boolean) {
        _isScreeningSpeakerOn.value = enabled
    }

    fun setIsOnHold(onHold: Boolean) {
        _isOnHold.value = onHold
    }

    fun updateAudioState(audioState: CallAudioState?) {
        if (audioState != null) {
            _isMicrophoneMuted.value = audioState.isMuted
            _currentAudioRoute.value = audioState.route
            _isSpeakerOn.value = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
        }
    }

    fun toggleSpeaker() {
        val currentlySpeaker = _isSpeakerOn.value
        val newRoute = if (currentlySpeaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        setAudioRoute(newRoute)
        _isSpeakerOn.value = !currentlySpeaker
    }

    fun setScreeningSpeaker(enabled: Boolean) {
        _isScreeningSpeakerOn.value = enabled
        currentInCallService?.setScreeningSpeakerEnabled(enabled)
    }

    fun answerCallNormally() {
        currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        setScreeningState(CallScreeningState.ACTIVE_CALL)
    }

    fun sendToLiveVoicemail() {
        currentInCallService?.triggerLiveVoicemailScreening()
    }

    fun pickUpVoicemail() {
        currentInCallService?.takeOverVoicemailCall()
    }

    fun endCall() {
        Log.i(TAG, "User requested end call.")
        val call = currentCall ?: return
        try {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error ending/rejecting call: ${t.message}", t)
            runCatching { call.disconnect() }
        }
    }

    fun toggleMute() {
        val callService = currentInCallService ?: return
        val current = _isMicrophoneMuted.value
        callService.setMuted(!current)
        _isMicrophoneMuted.value = !current
    }

    fun toggleHold() {
        val call = currentCall ?: return
        if (_isOnHold.value || call.state == Call.STATE_HOLDING) {
            call.unhold()
            _isOnHold.value = false
        } else {
            call.hold()
            _isOnHold.value = true
        }
    }

    fun mergeConference() {
        if (allCalls.size >= 2) {
            val primary = allCalls[0]
            val secondary = allCalls[1]
            try {
                primary.conference(secondary)
                Log.i(TAG, "Initiated call conference merge.")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed merging calls into conference", t)
            }
        }
    }

    fun swapCalls() {
        val active = allCalls.firstOrNull { it.state == Call.STATE_ACTIVE }
        val held = allCalls.firstOrNull { it.state == Call.STATE_HOLDING }
        if (active != null && held != null) {
            try {
                active.hold()
                held.unhold()
                currentCall = held
                Log.i(TAG, "Swapped active and held calls.")
            } catch (t: Throwable) {
                Log.e(TAG, "Error swapping calls", t)
            }
        }
    }

    fun setAudioRoute(route: Int) {
        currentInCallService?.setAudioRoute(route)
        _currentAudioRoute.value = route
        _isSpeakerOn.value = (route and CallAudioState.ROUTE_SPEAKER) != 0
    }

    fun playDtmfTone(digit: Char) {
        currentCall?.playDtmfTone(digit)
    }

    fun stopDtmfTone() {
        currentCall?.stopDtmfTone()
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        _callDurationSeconds.value = 0
        durationTimerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                _callDurationSeconds.value += 1
            }
        }
    }

    private fun stopDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = null
    }

    fun reset() {
        currentCall = null
        allCalls.clear()
        stopDurationTimer()
        _screeningState.value = CallScreeningState.IDLE
        _callerName.value = null
        _phoneNumber.value = ""
        _callDurationSeconds.value = 0
        _isMicrophoneMuted.value = false
        _isOnHold.value = false
        _isSpeakerOn.value = false
        _isScreeningSpeakerOn.value = false
        _canConference.value = false
        _hasMultipleCalls.value = false
    }
}
