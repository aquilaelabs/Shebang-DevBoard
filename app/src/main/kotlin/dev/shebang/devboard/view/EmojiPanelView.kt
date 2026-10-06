package dev.shebang.devboard.view

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import dev.shebang.devboard.R

/**
 * The emoji panel: shown in place of the keys when the terminal bar's Emoji item is tapped, the same size as
 * the keyboard. Category tabs along the top (Recent first), a scrolling grid of every emoji the phone's font
 * can draw, and ABC, space and backspace along the bottom. Emoji come from Unicode's list (assets/emoji).
 */
class EmojiPanelView(context: Context) : LinearLayout(context) {

    interface Listener : PanelKeyListener {
        fun onEmoji(emoji: String)
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private var theme: KeyboardTheme? = null
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = GridView(context)
    private val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }

    /** The groups in order (Recent first) with where each starts in [cells]. */
    private val groupNames = ArrayList<String>()
    private val groupStart = ArrayList<Int>()
    private var cells: List<String> = emptyList()
    private var all: List<Pair<String, List<String>>> = emptyList()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt())
                setOnClickListener { (tag as? String)?.let { e -> pick(e) } }
            }
            v.text = cells[position]
            v.tag = cells[position]
            v.contentDescription = cells[position]
            return v
        }
    }

    init {
        orientation = VERTICAL
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        grid.numColumns = COLUMNS
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.isVerticalScrollBarEnabled = false
        grid.overScrollMode = OVER_SCROLL_NEVER
        grid.selector = android.graphics.drawable.ColorDrawable(0)
        grid.adapter = adapter
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = markTab(groupAt(first))
        })
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, (48 * density).toInt()))
    }

    /** Reads the emoji list once; keeps only what the font can draw. Call off the main thread. */
    fun loadEmoji(): List<Pair<String, List<String>>> {
        val paint = Paint()
        val out = ArrayList<Pair<String, List<String>>>()
        var name: String? = null
        var list = ArrayList<String>()
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("= ")) {
                    name?.let { if (list.isNotEmpty()) out += it to list }
                    name = line.substring(2)
                    list = ArrayList()
                } else if (line.isNotEmpty()) {
                    val e = line.substringBefore('\t')
                    if (paint.hasGlyph(e)) list += e
                }
            }
        }
        name?.let { if (list.isNotEmpty()) out += it to list }
        return out
    }

    /** The groups to show, from [loadEmoji]. */
    fun setEmoji(groups: List<Pair<String, List<String>>>) {
        all = groups
        rebuild()
    }

    /** Shows the panel at its start, with the latest recent emoji. */
    fun open() {
        rebuild()
        grid.setSelection(0)
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        setBackgroundColor(t.background)
        buildTabs()
        buildBottom()
    }

    private fun recent(): List<String> = prefs.getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun pick(e: String) {
        val next = (listOf(e) + recent().filter { it != e }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, next.joinToString("\n")).apply()
        listener?.onEmoji(e)
    }

    private fun rebuild() {
        groupNames.clear()
        groupStart.clear()
        val out = ArrayList<String>()
        val r = recent()
        if (r.isNotEmpty()) {
            groupNames += RECENT
            groupStart += 0
            out += r
            // Each group starts on a fresh row.
            while (out.size % COLUMNS != 0) out += ""
        }
        for ((name, list) in all) {
            groupNames += name
            groupStart += out.size
            out += list
            while (out.size % COLUMNS != 0) out += ""
        }
        cells = out
        adapter.notifyDataSetChanged()
        buildTabs()
    }

    private fun groupAt(position: Int): Int {
        var g = 0
        for (i in groupStart.indices) if (groupStart[i] <= position) g = i
        return g
    }

    private fun buildTabs() {
        val t = theme ?: return
        tabs.removeAllViews()
        for ((i, name) in groupNames.withIndex()) {
            val v = TextView(context)
            v.text = TAB_ICONS[name] ?: name.take(1)
            v.gravity = Gravity.CENTER
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            v.contentDescription = TAB_NAMES[name]?.let { context.getString(it) } ?: name
            v.setTextColor(t.stripText)
            v.setOnClickListener { grid.setSelection(groupStart[i]) }
            tabs.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        markTab(groupAt(grid.firstVisiblePosition))
    }

    private fun markTab(index: Int) {
        val t = theme ?: return
        for (i in 0 until tabs.childCount) {
            val v = tabs.getChildAt(i)
            v.background = if (i == index) GradientDrawable().apply {
                setColor(t.keyFunctional)
                cornerRadius = 8 * density
            } else null
        }
    }

    private fun buildBottom() {
        val t = theme ?: return
        PanelKeys.fill(bottom, t) { listener }
    }

    companion object {
        const val ASSET = "emoji/emoji.txt"
        private const val PREFS = "emoji"
        private const val KEY_RECENT = "recent"
        private const val MAX_RECENT = 32
        private const val COLUMNS = 8
        private const val RECENT = "Recent"
        private val TAB_ICONS = mapOf(
            RECENT to "🕘", "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻",
            "Food & Drink" to "🍔", "Travel & Places" to "🚗", "Activities" to "⚽", "Objects" to "💡",
            "Symbols" to "❤️", "Flags" to "🏳️",
        )
        /** What a screen reader calls each tab: the emoji list's group names (English in the asset) as resources. */
        private val TAB_NAMES = mapOf(
            RECENT to R.string.emoji_recent, "Smileys & Emotion" to R.string.emoji_smileys, "People & Body" to R.string.emoji_people,
            "Animals & Nature" to R.string.emoji_animals, "Food & Drink" to R.string.emoji_food, "Travel & Places" to R.string.emoji_travel,
            "Activities" to R.string.emoji_activities, "Objects" to R.string.emoji_objects, "Symbols" to R.string.emoji_symbols,
            "Flags" to R.string.emoji_flags,
        )
    }
}
