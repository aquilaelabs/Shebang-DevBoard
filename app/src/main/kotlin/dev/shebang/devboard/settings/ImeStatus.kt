package dev.shebang.devboard.settings

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.shebang.devboard.ime.DevBoardService

/** Which setup steps are done. Checked on resume; the system does not notify. */
data class ImeStatus(val enabled: Boolean, val selected: Boolean) {
    companion object {
        fun check(context: Context): ImeStatus {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val ours = ComponentName(context, DevBoardService::class.java)
            val enabled = imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == ours || it.component == ours }
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val selected = current != null && ComponentName.unflattenFromString(current) == ours
            return ImeStatus(enabled, selected)
        }
    }
}
