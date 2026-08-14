package com.omoai.simpleuvcstreamer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.omoai.simpleuvcstreamer.util.FileLogger

class UsbDeviceMonitor(
    private val context: Context,
    private val permissionAction: String,
    private val listener: Listener
) {
    interface Listener {
        fun onDeviceAttached(device: UsbDevice?)
        fun onDeviceDetached(device: UsbDevice)
        fun onPermissionResult(device: UsbDevice?, granted: Boolean)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            FileLogger.log("USB Event: ${intent.action}")
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    listener.onDeviceAttached(readUsbDevice(intent))
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    readUsbDevice(intent)?.let { listener.onDeviceDetached(it) }
                }
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    FileLogger.log("USB Permission Granted: $granted")
                    listener.onPermissionResult(readUsbDevice(intent), granted)
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        // Must be explicit + mutable: UsbService writes EXTRA_PERMISSION_GRANTED
        // into the PI. FLAG_IMMUTABLE / implicit intents drop the result on API 31+.
        val intent = Intent(permissionAction).apply {
            setPackage(context.packageName)
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun readUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
