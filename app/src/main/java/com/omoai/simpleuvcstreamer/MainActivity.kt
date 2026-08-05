package com.omoai.simpleuvcstreamer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var usbConnection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var isStreaming = false
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var pendingStartAfterPermission = false

    private var uvcDevices: List<UsbDevice> = emptyList()

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

    private fun fileLog(msg: String) {
        Log.i(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] $msg\n"
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "uvc_debug.txt"
            )
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
                    val device = intent.usbDeviceExtra()
                    if (device != null && sameDevice(device, currentDevice)) {
                        closeDevice()
                        clearResolutions()
                        updateStatus("Device detached")
                    }
                    refreshDeviceList()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    fileLog("USB Permission Granted: $granted")
                    val device = intent.usbDeviceExtra()
                    if (granted && device != null) {
                        val shouldStart = pendingStartAfterPermission || switchStream.isChecked
                        pendingStartAfterPermission = false
                        openDeviceAndLoadResolutions(device, startStream = shouldStart)
                    } else {
                        pendingStartAfterPermission = false
                        setSwitchChecked(false)
                        updateStatus("USB permission denied")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeAreaInsets()
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
            if (isChecked) startStreaming() else stopStreaming()
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

    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.main)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
    }

    private fun bindSpinners() {
        spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressDeviceCallback) return
                val device = uvcDevices.getOrNull(pos) ?: return
                prepareDevice(device, startStream = switchStream.isChecked)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressResolutionCallback) return
                if (!isStreaming) return
                applySelectedResolution()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshDeviceList() {
        val allDevices = usbManager.deviceList.values
        uvcDevices = allDevices.filter { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == 14 // USB_CLASS_VIDEO
            }
        }

        fileLog("UVC Device count: ${uvcDevices.size} (Total USB: ${allDevices.size})")

        val labels = uvcDevices.map {
            "${it.productName ?: "Unknown Device"} (${it.vendorId}:${it.productId})"
        }

        val previousName = currentDevice?.deviceName
        val keepIndex = uvcDevices.indexOfFirst { it.deviceName == previousName }.coerceAtLeast(0)

        suppressDeviceCallback = true
        spinnerDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (labels.isNotEmpty()) {
            spinnerDevice.setSelection(keepIndex, false)
        }
        suppressDeviceCallback = false

        if (uvcDevices.isEmpty()) {
            closeDevice()
            clearResolutions()
            updateStatus("No UVC device")
            return
        }

        // Auto-open selected device so resolutions are available before streaming
        val selected = uvcDevices.getOrNull(spinnerDevice.selectedItemPosition) ?: uvcDevices.first()
        prepareDevice(selected, startStream = switchStream.isChecked)
    }

    private fun prepareDevice(device: UsbDevice, startStream: Boolean) {
        if (sameDevice(device, currentDevice) && usbConnection != null) {
            if (startStream && !isStreaming) {
                applySelectedResolution()
            } else if (!startStream && isStreaming) {
                stopStreaming()
            }
            return
        }

        if (!usbManager.hasPermission(device)) {
            pendingStartAfterPermission = startStream
            fileLog("Requesting USB permission for ${device.productName}")
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
            val pi = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
            updateStatus("Waiting USB permission...")
            return
        }

        openDeviceAndLoadResolutions(device, startStream)
    }

    private fun openDeviceAndLoadResolutions(device: UsbDevice, startStream: Boolean) {
        if (!openDevice(device)) {
            setSwitchChecked(false)
            return
        }

        val resList = loadResolutionsIntoSpinner()
        if (resList.isEmpty()) {
            updateStatus("ERR: MJPEG Not Supported")
            setSwitchChecked(false)
            return
        }

        updateStatus("Ready: ${resList.size} resolutions")
        if (startStream) {
            applySelectedResolution()
        }
    }

    private fun openDevice(device: UsbDevice): Boolean {
        fileLog("Opening device: ${device.deviceName}")
        closeDevice()

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            fileLog("Failed to open UsbConnection")
            updateStatus("Open device failed")
            return false
        }

        // Claim each USB interface id once (Android exposes every altsetting as a UsbInterface).
        // Only claim VideoControl / VideoStreaming; leave UAC alone.
        val claimedIds = mutableSetOf<Int>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != 14) continue // USB_CLASS_VIDEO
            if (!claimedIds.add(intf.id)) continue
            val ok = conn.claimInterface(intf, true)
            fileLog(
                "claimInterface if=${intf.id} class=${intf.interfaceClass}/" +
                    "${intf.interfaceSubclass} -> $ok"
            )
        }

        usbConnection = conn
        currentDevice = device

        val res = nativeOpenDevice(conn.fileDescriptor)
        fileLog("nativeOpenDevice res: $res")
        if (res != 0) {
            updateStatus("nativeOpen failed: $res")
            closeDevice()
            return false
        }
        return true
    }

    private fun loadResolutionsIntoSpinner(): List<String> {
        val resStr = nativeGetResolutions()
        fileLog("Available resolutions: $resStr")
        val resList = parseResolutions(resStr)

        suppressResolutionCallback = true
        spinnerResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resList
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (resList.isNotEmpty()) {
            // Do NOT default to the largest mode — USB bandwidth often cannot sustain it (0 FPS).
            spinnerResolution.setSelection(preferredResolutionIndex(resList), false)
        }
        suppressResolutionCallback = false
        return resList
    }

    private fun parseResolutions(resStr: String): List<String> {
        return resStr.split(";")
            .map { it.trim() }
            .filter { it.contains("x") }
            .distinct()
            .sortedWith(
                compareByDescending<String> {
                    val parts = it.split("x")
                    parts[0].trim().toInt() * parts[1].trim().toInt()
                }.thenByDescending {
                    it.split("x")[0].trim().toInt()
                }
            )
    }

    private fun preferredResolutionIndex(resList: List<String>): Int {
        val preferred = listOf(
            "1280x720", "960x540", "800x600", "640x480", "640x360", "320x240"
        )
        for (p in preferred) {
            val idx = resList.indexOf(p)
            if (idx >= 0) return idx
        }
        // List is largest-first; fall back to the smallest (safest bandwidth).
        return resList.lastIndex.coerceAtLeast(0)
    }

    private fun clearResolutions() {
        suppressResolutionCallback = true
        spinnerResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            emptyList<String>()
        )
        suppressResolutionCallback = false
    }

    private fun startStreaming() {
        if (uvcDevices.isEmpty()) {
            fileLog("startStreaming: No UVC devices")
            setSwitchChecked(false)
            updateStatus("No UVC device")
            return
        }

        val selectedIdx = spinnerDevice.selectedItemPosition
        val device = uvcDevices.getOrNull(selectedIdx)
        if (device == null) {
            setSwitchChecked(false)
            return
        }

        prepareDevice(device, startStream = true)
    }

    private fun applySelectedResolution() {
        val resLabel = spinnerResolution.selectedItem?.toString()
        if (resLabel.isNullOrBlank()) {
            fileLog("applySelectedResolution: no resolution selected")
            setSwitchChecked(false)
            updateStatus("Select a resolution first")
            return
        }

        val parts = resLabel.split("x")
        if (parts.size != 2) {
            setSwitchChecked(false)
            return
        }

        val width = parts[0].trim().toIntOrNull()
        val height = parts[1].trim().toIntOrNull()
        if (width == null || height == null) {
            setSwitchChecked(false)
            return
        }

        if (currentDevice == null || usbConnection == null) {
            fileLog("applySelectedResolution: device not open")
            setSwitchChecked(false)
            return
        }

        fileLog("Starting/switching stream to ${width}x${height}")
        val startRes = nativeStartStream(width, height, 30)
        fileLog("nativeStartStream result: $startRes")
        if (startRes == 0) {
            isStreaming = true
            setSwitchChecked(true)
            updateStatus("Streaming: ${width}x${height}")
        } else {
            isStreaming = false
            setSwitchChecked(false)
            updateStatus("Stream Error: $startRes (${width}x${height})")
        }
    }

    private fun stopStreaming() {
        if (!isStreaming && usbConnection != null) {
            // Already stopped; keep device open for resolution list
            nativeStopStream()
            updateStatus("Ready")
            return
        }
        fileLog("Stopping stream (keep device open)...")
        isStreaming = false
        nativeStopStream()
        updateStatus("Ready")
    }

    private fun closeDevice() {
        isStreaming = false
        nativeClose()
        val conn = usbConnection
        val device = currentDevice
        if (conn != null && device != null) {
            val released = mutableSetOf<Int>()
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != 14) continue
                if (!released.add(intf.id)) continue
                try {
                    conn.releaseInterface(intf)
                } catch (_: Exception) {
                }
            }
        }
        conn?.close()
        usbConnection = null
        currentDevice = null
    }

    private fun setSwitchChecked(checked: Boolean) {
        if (switchStream.isChecked == checked) return
        switchStream.setOnCheckedChangeListener(null)
        switchStream.isChecked = checked
        switchStream.setOnCheckedChangeListener { _, isChecked ->
            fileLog("Switch changed: $isChecked")
            if (isChecked) startStreaming() else stopStreaming()
        }
    }

    private fun sameDevice(a: UsbDevice?, b: UsbDevice?): Boolean {
        if (a == null || b == null) return false
        return a.deviceName == b.deviceName
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
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
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        closeDevice()
    }

    private external fun nativeInit(): Int
    private external fun nativeOpenDevice(fd: Int): Int
    private external fun nativeGetResolutions(): String
    private external fun nativeStartStream(width: Int, height: Int, fps: Int): Int
    private external fun nativeStopStream()
    private external fun nativeGetFrameCount(): Int
    private external fun nativeClose()
}
