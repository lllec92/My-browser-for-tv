package com.tvbrowser.pro

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView

class VideoPlayerActivity : AppCompatActivity(), RemoteController.Callback, PlayerHolder.ErrorListener {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        private const val TAG = "VideoPlayerActivity"
    }

    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: View
    private lateinit var errorText: android.widget.TextView
    private lateinit var remoteController: RemoteController
    private var playbackStateListener: androidx.media3.common.Player.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.fullPlayerView)
        loadingSpinner = findViewById(R.id.playerLoading)
        errorText = findViewById(R.id.playerErrorText)
        remoteController = RemoteController(this)

        val url = intent.getStringExtra(EXTRA_VIDEO_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "No video URL provided, closing player")
            finish()
            return
        }

        PlayerHolder.setErrorListener(this)
        PlayerHolder.isMinimized = false
        val exoPlayer = PlayerHolder.getOrCreatePlayer(this)
        playerView.player = exoPlayer
        playerView.requestFocus()

        if (PlayerHolder.currentUrl != url || !PlayerHolder.isActive) {
            PlayerHolder.playUrl(this, url)
        }

        loadingSpinner.visibility = View.VISIBLE
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    loadingSpinner.visibility = View.GONE
                }
            }
        }
        playbackStateListener = listener
        exoPlayer.addListener(listener)
    }

    override fun onPlaybackError(error: PlaybackException) {
        runOnUiThread {
            loadingSpinner.visibility = View.GONE
            errorText.visibility = View.VISIBLE
            errorText.text = getString(R.string.player_error_generic)
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (remoteController.handleKeyEvent(event, currentFocus)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // BACK minimizes playback into the windowed mini player hosted by MainActivity,
    // instead of stopping the video outright, per requirement #3.
    override fun onRemoteBackPressed(): Boolean {
        minimizeToWindow()
        return true
    }

    override fun onRemoteSelectPressed(focusedView: View?, repeatCount: Int): Boolean {
        // Let the default PlayerView controller handle OK/select for play-pause/seek.
        return false
    }

    override fun onRemoteDirectionPressed(keyCode: Int, repeatCount: Int, focusedView: View?): Boolean {
        // Let the default PlayerView controller handle seeking/scrubbing.
        return false
    }

    override fun onRemoteDirectionReleased(keyCode: Int) {
        // No-op: VideoPlayerActivity doesn't use the cursor/scroll system.
    }

    override fun onRemotePlayPausePressed(): Boolean {
        val player = playerView.player ?: return false
        player.playWhenReady = !player.playWhenReady
        return true
    }

    private fun minimizeToWindow() {
        PlayerHolder.isMinimized = true
        playerView.player = null
        setResult(RESULT_OK, Intent().putExtra("minimized", true))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If the user presses HOME while a video is playing, prefer real system PiP
        // when the device/launcher supports it (Android TV devices that implement PiP).
        tryEnterSystemPictureInPicture()
    }

    private fun tryEnterSystemPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            try {
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.w(TAG, "System PiP unavailable, falling back to custom windowed mode", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PlayerHolder.setErrorListener(null)
        playbackStateListener?.let { listener ->
            // playerView.player may already be null here if we were minimized
            // (minimizeToWindow() detaches it before finish()), so fetch the
            // shared instance straight from PlayerHolder instead.
            if (PlayerHolder.isActive) {
                PlayerHolder.getOrCreatePlayer(this).removeListener(listener)
            }
        }
        // Do NOT release the player here: if we were minimized, MainActivity's mini
        // player will pick it up. Only a real "close video" action releases it
        // (handled from MainActivity when the mini player is dismissed).
        if (playerView.player != null) {
            playerView.player = null
        }
    }
}
