package com.arya.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
import com.arya.ai.ui.theme.AryaSignal
import com.arya.ai.ui.theme.AryaSky
import com.arya.ai.ui.theme.AryaSprout
import com.arya.ai.ui.theme.AryaTextFaint

/**
 * "+" attach menu — replaces the plain gallery-image icon that used to sit next to the mic
 * in the input dock. Only lists things Arya can genuinely do from here (same rule as
 * AryaToolRegistry — no decorative entries that don't lead anywhere): attach a photo, jump
 * into Live conversation, kick off AI image generation, or pull up today's to-dos.
 *
 * Visually patterned after the Gemini app's "+" sheet (colored icon tile + title + subtitle
 * per row) but every tile here is wired to a real Arya action instead of a static icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachMenuSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onImageGen: () -> Unit,
    onVideoGen: () -> Unit,
    onMusicGen: () -> Unit,
    onCanvas: () -> Unit,
    onNotebook: () -> Unit,
    onFiles: () -> Unit,
    onPersonalIntelligence: () -> Unit,
    onTodo: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.arya.ai.ui.theme.AryaInk
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Kya karna hai?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF1EEFA),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            AttachMenuRow(
                icon = Icons.Filled.Photo,
                accent = AryaSky,
                title = "Photo",
                subtitle = "Gallery se image chuno aur bhejo",
                onClick = onPhoto
            )
            AttachMenuRow(
                icon = Icons.Filled.CameraAlt,
                accent = AryaSignal,
                title = "Live baat karo",
                subtitle = "Camera kholo, seedha baat karo",
                onClick = onCamera
            )
            AttachMenuRow(
                icon = Icons.Filled.Image,
                accent = AryaEmber,
                title = "Image banao",
                subtitle = "AI se ek naya image generate karo",
                onClick = onImageGen
            )
            AttachMenuRow(
                icon = Icons.Filled.Videocam,
                accent = AryaSignal,
                title = "Video banao",
                subtitle = "AI se ek naya video generate karo",
                onClick = onVideoGen
            )
            AttachMenuRow(
                icon = Icons.Filled.MusicNote,
                accent = AryaSky,
                title = "Music banao",
                subtitle = "AI se ek naya track generate karo",
                onClick = onMusicGen
            )
            AttachMenuRow(
                icon = Icons.Filled.Widgets,
                accent = AryaSprout,
                title = "Canvas",
                subtitle = "Draw/diagram/document banane ka open space",
                onClick = onCanvas
            )
            AttachMenuRow(
                icon = Icons.Filled.MenuBook,
                accent = AryaEmber,
                title = "Notebook",
                subtitle = "Notes likho, purane notes dekho",
                onClick = onNotebook
            )
            AttachMenuRow(
                icon = Icons.Filled.Folder,
                accent = AryaTextFaint,
                title = "Files",
                subtitle = "Koi bhi file attach karo",
                onClick = onFiles
            )
            AttachMenuRow(
                icon = Icons.Filled.Psychology,
                accent = AryaSignal,
                title = "Personal Intelligence",
                subtitle = "Tumhare baare mein Arya ne kya seekha hai",
                onClick = onPersonalIntelligence
            )
            AttachMenuRow(
                icon = Icons.Filled.CheckCircle,
                accent = AryaSprout,
                title = "Aaj ke kaam",
                subtitle = "To-do list dekho ya reminder lagao",
                onClick = onTodo,
                showDivider = false
            )
        }
    }
}

@Composable
private fun AttachMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF1EEFA), fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AryaTextFaint)
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp)
            ) {
                Divider(color = AryaHairline, thickness = 1.dp)
            }
        }
    }
}
