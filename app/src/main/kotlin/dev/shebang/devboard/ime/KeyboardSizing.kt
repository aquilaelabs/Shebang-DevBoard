package dev.shebang.devboard.ime

import android.content.res.Configuration
import android.content.res.Resources

/** Key sizes shared by the keyboard service and the settings app's glide recorder. */
object KeyboardSizing {
    fun rowHeightPx(resources: Resources, heightScale: Float): Float {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return (if (landscape) 40f else 52f) * resources.displayMetrics.density * heightScale
    }

    fun horizontalGapPx(resources: Resources) = 5f * resources.displayMetrics.density
    fun verticalGapPx(resources: Resources) = 8f * resources.displayMetrics.density
}
