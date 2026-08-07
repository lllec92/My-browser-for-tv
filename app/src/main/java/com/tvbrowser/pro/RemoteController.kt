package com.tvbrowser.pro

import android.view.KeyEvent
import android.view.View

/**
 * Centralises remote-control (D-pad) key handling so Activities can delegate
 * dispatchKeyEvent/onKeyDown to a single place.
 *
 * IMPORTANT: none of the callback methods below are named the same as any
 * Activity/Fragment/View lifecycle or callback method (onBackPressed,
 * onKeyDown, onResume, etc). This avoids a Kotlin override/return-type clash
 * when an Activity implements this interface directly, which was the root
 * cause of a build failure in the previous version of this app.
 */
class RemoteController(private val callback: Callback) {

    interface Callback {
        /** D-pad BACK was pressed. Return true if it was consumed. */
        fun onRemoteBackPressed(): Boolean

        /** D-pad center / OK / Enter was pressed. [repeatCount] is 0 for the initial
         *  press and increases while held — callers use this to distinguish a quick
         *  tap (click) from a long press (e.g. open link in new tab). Return true if
         *  consumed. */
        fun onRemoteSelectPressed(focusedView: View?, repeatCount: Int): Boolean

        /** D-pad directional press (UP/DOWN/LEFT/RIGHT). [repeatCount] is 0 for the
         *  initial press and increases while the key is held down (auto-repeat) —
         *  callers use this to move a cursor on a quick tap but scroll continuously
         *  while the direction is held. Return true if consumed. */
        fun onRemoteDirectionPressed(keyCode: Int, repeatCount: Int, focusedView: View?): Boolean

        /** D-pad directional key was released (ACTION_UP) — used to stop continuous
         *  cursor movement / page scrolling precisely when the person lets go,
         *  instead of only reacting to auto-repeat while held. */
        fun onRemoteDirectionReleased(keyCode: Int)

        /** Media play/pause remote button. Return true if consumed. */
        fun onRemotePlayPausePressed(): Boolean
    }

    /**
     * Call this from Activity.dispatchKeyEvent or onKeyDown.
     * Returns true if the event was handled and should not propagate further.
     */
    fun handleKeyEvent(event: KeyEvent, focusedView: View?): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    callback.onRemoteDirectionReleased(event.keyCode)
                    true
                }
                else -> false
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> callback.onRemoteBackPressed()

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> callback.onRemoteSelectPressed(focusedView, event.repeatCount)

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> callback.onRemoteDirectionPressed(event.keyCode, event.repeatCount, focusedView)

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> callback.onRemotePlayPausePressed()

            else -> false
        }
    }
}
