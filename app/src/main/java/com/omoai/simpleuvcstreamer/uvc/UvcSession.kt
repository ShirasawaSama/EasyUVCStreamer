package com.omoai.simpleuvcstreamer.uvc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * Owns USB connection + native device handle lifecycle.
 *
 * Main-line: camera MJPEG when available; otherwise YUV encoded to JPEG (turbojpeg)
 * for HTTP push. Preview decode is opt-in via [FramePreviewController].
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

        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            FileLogger.log("openDevice threw: ${t.message}")
            null
        }
        if (conn == null) {
            FileLogger.log("Failed to open UsbConnection")
            return false
        }

        try {
            UvcDeviceFinder.claimVideoInterfaces(conn, device)
        } catch (t: Throwable) {
            FileLogger.log("claimVideoInterfaces threw: ${t.message}")
            try {
                conn.close()
            } catch (_: Throwable) {
            }
            return false
        }

        usbConnection = conn
        currentDevice = device

        val res = try {
            UvcNative.nativeOpenDevice(conn.fileDescriptor)
        } catch (t: Throwable) {
            FileLogger.log("nativeOpenDevice threw: ${t.message}")
            close()
            return false
        }
        FileLogger.log("nativeOpenDevice res: $res")
        if (res != 0) {
            close()
            return false
        }
        return true
    }

    fun loadStreamModes(): List<StreamMode> {
        return try {
            val raw = UvcNative.nativeGetResolutions()
            FileLogger.log("Available modes: $raw")
            StreamMode.parse(raw)
        } catch (t: Throwable) {
            FileLogger.log("loadStreamModes threw: ${t.message}")
            emptyList()
        }
    }

    fun startStream(width: Int, height: Int, fps: Int, format: String): Int {
        FileLogger.log("Starting/switching stream to ${width}x${height} @${fps}fps fmt=$format")
        val startRes = try {
            UvcNative.nativeStartStream(width, height, fps, format)
        } catch (t: Throwable) {
            FileLogger.log("nativeStartStream threw: ${t.message}")
            isStreaming = false
            return -1
        }
        FileLogger.log("nativeStartStream result: $startRes")
        isStreaming = startRes == 0
        return startRes
    }

    fun stopStream() {
        FileLogger.log("Stopping stream (keep device open)...")
        isStreaming = false
        try {
            UvcNative.nativeStopStream()
        } catch (t: Throwable) {
            FileLogger.log("nativeStopStream threw: ${t.message}")
        }
    }

    fun close() {
        isStreaming = false
        try {
            UvcNative.nativeClose()
        } catch (t: Throwable) {
            FileLogger.log("nativeClose threw: ${t.message}")
        }
        val conn = usbConnection
        val device = currentDevice
        if (conn != null && device != null) {
            UvcDeviceFinder.releaseVideoInterfaces(conn, device)
        }
        try {
            conn?.close()
        } catch (t: Throwable) {
            FileLogger.log("UsbDeviceConnection.close threw: ${t.message}")
        }
        usbConnection = null
        currentDevice = null
    }

    fun frameCountPerSecond(): Int = try {
        UvcNative.nativeGetFrameCount()
    } catch (_: Throwable) {
        0
    }
}
