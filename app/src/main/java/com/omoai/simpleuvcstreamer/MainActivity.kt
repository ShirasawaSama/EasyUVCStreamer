package com.omoai.simpleuvcstreamer

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.omoai.simpleuvcstreamer.preview.FramePreviewController
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.ui.SafeArea
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.Resolution
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import com.omoai.simpleuvcstreamer.uvc.UvcSession

/**
 * Thin UI layer. Streaming main-line is raw MJPEG via [UvcSession] + HTTP push.
 * Preview is opt-in and off by default (decode only when enabled).
 */
class MainActivity : AppCompatActivity(), UsbDeviceMonitor.Listener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvPreviewHint: TextView
    private lateinit var switchStream: MaterialSwitch
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var spinnerDevice: Spinner
    private lateinit var spinnerResolution: Spinner
    private lateinit var imagePreview: ImageView
    private lateinit var editHttpPort: TextInputEditText
    private lateinit var btnApplyHttpPort: MaterialButton
    private lateinit var tvHttpState: TextView
    private lateinit var tvHttpUrls: TextView

    private lateinit var usbManager: UsbManager
    private lateinit var session: UvcSession
    private lateinit var usbMonitor: UsbDeviceMonitor
    private lateinit var previewController: FramePreviewController
    private lateinit var httpStream: HttpStreamController

    private var uvcDevices: List<UsbDevice> = emptyList()
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var pendingStartAfterPermission = false

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (session.isStreaming && UvcNative.isLibLoaded) {
                updateFpsText(session.frameCountPerSecond())
            } else {
                tvFps.visibility = View.GONE
            }
            refreshHttpUi()
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
        tvFps = findViewById(R.id.tvFps)
        tvPreviewHint = findViewById(R.id.tvPreviewHint)
        switchStream = findViewById(R.id.switchStream)
        switchPreview = findViewById(R.id.switchPreview)
        spinnerDevice = findViewById(R.id.spinnerDevice)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        imagePreview = findViewById(R.id.imagePreview)
        editHttpPort = findViewById(R.id.editHttpPort)
        btnApplyHttpPort = findViewById(R.id.btnApplyHttpPort)
        tvHttpState = findViewById(R.id.tvHttpState)
        tvHttpUrls = findViewById(R.id.tvHttpUrls)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        session = UvcSession(usbManager)
        previewController = FramePreviewController(imagePreview) { session.isStreaming }
        httpStream = HttpStreamController(this)

        if (!UvcNative.isLibLoaded) {
            updateStatus(getString(R.string.status_lib_missing))
            FileLogger.log("FATAL: Library not loaded")
            refreshHttpUi()
            return
        }

        try {
            val res = UvcNative.nativeInit()
            FileLogger.log("nativeInit result: $res")
            updateStatus(getString(R.string.status_ready))
        } catch (t: Throwable) {
            FileLogger.log("nativeInit CRASHED: ${t.message}")
            updateStatus(getString(R.string.status_init_crash))
            refreshHttpUi()
            return
        }

        editHttpPort.setText(httpStream.port.toString())
        val httpOk = httpStream.ensureStarted()
        FileLogger.log("HTTP auto-start ok=$httpOk port=${httpStream.port}")
        btnApplyHttpPort.setOnClickListener { applyHttpPortFromUi() }
        editHttpPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyHttpPortFromUi()
                true
            } else {
                false
            }
        }
        refreshHttpUi()

        switchPreview.isChecked = false
        tvPreviewHint.visibility = View.VISIBLE
        switchPreview.setOnCheckedChangeListener { _, checked ->
            FileLogger.log("Preview switch: $checked")
            previewController.setEnabled(checked)
            tvPreviewHint.visibility = if (checked) View.GONE else View.VISIBLE
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

    private fun applyHttpPortFromUi() {
        val raw = editHttpPort.text?.toString()?.trim().orEmpty()
        val p = raw.toIntOrNull()
        if (p == null || p !in 1..65535) {
            updateStatus(getString(R.string.status_bad_port))
            editHttpPort.setText(httpStream.port.toString())
            refreshHttpUi()
            return
        }
        val ok = httpStream.applyPort(p)
        editHttpPort.setText(httpStream.port.toString())
        updateStatus(
            if (ok) getString(R.string.status_http_ok, p)
            else getString(R.string.status_http_fail, p)
        )
        refreshHttpUi()
    }

    private fun refreshHttpUi() {
        runOnUiThread {
            if (!::httpStream.isInitialized) return@runOnUiThread
            val running = httpStream.isRunning
            val port = if (running) UvcNative.nativeGetHttpServerPort() else httpStream.port
            val clients = httpStream.clientCount
            tvHttpState.text = if (running) {
                getString(R.string.http_running, port, clients)
            } else {
                getString(R.string.http_stopped, port)
            }
            val urls = httpStream.accessLines()
            tvHttpUrls.text = if (urls.isEmpty()) {
                getString(R.string.http_no_urls)
            } else {
                urls.joinToString("\n")
            }
        }
    }

    override fun onDeviceAttached() = refreshDeviceList()

    override fun onDeviceDetached(device: UsbDevice) {
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice)) {
            session.close()
            clearResolutions()
            updateStatus(getString(R.string.status_device_detached))
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
            updateStatus(getString(R.string.status_permission_denied))
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

    private fun stringAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, R.layout.item_spinner, items).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun refreshDeviceList() {
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

        val labels = uvcDevices.map { UvcDeviceFinder.labelFor(it) }
        val previousName = session.currentDevice?.deviceName
        val keepIndex = uvcDevices.indexOfFirst { it.deviceName == previousName }.coerceAtLeast(0)

        suppressDeviceCallback = true
        spinnerDevice.adapter = stringAdapter(labels)
        if (labels.isNotEmpty()) {
            spinnerDevice.setSelection(keepIndex, false)
        }
        suppressDeviceCallback = false

        if (uvcDevices.isEmpty()) {
            session.close()
            clearResolutions()
            updateStatus(getString(R.string.status_no_device))
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
            updateStatus(getString(R.string.status_waiting_permission))
            return
        }

        openDeviceAndLoadResolutions(device, startStream)
    }

    private fun openDeviceAndLoadResolutions(device: UsbDevice, startStream: Boolean) {
        if (!session.open(device)) {
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_open_failed))
            return
        }

        val resList = loadResolutionsIntoSpinner(session.loadResolutions())
        if (resList.isEmpty()) {
            updateStatus(getString(R.string.status_no_mjpeg))
            setSwitchChecked(false)
            return
        }

        updateStatus(getString(R.string.status_resolutions_ready, resList.size))
        if (startStream) {
            applySelectedResolution()
        }
    }

    private fun loadResolutionsIntoSpinner(resList: List<String>): List<String> {
        suppressResolutionCallback = true
        spinnerResolution.adapter = stringAdapter(resList)
        if (resList.isNotEmpty()) {
            spinnerResolution.setSelection(Resolution.preferredIndex(resList), false)
        }
        suppressResolutionCallback = false
        return resList
    }

    private fun clearResolutions() {
        suppressResolutionCallback = true
        spinnerResolution.adapter = stringAdapter(emptyList())
        suppressResolutionCallback = false
    }

    private fun startStreaming() {
        if (uvcDevices.isEmpty()) {
            FileLogger.log("startStreaming: No UVC devices")
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_no_device))
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
            updateStatus(getString(R.string.status_select_resolution))
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
            updateStatus(getString(R.string.status_streaming, width, height))
        } else {
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_stream_error, startRes, width, height))
        }
    }

    private fun stopStreaming() {
        session.stopStream()
        tvFps.visibility = View.GONE
        updateStatus(getString(R.string.status_idle))
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
        runOnUiThread { tvStatus.text = status }
    }

    private fun updateFpsText(fps: Int) {
        runOnUiThread {
            tvFps.visibility = View.VISIBLE
            tvFps.text = getString(R.string.status_fps, fps)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fpsHandler.removeCallbacks(fpsRunnable)
        previewController.release()
        if (::usbMonitor.isInitialized) {
            usbMonitor.unregister()
        }
        if (::httpStream.isInitialized) {
            httpStream.stop()
        }
        if (::session.isInitialized) {
            session.close()
        }
    }
}
