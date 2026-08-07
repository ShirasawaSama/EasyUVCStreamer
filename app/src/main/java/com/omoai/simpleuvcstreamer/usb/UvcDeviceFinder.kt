package com.omoai.simpleuvcstreamer.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.omoai.simpleuvcstreamer.util.FileLogger

object UvcDeviceFinder {
    private const val USB_CLASS_VIDEO = 14

    fun listUvcDevices(usbManager: UsbManager): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == USB_CLASS_VIDEO
            }
        }
    }

    fun labelFor(device: UsbDevice): String {
        return "${device.productName ?: "Unknown Device"} (${device.vendorId}:${device.productId})"
    }

    fun sameDevice(a: UsbDevice?, b: UsbDevice?): Boolean {
        if (a == null || b == null) return false
        return a.deviceName == b.deviceName
    }

    fun claimVideoInterfaces(conn: UsbDeviceConnection, device: UsbDevice) {
        val claimedIds = mutableSetOf<Int>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != USB_CLASS_VIDEO) continue
            if (!claimedIds.add(intf.id)) continue
            val ok = try {
                conn.claimInterface(intf, true)
            } catch (t: Throwable) {
                FileLogger.log("claimInterface if=${intf.id} threw: ${t.message}")
                false
            }
            FileLogger.log(
                "claimInterface if=${intf.id} class=${intf.interfaceClass}/" +
                    "${intf.interfaceSubclass} -> $ok"
            )
        }
    }

    fun releaseVideoInterfaces(conn: UsbDeviceConnection, device: UsbDevice) {
        val released = mutableSetOf<Int>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != USB_CLASS_VIDEO) continue
            if (!released.add(intf.id)) continue
            try {
                conn.releaseInterface(intf)
            } catch (_: Exception) {
            }
        }
    }
}
