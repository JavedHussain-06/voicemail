package com.personal.voicemail.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.GlassSurface
import com.personal.voicemail.ui.theme.Sim1Color
import com.personal.voicemail.ui.theme.Sim2Color
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    backgroundColor: Color = GlassSurface,
    borderColor: Color = GlassBorder,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
    ) {
        content()
    }
}

@Composable
fun GlassmorphicCircleButton(
    icon: ImageVector,
    label: String? = null,
    size: Dp = 64.dp,
    containerColor: Color = GlassSurface,
    iconTint: Color = TextPrimary,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    val effectiveColor = if (isSelected) TextPrimary else containerColor
    val effectiveIconTint = if (isSelected) Color.Black else iconTint

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(effectiveColor)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = effectiveIconTint,
                modifier = Modifier.size(size * 0.45f)
            )
        }

        if (label != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun SimBadge(
    simName: String,
    simSlotIndex: Int,
    modifier: Modifier = Modifier
) {
    val badgeColor = if (simSlotIndex == 0) Sim1Color else Sim2Color
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeColor.copy(alpha = 0.2f))
            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = simName,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun LiveSpeechWaveform(
    rmsLevel: Float,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 5
    val animatables = remember { List(barCount) { Animatable(0.2f) } }

    LaunchedEffect(rmsLevel, isSpeaking) {
        animatables.forEachIndexed { index, animatable ->
            val target = if (isSpeaking) {
                val variance = (index % 3 + 1) * 0.25f
                (rmsLevel * 2.5f * variance).coerceIn(0.2f, 1.0f)
            } else {
                0.15f
            }
            animatable.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 100, easing = LinearEasing)
            )
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animatables.forEach { anim ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((28.dp * anim.value).coerceAtLeast(4.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF34C759),
                                Color(0xFF30D158)
                            )
                        )
                    )
            )
        }
    }
}
