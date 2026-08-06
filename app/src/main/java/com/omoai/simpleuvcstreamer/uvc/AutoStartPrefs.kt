package com.omoai.simpleuvcstreamer.uvc

import android.content.Context
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * When enabled: on app open (and when a device appears while idle),
 * auto-start HTTP MJPEG server and camera capture.
 * Default: off.
 */
object AutoStartPrefs {

    private const val PREFS = "auto_start"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = false

    fun isEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        FileLogger.log("Auto-start capture+HTTP enabled=$enabled")
    }
}
