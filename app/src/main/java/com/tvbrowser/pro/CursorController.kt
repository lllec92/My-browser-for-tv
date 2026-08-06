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
 * - Every D-pad press moves the cursor a small, precise step, including while the key is
 *   held (auto-repeat) — holding a direction keeps moving the cursor smoothly.
 * - Scrolling is edge-triggered, not hold-triggered: once the cursor is pinned against the
 *   very top or bottom of the *screen* (it can't move any further that way), further presses
 *   in that direction scroll the page via a synthetic swipe instead.
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
        private const val STEP_DP = 26f
        private const val SWIPE_DISTANCE_DP = 260f
        private const val SWIPE_STEPS = 8
        private const val SWIPE_STEP_DELAY_MS = 12L
        private const val SWIPE_THROTTLE_MS = 220L
        private const val TAP_UP_DELAY_MS = 60L
        private const val LONG_PRESS_CHECK_DELAY_MS = 90L
    }

    private val density = container.resources.displayMetrics.density
    private val stepPx = STEP_DP * density
    private val swipeDistancePx = SWIPE_DISTANCE_DP * density

    private var x = 0f
    private var y = 0f
    private var initialized = false
    private var lastSwipeDispatchTime = 0L
    private val handler = Handler(Looper.getMainLooper())

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
        val cursorWidth = cursorView.width.takeIf { it > 0 } ?: (STEP_DP * density).toInt()
        val cursorHeight = cursorView.height.takeIf { it > 0 } ?: (STEP_DP * density).toInt()
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
    private fun contentLocalX(): Float {
        val offsetX = contentOffsetX()
        return (hotspotX() - offsetX).coerceIn(0f, contentView.width.toFloat())
    }

    private fun contentLocalY(): Float {
        val offsetY = contentOffsetY()
        return (hotspotY() - offsetY).coerceIn(0f, contentView.height.toFloat())
    }

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
     * Handles a D-pad directional key. Always tries to move the cursor first — this is
     * what makes holding a direction move the cursor smoothly rather than immediately
     * scrolling. Only once the cursor is pinned against the corresponding edge of the
     * *screen* (top/bottom/left/right) does the same key press instead scroll the page,
     * via a synthetic swipe dispatched into [webView].
     */
    fun handleDirectionKey(keyCode: Int, webView: WebView?): Boolean {
        ensureInitialPosition()

        val moved = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveBy(0f, -stepPx)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0f, stepPx)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-stepPx, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(stepPx, 0f)
            else -> return false
        }

        if (moved) return true

        // Pinned at the screen edge: scroll instead, throttled so repeated key-repeat
        // events (which fire every ~50-100ms while held) don't overlap swipe gestures.
        if (webView != null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSwipeDispatchTime >= SWIPE_THROTTLE_MS) {
                lastSwipeDispatchTime = now
                dispatchSwipe(webView, keyCode)
            }
        }
        return true
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

    /**
     * Dispatches a synthetic finger-swipe gesture centred on the cursor's translated
     * position within [webView], in the direction implied by [keyCode]. DPAD_DOWN means
     * "show me what's below", i.e. the page content scrolls upward on screen — which
     * corresponds to the finger swiping from low to high on the screen (and vice-versa),
     * same as a real touchscreen scroll gesture.
     */
    private fun dispatchSwipe(webView: WebView, keyCode: Int) {
        val centerX = contentLocalX()
        val centerY = contentLocalY()
        val half = swipeDistancePx / 2f
        val maxY = contentView.height.toFloat()
        val maxX = contentView.width.toFloat()

        // (startX, startY, endX, endY), clamped into the WebView's own bounds.
        val coords: List<Float> = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> listOf(
                centerX, (centerY + half).coerceIn(0f, maxY),
                centerX, (centerY - half).coerceIn(0f, maxY)
            )
            KeyEvent.KEYCODE_DPAD_UP -> listOf(
                centerX, (centerY - half).coerceIn(0f, maxY),
                centerX, (centerY + half).coerceIn(0f, maxY)
            )
            KeyEvent.KEYCODE_DPAD_RIGHT -> listOf(
                (centerX + half).coerceIn(0f, maxX), centerY,
                (centerX - half).coerceIn(0f, maxX), centerY
            )
            KeyEvent.KEYCODE_DPAD_LEFT -> listOf(
                (centerX - half).coerceIn(0f, maxX), centerY,
                (centerX + half).coerceIn(0f, maxX), centerY
            )
            else -> return
        }
        val startX = coords[0]
        val startY = coords[1]
        val endX = coords[2]
        val endY = coords[3]

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        for (i in 1..SWIPE_STEPS) {
            val fraction = i.toFloat() / SWIPE_STEPS
            val moveX = startX + (endX - startX) * fraction
            val moveY = startY + (endY - startY) * fraction
            val isLast = i == SWIPE_STEPS
            handler.postDelayed({
                val stepTime = SystemClock.uptimeMillis()
                val action = if (isLast) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val event = MotionEvent.obtain(downTime, stepTime, action, moveX, moveY, 0)
                webView.dispatchTouchEvent(event)
                event.recycle()
            }, i * SWIPE_STEP_DELAY_MS)
        }
    }
}
