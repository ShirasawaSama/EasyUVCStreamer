package com.omoai.simpleuvcstreamer.stream

import android.content.Context
import android.content.SharedPreferences
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.UvcNative

/**
 * Owns HTTP MJPEG server lifecycle (native libhv).
 * Default: auto-start on [ensureStarted] with persisted port.
 */
class HttpStreamController(context: Context) {

    companion object {
        const val DEFAULT_PORT = 8080
        const val STREAM_PATH = "/stream.mjpg"
        private const val PREFS = "http_stream"
        private const val KEY_PORT = "port"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)
        private set(value) {
            prefs.edit().putInt(KEY_PORT, value.coerceIn(1, 65535)).apply()
        }

    val isRunning: Boolean
        get() = UvcNative.isLibLoaded && UvcNative.nativeIsHttpServerRunning()

    val clientCount: Int
        get() = if (UvcNative.isLibLoaded) UvcNative.nativeGetHttpClientCount() else 0

    fun ensureStarted(portOverride: Int? = null): Boolean {
        if (!UvcNative.isLibLoaded) {
            FileLogger.log("HTTP: native lib not loaded")
            return false
        }
        val p = (portOverride ?: port).coerceIn(1, 65535)
        port = p
        val res = UvcNative.nativeStartHttpServer(p)
        FileLogger.log("HTTP start port=$p res=$res")
        return res == 0 && UvcNative.nativeIsHttpServerRunning()
    }

    /** Apply a new port (restarts server). */
    fun applyPort(newPort: Int): Boolean {
        val p = newPort.coerceIn(1, 65535)
        if (isRunning && UvcNative.nativeGetHttpServerPort() == p) {
            port = p
            return true
        }
        stop()
        return ensureStarted(p)
    }

    fun stop() {
        if (!UvcNative.isLibLoaded) return
        UvcNative.nativeStopHttpServer()
        FileLogger.log("HTTP stopped")
    }

    fun accessLines(): List<String> {
        val p = if (isRunning) UvcNative.nativeGetHttpServerPort() else port
        if (p !in 1..65535) return emptyList()
        val lines = mutableListOf<String>()
        for (ep in NetworkAddresses.ipv4Endpoints()) {
            val label = if (ep.isLoopback) "本机" else ep.interfaceName
            lines += "$label  预览页  http://${ep.host}:$p/"
            lines += "$label  直链    http://${ep.host}:$p$STREAM_PATH"
        }
        return lines
    }
}
