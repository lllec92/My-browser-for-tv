package com.tvbrowser.pro

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
 * Drives an on-screen cursor that the person moves with the D-pad while a WebView has focus,
 * and turns cursor actions into real synthetic touch events dispatched straight into the
 * WebView. This is what makes every element on a page — links, buttons, custom video-player
 * controls, sliders — reachable and clickable exactly as with a mouse or finger, rather than
 * only the subset of elements that happen to support HTML/DOM keyboard focus.
 *
 * Behaviour:
 * - A quick D-pad press (repeatCount == 0) moves the cursor a small, precise step.
 * - Holding a direction (key auto-repeat, repeatCount > 0) instead performs a synthetic
 *   swipe gesture, scrolling the page the same way a real finger-swipe would.
 * - OK/Select dispatches a synthetic tap (ACTION_DOWN + ACTION_UP) at the cursor's position.
 * - If the cursor is already pinned against an edge of the container and can't move any
 *   further in the pressed direction, the key press is reported as NOT consumed, so normal
 *   Android focus-navigation takes over — this is how the person "escapes" cursor mode to
 *   reach the tab bar, address bar, or mini player above/around the page area.
 */
class CursorController(
    private val container: FrameLayout,
    private val cursorView: ImageView
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
            // Layout not measured yet; try again once it is.
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

    /** Cursor's hotspot (its visual center) in [container]'s local coordinate space —
     *  this lines up exactly with the active WebView's own coordinate space, since the
     *  WebView fills [container] edge-to-edge with no offset. */
    private fun hotspotX(): Float = x + cursorView.width / 2f
    private fun hotspotY(): Float = y + cursorView.height / 2f

    /** Returns true if the cursor actually moved (i.e. it wasn't already pinned at an edge). */
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
     * Handles a D-pad directional key while a WebView has focus.
     * Returns false (not consumed) when the cursor is pinned at an edge on a quick
     * press, so the caller's normal focus-navigation fallback can move focus to a
     * neighbouring widget (tab bar, address bar, mini player, etc).
     */
    fun handleDirectionKey(keyCode: Int, repeatCount: Int, webView: WebView?): Boolean {
        ensureInitialPosition()

        if (repeatCount == 0) {
            val moved = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveBy(0f, -stepPx)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0f, stepPx)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-stepPx, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(stepPx, 0f)
                else -> false
            }
            return moved
        }

        // Direction is being held: scroll the page via a synthetic swipe instead of
        // continuing to nudge the cursor pixel by pixel.
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
        val tapX = hotspotX()
        val tapY = hotspotY()
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
        val x = hotspotX()
        val y = hotspotY()
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
     * Dispatches a synthetic finger-swipe gesture centred on the cursor, in the direction
     * implied by [keyCode]. DPAD_DOWN means "show me what's below", i.e. the page content
     * scrolls upward on screen — which corresponds to the finger swiping from low to high
     * on the screen (and vice-versa), same as a real touchscreen scroll gesture.
     */
    private fun dispatchSwipe(webView: WebView, keyCode: Int) {
        val centerX = hotspotX()
        val centerY = hotspotY()
        val half = swipeDistancePx / 2f

        // (startX, startY, endX, endY)
        val coords: List<Float> = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> listOf(centerX, centerY + half, centerX, centerY - half)
            KeyEvent.KEYCODE_DPAD_UP -> listOf(centerX, centerY - half, centerX, centerY + half)
            KeyEvent.KEYCODE_DPAD_RIGHT -> listOf(centerX + half, centerY, centerX - half, centerY)
            KeyEvent.KEYCODE_DPAD_LEFT -> listOf(centerX - half, centerY, centerX + half, centerY)
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
