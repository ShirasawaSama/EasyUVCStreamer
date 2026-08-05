package com.omoai.simpleuvcstreamer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UVCStreamer"
        private const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
        private var isLibLoaded = false
        
        init {
            try {
                System.loadLibrary("simpleuvcstreamer")
                isLibLoaded = true
            } catch (t: Throwable) {
                Log.e(TAG, "Library Load Failed", t)
            }
        }
    }

    private lateinit var tvStatus: TextView
    private lateinit var switchStream: MaterialSwitch
    private lateinit var spinnerDevice: Spinner
    private lateinit var spinnerResolution: Spinner

    private lateinit var usbManager: UsbManager
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var isStreaming = false

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (isStreaming && isLibLoaded) {
                val count = nativeGetFrameCount()
                updateFpsText(count)
            }
            fpsHandler.postDelayed(this, 1000)
        }
    }

    // Log to both file and Logcat
    private fun fileLog(msg: String) {
        Log.i(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] $msg\n"
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "uvc_debug.txt")
            FileOutputStream(file, true).use { it.write(logLine.toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "File Log Failed", e)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            fileLog("USB Event: ${intent.action}")
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDeviceList()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device == currentDevice) stopStreaming()
                    refreshDeviceList()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    fileLog("USB Permission Granted: $granted")
                    if (granted) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        device?.let { openAndStart(it) }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fileLog("--- App Started ---")

        tvStatus = findViewById(R.id.tvStatus)
        switchStream = findViewById(R.id.switchStream)
        spinnerDevice = findViewById(R.id.spinnerDevice)
        spinnerResolution = findViewById(R.id.spinnerResolution)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (!isLibLoaded) {
            updateStatus("ERR: LIB NOT LOADED")
            fileLog("FATAL: Library not loaded")
            return
        }

        // 初始化 Native
        try {
            val res = nativeInit()
            fileLog("nativeInit result: $res")
            updateStatus("System Ready (Init: $res)")
        } catch (t: Throwable) {
            fileLog("nativeInit CRASHED: ${t.message}")
            updateStatus("Native Init Crash")
        }

        switchStream.setOnCheckedChangeListener { _, isChecked ->
            fileLog("Switch changed: $isChecked")
            if (isChecked) startAutoPull() else stopStreaming()
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        bindSpinners()
        refreshDeviceList()
        fpsHandler.post(fpsRunnable)
    }

    private fun bindSpinners() {
        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isStreaming) {
                    val res = p?.getItemAtPosition(pos).toString()
                    val parts = res.split("x")
                    if (parts.size == 2) {
                        fileLog("Switching resolution to $res")
                        nativeStartStream(parts[0].trim().toInt(), parts[1].trim().toInt(), 30)
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private var uvcDevices: List<UsbDevice> = emptyList()

    private fun refreshDeviceList() {
        val allDevices = usbManager.deviceList.values
        // Filter for devices that have a Video interface (Class 14)
        uvcDevices = allDevices.filter { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == 14 // USB_CLASS_VIDEO
            }
        }
        
        fileLog("UVC Device count: ${uvcDevices.size} (Total USB: ${allDevices.size})")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, uvcDevices.map { 
            "${it.productName ?: "Unknown Device"} (${it.vendorId}:${it.productId})"
        })
        spinnerDevice.adapter = adapter
    }

    private fun startAutoPull() {
        if (uvcDevices.isEmpty()) {
            fileLog("StartAutoPull: No UVC devices found")
            switchStream.isChecked = false
            return
        }
        
        val selectedIdx = spinnerDevice.selectedItemPosition
        if (selectedIdx < 0 || selectedIdx >= uvcDevices.size) {
            fileLog("StartAutoPull: Invalid selection")
            switchStream.isChecked = false
            return
        }

        val device = uvcDevices[selectedIdx]
        fileLog("Selected device: ${device.productName}")
        
        if (usbManager.hasPermission(device)) {
            openAndStart(device)
        } else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openAndStart(device: UsbDevice) {
        fileLog("Opening device: ${device.deviceName}")
        usbConnection = usbManager.openDevice(device)
        val conn = usbConnection
        if (conn == null) {
            fileLog("Failed to open UsbConnection")
            return
        }

        val fd = conn.fileDescriptor
        val res = nativeOpenDevice(fd)
        fileLog("nativeOpenDevice res: $res")
        
        if (res == 0) {
            updateStatus("Parsing Resolutions...")
            Handler(Looper.getMainLooper()).postDelayed({
                val resStr = nativeGetResolutions()
                fileLog("Available resolutions: $resStr")
                val resList = resStr.split(";").filter { it.contains("x") }
                
                runOnUiThread {
                    if (resList.isNotEmpty()) {
                        spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resList)
                        val parts = resList[0].split("x")
                        val startRes = nativeStartStream(parts[0].toInt(), parts[1].toInt(), 30)
                        fileLog("nativeStartStream result: $startRes")
                        if (startRes == 0) {
                            isStreaming = true
                            updateStatus("Streaming: ${resList[0]}")
                        }
                    } else {
                        fileLog("No compatible resolutions found in descriptor")
                    }
                }
            }, 300)
        }
    }

    private fun stopStreaming() {
        fileLog("Stopping stream...")
        isStreaming = false
        nativeClose()
        SystemClock.sleep(100)
        usbConnection?.close()
        usbConnection = null
        fileLog("Cleanup done")
        updateStatus("Stopped")
    }

    private fun updateStatus(status: String) {
        runOnUiThread { tvStatus.text = "Status: $status" }
    }

    private fun updateFpsText(fps: Int) {
        runOnUiThread {
            val cur = tvStatus.text.toString().substringBefore(" | FPS:")
            tvStatus.text = "$cur | FPS: $fps"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fpsHandler.removeCallbacks(fpsRunnable)
        unregisterReceiver(usbReceiver)
        stopStreaming()
    }

    private external fun nativeInit(): Int
    private external fun nativeOpenDevice(fd: Int): Int
    private external fun nativeGetResolutions(): String
    private external fun nativeStartStream(width: Int, height: Int, fps: Int): Int
    private external fun nativeGetFrameCount(): Int
    private external fun nativeClose()
}
