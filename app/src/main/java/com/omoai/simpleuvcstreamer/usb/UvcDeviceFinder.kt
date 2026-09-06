package com.omoai.simpleuvcstreamer.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.omoai.simpleuvcstreamer.util.FileLogger

object UvcDeviceFinder {
    private const val USB_CLASS_VIDEO = 14
    private const val USB_CLASS_MISC = 239
    private const val USB_MISC_SUBCLASS_IAD = 2
    private const val USB_IAD_PROTOCOL_UVC = 1

    fun listUvcDevices(usbManager: UsbManager): List<UsbDevice> {
        val all = usbManager.deviceList.values.toList()
        val uvc = all.filter { isLikelyUvc(it) }
        if (uvc.isEmpty() && all.isNotEmpty()) {
            // Rare: USB present but nothing looks like UVC / incomplete-descriptor candidate.
            for (device in all) {
                FileLogger.log(
                    "USB non-UVC ${device.deviceName} " +
                        "vid=${device.vendorId} pid=${device.productId} " +
                        "name=${device.productName} " +
                        "devClass=${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol} " +
                        "ifaces=${device.interfaceCount}"
                )
            }
        }
        return uvc
    }

    /**
     * Match UVC by video class / UVC IAD, or by empty interface list.
     *
     * Pico Neo / Pico 3 / Pico 4 often expose [UsbDevice] with [UsbDevice.getInterfaceCount] == 0
     * (incomplete Java-side descriptors). Quest / Pico 4 Ultra usually report class 14 normally.
     * Native libusb can still parse configs from the fd after [UsbManager.openDevice].
     */
    fun isLikelyUvc(device: UsbDevice): Boolean {
        if (device.interfaceCount == 0) return true
        if (device.deviceClass == USB_CLASS_VIDEO) return true
        if (device.deviceClass == USB_CLASS_MISC &&
            device.deviceSubclass == USB_MISC_SUBCLASS_IAD &&
            device.deviceProtocol == USB_IAD_PROTOCOL_UVC
        ) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == USB_CLASS_VIDEO) return true
            if (intf.interfaceClass == USB_CLASS_MISC &&
                intf.interfaceSubclass == USB_MISC_SUBCLASS_IAD &&
                intf.interfaceProtocol == USB_IAD_PROTOCOL_UVC
            ) {
                return true
            }
        }
        return false
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
