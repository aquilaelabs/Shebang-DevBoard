package dev.shebang.devboard.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Root of the IME's input view: the strip-and-keys column with the popup overlay exactly on top of it.
 *
 * A plain FrameLayout would let a MATCH_PARENT overlay expand to the whole available height and push the
 * column to the top of the screen whenever the popup appears, so the overlay is measured to the column's
 * size instead. Bottom padding (the navigation bar inset) is kept below both.
 */
@SuppressLint("ViewConstructor") // Built in code by the service, never inflated from XML.
class ImeRootView(context: Context, private val column: View, private val overlay: View) : FrameLayout(context) {
    init {
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = width - paddingLeft - paddingRight
        column.measure(
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val columnHeight = column.measuredHeight
        overlay.measure(
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(columnHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, columnHeight + paddingTop + paddingBottom)
    }
}
