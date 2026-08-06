package com.omoai.simpleuvcstreamer.uvc

import android.content.Context
import android.hardware.usb.UsbDevice
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * Remembers last successful resolution/FPS per camera model (USB vid:pid).
 */
object CameraModePrefs {

    private const val PREFS = "camera_mode_history"
    private const val SEP = "|"

    data class Choice(val width: Int, val height: Int, val fps: Int)

    fun modelKey(device: UsbDevice): String = "${device.vendorId}:${device.productId}"

    fun load(context: Context, device: UsbDevice): Choice? {
        val raw = prefs(context).getString(modelKey(device), null) ?: return null
        val parts = raw.split(SEP)
        if (parts.size != 3) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        val fps = parts[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || fps <= 0) return null
        return Choice(w, h, fps)
    }

    fun save(context: Context, device: UsbDevice, width: Int, height: Int, fps: Int) {
        val key = modelKey(device)
        prefs(context).edit()
            .putString(key, "$width$SEP$height$SEP$fps")
            .apply()
        FileLogger.log("Remembered mode for $key -> ${width}x${height} @${fps}fps")
    }

    fun clear(context: Context, device: UsbDevice) {
        val key = modelKey(device)
        prefs(context).edit().remove(key).apply()
        FileLogger.log("Cleared remembered mode for $key")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
