package dev.shebang.devboard

import android.app.Application

/** The keyboard's process: records where it crashes ([CrashLog]) before anything else runs. */
class DevBoardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
