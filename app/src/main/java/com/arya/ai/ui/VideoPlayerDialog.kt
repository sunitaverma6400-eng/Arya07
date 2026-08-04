package com.arya.ai.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

/**
 * Full-screen video player dialog. Reused for both:
 *  - YouTube links (via the official iframe embed in a [WebView] — ExoPlayer can't stream a
 *    youtube.com *page*, only direct media files, so this is the legitimate way to play those
 *    in-app without needing a stream-extraction library)
 *  - Direct video files / HLS streams (`.mp4`, `.m3u8`, etc. — via [ExoPlayer], same engine
 *    [com.arya.ai.player.StreamPlayerManager] already uses for audio radio streams)
 */
@Composable
fun VideoPlayerDialog(url: String, onDismiss: () -> Unit) {
    val youtubeId = YOUTUBE_ID_REGEX.find(url)?.groupValues?.get(1)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (youtubeId != null) {
                YoutubeEmbedPlayer(youtubeId)
            } else {
                ExoVideoPlayer(url)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Band karo", tint = Color.White)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeEmbedPlayer(videoId: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                loadData(
                    """<html><body style="margin:0;background:#000">
                       <iframe width="100%" height="100%" src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1"
                       frameborder="0" allow="autoplay; encrypted-media" allowfullscreen></iframe>
                       </body></html>""".trimIndent(),
                    "text/html", "UTF-8"
                )
            }
        }
    )
}

@Composable
private fun ExoVideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
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
