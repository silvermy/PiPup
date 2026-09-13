package nl.rogro82.pipup

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * Root view of the overlay window.
 *
 * Exists so a popup can see remote key events at all: a plain FrameLayout never
 * receives them, and the window itself must drop FLAG_NOT_FOCUSABLE. That is
 * also why key handling is opt-in per notification -- a focused overlay stops
 * the remote reaching whatever app is playing underneath it.
 *
 * Every key that arrives is logged, which doubles as the way to discover what a
 * particular TV remote actually emits (they differ, and colour buttons often do
 * not reach apps at all).
 */
class OverlayView(context: Context) : FrameLayout(context) {

    /** Return true to consume the event. */
    var onKey: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(
                LOG_TAG,
                "key ${KeyEvent.keyCodeToString(event.keyCode)} (code ${event.keyCode})"
            )
        }
        if (onKey?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val LOG_TAG = "PiPupOverlay"
    }
}
