package com.omoai.simpleuvcstreamer

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.omoai.simpleuvcstreamer.preview.FramePreviewController
import com.omoai.simpleuvcstreamer.ui.SafeArea
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.Resolution
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import com.omoai.simpleuvcstreamer.uvc.UvcSession

/**
 * Thin UI layer. Streaming main-line is raw MJPEG via [UvcSession]/
 * Preview is opt-in and off by default (decode only when enabled).
 */
class MainActivity : AppCompatActivity(), UsbDeviceMonitor.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
    }

    private lateinit var tvStatus: TextView
    private lateinit var switchStream: MaterialSwitch
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var spinnerDevice: Spinner
    private lateinit var spinnerResolution: Spinner
    private lateinit var imagePreview: ImageView

    private lateinit var usbManager: UsbManager
    private lateinit var session: UvcSession
    private lateinit var usbMonitor: UsbDeviceMonitor
    private lateinit var previewController: FramePreviewController

    private var uvcDevices: List<UsbDevice> = emptyList()
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var pendingStartAfterPermission = false

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (session.isStreaming && UvcNative.isLibLoaded) {
                updateFpsText(session.frameCountPerSecond())
            }
            fpsHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SafeArea.apply(this, R.id.main)
        FileLogger.log("--- App Started ---")

        tvStatus = findViewById(R.id.tvStatus)
        switchStream = findViewById(R.id.switchStream)
        switchPreview = findViewById(R.id.switchPreview)
        spinnerDevice = findViewById(R.id.spinnerDevice)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        imagePreview = findViewById(R.id.imagePreview)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        session = UvcSession(usbManager)
        previewController = FramePreviewController(imagePreview) { session.isStreaming }

        if (!UvcNative.isLibLoaded) {
            updateStatus("ERR: LIB NOT LOADED")
            FileLogger.log("FATAL: Library not loaded")
            return
        }

        try {
            val res = UvcNative.nativeInit()
            FileLogger.log("nativeInit result: $res")
            updateStatus("System Ready (Init: $res)")
        } catch (t: Throwable) {
            FileLogger.log("nativeInit CRASHED: ${t.message}")
            updateStatus("Native Init Crash")
            return
        }

        // Preview stays off unless user enables it — no decode on main path.
        switchPreview.isChecked = false
        switchPreview.setOnCheckedChangeListener { _, checked ->
            FileLogger.log("Preview switch: $checked")
            previewController.setEnabled(checked)
        }

        switchStream.setOnCheckedChangeListener { _, isChecked ->
            FileLogger.log("Switch changed: $isChecked")
            if (isChecked) startStreaming() else stopStreaming()
        }

        usbMonitor = UsbDeviceMonitor(this, ACTION_USB_PERMISSION, this)
        usbMonitor.register()

        bindSpinners()
        refreshDeviceList()
        fpsHandler.post(fpsRunnable)
    }

    override fun onDeviceAttached() = refreshDeviceList()

    override fun onDeviceDetached(device: UsbDevice) {
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice)) {
            session.close()
            clearResolutions()
            updateStatus("Device detached")
        }
        refreshDeviceList()
    }

    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
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
                if (!session.isStreaming) return
                applySelectedResolution()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshDeviceList() {
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

        val labels = uvcDevices.map { UvcDeviceFinder.labelFor(it) }
        val previousName = session.currentDevice?.deviceName
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
            session.close()
            clearResolutions()
            updateStatus("No UVC device")
            return
        }

        val selected = uvcDevices.getOrNull(spinnerDevice.selectedItemPosition) ?: uvcDevices.first()
        prepareDevice(selected, startStream = switchStream.isChecked)
    }

    private fun prepareDevice(device: UsbDevice, startStream: Boolean) {
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice) && session.isDeviceOpen) {
            when {
                startStream && !session.isStreaming -> applySelectedResolution()
                !startStream && session.isStreaming -> stopStreaming()
            }
            return
        }

        if (!usbManager.hasPermission(device)) {
            pendingStartAfterPermission = startStream
            FileLogger.log("Requesting USB permission for ${device.productName}")
            usbMonitor.requestPermission(usbManager, device)
            updateStatus("Waiting USB permission...")
            return
        }

        openDeviceAndLoadResolutions(device, startStream)
    }

    private fun openDeviceAndLoadResolutions(device: UsbDevice, startStream: Boolean) {
        if (!session.open(device)) {
            setSwitchChecked(false)
            updateStatus("Open device failed")
            return
        }

        val resList = loadResolutionsIntoSpinner(session.loadResolutions())
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

    private fun loadResolutionsIntoSpinner(resList: List<String>): List<String> {
        suppressResolutionCallback = true
        spinnerResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resList
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (resList.isNotEmpty()) {
            spinnerResolution.setSelection(Resolution.preferredIndex(resList), false)
        }
        suppressResolutionCallback = false
        return resList
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
            FileLogger.log("startStreaming: No UVC devices")
            setSwitchChecked(false)
            updateStatus("No UVC device")
            return
        }
        val device = uvcDevices.getOrNull(spinnerDevice.selectedItemPosition) ?: run {
            setSwitchChecked(false)
            return
        }
        prepareDevice(device, startStream = true)
    }

    private fun applySelectedResolution() {
        val resLabel = spinnerResolution.selectedItem?.toString()
        val size = resLabel?.let { Resolution.parseSize(it) }
        if (size == null) {
            FileLogger.log("applySelectedResolution: no resolution selected")
            setSwitchChecked(false)
            updateStatus("Select a resolution first")
            return
        }
        if (!session.isDeviceOpen) {
            setSwitchChecked(false)
            return
        }

        val (width, height) = size
        val startRes = session.startStream(width, height, 30)
        if (startRes == 0) {
            setSwitchChecked(true)
            updateStatus("Streaming: ${width}x${height}")
        } else {
            setSwitchChecked(false)
            updateStatus("Stream Error: $startRes (${width}x${height})")
        }
    }

    private fun stopStreaming() {
        session.stopStream()
        updateStatus("Ready")
    }

    private fun setSwitchChecked(checked: Boolean) {
        if (switchStream.isChecked == checked) return
        switchStream.setOnCheckedChangeListener(null)
        switchStream.isChecked = checked
        switchStream.setOnCheckedChangeListener { _, isChecked ->
            FileLogger.log("Switch changed: $isChecked")
            if (isChecked) startStreaming() else stopStreaming()
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
        previewController.release()
        if (::usbMonitor.isInitialized) {
            usbMonitor.unregister()
        }
        if (::session.isInitialized) {
            session.close()
        }
    }
}
