package com.omoai.simpleuvcstreamer.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.omoai.simpleuvcstreamer.util.FileLogger

/** CPU + Wi‑Fi radios while USB isochronous capture is running. */
class KeepAliveLocks(context: Context) {
    private val app = context.applicationContext

    private val wakeLock: PowerManager.WakeLock =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UVCStreamer:capture")
            .apply { setReferenceCounted(false) }

    private val wifiLock: WifiManager.WifiLock =
        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode(), "UVCStreamer:wifi")
            .apply { setReferenceCounted(false) }

    fun acquire() {
        try {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } catch (t: Throwable) {
            FileLogger.log("WakeLock acquire: ${t.message}")
        }
        try {
            if (!wifiLock.isHeld) wifiLock.acquire()
        } catch (t: Throwable) {
            FileLogger.log("WifiLock acquire: ${t.message}")
        }
    }

    fun release() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (t: Throwable) {
            FileLogger.log("WakeLock release: ${t.message}")
        }
        try {
            if (wifiLock.isHeld) wifiLock.release()
        } catch (t: Throwable) {
            FileLogger.log("WifiLock release: ${t.message}")
        }
    }

    private fun wifiLockMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }
}
