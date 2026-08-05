package com.omoai.simpleuvcstreamer.uvc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * Owns USB connection + native device handle lifecycle.
 *
 * Main-line: raw MJPEG from camera (no decode) — intended for low-latency HTTP push later.
 * Preview/UI decode is opt-in via [com.omoai.simpleuvcstreamer.preview.FramePreviewController].
 */
class UvcSession(private val usbManager: UsbManager) {

    var currentDevice: UsbDevice? = null
        private set
    var isStreaming: Boolean = false
        private set

    private var usbConnection: UsbDeviceConnection? = null

    val isDeviceOpen: Boolean
        get() = currentDevice != null && usbConnection != null

    fun open(device: UsbDevice): Boolean {
        FileLogger.log("Opening device: ${device.deviceName}")
        close()

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            FileLogger.log("Failed to open UsbConnection")
            return false
        }

        UvcDeviceFinder.claimVideoInterfaces(conn, device)
        usbConnection = conn
        currentDevice = device

        val res = UvcNative.nativeOpenDevice(conn.fileDescriptor)
        FileLogger.log("nativeOpenDevice res: $res")
        if (res != 0) {
            close()
            return false
        }
        return true
    }

    fun loadResolutions(): List<String> {
        val resStr = UvcNative.nativeGetResolutions()
        FileLogger.log("Available resolutions: $resStr")
        return Resolution.parse(resStr)
    }

    fun startStream(width: Int, height: Int, fps: Int = 30): Int {
        FileLogger.log("Starting/switching stream to ${width}x${height}")
        val startRes = UvcNative.nativeStartStream(width, height, fps)
        FileLogger.log("nativeStartStream result: $startRes")
        isStreaming = startRes == 0
        return startRes
    }

    fun stopStream() {
        FileLogger.log("Stopping stream (keep device open)...")
        isStreaming = false
        UvcNative.nativeStopStream()
    }

    fun close() {
        isStreaming = false
        UvcNative.nativeClose()
        val conn = usbConnection
        val device = currentDevice
        if (conn != null && device != null) {
            UvcDeviceFinder.releaseVideoInterfaces(conn, device)
        }
        conn?.close()
        usbConnection = null
        currentDevice = null
    }

    fun frameCountPerSecond(): Int = UvcNative.nativeGetFrameCount()
}
