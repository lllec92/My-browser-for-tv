package com.tvbrowser.pro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    private lateinit var rootOverlay: FrameLayout
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

    private lateinit var bookmarksLauncher: ActivityResultLauncher<Intent>
    private lateinit var historyLauncher: ActivityResultLauncher<Intent>
    private lateinit var voiceSearchLauncher: ActivityResultLauncher<Intent>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootOverlay = findViewById(R.id.rootOverlay)
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
        // The cursor's container is the WHOLE-SCREEN root overlay (not just the page
        // area) so it renders above and can move over every part of the browser —
        // tab bar, address bar, mini player, everything. webviewStack is passed
        // separately purely so cursor positions can be translated into the
        // WebView's own local coordinate space for touch dispatch.
        cursorController = CursorController(rootOverlay, tvCursor, webviewStack)
        cursorController.show()

        tabManager = TabManager(this, webviewContainer, browserPrefs, this)
        tabManager.restoreOrCreateInitialTabs()
        renderTabBar()
        updateBookmarkIcon()

        registerActivityResultLaunchers()

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
            closeButton.setOnClickListener { closeTab(tab.id) }

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
     * Fullscreen is never entered automatically — the person clicks the mini player
     * themselves (see [expandMiniPlayer]) whenever they want it big.
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
    // Cursor-based hit-testing against the browser's own UI (tab bar, address
    // bar, top buttons, mini player) — this is what lets OK-clicking the mini
    // player with the cursor actually expand it to fullscreen, instead of the
    // click silently being swallowed as a tap on the page underneath.
    // ---------------------------------------------------------------------

    private fun clickTargetsUnderCursor(): List<View> {
        val targets = mutableListOf<View>(
            newTabButton, bookmarksButton, historyButton, settingsButton,
            starButton, addressBar, micButton, zoomOutButton, zoomInButton
        )
        if (miniPlayerContainer.visibility == View.VISIBLE) {
            targets.add(0, miniPlayerContainer)
        }
        for (i in 0 until tabBarContainer.childCount) {
            val card = tabBarContainer.getChildAt(i)
            targets.add(card)
            card.findViewById<View>(R.id.tabCloseButton)?.let { targets.add(it) }
        }
        return targets
    }

    /** Returns the topmost browser-UI view the cursor is currently over, or null if
     *  it's over the page content instead. */
    private fun viewUnderCursor(): View? {
        val cursorPoint = cursorController.screenPosition()
        val rect = Rect()
        for (view in clickTargetsUnderCursor()) {
            if (view.visibility != View.VISIBLE) continue
            view.getGlobalVisibleRect(rect)
            if (rect.contains(cursorPoint.x, cursorPoint.y)) return view
        }
        return null
    }

    private fun clickView(view: View) {
        view.performClick()
        if (view === addressBar) {
            addressBar.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------------------------------------------------------------
    // RemoteController.Callback
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A dialog, popup (e.g. a WebView <select> dropdown listing episodes), or the
        // IME currently has input focus — or the address bar is being actively edited
        // (so arrow keys should move the text caret, not the cursor). Let the system
        // route the key event normally instead of hijacking it for cursor/scroll.
        if (!window.decorView.hasWindowFocus() || currentFocus === addressBar) {
            return super.dispatchKeyEvent(event)
        }
        if (remoteController.handleKeyEvent(event, currentFocus)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRemoteBackPressed(): Boolean {
        if (miniPlayerContainer.visibility == View.VISIBLE && viewUnderCursor() === miniPlayerContainer) {
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
        val hitView = viewUnderCursor()
        if (hitView != null) {
            if (repeatCount == 0) {
                clickView(hitView)
            }
            return true
        }

        val activeBrowser = tabManager.activeTab()?.browserView ?: return false
        val webView = activeBrowser.webView

        if (repeatCount == 0) {
            // Quick tap over the page: hint the primary-video heuristic that the user
            // deliberately interacted with the page, then dispatch a real synthetic
            // tap at the cursor's position — this is what lets any element (link,
            // button, custom video player control) be clicked exactly as with a
            // mouse/finger, not just elements that happen to support HTML keyboard
            // focus.
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
        // The cursor always owns D-pad direction presses now: a press moves it, and
        // only once it's pinned against the screen edge does the same press scroll
        // the page instead (see CursorController.handleDirectionKey).
        val webView = tabManager.activeTab()?.browserView?.webView
        return cursorController.handleDirectionKey(keyCode, webView)
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
