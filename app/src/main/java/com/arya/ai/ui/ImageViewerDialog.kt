package com.arya.ai.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider

/**
 * Full-screen tap-to-zoom viewer for images shown in chat — `search_images` results and
 * generated images alike (both land in [com.arya.ai.viewmodel.ChatMessage.images], see
 * [ChatBubble]'s image row). Pinch-to-zoom + drag-to-pan while zoomed, double-tap to reset,
 * and a save-to-gallery / share action since a bitmap sitting only in an in-memory chat
 * message was otherwise unreachable outside the app (no download, no way to share it).
 */
@Composable
fun ImageViewerDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offsetX = if (scale > 1f) offsetX + pan.x else 0f
                        offsetY = if (scale > 1f) offsetY + pan.y else 0f
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    })
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = {
                    val file = com.arya.ai.tools.ImageGenTools.saveToGallery(context, bitmap)
                    Toast.makeText(
                        context,
                        if (file != null) "✅ Image save ho gayi: Pictures/Arya" else "❌ Save nahi ho payi",
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "Download karo", tint = Color.White)
                }
                IconButton(onClick = {
                    val file = com.arya.ai.tools.ImageGenTools.saveToGallery(context, bitmap) ?: return@IconButton
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Image share karo"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share karo", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Band karo", tint = Color.White)
                }
            }
        }
    }
}
