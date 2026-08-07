package com.arya.ai.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.arya.ai.util.AvatarEmotion

/**
 * Drives [android.webkit.WebView]'s JS avatar scene (assets/avatar/index.html +
 * avatar.js — three.js + three-vrm) from Kotlin. Hold one of these per screen via
 * [rememberVrmAvatarController] and pass it to [VrmAvatarView]; call [setExpression] /
 * [setMouthOpen] from anywhere (e.g. [com.arya.ai.util.VoiceHelper]'s onEmotion /
 * onMouthLevel callbacks) — calls made before the WebView finishes attaching are just
 * dropped, matching the idle/neutral state the JS scene already starts in.
 */
class VrmAvatarController {
    internal var webView: WebView? = null

    fun setExpression(emotion: String) {
        val safe = AvatarEmotion.sanitize(emotion)
        webView?.post { webView?.evaluateJavascript("window.AryaAvatar && AryaAvatar.setExpression('$safe');", null) }
    }

    /** level: 0f (closed) .. 1f (fully open). Safe to call at high frequency (~30-60Hz) —
     *  see [com.arya.ai.util.VoiceHelper]'s Visualizer-driven real-time amplitude callback. */
    fun setMouthOpen(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        webView?.post { webView?.evaluateJavascript("window.AryaAvatar && AryaAvatar.setMouthOpen($clamped);", null) }
    }
}

@Composable
fun rememberVrmAvatarController(): VrmAvatarController = remember { VrmAvatarController() }

/**
 * Renders Arya's 3D (VRM) face. Requires `app/src/main/assets/avatar/model.vrm` to exist
 * (see assets/README.md — not bundled, it's licensed character art) — [onLoadResult] fires
 * `false` if it's missing or fails to parse, so the caller (see [LiveConversationScreen])
 * can fall back to the existing hand-drawn Canvas face instead of showing a blank/broken view.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VrmAvatarView(
    controller: VrmAvatarController,
    onLoadResult: (success: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var reported by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // WebGL needs hardware acceleration; layerType defaults to LAYER_TYPE_HARDWARE
                // on modern Android so no explicit setLayerType call is needed.
                setBackgroundColor(AndroidColor.TRANSPARENT)
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onModelLoaded() {
                            if (!reported) { reported = true; post { onLoadResult(true) } }
                        }

                        @JavascriptInterface
                        fun onLoadError(message: String) {
                            if (!reported) { reported = true; post { onLoadResult(false) } }
                        }
                    },
                    "AndroidBridge"
                )
                loadUrl("file:///android_asset/avatar/index.html")
                controller.webView = this
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.webView?.apply {
                loadUrl("about:blank")
                destroy()
            }
            controller.webView = null
        }
    }
}
