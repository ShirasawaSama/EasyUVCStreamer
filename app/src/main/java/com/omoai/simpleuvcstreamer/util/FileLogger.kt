package com.omoai.simpleuvcstreamer.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat plus a small in-memory ring buffer, so the app itself can show (and
 * copy) the recent log without needing a PC / adb.
 */
object FileLogger {
    private const val TAG = "UVCStreamer"
    private const val MAX_LINES = 400

    private val lock = Any()
    private val buffer = java.util.ArrayDeque<String>(MAX_LINES + 1)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(lock) {
            // SimpleDateFormat is not thread-safe; keep format() under the same lock.
            val line = "${stamp.format(Date())} $msg"
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeFirst()
            }
        }
    }

    fun recentLines(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }
}
