package com.tvbrowser.pro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity(), BrowserView.Listener, RemoteController.Callback {

    companion object {
        private const val TAG = "MainActivity"
        // How many auto-repeats of OK/Center count as a "long press" (used to open
        // the link under the cursor in a new tab instead of just clicking it).
        private const val LONG_PRESS_REPEAT_THRESHOLD = 3
    }

    private lateinit var tabBarContainer: LinearLayout
    private lateinit var newTabButton: ImageButton
    private lateinit var bookmarksButton: ImageButton
    private lateinit var historyButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var starButton: ImageButton
    private lateinit var addressBar: EditText
    private lateinit var micButton: ImageButton
    private lateinit var zoomOutButton: ImageButton
    private lateinit var zoomInButton: ImageButton
    private lateinit var webviewStack: FrameLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var tvCursor: ImageView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var miniPlayerView: PlayerView
    private lateinit var miniPlayerHint: TextView

    private lateinit var browserPrefs: android.content.SharedPreferences
    private lateinit var tabManager: TabManager
    private lateinit var bookmarksManager: BookmarksManager
    private lateinit var historyManager: HistoryManager
    private lateinit var remoteController: RemoteController
    private lateinit var cursorController: CursorController

    private var longPressTriggered = false
    private var chromeHasFocus = false

    private lateinit var bookmarksLauncher: ActivityResultLauncher<Intent>
    private lateinit var historyLauncher: ActivityResultLauncher<Intent>
    private lateinit var voiceSearchLauncher: ActivityResultLauncher<Intent>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabBarContainer = findViewById(R.id.tabBarContainer)
        newTabButton = findViewById(R.id.newTabButton)
        bookmarksButton = findViewById(R.id.bookmarksButton)
        historyButton = findViewById(R.id.historyButton)
        settingsButton = findViewById(R.id.settingsButton)
        starButton = findViewById(R.id.starButton)
        addressBar = findViewById(R.id.addressBar)
        micButton = findViewById(R.id.micButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        zoomInButton = findViewById(R.id.zoomInButton)
        webviewStack = findViewById(R.id.webviewStack)
        webviewContainer = findViewById(R.id.webviewContainer)
        tvCursor = findViewById(R.id.tvCursor)
        loadingProgress = findViewById(R.id.loadingProgress)
        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)
        miniPlayerView = findViewById(R.id.miniPlayerView)
        miniPlayerHint = findViewById(R.id.miniPlayerHint)

        browserPrefs = getSharedPreferences(
            VideoInterceptor.AdBlockSettings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        bookmarksManager = BookmarksManager(this)
        historyManager = HistoryManager(this)

        remoteController = RemoteController(this)
        cursorController = CursorController(webviewStack, tvCursor)

        registerActivityResultLaunchers()

        // The cursor is visible by default and moves with the D-pad whenever focus
        // isn't on one of the "chrome" widgets below (tab bar, address bar, top
        // buttons, mini player) — tracked explicitly via focus listeners rather than
        // by checking whether the WebView itself holds Android focus, since a
        // WebView's requestFocus() can silently fail right after it's made visible.
        cursorController.show()

        tabManager = TabManager(this, webviewContainer, browserPrefs, this)
        tabManager.restoreOrCreateInitialTabs()
        renderTabBar()
        updateBookmarkIcon()

        newTabButton.setOnClickListener { onNewTabRequested() }
        bookmarksButton.setOnClickListener {
            bookmarksLauncher.launch(Intent(this, BookmarksActivity::class.java))
        }
        historyButton.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        starButton.setOnClickListener { toggleBookmarkForActiveTab() }
        micButton.setOnClickListener { startVoiceSearch() }
        zoomInButton.setOnClickListener { tabManager.activeTab()?.browserView?.zoomIn() }
        zoomOutButton.setOnClickListener { tabManager.activeTab()?.browserView?.zoomOut() }

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

        trackChromeFocus(
            newTabButton, bookmarksButton, historyButton, settingsButton,
            starButton, addressBar, micButton, zoomOutButton, zoomInButton,
            miniPlayerContainer
        )
    }

    /** Marks each given view as "chrome" — while any of them holds focus, D-pad
     *  presses go to normal Android focus-navigation/clicks instead of the cursor. */
    private fun trackChromeFocus(vararg views: View) {
        views.forEach { view ->
            view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                chromeHasFocus = hasFocus
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMiniPlayerVisibility()
    }

    private fun registerActivityResultLaunchers() {
        bookmarksLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_SELECTED_URL)
                if (!url.isNullOrBlank()) openUrlInNewTab(url)
            }
        }
        historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val url = result.data?.getStringExtra(HistoryActivity.EXTRA_SELECTED_URL)
                if (!url.isNullOrBlank()) openUrlInNewTab(url)
            }
        }
        voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    addressBar.setText(spoken)
                    submitAddressBar()
                }
            }
        }
        micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchVoiceRecognizer()
            } else {
                Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
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

            trackChromeFocus(card, closeButton)

            tabBarContainer.addView(card)
        }
    }

    private fun switchTab(tabId: String) {
        tabManager.switchToTab(tabId)
        val tab = tabManager.activeTab()
        addressBar.setText(tab?.browserView?.currentUrl ?: "")
        renderTabBar()
        updateBookmarkIcon()
    }

    private fun closeTab(tabId: String) {
        tabManager.closeTab(tabId)
        val tab = tabManager.activeTab()
        addressBar.setText(tab?.browserView?.currentUrl ?: "")
        renderTabBar()
        updateBookmarkIcon()
    }

    private fun onNewTabRequested() {
        tabManager.openNewTab()
        renderTabBar()
        updateBookmarkIcon()
        addressBar.requestFocus()
        addressBar.selectAll()
    }

    /** Opens [url] in a brand-new tab and switches to it — used by bookmarks/history. */
    private fun openUrlInNewTab(url: String) {
        tabManager.openNewTab(url)
        renderTabBar()
        addressBar.setText(url)
        updateBookmarkIcon()
    }

    private fun submitAddressBar() {
        val raw = addressBar.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val target = resolveAddressBarInput(raw)
        tabManager.activeTab()?.browserView?.loadUrl(target)
        tabManager.activeTab()?.browserView?.webView?.requestFocus()
    }

    /** Baseline browser behaviour: if the typed text doesn't look like a URL/domain,
     *  treat it as a search query instead of trying (and failing) to navigate to it. */
    private fun resolveAddressBarInput(input: String): String {
        val hasScheme = input.startsWith("http://") || input.startsWith("https://")
        val looksLikeDomain = !input.contains(" ") && input.contains(".") && !input.contains("..")
        return if (hasScheme || looksLikeDomain) {
            input
        } else {
            "https://www.google.com/search?q=" + Uri.encode(input)
        }
    }

    private fun isSelectKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---------------------------------------------------------------------
    // Bookmarks
    // ---------------------------------------------------------------------

    private fun toggleBookmarkForActiveTab() {
        val tab = tabManager.activeTab() ?: return
        val url = tab.browserView.currentUrl ?: return
        val title = tab.browserView.currentTitle ?: url
        val nowBookmarked = bookmarksManager.toggle(title, url)
        updateBookmarkIcon()
        Toast.makeText(
            this,
            if (nowBookmarked) R.string.add_bookmark else R.string.remove_bookmark,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateBookmarkIcon() {
        val url = tabManager.activeTab()?.browserView?.currentUrl
        val bookmarked = url != null && bookmarksManager.isBookmarked(url)
        starButton.setImageResource(if (bookmarked) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    }

    // ---------------------------------------------------------------------
    // Voice search
    // ---------------------------------------------------------------------

    private fun startVoiceSearch() {
        val recognizerAvailable = packageManager
            .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            .isNotEmpty()
        if (!recognizerAvailable) {
            Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceRecognizer()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchVoiceRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search_prompt))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognizer unavailable", e)
            Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------
    // BrowserView.Listener
    // ---------------------------------------------------------------------

    override fun onPageStarted(tab: BrowserView, url: String) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.visibility = View.VISIBLE
            addressBar.setText(url)
            updateBookmarkIcon()
        }
    }

    override fun onPageFinished(tab: BrowserView, url: String, title: String?) {
        if (tab.tabId == tabManager.activeTabId) {
            loadingProgress.visibility = View.GONE
            updateBookmarkIcon()
        }
        tabManager.updateTabTitle(tab.tabId, title, url)
        historyManager.recordVisit(title, url)
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

    override fun onDownloadStarted(tab: BrowserView, fileName: String) {
        Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
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

    override fun onRemoteSelectPressed(focusedView: View?, repeatCount: Int): Boolean {
        if (chromeHasFocus) return false
        val activeBrowser = tabManager.activeTab()?.browserView ?: return false
        val webView = activeBrowser.webView

        if (repeatCount == 0) {
            // Quick tap: hint the primary-video heuristic that the user deliberately
            // interacted with the page, then dispatch a real synthetic tap at the
            // cursor's position — this is what lets any element (link, button, custom
            // video player control) be clicked exactly as with a mouse/finger, not
            // just elements that happen to support HTML keyboard focus.
            longPressTriggered = false
            activeBrowser.notifyUserTappedVideoArea()
            cursorController.dispatchTap(webView)
        } else if (repeatCount == LONG_PRESS_REPEAT_THRESHOLD && !longPressTriggered) {
            // Held long enough: check what's under the cursor and, if it's a link,
            // open it in a new tab instead of just clicking it.
            longPressTriggered = true
            cursorController.checkLinkUnderCursor(webView) { link ->
                if (!link.isNullOrBlank()) {
                    openUrlInNewTab(link)
                    Toast.makeText(this, R.string.link_opened_new_tab, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    override fun onRemoteDirectionPressed(keyCode: Int, repeatCount: Int, focusedView: View?): Boolean {
        if (chromeHasFocus) {
            // Defer to normal Android focus-navigation between tab bar / address bar /
            // top buttons / mini player.
            return false
        }
        // A quick press moves the cursor; holding the direction scrolls the page via
        // a synthetic swipe. If the cursor is already pinned at an edge on a quick
        // press, this returns false so normal Android focus-navigation can take over
        // (e.g. moving up out of the page to the address bar/tab bar).
        val webView = tabManager.activeTab()?.browserView?.webView
        return cursorController.handleDirectionKey(keyCode, repeatCount, webView)
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
