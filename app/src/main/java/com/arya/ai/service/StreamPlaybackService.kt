package com.arya.ai.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the actual ExoPlayer + [MediaSession] for streaming (radio/audio) — replaces the old
 * in-process-only player in `player/StreamPlayerManager.kt`, which stopped the moment the app
 * was backgrounded long enough for Android to reclaim it.
 *
 * As a [MediaSessionService], the framework itself (`DefaultMediaNotificationProvider`)
 * automatically shows a system media notification and enters/exits the foreground state as
 * playback starts/stops — nothing extra to wire up here for lock-screen controls to work.
 *
 * [com.arya.ai.player.StreamPlayerManager] binds to this service (see [LocalBinder]) to reach
 * the player from any thread/tool call without duplicating playback state.
 */
class StreamPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /** Last label passed to [playUrl] — surfaced back to [com.arya.ai.tools.StreamTools.streamStatus]. */
    var currentLabel: String? = null
        private set

    inner class LocalBinder : Binder() {
        val service: StreamPlaybackService get() = this@StreamPlaybackService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        mediaSession = MediaSession.Builder(this, exo).build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Only intercept our own StreamPlayerManager binding intent — any other bind (system
        // media notification, Bluetooth/lock-screen controllers) must go through
        // MediaSessionService's own super.onBind(), or those stop working.
        return if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
        // else: actively playing — let it keep running in the background, same as any
        // music app, until the user explicitly stops it or it finishes.
    }

    fun playUrl(url: String, label: String) {
        val exo = player ?: return
        currentLabel = label
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(label).build())
                .build()
        )
        exo.prepare()
        exo.playWhenReady = true
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun stopPlayback() { player?.stop(); currentLabel = null }
    fun playbackState(): Int? = player?.playbackState
    fun isPlaying(): Boolean = player?.isPlaying == true

    /** Exposed for [com.arya.ai.player.StreamPlayerManager] to attach a [androidx.media3.common.Player.Listener]
     *  (mini-player state) and to read/set volume — see that class's `uiState`. */
    fun player(): ExoPlayer? = player
    fun currentVolume(): Float = player?.volume ?: 1f
    fun setVolume(v: Float) { player?.volume = v.coerceIn(0f, 1f) }

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** Action StreamPlayerManager's bind Intent uses, kept distinct from
         *  [MediaSessionService.SERVICE_INTERFACE] so [onBind] can tell them apart. */
        const val ACTION_LOCAL_BIND = "com.arya.ai.action.STREAM_LOCAL_BIND"
    }
}
