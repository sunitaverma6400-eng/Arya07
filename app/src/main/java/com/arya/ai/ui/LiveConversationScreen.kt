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
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.compose.ui.geometry.Offset
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
    val emotion by LiveConversationState.lastEmotion.collectAsState()
    val mouthLevel by LiveConversationState.mouthLevel.collectAsState()

    // Real 3D face (WebView + VRM) when assets/avatar/model.vrm loads successfully; falls
    // back to the hand-drawn Canvas face otherwise (missing model file, load error, or WebGL
    // unavailable on the device) — see VrmAvatarView.kt / assets/README.md for how to add one.
    val avatarController = rememberVrmAvatarController()
    var vrmLoadResult by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(emotion) { avatarController.setExpression(emotion) }
    LaunchedEffect(mouthLevel) { avatarController.setMouthOpen(mouthLevel) }


    var cameraOn by remember { mutableStateOf(false) }
    var isFrontCamera by remember { mutableStateOf(false) }
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
            isFrontCamera = false
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
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
                    // vrmLoadResult == null: still loading — keep the Canvas face visible
                    // underneath so there's no blank gap while the WebView spins up.
                    if (vrmLoadResult != false) {
                        VrmAvatarView(
                            controller = avatarController,
                            onLoadResult = { success -> vrmLoadResult = success },
                            modifier = Modifier.size(220.dp)
                        )
                    }
                    if (vrmLoadResult != true) {
                        LiveAnimeFace(status = status, replyText = aryaText)
                    }
                }
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
                            isFrontCamera = false
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

                // Front/back camera flip — only meaningful while the camera preview is on.
                if (cameraOn) {
                    IconButton(
                        onClick = {
                            cameraCapture?.switchLens()
                            isFrontCamera = !isFrontCamera
                        },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            Icons.Filled.Cameraswitch,
                            contentDescription = "Front/back camera switch karo",
                            tint = Color.White
                        )
                    }
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
                                isFrontCamera = false
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

/**
 * A simple vector-drawn anime-style face — replaces the plain pulsing orb (see chat history:
 * "video call jaisa, anime character dikhe" request). Worth being upfront about what this
 * actually is: hand-drawn shapes via [androidx.compose.foundation.Canvas], animated with
 * ordinary Compose animation APIs — NOT a licensed character model (Live2D etc.), which would
 * need real character art/rigging files no amount of Kotlin code can generate on its own.
 * What this DOES genuinely do:
 *  - Blinks on an irregular loop (idle "alive" motion, not just a static picture)
 *  - Mouth actually opens/closes in a loop while [LiveStatus.SPEAKING] — a talking animation
 *    synced to real TTS state, not just decorative
 *  - Picks one of a few basic expressions from [status] plus a light keyword/emoji scan of
 *    [replyText], so "Arya ne kuch achha bola" and "Arya soch rahi hai" visibly look different
 */
@Composable
private fun LiveAnimeFace(status: LiveStatus, replyText: String) {
    val emotion = remember(status, replyText) { inferEmotion(status, replyText) }

    // Idle blink: closed for a short beat, on an irregular interval — a fixed-period blink
    // reads as robotic, so the wait between blinks is randomized each cycle.
    var eyeOpenness by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay((2200..4500).random().toLong())
            eyeOpenness = 0.05f
            kotlinx.coroutines.delay(120)
            eyeOpenness = 1f
        }
    }

    // Talking animation: mouth openness cycles while speaking, closed (or a small idle
    // wobble) otherwise. Not real lip-sync (no viseme/phoneme data available here) — just a
    // continuous open/close loop, the same "flapping mouth" convention simple VTuber/avatar
    // apps use when they don't have per-phoneme mouth shapes.
    val infiniteTransition = rememberInfiniteTransition(label = "face")
    val mouthCycle by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(180), repeatMode = RepeatMode.Reverse),
        label = "mouth"
    )
    val mouthOpenness = if (status == LiveStatus.SPEAKING) mouthCycle else 0.12f

    // Gentle idle bob so the face doesn't look frozen even outside blink/talk moments.
    val bob by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(2000), repeatMode = RepeatMode.Reverse),
        label = "bob"
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.size(180.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f + bob
        val faceRadius = size.width * 0.42f

        // Face
        drawCircle(color = Color(0xFFFFE0C8), radius = faceRadius, center = Offset(cx, cy))

        // Hair silhouette — a simple anime-style top fringe, just enough to read as a
        // character rather than a plain skin-tone circle.
        val hairColor = when (emotion) {
            Emotion.SAD -> Color(0xFF4A3B6B)
            else -> Color(0xFF6B4EA3)
        }
        drawArc(
            color = hairColor,
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(cx - faceRadius * 1.05f, cy - faceRadius * 1.15f),
            size = androidx.compose.ui.geometry.Size(faceRadius * 2.1f, faceRadius * 1.5f)
        )

        val eyeY = cy - faceRadius * 0.05f
        val eyeDx = faceRadius * 0.42f
        val eyeW = faceRadius * 0.32f
        val eyeH = faceRadius * 0.4f * eyeOpenness

        listOf(-1f, 1f).forEach { side ->
            val eyeCenter = Offset(cx + side * eyeDx, eyeY)
            // Eye white
            drawOval(
                color = Color.White,
                topLeft = Offset(eyeCenter.x - eyeW / 2, eyeCenter.y - eyeH / 2),
                size = androidx.compose.ui.geometry.Size(eyeW, eyeH)
            )
            if (eyeOpenness > 0.15f) {
                // Iris + highlight — the classic big-anime-eye look, simplified to two circles.
                val irisR = eyeW * 0.32f
                drawCircle(color = Color(0xFF3C2A6B), radius = irisR, center = eyeCenter)
                drawCircle(
                    color = Color.White,
                    radius = irisR * 0.35f,
                    center = Offset(eyeCenter.x - irisR * 0.35f, eyeCenter.y - irisR * 0.35f)
                )
            }
            // Eyebrow — angle/position shifts per emotion (worried, surprised, neutral, happy).
            val browY = eyeY - eyeH / 2 - faceRadius * 0.18f
            val browTilt = when (emotion) {
                Emotion.THINKING -> if (side < 0) -0.15f else 0.2f
                Emotion.SURPRISED -> -0.05f
                Emotion.SAD -> 0.18f * side
                Emotion.HAPPY -> -0.08f
                Emotion.NEUTRAL -> 0f
            }
            drawLine(
                color = Color(0xFF3C2A6B),
                start = Offset(eyeCenter.x - eyeW * 0.4f, browY + browTilt * eyeW),
                end = Offset(eyeCenter.x + eyeW * 0.4f, browY - browTilt * eyeW),
                strokeWidth = faceRadius * 0.06f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        // Mouth — a simple open oval that scales with [mouthOpenness]; emotion nudges its
        // resting shape (curved up for happy, flat/down for sad, small "o" for surprised).
        val mouthY = cy + faceRadius * 0.48f
        val mouthW = faceRadius * (if (emotion == Emotion.SURPRISED) 0.35f else 0.55f)
        val mouthH = (faceRadius * 0.35f * mouthOpenness).coerceAtLeast(faceRadius * 0.04f)
        when (emotion) {
            Emotion.HAPPY -> drawArc(
                color = Color(0xFF8B3A3A),
                startAngle = 10f, sweepAngle = 160f, useCenter = false,
                topLeft = Offset(cx - mouthW / 2, mouthY - mouthH / 2),
                size = androidx.compose.ui.geometry.Size(mouthW, mouthH.coerceAtLeast(faceRadius * 0.18f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = faceRadius * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            else -> drawOval(
                color = Color(0xFF8B3A3A),
                topLeft = Offset(cx - mouthW / 2, mouthY - mouthH / 2),
                size = androidx.compose.ui.geometry.Size(mouthW, mouthH)
            )
        }
    }
}

private enum class Emotion { NEUTRAL, HAPPY, THINKING, SURPRISED, SAD }

/** Cheap heuristic, not real sentiment analysis: [status] drives the default expression
 *  (thinking/neutral), then a light keyword/emoji scan of what Arya actually just said can
 *  override it — good enough to make the face feel responsive without needing another model
 *  call just to decide which face to draw. */
private fun inferEmotion(status: LiveStatus, replyText: String): Emotion {
    if (status == LiveStatus.THINKING) return Emotion.THINKING
    val t = replyText.lowercase()
    return when {
        t.isBlank() -> Emotion.NEUTRAL
        listOf("😊", "😄", "🎉", "haha", "great", "achha", "badhiya", "mazaa").any { t.contains(it) } -> Emotion.HAPPY
        listOf("😢", "😔", "sorry", "maaf", "afsos", "dukh").any { t.contains(it) } -> Emotion.SAD
        listOf("😲", "wow", "vaah", "really?", "sach me").any { t.contains(it) } -> Emotion.SURPRISED
        else -> Emotion.NEUTRAL
    }
}
