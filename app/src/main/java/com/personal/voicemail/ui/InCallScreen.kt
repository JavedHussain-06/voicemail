package com.personal.voicemail.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.voicemail.recognition.LiveVoicemailTranscriber
import com.personal.voicemail.service.TelecomCallManager
import com.personal.voicemail.service.TelecomCallManager.CallScreeningState
import com.personal.voicemail.ui.components.GlassmorphicCard
import com.personal.voicemail.ui.components.GlassmorphicCircleButton
import com.personal.voicemail.ui.components.LiveSpeechWaveform
import com.personal.voicemail.ui.components.SimBadge
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.CallEndRed
import com.personal.voicemail.ui.theme.DarkBackground
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.GlassSurface
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary

@Composable
fun InCallScreen(
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val transcriber = remember { LiveVoicemailTranscriber.getInstance(context) }

    val screeningState by TelecomCallManager.screeningState.collectAsState()
    val callerName by TelecomCallManager.callerName.collectAsState()
    val phoneNumber by TelecomCallManager.phoneNumber.collectAsState()
    val simDisplayName by TelecomCallManager.simDisplayName.collectAsState()
    val simSlotIndex by TelecomCallManager.simSlotIndex.collectAsState()
    val callDuration by TelecomCallManager.callDurationSeconds.collectAsState()
    val isMuted by TelecomCallManager.isMicrophoneMuted.collectAsState()
    val isHold by TelecomCallManager.isOnHold.collectAsState()
    val isSpeakerOn by TelecomCallManager.isSpeakerOn.collectAsState()
    val isScreeningSpeakerOn by TelecomCallManager.isScreeningSpeakerOn.collectAsState()
    val canConference by TelecomCallManager.canConference.collectAsState()
    val hasMultipleCalls by TelecomCallManager.hasMultipleCalls.collectAsState()

    val liveTranscript by transcriber.liveTranscript.collectAsState()
    val audioRms by transcriber.audioRmsLevel.collectAsState()

    var showKeypadOverlay by remember { mutableStateOf(false) }
    var showAddCallOverlay by remember { mutableStateOf(false) }

    // If call disconnected, show "Call Ended" briefly before auto-dismiss
    LaunchedEffect(screeningState) {
        if (screeningState == CallScreeningState.DISCONNECTED) {
            delay(1200L)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF16161E),
                        DarkBackground,
                        Color(0xFF0F0F14)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Top Header: SIM badge, Caller details & status pill
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                SimBadge(simName = simDisplayName, simSlotIndex = simSlotIndex)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = callerName ?: phoneNumber.ifEmpty { "Phone Call" },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                if (!callerName.isNullOrBlank() && phoneNumber.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = phoneNumber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Animated Status Pill
                StatusPill(
                    state = screeningState,
                    durationSeconds = callDuration,
                    rmsLevel = audioRms,
                    isHold = isHold
                )
            }

            // Center Graphic / Transcript View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                when (screeningState) {
                    CallScreeningState.SCREENING_ANSWERING,
                    CallScreeningState.SCREENING_GREETING,
                    CallScreeningState.SCREENING_LIVE_VOICEMAIL -> {
                        ScreeningTranscriptCard(
                            transcript = liveTranscript,
                            isGreeting = screeningState == CallScreeningState.SCREENING_GREETING || screeningState == CallScreeningState.SCREENING_ANSWERING,
                            rmsLevel = audioRms,
                            isSpeakerListening = isScreeningSpeakerOn
                        )
                    }
                    CallScreeningState.OUTGOING_DIALING -> {
                        OutgoingCallDialingCard()
                    }
                    CallScreeningState.ACTIVE_CALL -> {
                        ActiveCallWaveformCard(callDuration = callDuration, isHold = isHold)
                    }
                    else -> {
                        IncomingCallRingingCard(rmsLevel = audioRms)
                    }
                }
            }

            // Bottom Controls Section
            when (screeningState) {
                CallScreeningState.INCOMING_RINGING -> {
                    IncomingCallControls(
                        onVoicemail = { TelecomCallManager.sendToLiveVoicemail() },
                        onDecline = { TelecomCallManager.endCall() },
                        onAnswer = { TelecomCallManager.answerCallNormally() }
                    )
                }

                CallScreeningState.OUTGOING_DIALING -> {
                    OutgoingCallControls(
                        isMuted = isMuted,
                        isSpeakerOn = isSpeakerOn,
                        onToggleMute = { TelecomCallManager.toggleMute() },
                        onToggleKeypad = { showKeypadOverlay = !showKeypadOverlay },
                        onToggleSpeaker = { TelecomCallManager.toggleSpeaker() },
                        onEndCall = { TelecomCallManager.endCall() }
                    )
                }

                CallScreeningState.SCREENING_ANSWERING,
                CallScreeningState.SCREENING_GREETING,
                CallScreeningState.SCREENING_LIVE_VOICEMAIL -> {
                    ScreeningCallControls(
                        isSpeakerOn = isScreeningSpeakerOn,
                        onToggleSpeaker = {
                            TelecomCallManager.setScreeningSpeaker(!isScreeningSpeakerOn)
                        },
                        onPickUp = { TelecomCallManager.pickUpVoicemail() },
                        onEndCall = { TelecomCallManager.endCall() }
                    )
                }

                CallScreeningState.ACTIVE_CALL -> {
                    ActiveCallControls(
                        isMuted = isMuted,
                        isHold = isHold,
                        isSpeakerOn = isSpeakerOn,
                        canConference = canConference,
                        hasMultipleCalls = hasMultipleCalls,
                        onToggleMute = { TelecomCallManager.toggleMute() },
                        onToggleKeypad = { showKeypadOverlay = !showKeypadOverlay },
                        onToggleSpeaker = { TelecomCallManager.toggleSpeaker() },
                        onToggleHold = { TelecomCallManager.toggleHold() },
                        onAddCall = { showAddCallOverlay = true },
                        onMergeCalls = { TelecomCallManager.mergeConference() },
                        onSwapCalls = { TelecomCallManager.swapCalls() },
                        onSendToVoicemail = { TelecomCallManager.sendToLiveVoicemail() },
                        onEndCall = { TelecomCallManager.endCall() }
                    )
                }

                else -> {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }

        // Interactive In-Call DTMF Keypad Overlay
        AnimatedVisibility(
            visible = showKeypadOverlay,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            DtmfKeypadOverlay(
                onClose = { showKeypadOverlay = false },
                onKeyPress = { digit -> TelecomCallManager.playDtmfTone(digit) },
                onKeyRelease = { TelecomCallManager.stopDtmfTone() }
            )
        }

        // Add Call Overlay for placing a second call / conference
        AnimatedVisibility(
            visible = showAddCallOverlay,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            AddCallOverlay(
                onClose = { showAddCallOverlay = false },
                onPlaceCall = { number ->
                    showAddCallOverlay = false
                    TelecomCallManager.currentInCallService?.placeSecondCall(number)
                }
            )
        }
    }
}

@Composable
private fun StatusPill(
    state: CallScreeningState,
    durationSeconds: Int,
    rmsLevel: Float,
    isHold: Boolean = false
) {
    val pillBg = when (state) {
        CallScreeningState.SCREENING_ANSWERING -> Color(0x33FF9500)
        CallScreeningState.SCREENING_GREETING -> Color(0x33FF9500)
        CallScreeningState.SCREENING_LIVE_VOICEMAIL -> Color(0x3334C759)
        CallScreeningState.ACTIVE_CALL -> if (isHold) Color(0x33FF9500) else Color(0x33007AFF)
        CallScreeningState.OUTGOING_DIALING -> Color(0x33007AFF)
        else -> GlassSurface
    }

    val pillText = when (state) {
        CallScreeningState.INCOMING_RINGING -> "Incoming Call…"
        CallScreeningState.OUTGOING_DIALING -> "Calling…"
        CallScreeningState.SCREENING_ANSWERING -> "Answering for Voicemail…"
        CallScreeningState.SCREENING_GREETING -> "Greeting Caller…"
        CallScreeningState.SCREENING_LIVE_VOICEMAIL -> "Live Voicemail"
        CallScreeningState.ACTIVE_CALL -> if (isHold) "On Hold" else formatCallDuration(durationSeconds)
        CallScreeningState.DISCONNECTED -> "Call Ended"
        CallScreeningState.IDLE -> ""
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(pillBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state == CallScreeningState.SCREENING_LIVE_VOICEMAIL) {
            LiveSpeechWaveform(
                rmsLevel = rmsLevel,
                isSpeaking = true,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Text(
            text = pillText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}

@Composable
private fun OutgoingCallDialingCard() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color(0x33007AFF))
                .border(2.dp, Color(0xFF007AFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                tint = Color(0xFF007AFF),
                modifier = Modifier.size(46.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Calling recipient…",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
    }
}

@Composable
private fun OutgoingCallControls(
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleKeypad: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GlassmorphicCircleButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = "Mute",
                isSelected = isMuted,
                onClick = onToggleMute
            )

            GlassmorphicCircleButton(
                icon = Icons.Default.Dialpad,
                label = "Keypad",
                onClick = onToggleKeypad
            )

            GlassmorphicCircleButton(
                icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                label = if (isSpeakerOn) "Speaker ON" else "Speaker",
                isSelected = isSpeakerOn,
                onClick = onToggleSpeaker
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Big Red End Call Button
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(CallEndRed)
                .clickable { onEndCall() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End Call",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun ScreeningTranscriptCard(
    transcript: String,
    isGreeting: Boolean,
    rmsLevel: Float,
    isSpeakerListening: Boolean
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom as new speech words arrive
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(1)
        }
    }

    GlassmorphicCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp),
        cornerRadius = 28.dp,
        backgroundColor = Color(0x24FFFFFF)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Live Screening Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(CallAcceptGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isGreeting) "Playing Greeting to Caller" else "Caller Recording Message",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }

                if (isSpeakerListening) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33007AFF))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color(0xFF007AFF),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Listening",
                            fontSize = 10.sp,
                            color = Color(0xFF007AFF),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Transcript Scroll View
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                item {
                    if (transcript.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isGreeting) "Greeting caller through cellular uplink…" else "Waiting for caller to speak…",
                                color = TextSecondary,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = transcript,
                            color = TextPrimary,
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Footer note
            Text(
                text = "Live Voicemail recording caller. Tap Pick Up to answer.",
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ActiveCallWaveformCard(
    callDuration: Int,
    isHold: Boolean
) {
    GlassmorphicCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        cornerRadius = 28.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isHold) Icons.Default.Pause else Icons.Default.Call,
                contentDescription = null,
                tint = if (isHold) Color(0xFFFF9500) else CallAcceptGreen,
                modifier = Modifier.size(54.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isHold) "Call on Hold" else "Call in Progress",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = formatCallDuration(callDuration),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun IncomingCallRingingCard(rmsLevel: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(GlassSurface)
                .border(2.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(42.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Live Voicemail starts in 8s…",
            fontSize = 14.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun IncomingCallControls(
    onVoicemail: () -> Unit,
    onDecline: () -> Unit,
    onAnswer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Decline Call
        GlassmorphicCircleButton(
            icon = Icons.Default.CallEnd,
            label = "Decline",
            size = 68.dp,
            containerColor = CallEndRed,
            iconTint = Color.White,
            onClick = onDecline
        )

        // Voicemail - Purple/Blue button
        GlassmorphicCircleButton(
            icon = Icons.Default.Voicemail,
            label = "Voicemail",
            size = 68.dp,
            containerColor = Color(0xFF5E5CE6),
            iconTint = Color.White,
            onClick = onVoicemail
        )

        // Answer Call - Green button
        GlassmorphicCircleButton(
            icon = Icons.Default.Call,
            label = "Answer Call",
            size = 68.dp,
            containerColor = CallAcceptGreen,
            iconTint = Color.White,
            onClick = onAnswer
        )
    }
}

@Composable
private fun ScreeningCallControls(
    isSpeakerOn: Boolean,
    onToggleSpeaker: () -> Unit,
    onPickUp: () -> Unit,
    onEndCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speaker toggle (listen live in room)
        GlassmorphicCircleButton(
            icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
            label = if (isSpeakerOn) "Speaker ON" else "Listen Live",
            size = 64.dp,
            isSelected = isSpeakerOn,
            containerColor = GlassSurface,
            iconTint = Color.White,
            onClick = onToggleSpeaker
        )

        // Big Pick Up Takeover Button
        Box(
            modifier = Modifier
                .height(64.dp)
                .width(140.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(CallAcceptGreen)
                .clickable { onPickUp() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pick Up",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // End Call
        GlassmorphicCircleButton(
            icon = Icons.Default.CallEnd,
            label = "End",
            size = 64.dp,
            containerColor = CallEndRed,
            iconTint = Color.White,
            onClick = onEndCall
        )
    }
}

@Composable
private fun ActiveCallControls(
    isMuted: Boolean,
    isHold: Boolean,
    isSpeakerOn: Boolean,
    canConference: Boolean,
    hasMultipleCalls: Boolean,
    onToggleMute: () -> Unit,
    onToggleKeypad: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleHold: () -> Unit,
    onAddCall: () -> Unit,
    onMergeCalls: () -> Unit,
    onSwapCalls: () -> Unit,
    onSendToVoicemail: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1: Mute, Keypad, Speaker (Audio)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GlassmorphicCircleButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = "Mute",
                isSelected = isMuted,
                onClick = onToggleMute
            )

            GlassmorphicCircleButton(
                icon = Icons.Default.Dialpad,
                label = "Keypad",
                onClick = onToggleKeypad
            )

            GlassmorphicCircleButton(
                icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                label = if (isSpeakerOn) "Speaker ON" else "Speaker",
                isSelected = isSpeakerOn,
                onClick = onToggleSpeaker
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Row 2: Add Call / Merge, Hold, Screen Call (Voicemail)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (canConference) {
                GlassmorphicCircleButton(
                    icon = Icons.Default.CallMerge,
                    label = "Merge",
                    containerColor = Color(0xFF34C759),
                    iconTint = Color.White,
                    onClick = onMergeCalls
                )
            } else {
                GlassmorphicCircleButton(
                    icon = Icons.Default.PersonAdd,
                    label = "Add Call",
                    onClick = onAddCall
                )
            }

            GlassmorphicCircleButton(
                icon = if (isHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                label = if (isHold) "Unhold" else "Hold",
                isSelected = isHold,
                onClick = onToggleHold
            )

            GlassmorphicCircleButton(
                icon = Icons.Default.Voicemail,
                label = "Voicemail",
                containerColor = Color(0xFF5E5CE6),
                iconTint = Color.White,
                onClick = onSendToVoicemail
            )
        }

        if (hasMultipleCalls) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { onSwapCalls() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SwapCalls,
                    contentDescription = "Swap Calls",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Swap Calls",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Big Red End Call Button
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(CallEndRed)
                .clickable { onEndCall() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End Call",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun AddCallOverlay(
    onClose: () -> Unit,
    onPlaceCall: (String) -> Unit
) {
    var inputNumber by remember { mutableStateOf("") }
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0A0C))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add Call (Conference)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF007AFF),
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(8.dp)
                )
            }

            // Display dialed number
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = inputNumber.ifEmpty { "Enter Number" },
                    fontSize = if (inputNumber.isEmpty()) 20.sp else 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (inputNumber.isEmpty()) TextSecondary else TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                if (inputNumber.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Backspace,
                        contentDescription = "Backspace",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                inputNumber = inputNumber.dropLast(1)
                            }
                    )
                }
            }

            // Dialpad grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (row in keys) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for ((digit, sub) in row) {
                            DtmfKeyButton(
                                digit = digit,
                                subtext = sub,
                                onPress = {
                                    inputNumber += digit
                                },
                                onRelease = {}
                            )
                        }
                    }
                }
            }

            // Green Call Button
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(if (inputNumber.isNotBlank()) CallAcceptGreen else Color.DarkGray)
                    .clickable(enabled = inputNumber.isNotBlank()) {
                        onPlaceCall(inputNumber)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun DtmfKeypadOverlay(
    onClose: () -> Unit,
    onKeyPress: (Char) -> Unit,
    onKeyRelease: () -> Unit
) {
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0A0C))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Hide",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF007AFF),
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(8.dp)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (row in keys) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for ((digit, sub) in row) {
                            DtmfKeyButton(
                                digit = digit,
                                subtext = sub,
                                onPress = { onKeyPress(digit[0]) },
                                onRelease = onKeyRelease
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DtmfKeyButton(
    digit: String,
    subtext: String,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable {
                scope.launch {
                    onPress()
                    delay(150L)
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = digit,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (subtext.isNotEmpty()) {
                Text(
                    text = subtext,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private fun formatCallDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
