package com.arya.ai.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import com.arya.ai.service.StreamPlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Thin client that binds to [StreamPlaybackService] and forwards playback commands to it —
 * backing the `play_stream`/`pause_stream`/`resume_stream`/`stop_stream`/`stream_status` tools
 * in [com.arya.ai.tools.StreamTools]. Ported from the original assistant's `hls_player.py` +
 * `hls_stream_pipeline.py`, which ran ffmpeg/mpv on the Termux side — here it's replaced with
 * Media3 ExoPlayer, which has built-in HLS (`.m3u8`) support.
 *
 * As of the Phase 2 pass, the actual [ExoPlayer] lives inside [StreamPlaybackService] (a real
 * `MediaSessionService`) instead of in-process here — that's what gives streaming proper
 * background/lock-screen playback with a system media notification, surviving the app being
 * backgrounded (previously it stopped the moment Android reclaimed the app's process).
 *
 * Every public function keeps the same simple non-suspend `String` return signature
 * [com.arya.ai.tools.AryaToolRegistry]/`StreamTools` already expect — internally each one binds
 * (once; the connection is cached) and hops onto the main thread, since both binding callbacks
 * and ExoPlayer itself only work correctly from a thread with a [android.os.Looper].
 */
object StreamPlayerManager {

    private var boundService: StreamPlaybackService? = null
    private var connection: ServiceConnection? = null
    private var bindDeferred: CompletableDeferred<StreamPlaybackService>? = null

    private suspend fun service(context: Context): StreamPlaybackService = withContext(Dispatchers.Main) {
        boundService?.let { return@withContext it }

        val existing = bindDeferred
        if (existing != null) return@withContext existing.await()

        val appContext = context.applicationContext
        val deferred = CompletableDeferred<StreamPlaybackService>()
        bindDeferred = deferred

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as StreamPlaybackService.LocalBinder).service
                boundService = svc
                deferred.complete(svc)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
                bindDeferred = null
            }
        }
        connection = conn

        val bindIntent = Intent(appContext, StreamPlaybackService::class.java)
            .setAction(StreamPlaybackService.ACTION_LOCAL_BIND)
        ContextCompat.startForegroundService(appContext, Intent(appContext, StreamPlaybackService::class.java))
        appContext.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)

        deferred.await()
    }

    fun play(context: Context, url: String, label: String = url): String = runBlocking {
        try {
            val svc = service(context)
            withContext(Dispatchers.Main) { svc.playUrl(url, label) }
            "▶️ Stream shuru: $label (background me bhi chalti rahegi, notification se control karo)"
        } catch (e: Exception) {
            "❌ Stream play nahi ho paaya: ${e.message}"
        }
    }

    fun pause(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream chal hi nahi raha"
        withContext(Dispatchers.Main) { svc.pause() }
        "⏸️ Stream pause kar di"
    }

    fun resume(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream nahi hai resume karne ko"
        withContext(Dispatchers.Main) { svc.resume() }
        "▶️ Stream resume kar di"
    }

    fun stop(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream chal hi nahi raha"
        withContext(Dispatchers.Main) { svc.stopPlayback() }
        "⏹️ Stream stop kar di"
    }

    fun status(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "⏹️ Koi stream active nahi hai"
        withContext(Dispatchers.Main) {
            val state = when (svc.playbackState()) {
                ExoPlayer.STATE_READY -> if (svc.isPlaying()) "playing ▶️" else "paused ⏸️"
                ExoPlayer.STATE_BUFFERING -> "buffering ⏳"
                ExoPlayer.STATE_ENDED -> "ended ⏹️"
                else -> "idle"
            }
            "🎧 ${svc.currentLabel ?: "no stream"} — $state"
        }
    }

    /** Unbinds (doesn't stop playback — the service keeps running in the background as a
     *  proper foreground media session; call [stop] first if you actually want it to end). */
    fun release(context: Context) {
        val conn = connection ?: return
        context.applicationContext.unbindService(conn)
        connection = null
        boundService = null
        bindDeferred = null
    }
}
