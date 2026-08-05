package com.omoai.simpleuvcstreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.omoai.simpleuvcstreamer.preview.FramePreviewController
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.stream.NetworkAddresses
import com.omoai.simpleuvcstreamer.ui.SafeArea
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
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
    private lateinit var switchStream: MaterialSwitch
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var previewContainer: MaterialCardView
    private lateinit var dropdownDevice: AutoCompleteTextView
    private lateinit var dropdownResolution: AutoCompleteTextView
    private lateinit var imagePreview: ImageView
    private lateinit var editHttpPort: TextInputEditText
    private lateinit var btnApplyHttpPort: MaterialButton
    private lateinit var tvHttpState: TextView
    private lateinit var listAccessUrls: LinearLayout
    private lateinit var tvHttpNoUrls: TextView

    private lateinit var usbManager: UsbManager
    private lateinit var session: UvcSession
    private lateinit var usbMonitor: UsbDeviceMonitor
    private lateinit var previewController: FramePreviewController
    private lateinit var httpStream: HttpStreamController

    private var uvcDevices: List<UsbDevice> = emptyList()
    private var resolutionLabels: List<String> = emptyList()
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var pendingStartAfterPermission = false
    private var lastAccessSignature: String = ""

    private var lastHttpStateText: String = ""

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            FileLogger.log("Runtime permissions: $result")
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                FileLogger.log("Permissions denied: $denied (MJPEG video can still work)")
            }
            startUsbSession()
        }

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (session.isStreaming && UvcNative.isLibLoaded) {
                updateFpsText(session.frameCountPerSecond())
            } else if (::tvFps.isInitialized) {
                tvFps.visibility = View.GONE
            }
            refreshHttpUi(forceList = false)
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
        switchStream = findViewById(R.id.switchStream)
        switchPreview = findViewById(R.id.switchPreview)
        previewContainer = findViewById(R.id.previewContainer)
        dropdownDevice = findViewById(R.id.dropdownDevice)
        dropdownResolution = findViewById(R.id.dropdownResolution)
        imagePreview = findViewById(R.id.imagePreview)
        editHttpPort = findViewById(R.id.editHttpPort)
        btnApplyHttpPort = findViewById(R.id.btnApplyHttpPort)
        tvHttpState = findViewById(R.id.tvHttpState)
        listAccessUrls = findViewById(R.id.listAccessUrls)
        tvHttpNoUrls = findViewById(R.id.tvHttpNoUrls)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        session = UvcSession(usbManager)
        previewController = FramePreviewController(imagePreview) { session.isStreaming }
        httpStream = HttpStreamController(this)

        if (!UvcNative.isLibLoaded) {
            updateStatus(getString(R.string.status_lib_missing))
            FileLogger.log("FATAL: Library not loaded")
            refreshHttpUi(forceList = true)
            return
        }

        try {
            val res = UvcNative.nativeInit()
            FileLogger.log("nativeInit result: $res")
            updateStatus(getString(R.string.status_ready))
        } catch (t: Throwable) {
            FileLogger.log("nativeInit CRASHED: ${t.message}")
            updateStatus(getString(R.string.status_init_crash))
            refreshHttpUi(forceList = true)
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
        refreshHttpUi(forceList = true)

        switchPreview.isChecked = false
        previewContainer.visibility = View.GONE
        switchPreview.setOnCheckedChangeListener { _, checked ->
            FileLogger.log("Preview switch: $checked")
            previewController.setEnabled(checked)
            previewContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }

        switchStream.setOnCheckedChangeListener { _, isChecked ->
            FileLogger.log("Switch changed: $isChecked")
            if (isChecked) startStreaming() else stopStreaming()
        }

        ensureRuntimePermissionsThenStartUsb()
        fpsHandler.post(fpsRunnable)
    }

    private fun ensureRuntimePermissionsThenStartUsb() {
        val missing = AppPermissions.missing(this)
        if (missing.isEmpty()) {
            startUsbSession()
            return
        }
        FileLogger.log("Requesting permissions: ${missing.toList()}")
        runtimePermissionLauncher.launch(missing)
    }

    private fun startUsbSession() {
        if (!::usbMonitor.isInitialized) {
            usbMonitor = UsbDeviceMonitor(this, ACTION_USB_PERMISSION, this)
            usbMonitor.register()
        }
        bindDropdowns()
        refreshDeviceList()
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
        lastAccessSignature = ""
        lastHttpStateText = ""
        refreshHttpUi(forceList = true)
    }

    private fun refreshHttpUi(forceList: Boolean = false) {
        runOnUiThread {
            if (!::httpStream.isInitialized) return@runOnUiThread
            val running = httpStream.isRunning
            val port = if (running) UvcNative.nativeGetHttpServerPort() else httpStream.port
            val clients = httpStream.clientCount
            val state = if (running) {
                getString(R.string.http_running, port, clients)
            } else {
                getString(R.string.http_stopped, port)
            }
            // Avoid setText when unchanged — prevents layout/focus scroll fights.
            if (state != lastHttpStateText) {
                lastHttpStateText = state
                tvHttpState.text = state
            }
            if (forceList) {
                lastAccessSignature = ""
            }
            renderAccessList(httpStream.accessEndpoints())
        }
    }

    private fun renderAccessList(endpoints: List<HttpStreamController.AccessEndpoint>) {
        val signature = endpoints.joinToString("|") { "${it.tier}:${it.url}" }
        if (signature == lastAccessSignature && listAccessUrls.childCount > 0) {
            tvHttpNoUrls.visibility = if (endpoints.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        lastAccessSignature = signature
        listAccessUrls.removeAllViews()

        if (endpoints.isEmpty()) {
            tvHttpNoUrls.visibility = View.VISIBLE
            return
        }
        tvHttpNoUrls.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        var lastTier: NetworkAddresses.AccessTier? = null
        for (ep in endpoints) {
            if (ep.tier != lastTier) {
                lastTier = ep.tier
                val header = inflater.inflate(R.layout.item_access_group, listAccessUrls, false) as TextView
                header.text = when (ep.tier) {
                    NetworkAddresses.AccessTier.LAN -> getString(R.string.http_group_lan)
                    NetworkAddresses.AccessTier.OTHER -> getString(R.string.http_group_other)
                    NetworkAddresses.AccessTier.LOCAL -> getString(R.string.http_group_local)
                }
                header.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when (ep.tier) {
                            NetworkAddresses.AccessTier.LAN -> R.color.signal
                            NetworkAddresses.AccessTier.OTHER -> R.color.text_secondary
                            NetworkAddresses.AccessTier.LOCAL -> R.color.text_muted
                        }
                    )
                )
                listAccessUrls.addView(header)
            }

            val row = inflater.inflate(R.layout.item_access_url, listAccessUrls, false)
            val badge = row.findViewById<TextView>(R.id.tvAccessBadge)
            val label = row.findViewById<TextView>(R.id.tvAccessLabel)
            val urlView = row.findViewById<TextView>(R.id.tvAccessUrl)
            val hint = row.findViewById<TextView>(R.id.tvAccessHint)

            label.text = "${ep.label} · ${ep.host}"
            urlView.text = ep.url

            when (ep.tier) {
                NetworkAddresses.AccessTier.LAN -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_lan)
                    badge.visibility = View.VISIBLE
                    badge.text = getString(R.string.http_badge_recommend)
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.signal))
                    hint.text = getString(R.string.http_hint_lan)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
                NetworkAddresses.AccessTier.OTHER -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_other)
                    badge.visibility = View.GONE
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    hint.text = getString(R.string.http_hint_other)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                }
                NetworkAddresses.AccessTier.LOCAL -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_local)
                    badge.visibility = View.GONE
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    hint.text = getString(R.string.http_hint_local)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                }
            }

            row.setOnClickListener { copyUrl(ep.url) }
            listAccessUrls.addView(row)
        }
    }

    private fun copyUrl(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("stream", url))
        Toast.makeText(this, R.string.http_copied, Toast.LENGTH_SHORT).show()
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

    private fun bindDropdowns() {
        dropdownDevice.setOnItemClickListener { _, _, pos, _ ->
            if (suppressDeviceCallback) return@setOnItemClickListener
            val device = uvcDevices.getOrNull(pos) ?: return@setOnItemClickListener
            prepareDevice(device, startStream = switchStream.isChecked)
        }
        dropdownResolution.setOnItemClickListener { _, _, pos, _ ->
            if (suppressResolutionCallback) return@setOnItemClickListener
            if (!session.isStreaming) return@setOnItemClickListener
            applySelectedResolution(pos)
        }
    }

    private fun refreshDeviceList() {
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

        val labels = uvcDevices.map { UvcDeviceFinder.labelFor(it) }
        val previousName = session.currentDevice?.deviceName
        val keepIndex = uvcDevices.indexOfFirst { it.deviceName == previousName }.coerceAtLeast(0)

        suppressDeviceCallback = true
        dropdownDevice.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (labels.isNotEmpty()) {
            dropdownDevice.setText(labels[keepIndex], false)
        } else {
            dropdownDevice.setText("", false)
        }
        suppressDeviceCallback = false

        if (uvcDevices.isEmpty()) {
            session.close()
            clearResolutions()
            updateStatus(getString(R.string.status_no_device))
            return
        }

        val selected = uvcDevices.getOrNull(keepIndex) ?: uvcDevices.first()
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

        val resList = loadResolutionsIntoDropdown(session.loadResolutions())
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

    private fun loadResolutionsIntoDropdown(resList: List<String>): List<String> {
        resolutionLabels = resList
        suppressResolutionCallback = true
        dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, resList))
        if (resList.isNotEmpty()) {
            val idx = Resolution.preferredIndex(resList)
            dropdownResolution.setText(resList[idx], false)
        } else {
            dropdownResolution.setText("", false)
        }
        suppressResolutionCallback = false
        return resList
    }

    private fun clearResolutions() {
        resolutionLabels = emptyList()
        suppressResolutionCallback = true
        dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, emptyList<String>()))
        dropdownResolution.setText("", false)
        suppressResolutionCallback = false
    }

    private fun startStreaming() {
        if (uvcDevices.isEmpty()) {
            FileLogger.log("startStreaming: No UVC devices")
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_no_device))
            return
        }
        val label = dropdownDevice.text?.toString()
        val index = uvcDevices.indexOfFirst { UvcDeviceFinder.labelFor(it) == label }.coerceAtLeast(0)
        val device = uvcDevices.getOrNull(index) ?: run {
            setSwitchChecked(false)
            return
        }
        prepareDevice(device, startStream = true)
    }

    private fun applySelectedResolution(forcedIndex: Int? = null) {
        val resLabel = if (forcedIndex != null) {
            resolutionLabels.getOrNull(forcedIndex)
        } else {
            dropdownResolution.text?.toString()
        }
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
        if (::previewController.isInitialized) {
            previewController.release()
        }
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
