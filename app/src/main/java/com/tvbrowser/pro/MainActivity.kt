package com.tvbrowser.pro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity(), BrowserView.Listener, RemoteController.Callback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var tabBarContainer: LinearLayout
    private lateinit var newTabButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var addressBar: EditText
    private lateinit var webviewContainer: FrameLayout
    private lateinit var loadingProgress: ProgressBar
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var miniPlayerView: PlayerView
    private lateinit var miniPlayerHint: TextView

    private lateinit var browserPrefs: android.content.SharedPreferences
    private lateinit var tabManager: TabManager
    private lateinit var remoteController: RemoteController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabBarContainer = findViewById(R.id.tabBarContainer)
        newTabButton = findViewById(R.id.newTabButton)
        settingsButton = findViewById(R.id.settingsButton)
        addressBar = findViewById(R.id.addressBar)
        webviewContainer = findViewById(R.id.webviewContainer)
        loadingProgress = findViewById(R.id.loadingProgress)
        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)
        miniPlayerView = findViewById(R.id.miniPlayerView)
        miniPlayerHint = findViewById(R.id.miniPlayerHint)

        browserPrefs = getSharedPreferences(
            VideoInterceptor.AdBlockSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        remoteController = RemoteController(this)
        tabManager = TabManager(this, webviewContainer, browserPrefs, this)
        tabManager.restoreOrCreateInitialTabs()
        renderTabBar()

        newTabButton.setOnClickListener { onNewTabRequested() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitAddressBar()
                true
            } else false
        }

        miniPlayerContainer.setOnClickListener { expandMiniPlayer() }
        miniPlayerContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && isSelectKey(keyCode)) {
                expandMiniPlayer()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMiniPlayerVisibility()
    }

    // ---------------------------------------------------------------------
    // Tab bar rendering
    // ---------------------------------------------------------------------

    private fun renderTabBar() {
        tabBarContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        tabManager.tabs().forEach { tab ->
            val card = inflater.inflate(R.layout.item_tab, tabBarContainer, false)
            val titleView = card.findViewById<TextView>(R.id.tabTitle)
            val closeButton = card.findViewById<ImageButton>(R.id.tabCloseButton)

            titleView.text = tab.title.ifBlank { tab.browserView.currentUrl ?: "about:blank" }
            card.isSelected = tab.id == tabManager.activeTabId

            card.setOnClickListener { switchTab(tab.id) }
            card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && isSelectKey(keyCode)) {
                    switchTab(tab.id)
                    true
                } else false
            }
            closeButton.setOnClickListener { closeTab(tab.id) }
            closeButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && isSelectKey(keyCode)) {
                    closeTab(tab.id)
                    true
                } else false
            }

            tabBarContainer.addView(card)
        }
    }

    private fun switchTab(tabId: String) {
        tabManager.switchToTab(tabId)
        val tab = tabManager.activeTab()
        addressBar.setText(tab?.browserView?.currentUrl ?: "")
        renderTabBar()
    }

    private fun closeTab(tabId: String) {
        tabManager.closeTab(tabId)
        val tab = tabManager.activeTab()
        addressBar.setText(tab?.browserView?.currentUrl ?: "")
        renderTabBar()
    }

    private fun onNewTabRequested() {
        tabManager.openNewTab()
        renderTabBar()
        addressBar.requestFocus()
        addressBar.selectAll()
    }

    private fun submitAddressBar() {
        val text = addressBar.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        tabManager.activeTab()?.browserView?.loadUrl(text)
        tabManager.activeTab()?.browserView?.webView?.requestFocus()
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---------------------------------------------------------------------
    // BrowserView.Listener
    // ---------------------------------------------------------------------

    override fun onPageStarted(tab: BrowserView, url: String) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.visibility = View.VISIBLE
            addressBar.setText(url)
        }
    }

    override fun onPageFinished(tab: BrowserView, url: String, title: String?) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.visibility = View.GONE
        }
        tabManager.updateTabTitle(tab.tabId, title, url)
        renderTabBar()
    }

    override fun onProgressChanged(tab: BrowserView, progress: Int) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.progress = progress
            loadingProgress.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
        }
    }

    override fun onPrimaryVideoReady(tab: BrowserView, videoUrl: String) {
        if (tab.tabId != tabManager.activeTabId) return
        Log.d(TAG, "Primary video candidate ready: $videoUrl")
        startPlaybackWindowed(videoUrl)
    }

    override fun onDirectStreamRequested(tab: BrowserView, videoUrl: String) {
        if (tab.tabId != tabManager.activeTabId) return
        // This request has already passed the ad-domain filter in shouldInterceptRequest,
        // so a direct .mp4/.m3u8/.mpd hit here is very likely genuine content rather than
        // an ad segment. Start it in ExoPlayer, but windowed rather than fullscreen —
        // the person decides for themselves when (and whether) to go fullscreen.
        Log.d(TAG, "Direct stream requested: $videoUrl")
        startPlaybackWindowed(videoUrl)
    }

    override fun onReceivedError(tab: BrowserView, description: String?) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.visibility = View.GONE
            Toast.makeText(this, getString(R.string.webview_error_generic), Toast.LENGTH_SHORT).show()
        }
        Log.e(TAG, "WebView error on tab ${tab.tabId}: $description")
    }

    // ---------------------------------------------------------------------
    // Fullscreen / windowed (mini) player handoff
    // ---------------------------------------------------------------------

    /**
     * Starts (or resumes) playback of [url] in the small windowed mini-player only.
     * Fullscreen is never entered automatically — the person taps/selects the
     * mini player themselves (see [expandMiniPlayer]) whenever they want it big.
     */
    private fun startPlaybackWindowed(url: String) {
        PlayerHolder.playUrl(this, url)
        PlayerHolder.isMinimized = true
        refreshMiniPlayerVisibility()
    }

    private fun launchFullscreenPlayer(url: String) {
        hideMiniPlayer(release = false)
        PlayerHolder.isMinimized = false
        val intent = Intent(this, VideoPlayerActivity::class.java)
            .putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, url)
        startActivity(intent)
    }

    private fun expandMiniPlayer() {
        val url = PlayerHolder.currentUrl ?: return
        launchFullscreenPlayer(url)
    }

    private fun refreshMiniPlayerVisibility() {
        if (PlayerHolder.isActive && PlayerHolder.isMinimized) {
            miniPlayerView.player = PlayerHolder.getOrCreatePlayer(this)
            miniPlayerContainer.visibility = View.VISIBLE
        } else {
            hideMiniPlayer(release = false)
        }
    }

    private fun hideMiniPlayer(release: Boolean) {
        miniPlayerView.player = null
        miniPlayerContainer.visibility = View.GONE
        if (release) {
            PlayerHolder.stopAndRelease()
        }
    }

    private fun closeMiniPlayer() {
        hideMiniPlayer(release = true)
    }

    // ---------------------------------------------------------------------
    // RemoteController.Callback
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (remoteController.handleKeyEvent(event, currentFocus)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRemoteBackPressed(): Boolean {
        val focused = currentFocus
        if (miniPlayerContainer.hasFocus() || focused === miniPlayerContainer) {
            closeMiniPlayer()
            return true
        }
        val activeBrowser = tabManager.activeTab()?.browserView
        if (activeBrowser != null && activeBrowser.canGoBack()) {
            activeBrowser.goBack()
            return true
        }
        // Nothing left to go back to: let the system handle it (e.g. move task to back).
        return false
    }

    override fun onRemoteSelectPressed(focusedView: View?): Boolean {
        val activeBrowser = tabManager.activeTab()?.browserView
        if (activeBrowser != null && focusedView === activeBrowser.webView) {
            // Hint the heuristic that the user deliberately interacted with the page;
            // still let the key event propagate to the WebView itself for normal clicks.
            activeBrowser.notifyUserTappedVideoArea()
        }
        return false
    }

    override fun onRemoteDirectionPressed(keyCode: Int, focusedView: View?): Boolean {
        // Defer to normal Android focus-navigation between tab bar / address bar /
        // webview / mini player.
        return false
    }

    override fun onRemotePlayPausePressed(): Boolean {
        if (PlayerHolder.isActive) {
            val player = miniPlayerView.player
            if (player != null) {
                player.playWhenReady = !player.playWhenReady
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        tabManager.persistTabs()
    }
}
