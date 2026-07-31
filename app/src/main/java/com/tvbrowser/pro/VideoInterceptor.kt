package com.tvbrowser.pro

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Handles two related but distinct jobs:
 *
 * 1. A lightweight adblock-style filter for [android.webkit.WebViewClient.shouldInterceptRequest],
 *    based on a static list of well-known ad/tracking domains.
 * 2. Detection of direct playable video URLs (mp4/m3u8/mpd/webm) so the caller can decide
 *    whether to hand a request off to Media3 ExoPlayer instead of the WebView.
 *
 * Both behaviours are individually toggleable at runtime via [AdBlockSettings] so they can be
 * wired up to the Settings screen switches.
 */
object VideoInterceptor {

    /** Runtime-configurable behaviour flags, backed by SharedPreferences in SettingsActivity. */
    object AdBlockSettings {
        const val PREFS_NAME = "tv_browser_settings"
        const val KEY_ADBLOCK_ENABLED = "adblock_enabled"
        const val KEY_HEURISTIC_ENABLED = "primary_video_heuristic_enabled"
    }

    // A compact, well-known list of ad/tracking domains. Not exhaustive, but covers the
    // large majority of preroll/midroll/display ad networks encountered on typical
    // online-cinema style sites.
    private val AD_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adform.net",
        "adsrvr.org",
        "advertising.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "pubmatic.com",
        "rubiconproject.com",
        "casale.media",
        "openx.net",
        "smartadserver.com",
        "yandex.ru/ads",
        "an.yandex.ru",
        "mgid.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "adcash.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "revcontent.com",
        "media.net",
        "moatads.com",
        "scorecardresearch.com",
        "adition.com",
        "adroll.com",
        "bidswitch.net",
        "contextweb.com",
        "quantserve.com",
        "yieldmo.com"
    )

    private val DIRECT_VIDEO_EXTENSIONS = listOf(".mp4", ".m3u8", ".mpd", ".webm")

    private val EMPTY_RESPONSE = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0))
    )

    fun isAdBlockEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(AdBlockSettings.KEY_ADBLOCK_ENABLED, true)

    fun isHeuristicEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(AdBlockSettings.KEY_HEURISTIC_ENABLED, true)

    /** Returns true if [url]'s host matches (or is a subdomain of) a known ad domain. */
    fun isAdUrl(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        } catch (e: Exception) {
            return false
        }
        return AD_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    /** A ready-to-return empty response used to effectively block a request. */
    fun blockedResponse(): WebResourceResponse = EMPTY_RESPONSE

    /** True if the URL looks like a direct playable stream rather than an HTML page. */
    fun isDirectVideoUrl(url: String): Boolean {
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.ROOT) ?: return false
        } catch (e: Exception) {
            return false
        }
        return DIRECT_VIDEO_EXTENSIONS.any { ext -> path.endsWith(ext) }
    }

    /**
     * JavaScript injected into every page (via WebView.evaluateJavascript) that scans the DOM
     * for <video> elements, scores each by (duration * on-screen area) as a proxy for "this is
     * the main content player, not a tiny preroll unit", and reports the best candidate back to
     * the app through the "AndroidVideoDetector" JavaScript interface.
     *
     * This deliberately does NOT auto-play anything in ExoPlayer by itself — it only reports
     * candidates. The decision of whether/when to actually switch to ExoPlayer is made natively
     * in [BrowserView], which additionally waits for a duration signal or an explicit tap on the
     * video area before switching, as required.
     */
    val PRIMARY_VIDEO_DETECTION_JS = """
        (function() {
            function scoreVideo(v) {
                var rect = v.getBoundingClientRect();
                var area = Math.max(0, rect.width) * Math.max(0, rect.height);
                var duration = (isFinite(v.duration) && v.duration > 0) ? v.duration : 0;
                // Videos under 60s are very likely preroll/midroll ad units; heavily
                // de-prioritise them relative to full-length content.
                var durationWeight = duration > 60 ? duration : duration * 0.05;
                return area * (1 + durationWeight);
            }
            function collect() {
                var videos = document.querySelectorAll('video');
                var best = null;
                var bestScore = -1;
                var srcCandidate = null;
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    var s = scoreVideo(v);
                    if (s > bestScore) {
                        bestScore = s;
                        best = v;
                    }
                }
                if (best) {
                    srcCandidate = best.currentSrc || best.src;
                    if ((!srcCandidate || srcCandidate.length === 0) && best.querySelector('source')) {
                        srcCandidate = best.querySelector('source').src;
                    }
                    if (srcCandidate) {
                        window.AndroidVideoDetector.onPrimaryVideoCandidate(
                            srcCandidate,
                            best.duration || 0,
                            best.getBoundingClientRect().width,
                            best.getBoundingClientRect().height
                        );
                    }
                }
            }
            collect();
            document.addEventListener('loadedmetadata', collect, true);
            document.addEventListener('click', function() { setTimeout(collect, 500); }, true);
            setInterval(collect, 2000);
        })();
    """.trimIndent()
}
