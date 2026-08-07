package com.tvbrowser.pro

import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Drives an on-screen cursor that covers the ENTIRE screen (tab bar, address bar, page
 * content, mini player — everything), moved by the D-pad, and turns cursor actions into
 * real synthetic touch events dispatched into the active WebView.
 *
 * Behaviour:
 * - Holding a direction runs a single continuous ~60fps loop ([startDirectionHold] /
 *   [stopDirectionHold], driven by real key-down/key-up, not just auto-repeat) that nudges
 *   the cursor a few pixels every tick — this is what makes movement look and feel smooth
 *   rather than jumping in big discrete steps.
 * - The instant the cursor is pinned against an edge of the *screen* and can't move any
 *   further that way, the same loop seamlessly switches to dragging the page instead: a
 *   single continuous touch gesture (one ACTION_DOWN, many ACTION_MOVE ticks, one
 *   ACTION_UP exactly when the key is released) — a real drag, not a series of separate
 *   swipe bursts, so the scroll feels continuous and natural.
 * - OK/Select dispatches a synthetic tap at the cursor's position, translated into the
 *   WebView's own local coordinate space (the WebView normally sits below the tab bar and
 *   address bar, so its origin is offset from the screen's).
 *
 * [container] is the full-screen root overlay the cursor moves within and is drawn in.
 * [contentView] is the WebView-bearing area (used only to translate cursor position into
 * WebView-local coordinates for touch dispatch — the cursor's own movement is not confined
 * to it).
 */
class CursorController(
    private val container: FrameLayout,
    private val cursorView: ImageView,
    private val contentView: View
) {
    companion object {
        // Per-tick movement, run at ~60fps — small enough to look smooth, fast enough
        // to feel responsive (roughly 550dp/sec of on-screen travel).
        private const val CURSOR_TICK_DP = 9f
        private const val SCROLL_TICK_DP = 11f
        private const val TICK_INTERVAL_MS = 16L
        private const val TAP_UP_DELAY_MS = 60L
        private const val LONG_PRESS_CHECK_DELAY_MS = 90L
    }

    private val density = container.resources.displayMetrics.density
    private val cursorTickPx = CURSOR_TICK_DP * density
    private val scrollTickPx = SCROLL_TICK_DP * density

    private var x = 0f
    private var y = 0f
    private var initialized = false
    private val handler = Handler(Looper.getMainLooper())

    // --- Continuous hold-to-move/scroll state ---
    private var holdRunnable: Runnable? = null
    private var holdKeyCode: Int = 0
    private var isScrolling = false
    private var scrollWebView: WebView? = null
    private var scrollDownTime: Long = 0L
    private var scrollCurrentX: Float = 0f
    private var scrollCurrentY: Float = 0f

    fun show() {
        ensureInitialPosition()
        cursorView.visibility = View.VISIBLE
    }

    fun hide() {
        cursorView.visibility = View.GONE
    }

    fun isVisible(): Boolean = cursorView.visibility == View.VISIBLE

    private fun ensureInitialPosition() {
        if (initialized) return
        val width = container.width
        val height = container.height
        if (width == 0 || height == 0) {
            container.post { ensureInitialPosition() }
            return
        }
        x = width / 2f
        y = height / 2f
        initialized = true
        applyPosition()
    }

    private fun applyPosition() {
        val cursorWidth = cursorView.width.takeIf { it > 0 } ?: (CURSOR_TICK_DP * density).toInt()
        val cursorHeight = cursorView.height.takeIf { it > 0 } ?: (CURSOR_TICK_DP * density).toInt()
        val maxX = (container.width - cursorWidth).coerceAtLeast(0)
        val maxY = (container.height - cursorHeight).coerceAtLeast(0)
        x = x.coerceIn(0f, maxX.toFloat())
        y = y.coerceIn(0f, maxY.toFloat())
        cursorView.x = x
        cursorView.y = y
    }

    /** Cursor's hotspot (its visual center), in [container]'s (the whole screen's) local
     *  coordinate space. */
    private fun hotspotX(): Float = x + cursorView.width / 2f
    private fun hotspotY(): Float = y + cursorView.height / 2f

    /** The cursor's absolute on-screen position — used for hit-testing against other
     *  views (e.g. the mini player, tab bar buttons) via View.getGlobalVisibleRect. */
    fun screenPosition(): Point {
        val loc = IntArray(2)
        container.getLocationOnScreen(loc)
        return Point((loc[0] + hotspotX()).toInt(), (loc[1] + hotspotY()).toInt())
    }

    /** Translates the cursor's position from screen-wide coordinates into [contentView]'s
     *  (the WebView area's) own local coordinate space, clamped to its bounds — this is
     *  what touch events dispatched into the WebView need. */
    private fun contentLocalX(): Float = (hotspotX() - contentOffsetX()).coerceIn(0f, contentView.width.toFloat())
    private fun contentLocalY(): Float = (hotspotY() - contentOffsetY()).coerceIn(0f, contentView.height.toFloat())

    private fun contentOffsetX(): Float {
        val containerLoc = IntArray(2)
        val contentLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)
        contentView.getLocationOnScreen(contentLoc)
        return (contentLoc[0] - containerLoc[0]).toFloat()
    }

    private fun contentOffsetY(): Float {
        val containerLoc = IntArray(2)
        val contentLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)
        contentView.getLocationOnScreen(contentLoc)
        return (contentLoc[1] - containerLoc[1]).toFloat()
    }

    /** Returns true if the cursor actually moved (i.e. it wasn't already pinned at the
     *  edge of the screen). */
    private fun moveBy(dx: Float, dy: Float): Boolean {
        ensureInitialPosition()
        val oldX = x
        val oldY = y
        x += dx
        y += dy
        applyPosition()
        return x != oldX || y != oldY
    }

    /**
     * Starts (or continues) smoothly moving the cursor while [keyCode] is held. Safe to
     * call repeatedly for the same key (e.g. once per auto-repeat KeyEvent) — it's a
     * no-op if that direction's loop is already running. Call [stopDirectionHold] on
     * ACTION_UP to stop it.
     */
    fun startDirectionHold(keyCode: Int, webView: WebView?) {
        ensureInitialPosition()
        if (holdRunnable != null && holdKeyCode == keyCode) return
        stopDirectionHold()
        holdKeyCode = keyCode
        isScrolling = false

        val runnable = object : Runnable {
            override fun run() {
                if (!isScrolling) {
                    val moved = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> moveBy(0f, -cursorTickPx)
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0f, cursorTickPx)
                        KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-cursorTickPx, 0f)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(cursorTickPx, 0f)
                        else -> false
                    }
                    if (!moved && webView != null) {
                        beginScrollDrag(webView)
                    }
                } else {
                    continueScrollDrag(keyCode)
                }
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
        holdRunnable = runnable
        handler.post(runnable)
    }

    /** Stops any in-progress hold-to-move/scroll loop, cleanly ending a scroll drag
     *  (dispatching ACTION_UP) if one was active. */
    fun stopDirectionHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        if (isScrolling) {
            scrollWebView?.let { webView ->
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(scrollDownTime, upTime, MotionEvent.ACTION_UP, scrollCurrentX, scrollCurrentY, 0)
                webView.dispatchTouchEvent(up)
                up.recycle()
            }
        }
        isScrolling = false
        scrollWebView = null
    }

    private fun beginScrollDrag(webView: WebView) {
        isScrolling = true
        scrollWebView = webView
        scrollDownTime = SystemClock.uptimeMillis()
        scrollCurrentX = contentLocalX()
        scrollCurrentY = contentLocalY()
        val down = MotionEvent.obtain(scrollDownTime, scrollDownTime, MotionEvent.ACTION_DOWN, scrollCurrentX, scrollCurrentY, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()
    }

    /** DPAD_DOWN means "show me what's below", i.e. the page content scrolls upward on
     *  screen — which corresponds to the finger dragging from low to high (and
     *  vice-versa), same relationship a real touchscreen scroll relies on. */
    private fun continueScrollDrag(keyCode: Int) {
        val webView = scrollWebView ?: return
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to -scrollTickPx
            KeyEvent.KEYCODE_DPAD_UP -> 0f to scrollTickPx
            KeyEvent.KEYCODE_DPAD_RIGHT -> -scrollTickPx to 0f
            KeyEvent.KEYCODE_DPAD_LEFT -> scrollTickPx to 0f
            else -> 0f to 0f
        }
        scrollCurrentX += dx
        scrollCurrentY += dy
        val moveTime = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(scrollDownTime, moveTime, MotionEvent.ACTION_MOVE, scrollCurrentX, scrollCurrentY, 0)
        webView.dispatchTouchEvent(move)
        move.recycle()
    }

    /** Dispatches a synthetic tap (as a real finger/mouse click would be) at the
     *  cursor's current position into [webView]. */
    fun dispatchTap(webView: WebView) {
        val tapX = contentLocalX()
        val tapY = contentLocalY()
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        handler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, tapX, tapY, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, TAP_UP_DELAY_MS)
    }

    /**
     * Long-press support: touches down at the cursor's position so the WebView updates
     * its internal hit-test state for that point, reads back what's under the cursor a
     * moment later, then cancels the gesture (ACTION_CANCEL rather than ACTION_UP) so it
     * doesn't also trigger a normal click/navigation. [onResult] receives the link URL
     * if the cursor was over a link/image-link, or null otherwise.
     */
    fun checkLinkUnderCursor(webView: WebView, onResult: (String?) -> Unit) {
        val x = contentLocalX()
        val y = contentLocalY()
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        handler.postDelayed({
            val result = webView.hitTestResult
            val link = when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            }
            val cancelTime = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(downTime, cancelTime, MotionEvent.ACTION_CANCEL, x, y, 0)
            webView.dispatchTouchEvent(cancel)
            cancel.recycle()
            onResult(link)
        }, LONG_PRESS_CHECK_DELAY_MS)
    }
}
