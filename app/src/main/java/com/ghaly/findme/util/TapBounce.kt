package com.ghaly.findme.util

import android.view.MotionEvent
import android.view.View

/**
 * A tiny, dependency-free "press" animation: the view dips to 90% scale the
 * moment a finger touches it and springs back on release/cancel. Applied to
 * the top bar's quick-access icons (and the bottom tab bar) so the newly
 * promoted tools ("all the buttons in the top nav bar… make this dynamic")
 * feel responsive rather than just appearing — purely cosmetic, never
 * consumes the touch, so the view's existing OnClickListener still fires
 * exactly as before.
 */
fun View.withTapBounce() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }
        false
    }
}
