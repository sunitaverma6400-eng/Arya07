package com.arya.ai.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

/** Matches youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID, and youtube.com/embed/ID. */
private val YOUTUBE_ID_REGEX = Regex(
    """(?:youtube\.com/(?:watch\?v=|shorts/|embed/)|youtu\.be/)([\w-]{11})"""
)

/** Finds the first thing in [text] that looks like a playable video: a YouTube link, or a
 *  direct URL ending in a video/HLS-ish extension. Returns null if nothing playable is found —
 *  callers use this to decide whether to show a "▶️ Video dekho" button under a chat bubble. */
fun findPlayableVideoUrl(text: String): String? {
    YOUTUBE_ID_REGEX.find(text)?.let { return "https://www.youtube.com/watch?v=${it.groupValues[1]}" }
    val directUrl = Regex("""https?://\S+\.(?:mp4|webm|mov|m3u8|mkv)\b""", RegexOption.IGNORE_CASE).find(text)
    return directUrl?.value
}

/** Quality choices shown in the picker. For the YouTube embed these map to the iframe's `vq`
 *  param (best-effort — YouTube's own embed player still has its own gear-icon quality menu
 *  regardless of this, since Google doesn't guarantee `vq` is honored). For direct files, these
 *  map to a real max-resolution cap on ExoPlayer's track selector, which actually constrains an
 *  adaptive (HLS/DASH) source — same engine [com.arya.ai.player.StreamPlayerManager] uses. */
private enum class VideoQuality(val label: String, val ytParam: String?, val maxHeight: Int) {
    AUTO("Auto", null, Int.MAX_VALUE),
    HIGH("High (1080p)", "hd1080", 1080),
    MEDIUM("Medium (480p)", "large", 480),
    LOW("Low (360p)", "small", 360)
}

/**
 * Full-screen (or windowed) video player dialog. Reused for both:
 *  - YouTube links (via the official iframe embed in a [WebView] — ExoPlayer can't stream a
 *    youtube.com *page*, only direct media files, so this is the legitimate way to play those
 *    in-app without needing a stream-extraction library)
 *  - Direct video files / HLS streams (`.mp4`, `.m3u8`, etc. — via [ExoPlayer], same engine
 *    [com.arya.ai.player.StreamPlayerManager] already uses for audio radio streams)
 *
 * Has an on-screen fullscreen/windowed toggle and a quality picker.
 */
@Composable
fun VideoPlayerDialog(url: String, onDismiss: () -> Unit) {
    val youtubeId = YOUTUBE_ID_REGEX.find(url)?.groupValues?.get(1)
    var isFullscreen by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(VideoQuality.AUTO) }
    var qualityMenuOpen by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(if (isFullscreen) Color.Black else Color.Black.copy(alpha = 0.85f))) {
            val playerModifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.align(Alignment.Center).fillMaxWidth(0.85f).aspectRatio(16f / 9f)
            }
            Box(modifier = playerModifier.background(Color.Black)) {
                if (youtubeId != null) {
                    key(youtubeId, quality) { YoutubeEmbedPlayer(youtubeId, quality) }
                } else {
                    key(url, quality) { ExoVideoPlayer(url, quality) }
                }
                // Controls — top-right corner of the player itself, so they sit correctly
                // whether the player is fullscreen or a smaller windowed box.
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box {
                        IconButton(onClick = { qualityMenuOpen = true }) {
                            Icon(Icons.Filled.HighQuality, contentDescription = "Quality", tint = Color.White)
                        }
                        DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
                            VideoQuality.values().forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(q.label + if (q == quality) " ✓" else "") },
                                    onClick = { quality = q; qualityMenuOpen = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { isFullscreen = !isFullscreen }) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isFullscreen) "Chhota screen" else "Bada screen",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Band karo", tint = Color.White)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeEmbedPlayer(videoId: String, quality: VideoQuality) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                val vqParam = quality.ytParam?.let { "&vq=$it" } ?: ""
                loadData(
                    """<html><body style="margin:0;background:#000">
                       <iframe width="100%" height="100%" src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1$vqParam"
                       frameborder="0" allow="autoplay; encrypted-media" allowfullscreen></iframe>
                       </body></html>""".trimIndent(),
                    "text/html", "UTF-8"
                )
            }
        }
    )
}

@Composable
private fun ExoVideoPlayer(url: String, quality: VideoQuality) {
    val context = LocalContext.current
    val trackSelector = remember { DefaultTrackSelector(context) }
    val player = remember {
        ExoPlayer.Builder(context).setTrackSelector(trackSelector).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    // Constrains resolution on adaptive (HLS/DASH) sources — a no-op on a flat single-quality
    // .mp4, but real quality switching on multi-bitrate streams.
    DisposableEffect(quality) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight)
            .build()
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PlayerView(it).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    )
}
