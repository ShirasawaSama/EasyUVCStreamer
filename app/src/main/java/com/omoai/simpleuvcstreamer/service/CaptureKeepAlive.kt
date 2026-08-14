package com.omoai.simpleuvcstreamer.service

import android.content.Context
import com.omoai.simpleuvcstreamer.util.FileLogger

/** Survives process death so sticky FGS restart can resume capture. */
object CaptureKeepAlive {
    private const val PREFS = "capture_keep_alive"
    private const val KEY_WANT_STREAMING = "want_streaming"

    fun wantStreaming(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANT_STREAMING, false)
    }

    fun setWantStreaming(context: Context, want: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WANT_STREAMING, want)
            .apply()
        FileLogger.log("Keep-alive wantStreaming=$want")
    }
}
