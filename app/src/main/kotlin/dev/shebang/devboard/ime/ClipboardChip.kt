package dev.shebang.devboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import dev.shebang.devboard.view.KeyboardTheme
import kotlin.math.abs

/**
 * What the clipboard chip offers: text copied in the last few minutes that has not been pasted from the chip
 * or swiped away. Only the start of it is shown, and nothing of it when the copying app marked it sensitive
 * (a password manager's copy) or the field is a password field. Read only while the keyboard is up.
 */
class ClipboardChip(private val context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    /** The clip last pasted from the chip or swiped away, by its timestamp. */
    private var handled = Long.MIN_VALUE
    /** When this process saw the clipboard change, for clips whose own timestamp is not usable. */
    private var seenChangeAt = Long.MIN_VALUE
    private var seenStamp = Long.MIN_VALUE

    class Offer(val text: String, val preview: String, val stamp: Long)

    fun listen(onChange: () -> Unit) {
        clipboard?.addPrimaryClipChangedListener {
            seenChangeAt = SystemClock.elapsedRealtime()
            seenStamp = clipboard.primaryClipDescription?.timestamp ?: Long.MIN_VALUE
            onChange()
        }
    }

    /** The text on the clipboard now, for the history; null when it is not text or the copying app marked it sensitive. */
    fun textToKeep(): String? {
        val cm = clipboard ?: return null
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType("text/*")) return null
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** A picture on the clipboard now, for the history: its content address and type; null when there is none or it is sensitive. */
    fun imageToKeep(): Pair<android.net.Uri, String>? {
        val cm = clipboard ?: return null
        val desc = cm.primaryClipDescription ?: return null
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        val uri = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return null
        // The item's own type when the description only says image/*.
        val type = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: mime ?: return null
        return uri to type
    }

    /** The clip to offer now, or null. [masked] hides its text (password fields). */
    fun offer(masked: Boolean): Offer? {
        val cm = clipboard ?: return null
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType("text/*")) return null
        val stamp = desc.timestamp
        if (stamp == handled || !isFresh(stamp)) return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) return null
        val sensitive = Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        val preview = when {
            sensitive || masked -> "•".repeat(6)
            oneLine.length > PREVIEW_CHARS -> oneLine.take(PREVIEW_CHARS - 1) + "…"
            else -> oneLine
        }
        return Offer(text, preview, stamp)
    }

    /** Copied within [FRESH_MS]: by when this process saw the change, else by the clip's own timestamp. */
    private fun isFresh(stamp: Long): Boolean {
        if (stamp == seenStamp && seenChangeAt != Long.MIN_VALUE) return SystemClock.elapsedRealtime() - seenChangeAt <= FRESH_MS
        // The timestamp's clock differs between releases: accept it on either.
        return abs(SystemClock.elapsedRealtime() - stamp) <= FRESH_MS || abs(System.currentTimeMillis() - stamp) <= FRESH_MS
    }

    /** The chip was used or swiped away: this clip is not offered again. */
    fun markHandled(stamp: Long) {
        handled = stamp
    }

    /** The chip: "Paste" and the start of the clip, styled like the bar's chips. */
    fun chipView(offer: Offer, theme: KeyboardTheme, height: Int, onPaste: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            val label = SpannableStringBuilder("Paste  ")
            label.setSpan(ForegroundColorSpan(theme.keyTextSecondary), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.append(offer.preview)
            text = label
            setTextColor(theme.stripText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            maxLines = 1
            val pad = (12 * density).toInt()
            setPadding(pad, 0, pad, 0)
            minHeight = height
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, height)
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(theme.keyFunctional)
            }
            contentDescription = "Paste from clipboard"
            setOnClickListener { onPaste() }
        }
    }

    companion object {
        /** How long after a copy the chip offers it. */
        const val FRESH_MS = 3 * 60_000L
        const val PREVIEW_CHARS = 28
    }
}
