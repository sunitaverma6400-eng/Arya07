package com.arya.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arya.ai.ui.theme.AryaEmber
import com.arya.ai.ui.theme.AryaHairline
import com.arya.ai.ui.theme.AryaInk
import com.arya.ai.ui.theme.AryaInkSurface
import com.arya.ai.ui.theme.AryaSignal
import com.arya.ai.ui.theme.AryaTextFaint

private data class ToolCategory(val emoji: String, val label: String, val warn: Boolean = false)

// Mirrors arya-ui.html's `.drawer-list` — one entry per group of tools in AryaToolRegistry.
private val TOOL_CATEGORIES = listOf(
    ToolCategory("🖼️", "Images & Media"),
    ToolCategory("🌐", "Web & News"),
    ToolCategory("ℹ️", "Info Tools"),
    ToolCategory("📻", "Radio"),
    ToolCategory("🧠", "Memory"),
    ToolCategory("🎬", "Play & Fetch Media"),
    ToolCategory("🌍", "Places & Time"),
    ToolCategory("📌", "Saved Sites & Watchers"),
    ToolCategory("🧮", "Utility"),
    ToolCategory("🛰️", "NASA Extras"),
    ToolCategory("✅", "Todo Extras"),
    ToolCategory("📡", "Streaming Controls"),
    ToolCategory("🎭", "Persona / Roleplay"),
    ToolCategory("⚠️", "Self-Evolution (Advanced)", warn = true)
)

/**
 * Right-side sliding drawer listing Arya's tool categories — translated from arya-ui.html's
 * `.drawer`/`.scrim`. Opened from [ChipRow]'s "Tools" chip. Categories are informational
 * groupings of [com.arya.ai.tools.AryaToolRegistry]'s tools; tapping one just closes the
 * drawer back to chat for now (same as the mockup — no per-category destination screen exists
 * yet).
 */
@Composable
fun ToolsDrawer(visible: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050408).copy(alpha = 0.6f))
                .clickable(onClick = onClose)
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.88f)
                .background(AryaInk)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ArrowBackIosNew,
                    contentDescription = "Band karo",
                    tint = AryaSignal,
                    modifier = Modifier.clickable(onClick = onClose).padding(end = 12.dp)
                )
                Column {
                    Text(
                        "\uD83D\uDEE0\uFE0F Tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1EEFA)
                    )
                    Text(
                        "Arya ke saare tools ek jagah",
                        style = MaterialTheme.typography.bodySmall,
                        color = AryaTextFaint
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AryaHairline))

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                items(TOOL_CATEGORIES) { cat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 9.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AryaInkSurface)
                            .clickable(onClick = onClose)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(cat.emoji, modifier = Modifier.width(24.dp))
                        Text(
                            cat.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (cat.warn) AryaEmber else Color(0xFFF1EEFA),
                            modifier = Modifier.weight(1f).padding(start = 13.dp)
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = AryaTextFaint
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(70.dp)) }
            }
        }
    }
}
