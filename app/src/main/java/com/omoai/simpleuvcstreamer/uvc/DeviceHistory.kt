package com.omoai.simpleuvcstreamer.uvc

import android.content.Context
import android.hardware.usb.UsbDevice
import com.omoai.simpleuvcstreamer.util.FileLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers up to [MAX_ENTRIES] camera models (vid:pid) by last-used time,
 * including last successful resolution/FPS.
 */
object DeviceHistory {

    const val MAX_ENTRIES = 50

    private const val PREFS = "device_history_v1"
    private const val KEY_ENTRIES = "entries"

    data class Choice(val width: Int, val height: Int, val fps: Int)

    data class Entry(
        val vendorId: Int,
        val productId: Int,
        val lastUsedMs: Long,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
    ) {
        val key: String get() = keyOf(vendorId, productId)
        val hasMode: Boolean get() = width > 0 && height > 0 && fps > 0
    }

    fun keyOf(vendorId: Int, productId: Int): String = "$vendorId:$productId"

    fun modelKey(device: UsbDevice): String = keyOf(device.vendorId, device.productId)

    /** Among currently connected devices, prefer the one used most recently. */
    fun pickPreferred(context: Context, connected: List<UsbDevice>): UsbDevice? {
        if (connected.isEmpty()) return null
        val rank = load(context).associateBy { it.key }
        return connected.maxWithOrNull(
            compareBy<UsbDevice> { rank[modelKey(it)]?.lastUsedMs ?: 0L }
                .thenBy { it.deviceName }
        )
    }

    fun loadMode(context: Context, device: UsbDevice): Choice? {
        val entry = load(context).firstOrNull { it.key == modelKey(device) } ?: return null
        if (!entry.hasMode) return null
        return Choice(entry.width, entry.height, entry.fps)
    }

    /** Bump last-used without changing resolution/FPS. */
    fun touch(context: Context, device: UsbDevice) {
        mutate(context) { list ->
            val now = System.currentTimeMillis()
            val key = modelKey(device)
            val existing = list.firstOrNull { it.key == key }
            list.removeAll { it.key == key }
            list.add(
                0,
                Entry(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    lastUsedMs = now,
                    width = existing?.width ?: 0,
                    height = existing?.height ?: 0,
                    fps = existing?.fps ?: 0,
                ),
            )
            FileLogger.log("DeviceHistory touch $key (size=${list.size})")
        }
    }

    fun saveMode(context: Context, device: UsbDevice, width: Int, height: Int, fps: Int) {
        mutate(context) { list ->
            val now = System.currentTimeMillis()
            val key = modelKey(device)
            list.removeAll { it.key == key }
            list.add(
                0,
                Entry(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    lastUsedMs = now,
                    width = width,
                    height = height,
                    fps = fps,
                ),
            )
            FileLogger.log("DeviceHistory save $key -> ${width}x${height} @${fps}fps")
        }
    }

    fun clearMode(context: Context, device: UsbDevice) {
        mutate(context) { list ->
            val key = modelKey(device)
            val idx = list.indexOfFirst { it.key == key }
            if (idx < 0) return@mutate
            val old = list[idx]
            list[idx] = old.copy(width = 0, height = 0, fps = 0)
            FileLogger.log("DeviceHistory cleared mode for $key")
        }
    }

    private fun mutate(context: Context, block: (MutableList<Entry>) -> Unit) {
        val list = load(context).toMutableList()
        block(list)
        list.sortByDescending { it.lastUsedMs }
        while (list.size > MAX_ENTRIES) {
            val dropped = list.removeAt(list.lastIndex)
            FileLogger.log("DeviceHistory evicted LRU ${dropped.key}")
        }
        save(context, list)
    }

    private fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val vendorId = o.optInt("vid", -1)
                    val productId = o.optInt("pid", -1)
                    if (vendorId < 0 || productId < 0) continue
                    add(
                        Entry(
                            vendorId = vendorId,
                            productId = productId,
                            lastUsedMs = o.optLong("ts", 0L),
                            width = o.optInt("w", 0),
                            height = o.optInt("h", 0),
                            fps = o.optInt("fps", 0),
                        ),
                    )
                }
            }.sortedByDescending { it.lastUsedMs }
        } catch (t: Throwable) {
            FileLogger.log("DeviceHistory load failed: ${t.message}")
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("vid", e.vendorId)
                    .put("pid", e.productId)
                    .put("ts", e.lastUsedMs)
                    .put("w", e.width)
                    .put("h", e.height)
                    .put("fps", e.fps),
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
