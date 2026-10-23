package com.personal.voicemail.ui.screens

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.data.VoicemailEntity
import com.personal.voicemail.data.VoicemailRepository
import com.personal.voicemail.ui.components.GlassmorphicCard
import com.personal.voicemail.ui.components.SimBadge
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.CallEndRed
import com.personal.voicemail.ui.theme.DarkBackground
import com.personal.voicemail.ui.theme.DarkSurfaceElevated
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.GlassSurface
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun VoicemailInboxTab(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { com.personal.voicemail.VoicemailApplication.voicemailRepository }
    val voicemails by repository.voicemails.collectAsState()

    var activePlayingId by remember { mutableStateOf<Long?>(null) }
    var showGreetingDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(DarkBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // iOS Top Header Bar with "Greeting" button on the left and space for top-right menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Greeting",
                    fontSize = 17.sp,
                    color = AccentBlue,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.clickable { showGreetingDialog = true }
                )
                Spacer(modifier = Modifier.width(1.dp))
            }

            Text(
                text = "Voicemail",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (voicemails.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1C1C1E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Voicemail,
                                contentDescription = null,
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Voicemail",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Calls answered by Live Voicemail will appear here.",
                            fontSize = 13.sp,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(horizontal = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(voicemails, key = { it.id }) { item ->
                        IOSVoicemailRow(
                            voicemail = item,
                            isPlaying = activePlayingId == item.id,
                            onPlayToggle = {
                                activePlayingId = if (activePlayingId == item.id) null else item.id
                                if (!item.isRead) {
                                    scope.launch { repository.markAsRead(item.id) }
                                }
                            },
                            onDelete = {
                                scope.launch { repository.deleteVoicemail(item) }
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = Color(0x38545458)
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        // Custom Greeting Recording Dialog
        if (showGreetingDialog) {
            CustomGreetingDialog(
                onDismiss = { showGreetingDialog = false }
            )
        }
    }
}

@Composable
private fun IOSVoicemailRow(
    voicemail: VoicemailEntity,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Main Summary Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!voicemail.isRead) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentBlue)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column {
                    Text(
                        text = voicemail.callerName ?: voicemail.phoneNumber,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!voicemail.callerName.isNullOrBlank()) {
                        Text(
                            text = voicemail.phoneNumber,
                            fontSize = 13.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (voicemail.simSlotIndex >= 0) {
                    SimBadge(
                        simName = voicemail.simDisplayName,
                        simSlotIndex = voicemail.simSlotIndex
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = formatDate(voicemail.timestamp),
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E93)
                )
            }
        }

        // Expanded Section: Transcript and Audio Player
        if (isExpanded) {
            Spacer(modifier = Modifier.height(10.dp))

            // Transcript Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(12.dp)
            ) {
                Text(
                    text = voicemail.transcript.ifBlank { "[No speech transcript recorded]" },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (voicemail.transcript.isBlank()) Color(0xFF8E8E93) else Color(0xFFE5E5EA)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            VoicemailAudioPlayerRow(
                audioPath = voicemail.audioFilePath,
                durationSeconds = voicemail.durationSeconds,
                isPlaying = isPlaying,
                onPlayToggle = onPlayToggle,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun VoicemailAudioPlayerRow(
    audioPath: String,
    durationSeconds: Int,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(durationSeconds * 1000) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(audioPath, isPlaying) {
        if (isPlaying) {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(audioPath)
                    setOnErrorListener { _, what, extra ->
                        Log.e("VoicemailPlayer", "MediaPlayer playback error: what=$what extra=$extra")
                        onPlayToggle()
                        true
                    }
                    prepare()
                    totalDurationMs = duration
                    start()
                    setOnCompletionListener {
                        onPlayToggle()
                    }
                }
                mediaPlayer = mp

                val progressRunnable = object : Runnable {
                    override fun run() {
                        val active = mediaPlayer ?: return
                        val playing = runCatching { active.isPlaying }.getOrDefault(false)
                        if (playing && !isDraggingSlider) {
                            runCatching { currentPositionMs = active.currentPosition }
                            mainHandler.postDelayed(this, 100)
                        }
                    }
                }
                mainHandler.post(progressRunnable)
            } catch (e: Exception) {
                Log.e("VoicemailPlayer", "Error initializing MediaPlayer for $audioPath", e)
                onPlayToggle()
            }
        } else {
            mainHandler.removeCallbacksAndMessages(null)
            mediaPlayer?.let {
                runCatching {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            }
            mediaPlayer = null
            currentPositionMs = 0
        }

        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            mediaPlayer?.let {
                runCatching {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            }
            mediaPlayer = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause circular button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isPlaying) AccentBlue else GlassSurface)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { onPlayToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Seekbar slider
        Column(modifier = Modifier.weight(1f)) {
            val progress = if (totalDurationMs > 0) {
                (currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
            } else 0f

            Slider(
                value = progress,
                onValueChange = { newFrac ->
                    isDraggingSlider = true
                    currentPositionMs = (newFrac * totalDurationMs).toInt()
                },
                onValueChangeFinished = {
                    mediaPlayer?.seekTo(currentPositionMs)
                    isDraggingSlider = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AccentBlue,
                    inactiveTrackColor = Color(0x33FFFFFF)
                ),
                modifier = Modifier.height(20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentPositionMs / 1000),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                Text(
                    text = formatDuration(totalDurationMs / 1000),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Delete button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete Voicemail",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun CustomGreetingDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isPlayingGreeting by remember { mutableStateOf(false) }
    var isRegenerating by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var fileRefreshTrigger by remember { mutableIntStateOf(0) }

    val greetingFile = remember(fileRefreshTrigger) {
        GreetingGenerator.getActiveGreetingFile(context)
    }

    val fileSizeKb = (greetingFile.length() / 1024L)
    val durationSec = maxOf(1, (greetingFile.length() / (16000L * 2L)).toInt())

    DisposableEffect(Unit) {
        onDispose {
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
        }
    }

    AlertDialog(
        onDismissRequest = {
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Voicemail Voice Greeting",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "This spoken message is injected into the cellular uplink when Live Voicemail screens an incoming call.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Greeting File Status Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "File: ${greetingFile.name}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${fileSizeKb} KB • ${durationSec}s",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlue
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "\"${GreetingGenerator.GREETING_SPOKEN_TEXT}\"",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 1. Play Greeting Out Loud Button
                Button(
                    onClick = {
                        if (isPlayingGreeting) {
                            activePlayer?.stop()
                            activePlayer?.release()
                            activePlayer = null
                            isPlayingGreeting = false
                        } else {
                            try {
                                val mp = MediaPlayer().apply {
                                    setDataSource(greetingFile.absolutePath)
                                    prepare()
                                    start()
                                    setOnCompletionListener {
                                        isPlayingGreeting = false
                                        it.release()
                                        activePlayer = null
                                    }
                                }
                                activePlayer = mp
                                isPlayingGreeting = true
                            } catch (e: Exception) {
                                Log.e("GreetingDialog", "Error playing greeting audio", e)
                                isPlayingGreeting = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayingGreeting) CallEndRed else AccentBlue
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isPlayingGreeting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPlayingGreeting) "Stop Playback" else "Play Greeting Out Loud",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Regenerate Voice Greeting Button (TTS)
                Button(
                    onClick = {
                        activePlayer?.stop()
                        activePlayer?.release()
                        activePlayer = null
                        isPlayingGreeting = false
                        isRegenerating = true
                        GreetingGenerator.generateVoiceGreetingTts(context) { success ->
                            isRegenerating = false
                            fileRefreshTrigger++
                        }
                    },
                    enabled = !isRegenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = GlassSurface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRegenerating) "Synthesizing Voice…" else "Regenerate Voice Greeting (TTS)",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    activePlayer?.stop()
                    activePlayer?.release()
                    activePlayer = null
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GlassSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Close", color = Color.White)
            }
        },
        containerColor = Color(0xFF1C1C22),
        shape = RoundedCornerShape(24.dp)
    )
}

private fun formatDate(epochMs: Long): String {
    val date = Date(epochMs)
    val now = Date()
    val cal1 = java.util.Calendar.getInstance().apply { time = date }
    val cal2 = java.util.Calendar.getInstance().apply { time = now }

    return if (cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
        cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
