package com.arya.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arya.ai.service.ScreenShareCaptureService
import com.arya.ai.service.WakeWordService
import com.arya.ai.util.CameraFrameCapture
import com.arya.ai.util.LiveConversationState
import com.arya.ai.util.LiveStatus
import com.arya.ai.util.VisionFrameProvider

/**
 * Gemini-Live-style full-screen continuous conversation. Starts [WakeWordService] in live
 * mode (no repeated "Hey Arya" needed — see [WakeWordService.ACTION_START_LIVE]) and reflects
 * its status via [LiveConversationState]. Camera/screen-share are optional vision add-ons —
 * see [CameraFrameCapture] and [ScreenShareCaptureService] — the voice loop works without
 * either.
 *
 * Leaving via system back keeps [WakeWordService] running in the background (that's the
 * point — "background me chale"); only the explicit close (X) button stops live mode.
 */
@Composable
fun LiveConversationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isActive by LiveConversationState.isActive.collectAsState()
    val status by LiveConversationState.status.collectAsState()
    val userText by LiveConversationState.lastUserText.collectAsState()
    val aryaText by LiveConversationState.lastAryaText.collectAsState()

    var cameraOn by remember { mutableStateOf(false) }
    var screenShareOn by remember { mutableStateOf(false) }
    var cameraCapture by remember { mutableStateOf<CameraFrameCapture?>(null) }

    val screenSharePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenShareCaptureService::class.java).apply {
                    putExtra(ScreenShareCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareCaptureService.EXTRA_RESULT_DATA, result.data)
                }
            )
            screenShareOn = true
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (screenShareOn) {
                context.stopService(Intent(context, ScreenShareCaptureService::class.java))
                screenShareOn = false
            }
            cameraOn = true
        }
    }

    // Start live mode on entry, stop it only on explicit close — NOT on dispose, since
    // leaving this screen (back/home) should keep the background conversation going.
    LaunchedEffect(Unit) {
        ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START_LIVE
        })
    }

    DisposableEffect(Unit) {
        onDispose {
            if (cameraOn) cameraCapture?.stop()
        }
    }

    fun stopLive() {
        if (cameraOn) {
            cameraCapture?.stop()
            cameraOn = false
        }
        if (screenShareOn) {
            context.stopService(Intent(context, ScreenShareCaptureService::class.java))
            screenShareOn = false
        }
        ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP_LIVE
        })
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview fills the background when camera vision is on
        if (cameraOn) {
            AndroidView(
                factory = { ctx ->
                    androidx.camera.view.PreviewView(ctx).also { previewView ->
                        val capture = CameraFrameCapture(ctx, lifecycleOwner, previewView)
                        capture.start()
                        cameraCapture = capture
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (screenShareOn) "Screen dekh rahi hoon" else if (cameraOn) "Camera dekh rahi hoon" else "Live baat cheet",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { stopLive() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Band karo", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Orb + status
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LiveOrb(status = status)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = when (status) {
                        LiveStatus.LISTENING -> "Sun rahi hoon..."
                        LiveStatus.THINKING -> "Soch rahi hoon..."
                        LiveStatus.SPEAKING -> "Bol rahi hoon..."
                        LiveStatus.IDLE -> if (isActive) "Taiyaar hoon" else "Shuru ho rahi hoon..."
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (userText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Tum: $userText",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                if (aryaText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Arya: $aryaText",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom controls — camera / screen-share toggles
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        if (cameraOn) {
                            cameraCapture?.stop()
                            cameraCapture = null
                            cameraOn = false
                        } else {
                            val hasCameraPermission = ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasCameraPermission) {
                                if (screenShareOn) {
                                    context.stopService(Intent(context, ScreenShareCaptureService::class.java))
                                    screenShareOn = false
                                }
                                cameraOn = true
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (cameraOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = "Camera vision",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = {
                        if (screenShareOn) {
                            context.stopService(Intent(context, ScreenShareCaptureService::class.java))
                            screenShareOn = false
                        } else {
                            if (cameraOn) {
                                cameraCapture?.stop()
                                cameraCapture = null
                                cameraOn = false
                            }
                            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenSharePermissionLauncher.launch(mpManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (screenShareOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        if (screenShareOn) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                        contentDescription = "Screen-share vision",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveOrb(status: LiveStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (status == LiveStatus.SPEAKING) 400 else 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val scale = if (status == LiveStatus.IDLE) 1f else pulse
    val gradientColors = when (status) {
        LiveStatus.LISTENING -> listOf(Color(0xFF4285F4), Color(0xFF34A853))
        LiveStatus.THINKING -> listOf(Color(0xFFFBBC05), Color(0xFFEA4335))
        LiveStatus.SPEAKING -> listOf(Color(0xFF9C27B0), Color(0xFF4285F4))
        LiveStatus.IDLE -> listOf(Color(0xFF5F6368), Color(0xFF3C4043))
    }
    Box(
        modifier = Modifier
            .size((140 * scale).dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {}
}
