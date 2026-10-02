package dev.shebang.devboard.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.shebang.devboard.ime.ClipboardHistory

/**
 * The clipboard panel: shown in place of the keys when the terminal bar's Clipboard item is tapped. The text
 * copied lately, newest first and pinned first: tap a copy to paste it, pin it to keep it, or remove it;
 * Clear forgets everything not pinned. The ABC, space and backspace row along the bottom.
 */
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    interface Listener : PanelKeyListener {
        fun onClipPaste(text: String)
        fun onClipPinned(text: String, pinned: Boolean)
        fun onClipRemove(text: String)
        fun onClipClear()
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private var theme: KeyboardTheme? = null
    private val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var items: List<ClipboardHistory.Item> = emptyList()

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, (48 * density).toInt()))
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        buildHeader()
        rebuild()
        PanelKeys.fill(bottom, t) { listener }
    }

    /** Shows [items] (newest first, pinned first) from the top. */
    fun show(items: List<ClipboardHistory.Item>) {
        this.items = items
        rebuild()
        scroll.scrollTo(0, 0)
    }

    private fun buildHeader() {
        val t = theme ?: return
        header.removeAllViews()
        val title = TextView(context).apply {
            text = "Clipboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(t.keyTextSecondary)
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val clear = chip("Clear", t.keyFunctional, t.stripText).apply {
            contentDescription = "Clear the clipboard history (pinned copies stay)"
            setOnClickListener { listener?.onClipClear() }
        }
        header.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, (32 * density).toInt()).apply { rightMargin = (8 * density).toInt() })
    }

    private fun rebuild() {
        val t = theme ?: return
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Text you copy shows here, kept on this phone for 24 hours. Pin a copy to keep it."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(t.keyTextSecondary)
                gravity = Gravity.CENTER
                setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), 0)
            })
            return
        }
        val gap = (4 * density).toInt()
        for (item in items) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(t.key)
                isClickable = true
                setOnClickListener { listener?.onClipPaste(item.text) }
                contentDescription = "Paste: ${item.text.take(60)}"
            }
            val text = TextView(context).apply {
                text = item.text.replace(Regex("\\s+"), " ").trim()
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(t.keyText)
                setPadding((12 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val pin = chip(if (item.pinned) "Pinned" else "Pin", if (item.pinned) t.accent else t.keyFunctional, if (item.pinned) t.onAccent else t.stripText).apply {
                contentDescription = if (item.pinned) "Unpin" else "Pin"
                setOnClickListener { listener?.onClipPinned(item.text, !item.pinned) }
            }
            row.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, (32 * density).toInt()).apply { rightMargin = gap })
            val remove = chip("✕", t.keyFunctional, t.stripText).apply {
                contentDescription = "Remove"
                setOnClickListener { listener?.onClipRemove(item.text) }
            }
            row.addView(remove, LayoutParams((36 * density).toInt(), (32 * density).toInt()).apply { rightMargin = (8 * density).toInt() })
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(gap * 2, gap, gap * 2, gap) })
        }
    }

    private fun chip(label: String, fill: Int, fg: Int) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(fg)
        background = rounded(fill)
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 8 * density
    }
}
