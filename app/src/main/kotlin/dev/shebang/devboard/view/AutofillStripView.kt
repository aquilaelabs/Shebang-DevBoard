package dev.shebang.devboard.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Autofill suggestions from the user's password manager or autofill service (Android 11+), as the chips the
 * service drew in the keyboard's style. The keyboard only places them: what they say and what tapping one
 * fills in are the service's, and nothing about them is read or learned.
 */
class AutofillStripView(context: Context) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val gap = (6 * resources.displayMetrics.density).toInt()

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun show(chips: List<View>) {
        row.removeAllViews()
        for (c in chips) {
            // The platform sizes each chip to what the service drew; a chip has no content size of its own to wrap.
            val lp = c.layoutParams?.let { LinearLayout.LayoutParams(it.width, it.height) }
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = gap
            row.addView(c, lp)
        }
        scrollTo(0, 0)
    }

    fun clear() = row.removeAllViews()
}
