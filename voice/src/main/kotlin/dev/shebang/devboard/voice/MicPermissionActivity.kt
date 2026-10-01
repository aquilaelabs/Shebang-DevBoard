package dev.shebang.devboard.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Asks for the microphone permission. The keyboard opens this when the add-on reports it has none (a
 * keyboard cannot ask for permissions itself), and it closes as soon as the user answers.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) "Voice typing is ready: tap the mic again" else "Voice typing needs the microphone", Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}
