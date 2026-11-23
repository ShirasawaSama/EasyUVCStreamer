package com.omoai.simpleuvcstreamer.uvc

import android.util.Log

object UvcNative {
    const val CHANNEL_COUNT = 3
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
    external fun nativeOpenDevice(channel: Int, fd: Int): Int
    external fun nativeGetResolutions(channel: Int): String
    external fun nativeStartStream(channel: Int, width: Int, height: Int, fps: Int, format: String): Int
    external fun nativeStopStream(channel: Int)
    external fun nativeGetFrameCount(channel: Int): Int
    external fun nativeClose(channel: Int)
    external fun nativeSetPreviewEnabled(channel: Int, enabled: Boolean)
    external fun nativeTakeLatestFrame(channel: Int): ByteArray?

    external fun nativeSetChannelOrder(slots: IntArray)
    external fun nativeStartHttpServer(port: Int): Int
    external fun nativeStopHttpServer()
    external fun nativeIsHttpServerRunning(): Boolean
    external fun nativeGetHttpServerPort(): Int
    external fun nativeGetHttpClientCount(): Int
}
