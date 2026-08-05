package com.omoai.simpleuvcstreamer.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val TAG = "UVCStreamer"

    fun log(msg: String) {
        Log.i(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] $msg\n"
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "uvc_debug.txt"
            )
            FileOutputStream(file, true).use { it.write(logLine.toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "File Log Failed", e)
        }
    }
}
