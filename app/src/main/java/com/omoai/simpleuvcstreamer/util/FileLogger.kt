package com.omoai.simpleuvcstreamer.util

import android.util.Log

object FileLogger {
    private const val TAG = "UVCStreamer"

    fun log(msg: String) {
        Log.i(TAG, msg)
    }
}
