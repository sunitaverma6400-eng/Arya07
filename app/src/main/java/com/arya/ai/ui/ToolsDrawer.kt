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

private data class ToolCategory(
    val emoji: String,
    val label: String,
    val warn: Boolean = false,
    /** Real example prompt for a tool actually registered in
     *  [com.arya.ai.tools.AryaToolRegistry] under this group — tapping the row fills this
     *  into the chat input so it's obvious the category does something, not just a static
     *  label. Null for the "Self-Evolution" row, which stays informational-only (advanced/risky). */
    val examplePrompt: String? = null
)

// Mirrors arya-ui.html's `.drawer-list` — one entry per group of tools in AryaToolRegistry.
private val TOOL_CATEGORIES = listOf(
    ToolCategory("🖼️", "Images & Media", examplePrompt = "/image ek sunset banao"),
    ToolCategory("🌐", "Web & News", examplePrompt = "Aaj ki taaza khabar batao"),
    ToolCategory("ℹ️", "Info Tools", examplePrompt = "Japan ke baare me batao"),
    ToolCategory("📻", "Radio", examplePrompt = "Ye stream URL 'morning radio' naam se save karo: "),
    ToolCategory("🧠", "Memory", examplePrompt = "Yaad rakhna ki mera favourite color blue hai"),
    ToolCategory("🎬", "Play & Fetch Media", examplePrompt = "Ye video/audio chalao: "),
    ToolCategory("🌍", "Places & Time", examplePrompt = "Abhi mera location kya hai"),
    ToolCategory("📌", "Saved Sites & Watchers", examplePrompt = "Is website ko 'college portal' naam se save karo: "),
    ToolCategory("🧮", "Utility", examplePrompt = "10 baje ka alarm lagao"),
    ToolCategory("🛰️", "NASA Extras", examplePrompt = "Aaj ki NASA space photo dikhao"),
    ToolCategory("✅", "Todo Extras", examplePrompt = "Mujhe paani peene ka reminder lagao 30 minute me"),
    ToolCategory("📡", "Streaming Controls", examplePrompt = "Saved stream 'morning radio' play karo"),
    ToolCategory("🎭", "Persona / Roleplay", examplePrompt = "Apna tone thoda mazakiya kar do"),
    ToolCategory("⚠️", "Self-Evolution (Advanced)", warn = true, examplePrompt = null)
)

/**
 * Right-side sliding drawer listing Arya's tool categories — translated from arya-ui.html's
 * `.drawer`/`.scrim`. Opened from [ChipRow]'s "Tools" chip. Tapping a category fills a real
 * example prompt for that group into the chat input via [onExamplePrompt] (see
 * [ToolCategory.examplePrompt]) and closes the drawer — these aren't separate screens, since
 * the actual tools are invoked by Arya during conversation, not by direct navigation.
 */
@Composable
fun ToolsDrawer(visible: Boolean, onClose: () -> Unit, onExamplePrompt: (String) -> Unit = {}) {
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
                            .clickable(onClick = {
                            cat.examplePrompt?.let(onExamplePrompt)
                            onClose()
                        })
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
