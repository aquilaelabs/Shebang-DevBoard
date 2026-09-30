package dev.shebang.devboard.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.settings.Settings

/** Haptic and sound feedback using the system's own effects; no bundled audio. */
class Feedback(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val effects = arrayOf(
        VibrationEffect.createOneShot(8, 60),
        VibrationEffect.createOneShot(12, 140),
        VibrationEffect.createOneShot(18, 255),
    )
    var settings: Settings = Settings()

    fun keyPress(action: KeyAction = KeyAction.NONE) {
        if (settings.haptics) vibrator?.let { if (it.hasVibrator()) it.vibrate(effects[settings.hapticStrength.coerceIn(1, 3) - 1]) }
        if (settings.keySounds) {
            val fx = when (action) {
                KeyAction.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyAction.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyAction.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, 0.6f)
        }
    }
}
