package com.omoai.simpleuvcstreamer.uvc

import android.util.Log

object UvcNative {
    private const val TAG = "UVCStreamer"
    var isLibLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("simpleuvcstreamer")
            isLibLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Library Load Failed", t)
            isLibLoaded = false
        }
    }

    external fun nativeInit(): Int
    external fun nativeOpenDevice(fd: Int): Int
    external fun nativeGetResolutions(): String
    external fun nativeStartStream(width: Int, height: Int, fps: Int): Int
    external fun nativeStopStream()
    external fun nativeGetFrameCount(): Int
    external fun nativeClose()
    external fun nativeSetPreviewEnabled(enabled: Boolean)
    external fun nativeTakeLatestFrame(): ByteArray?

    external fun nativeStartHttpServer(port: Int): Int
    external fun nativeStopHttpServer()
    external fun nativeIsHttpServerRunning(): Boolean
    external fun nativeGetHttpServerPort(): Int
    external fun nativeGetHttpClientCount(): Int
}
