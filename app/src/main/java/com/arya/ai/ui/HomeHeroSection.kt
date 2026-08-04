package com.arya.ai.ui

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arya.ai.ui.theme.AryaEmber
import com.arya.ai.ui.theme.AryaHairline
import com.arya.ai.ui.theme.AryaInkSurface
import com.arya.ai.ui.theme.AryaSignal
import com.arya.ai.ui.theme.AryaSprout
import com.arya.ai.ui.theme.AryaTextFaint

/**
 * "Hey Arya, sun rahi hai" empty-state — shown in [ChatScreen] only when there are no
 * messages yet, translated from arya-ui.html's `.hero` + `.suggestions` sections. [userName]
 * comes from [com.arya.ai.util.PreferencesManager.userName] (set once via [NameEntryScreen]);
 * falls back to a name-less greeting if it's somehow still blank.
 */
@Composable
fun HomeHeroSection(
    userName: String,
    onSuggestionLive: () -> Unit,
    onSuggestionImage: () -> Unit,
    onSuggestionTodo: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SignalMark()
        val greeting = if (userName.isBlank()) "Bolo" else "$userName, बोलो"
        Text(
            text = greeting,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF1EEFA),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Arya sun rahi hai",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFB6AFC7),
            textAlign = TextAlign.Center
        )
        Text(
            text = "Kuch bhi poochho, ya \"Hey Arya\" bolke jagao",
            style = MaterialTheme.typography.bodySmall,
            color = AryaTextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
        )

        SuggestionRow(
            icon = Icons.Filled.GraphicEq,
            swatch = AryaSignal.copy(alpha = 0.15f),
            iconTint = AryaSignal,
            title = "Live baat karo",
            subtitle = "Camera dikhao, seedha baat karo",
            onClick = onSuggestionLive
        )
        SuggestionRow(
            icon = Icons.Filled.Image,
            swatch = AryaEmber.copy(alpha = 0.15f),
            iconTint = AryaEmber,
            title = "Image banao",
            subtitle = "\"/image\" likho ya seedha poocho",
            onClick = onSuggestionImage
        )
        SuggestionRow(
            icon = Icons.Filled.CheckCircle,
            swatch = AryaSprout.copy(alpha = 0.15f),
            iconTint = AryaSprout,
            title = "Aaj ke kaam",
            subtitle = "To-do list dekho ya reminder lagao",
            onClick = onSuggestionTodo,
            showDivider = false
        )
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    swatch: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(swatch),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(17.dp))
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF1EEFA))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AryaTextFaint)
            }
        }
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AryaHairline))
        }
    }
}

/**
 * Signature mark — three concentric rings pulsing outward from a solid violet core, matching
 * arya-ui.html's `.signal-mark` (rings staggered ~0.9s apart within a 2.8s cycle). Performs
 * Arya's own identity: an always-listening wake-word assistant.
 */
@Composable
private fun SignalMark() {
    val transition = rememberInfiniteTransition(label = "signal-mark")
    val ring1 = ringProgress(transition, delayMs = 0)
    val ring2 = ringProgress(transition, delayMs = 900)
    val ring3 = ringProgress(transition, delayMs = 1800)

    Box(modifier = Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        listOf(ring1, ring2, ring3).forEach { progress ->
            val size = 22.dp + (66.dp * progress)
            val alpha = (0.9f * (1f - progress)).coerceIn(0f, 0.9f)
            Box(
                modifier = Modifier
                    .size(size)
                    .border(1.5.dp, AryaSignal.copy(alpha = alpha), CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFC9B8FF), AryaSignal),
                    )
                )
        )
    }
}

@Composable
private fun ringProgress(transition: InfiniteTransition, delayMs: Int): Float {
    val anim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMs, StartOffsetType.Delay)
        ),
        label = "ring"
    )
    return anim
}

/**
 * Attachment chip row above the input dock — Photo / Camera / Live — matching arya-ui.html's
 * `.chiprow` minus the "Tools" entry (moved to the top bar — see [ChatScreen] — since it's a
 * reference menu, not a per-message attachment like the other three).
 */
@Composable
fun ChipRow(
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onLive: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Chip(icon = Icons.Filled.Photo, label = "Photo", onClick = onPhoto)
        Chip(icon = Icons.Filled.CameraAlt, label = "Camera", onClick = onCamera)
        Chip(icon = Icons.Filled.Schedule, label = "Live", onClick = onLive)
    }
}

@Composable
private fun Chip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) AryaSignal.copy(alpha = 0.2f) else AryaInkSurface)
            .border(
                width = 1.dp,
                color = if (active) AryaSignal else AryaHairline,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color(0xFFF1EEFA) else AryaTextFaint,
            modifier = Modifier.size(15.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (active) Color(0xFFF1EEFA) else AryaTextFaint,
            modifier = Modifier.padding(start = 7.dp)
        )
    }
}
