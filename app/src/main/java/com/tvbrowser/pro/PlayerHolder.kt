package com.tvbrowser.pro

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Process-wide singleton that owns a single ExoPlayer instance.
 *
 * Both the fullscreen [VideoPlayerActivity] and the windowed mini-player hosted
 * inside [MainActivity] attach/detach their PlayerView to this same player, which
 * is what makes the "minimize to window" feature (requirement #3) work without
 * interrupting or re-buffering playback.
 */
object PlayerHolder {

    private var player: ExoPlayer? = null

    var currentUrl: String? = null
        private set

    /** True while a video is loaded, whether shown fullscreen or minimized. */
    var isActive: Boolean = false
        private set

    /** True while the video is being shown as a small windowed overlay in MainActivity. */
    var isMinimized: Boolean = false

    var lastError: PlaybackException? = null
        private set

    interface ErrorListener {
        fun onPlaybackError(error: PlaybackException)
    }

    private var errorListener: ErrorListener? = null

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    private val internalListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            lastError = error
            errorListener?.onPlaybackError(error)
        }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        val existing = player
        if (existing != null) return existing

        // Prefer hardware (platform) decoders, but allow the extension /
        // software renderer as a fallback when a hardware codec is missing
        // or fails, per the base architecture requirement.
        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val newPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .build()
        newPlayer.addListener(internalListener)
        player = newPlayer
        return newPlayer
    }

    fun playUrl(context: Context, url: String, headers: Map<String, String> = emptyMap()) {
        val exo = getOrCreatePlayer(context)
        if (currentUrl == url && isActive) {
            // Already playing this URL; just make sure it's playing.
            exo.playWhenReady = true
            return
        }
        currentUrl = url
        lastError = null
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .build()
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true
        isActive = true
    }

    fun stopAndRelease() {
        player?.let {
            it.removeListener(internalListener)
            it.stop()
            it.release()
        }
        player = null
        currentUrl = null
        isActive = false
        isMinimized = false
        lastError = null
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun resume() {
        player?.playWhenReady = true
    }
}
