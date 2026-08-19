package com.arya.ai.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.arya.ai.service.StreamPlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Everything the persistent mini-player bar ([com.arya.ai.ui.NowPlayingBar]) needs to
     *  render — mirrors [StreamPlaybackService]'s ExoPlayer state without the UI needing a
     *  service binding of its own. `label == null` means "nothing to show the bar for". */
    data class NowPlayingUi(
        val label: String? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val volume: Float = 1f,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false
    )

    private val _uiState = MutableStateFlow(NowPlayingUi())
    val uiState: StateFlow<NowPlayingUi> = _uiState.asStateFlow()

    private var boundService: StreamPlaybackService? = null
    private var connection: ServiceConnection? = null
    private var bindDeferred: CompletableDeferred<StreamPlaybackService>? = null
    private var listenerAttached = false

    /** Station/stream "playlist" the current play came from (e.g. a `search_radio` result
     *  list) — lets [next]/[previous] cycle through it for the mini-player's ⏮/⏭ buttons.
     *  A single ad-hoc [play] (not from a list) leaves this empty, so next/previous just no-op. */
    private var playlist: List<Pair<String, String>> = emptyList() // name to url
    private var playlistIndex: Int = -1

    private fun refreshUiState(svc: StreamPlaybackService) {
        val state = svc.playbackState()
        _uiState.value = NowPlayingUi(
            label = svc.currentLabel,
            isPlaying = svc.isPlaying(),
            isBuffering = state == ExoPlayer.STATE_BUFFERING,
            volume = svc.currentVolume(),
            hasNext = playlist.size > 1,
            hasPrevious = playlist.size > 1
        )
    }

    private fun attachListenerIfNeeded(svc: StreamPlaybackService) {
        if (listenerAttached) return
        svc.player()?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = refreshUiState(svc)
            override fun onPlaybackStateChanged(playbackState: Int) = refreshUiState(svc)
        })
        listenerAttached = true
    }

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
                attachListenerIfNeeded(svc)
                refreshUiState(svc)
                deferred.complete(svc)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
                bindDeferred = null
                listenerAttached = false
                _uiState.value = NowPlayingUi()
            }
        }
        connection = conn

        val bindIntent = Intent(appContext, StreamPlaybackService::class.java)
            .setAction(StreamPlaybackService.ACTION_LOCAL_BIND)
        ContextCompat.startForegroundService(appContext, Intent(appContext, StreamPlaybackService::class.java))
        appContext.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)

        deferred.await()
    }

    /** Plain single-URL play — used by everything that isn't a tap on a `search_radio` list
     *  (typed "X radio lagao", find_and_play, generated music clips, etc). Clears any prior
     *  [playlist] since there's no known set of siblings to skip through anymore. */
    fun play(context: Context, url: String, label: String = url): String = runBlocking {
        try {
            val svc = service(context)
            playlist = emptyList()
            playlistIndex = -1
            withContext(Dispatchers.Main) { svc.playUrl(url, label) }
            refreshUiState(svc)
            "▶️ Stream shuru: $label (background me bhi chalti rahegi, notification se control karo)"
        } catch (e: Exception) {
            "❌ Stream play nahi ho paaya: ${e.message}"
        }
    }

    /** Same as [play] but remembers the whole (name, url) list it came from — e.g. a
     *  `search_radio` result — so the mini-player's ⏮/⏭ can cycle through siblings instead of
     *  only ever playing a single fixed URL. */
    fun playFromList(context: Context, stations: List<Pair<String, String>>, index: Int): String {
        if (stations.isEmpty() || index !in stations.indices) return play(context, "", "")
        playlist = stations
        playlistIndex = index
        val (name, url) = stations[index]
        return runBlocking {
            try {
                val svc = service(context)
                withContext(Dispatchers.Main) { svc.playUrl(url, name) }
                refreshUiState(svc)
                "▶️ Stream shuru: $name (background me bhi chalti rahegi, notification se control karo)"
            } catch (e: Exception) {
                "❌ Stream play nahi ho paaya: ${e.message}"
            }
        }
    }

    /** Mini-player ⏭ — next station in the current [playlist], wrapping around. No-op
     *  (returns null) if the current stream wasn't started from a list. */
    fun next(context: Context): String? {
        if (playlist.size <= 1) return null
        playlistIndex = (playlistIndex + 1) % playlist.size
        val (name, url) = playlist[playlistIndex]
        return runBlocking {
            val svc = service(context)
            withContext(Dispatchers.Main) { svc.playUrl(url, name) }
            refreshUiState(svc)
            "▶️ $name"
        }
    }

    /** Mini-player ⏮ — same as [next] but backwards. */
    fun previous(context: Context): String? {
        if (playlist.size <= 1) return null
        playlistIndex = (playlistIndex - 1 + playlist.size) % playlist.size
        val (name, url) = playlist[playlistIndex]
        return runBlocking {
            val svc = service(context)
            withContext(Dispatchers.Main) { svc.playUrl(url, name) }
            refreshUiState(svc)
            "▶️ $name"
        }
    }

    fun pause(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream chal hi nahi raha"
        withContext(Dispatchers.Main) { svc.pause() }
        refreshUiState(svc)
        "⏸️ Stream pause kar di"
    }

    fun resume(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream nahi hai resume karne ko"
        withContext(Dispatchers.Main) { svc.resume() }
        refreshUiState(svc)
        "▶️ Stream resume kar di"
    }

    fun stop(): String = runBlocking {
        val svc = boundService ?: return@runBlocking "❌ Koi stream chal hi nahi raha"
        withContext(Dispatchers.Main) { svc.stopPlayback() }
        playlist = emptyList()
        playlistIndex = -1
        refreshUiState(svc)
        "⏹️ Stream stop kar di"
    }

    /** Mini-player volume slider — 0f (mute) to 1f (full). Silently no-ops if nothing is
     *  bound yet (slider just won't have moved anything, no crash). */
    fun setVolume(volume: Float) {
        val svc = boundService ?: return
        svc.setVolume(volume)
        refreshUiState(svc)
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
