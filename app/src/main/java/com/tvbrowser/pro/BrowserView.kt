package com.tvbrowser.pro

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * A single browser tab: wraps a WebView configured for JS/DOM storage/autoplay/fullscreen,
 * applies the ad-block filter from [VideoInterceptor] to network requests, and injects the
 * primary-video detection script so the host Activity can decide when to hand playback off
 * to Media3 ExoPlayer.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class BrowserView(
    context: Context,
    private val prefs: SharedPreferences,
    val tabId: String
) : FrameLayout(context) {

    companion object {
        private const val TAG = "BrowserView"
        // Ignore candidates shorter than this — almost always a preroll ad unit,
        // unless nothing better ever appears.
        private const val MIN_CONFIDENT_DURATION_SECONDS = 60.0
    }

    interface Listener {
        fun onPageStarted(tab: BrowserView, url: String)
        fun onPageFinished(tab: BrowserView, url: String, title: String?)
        fun onProgressChanged(tab: BrowserView, progress: Int)
        fun onPrimaryVideoReady(tab: BrowserView, videoUrl: String)
        fun onDirectStreamRequested(tab: BrowserView, videoUrl: String)
        fun onReceivedError(tab: BrowserView, description: String?)
        fun onDownloadStarted(tab: BrowserView, fileName: String)
    }

    var listener: Listener? = null
    var currentUrl: String? = null
        private set
    var currentTitle: String? = null
        private set

    private var bestCandidateUrl: String? = null
    private var bestCandidateScore: Double = -1.0
    private var userTappedVideoArea = false

    val webView: WebView = WebView(context)

    /** The WebView's own default User-Agent, captured before any override, so we can
     *  restore it exactly when "Desktop mode" is turned off. Must be read before
     *  setupWebView() (called from init{} below) applies any override. */
    private val defaultUserAgent: String = webView.settings.userAgentString

    init {
        setupWebView()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            // Page zoom, driven by our own zoom in/out buttons rather than the
            // built-in on-screen +/- widget (which needs touch to use).
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        applyUserAgentSetting()

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.addJavascriptInterface(VideoDetectorBridge(), "AndroidVideoDetector")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            startDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                if (VideoInterceptor.isAdBlockEnabled(prefs) && VideoInterceptor.isAdUrl(url)) {
                    Log.d(TAG, "Blocked ad request: $url")
                    return VideoInterceptor.blockedResponse()
                }

                if (VideoInterceptor.isDirectVideoUrl(url)) {
                    Log.d(TAG, "Direct stream URL observed: $url")
                    post { listener?.onDirectStreamRequested(this@BrowserView, url) }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                applyUserAgentSetting()
                currentUrl = url
                bestCandidateUrl = null
                bestCandidateScore = -1.0
                userTappedVideoArea = false
                listener?.onPageStarted(this@BrowserView, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentUrl = url
                currentTitle = view.title
                if (VideoInterceptor.isHeuristicEnabled(prefs)) {
                    view.evaluateJavascript(VideoInterceptor.PRIMARY_VIDEO_DETECTION_JS, null)
                }
                listener?.onPageFinished(this@BrowserView, url, view.title)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    Log.e(TAG, "WebView error: ${error.description} (${error.errorCode}) url=${request.url}")
                    listener?.onReceivedError(this@BrowserView, error.description?.toString())
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                listener?.onProgressChanged(this@BrowserView, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                currentTitle = title
            }
        }
    }

    /** JS -> Kotlin bridge used by [VideoInterceptor.PRIMARY_VIDEO_DETECTION_JS]. */
    private inner class VideoDetectorBridge {
        @JavascriptInterface
        fun onPrimaryVideoCandidate(url: String, durationSeconds: Double, width: Double, height: Double) {
            val area = (width.coerceAtLeast(0.0)) * (height.coerceAtLeast(0.0))
            val durationWeight = if (durationSeconds > 60) durationSeconds else durationSeconds * 0.05
            val score = area * (1 + durationWeight)

            // Requirement 4c: don't blindly open ExoPlayer on the first stream seen.
            // Wait until either:
            //  - we have a confident long-form candidate (duration > 60s), or
            //  - the user has explicitly tapped/clicked in the video area.
            val confident = durationSeconds >= MIN_CONFIDENT_DURATION_SECONDS || userTappedVideoArea

            if (score > bestCandidateScore) {
                bestCandidateScore = score
                bestCandidateUrl = url
            }

            if (confident && bestCandidateUrl != null) {
                val finalUrl = bestCandidateUrl!!
                post { listener?.onPrimaryVideoReady(this@BrowserView, finalUrl) }
            }
        }
    }

    /** Called by the host Activity when the user presses OK while the video area has focus. */
    fun notifyUserTappedVideoArea() {
        userTappedVideoArea = true
        bestCandidateUrl?.let { url ->
            listener?.onPrimaryVideoReady(this@BrowserView, url)
        }
    }

    /** Applies the Desktop/Mobile User-Agent based on the current setting. Safe to call
     *  repeatedly (e.g. on every page load) so a setting change takes effect on the very
     *  next navigation without needing to recreate the tab. */
    fun applyUserAgentSetting() {
        webView.settings.userAgentString = if (VideoInterceptor.isDesktopModeEnabled(prefs)) {
            VideoInterceptor.DESKTOP_USER_AGENT
        } else {
            defaultUserAgent
        }
    }

    private fun startDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String?,
        contentLength: Long
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverRoaming(true)
            }
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Log.d(TAG, "Download started: $fileName ($url)")
            listener?.onDownloadStarted(this@BrowserView, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download for $url", e)
        }
    }

    /** Zooms the page in/out. Backed by the WebView's built-in zoom engine (enabled via
     *  setSupportZoom/builtInZoomControls above), just triggered by our own buttons
     *  instead of the touch-only on-screen zoom widget. */
    fun zoomIn() {
        webView.zoomIn()
    }

    fun zoomOut() {
        webView.zoomOut()
    }

    fun loadUrl(url: String) {
        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else url
        webView.loadUrl(finalUrl)
    }

    fun canGoBack(): Boolean = webView.canGoBack()
    fun goBack() = webView.goBack()
    fun reload() = webView.reload()

    fun attachedView(): View = this

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }
}
