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
        fun onClipPaste(item: ClipboardHistory.Item)
        fun onClipPinned(key: String, pinned: Boolean)
        fun onClipRemove(key: String)
        fun onClipClear()
    }

    var listener: Listener? = null
    /** Where a picture item's image is kept. */
    var imageFile: (ClipboardHistory.Item) -> java.io.File? = { null }
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
                text = "Text and pictures you copy show here, kept on this phone for 24 hours. Pin a copy to keep it."
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
                setOnClickListener { listener?.onClipPaste(item) }
                contentDescription = if (item.isImage) "Insert picture" else "Paste: ${item.text.take(60)}"
            }
            val thumb = if (item.isImage) imageFile(item)?.let { thumbnail(it) } else null
            if (thumb != null) {
                val iv = android.widget.ImageView(context).apply {
                    setImageBitmap(thumb)
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    adjustViewBounds = true
                    setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                }
                row.addView(iv, LayoutParams(0, (THUMB_DP * density).toInt(), 1f))
            } else {
                val text = TextView(context).apply {
                    text = if (item.isImage) "Picture" else item.text.replace(Regex("\\s+"), " ").trim()
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(t.keyText)
                    setPadding((12 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                }
                row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            val pin = chip(if (item.pinned) "Pinned" else "Pin", if (item.pinned) t.accent else t.keyFunctional, if (item.pinned) t.onAccent else t.stripText).apply {
                contentDescription = if (item.pinned) "Unpin" else "Pin"
                setOnClickListener { listener?.onClipPinned(item.key, !item.pinned) }
            }
            row.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, (32 * density).toInt()).apply { rightMargin = gap })
            val remove = chip("✕", t.keyFunctional, t.stripText).apply {
                contentDescription = "Remove"
                setOnClickListener { listener?.onClipRemove(item.key) }
            }
            row.addView(remove, LayoutParams((36 * density).toInt(), (32 * density).toInt()).apply { rightMargin = (8 * density).toInt() })
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(gap * 2, gap, gap * 2, gap) })
        }
    }

    /** A small copy of a picture, about [THUMB_DP] tall, decoded at a fraction of its size. */
    private fun thumbnail(file: java.io.File): android.graphics.Bitmap? = runCatching {
        val target = (THUMB_DP * density).toInt()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= target) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

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

    private companion object {
        const val THUMB_DP = 72
    }
}
