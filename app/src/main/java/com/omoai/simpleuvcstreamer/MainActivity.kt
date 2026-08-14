package com.omoai.simpleuvcstreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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
import com.google.android.material.textfield.TextInputLayout
import com.omoai.simpleuvcstreamer.preview.FramePreviewController
import com.omoai.simpleuvcstreamer.service.BatteryKeepAlive
import com.omoai.simpleuvcstreamer.service.CaptureService
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.stream.NetworkAddresses
import com.omoai.simpleuvcstreamer.ui.SafeArea
import com.omoai.simpleuvcstreamer.usb.UsbAutoLaunch
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.AutoStartPrefs
import com.omoai.simpleuvcstreamer.uvc.StreamMode
import com.omoai.simpleuvcstreamer.uvc.UvcNative

/**
 * UI only. USB capture and HTTP live in [CaptureService] so leaving this
 * screen does not tear down an active stream.
 */
class MainActivity : AppCompatActivity(), CaptureService.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var switchStream: MaterialSwitch
    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchAutoLaunch: MaterialSwitch
    private lateinit var switchBattery: MaterialSwitch
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var previewContainer: MaterialCardView
    private lateinit var dropdownDevice: AutoCompleteTextView
    private lateinit var layoutResolution: TextInputLayout
    private lateinit var dropdownResolution: AutoCompleteTextView
    private lateinit var layoutFps: TextInputLayout
    private lateinit var dropdownFps: AutoCompleteTextView
    private lateinit var imagePreview: ImageView
    private lateinit var editHttpPort: TextInputEditText
    private lateinit var btnApplyHttpPort: MaterialButton
    private lateinit var tvHttpState: TextView
    private lateinit var listAccessUrls: LinearLayout
    private lateinit var tvHttpNoUrls: TextView

    private lateinit var previewController: FramePreviewController

    private var capture: CaptureService? = null
    private var bound = false
    private var pendingAttachDevice: UsbDevice? = null
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var suppressFpsCallback = false
    private var lastAccessSignature: String = ""
    private var lastHttpStateText: String = ""
    private var lastDeviceSig: String = ""
    private var lastModeSig: String = ""
    private var uiResumed = false

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            FileLogger.log("Runtime permissions: $result")
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                FileLogger.log("Permissions denied: $denied (MJPEG video can still work)")
            }
        }

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            val session = capture
            if (session != null && session.isStreaming && UvcNative.isLibLoaded) {
                updateFpsText(session.frameCountPerSecond())
            } else if (::tvFps.isInitialized) {
                tvFps.visibility = View.GONE
            }
            refreshHttpUi(forceList = false)
            fpsHandler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as CaptureService.LocalBinder).service()
            capture = service
            bound = true
            service.addListener(this@MainActivity)
            pendingAttachDevice?.let {
                pendingAttachDevice = null
                service.onUsbAttachIntent(it)
            }
            FileLogger.log("CaptureService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture?.removeListener(this@MainActivity)
            capture = null
            bound = false
            FileLogger.log("CaptureService disconnected")
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
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchAutoLaunch = findViewById(R.id.switchAutoLaunch)
        switchBattery = findViewById(R.id.switchBattery)
        switchPreview = findViewById(R.id.switchPreview)
        previewContainer = findViewById(R.id.previewContainer)
        dropdownDevice = findViewById(R.id.dropdownDevice)
        layoutResolution = findViewById(R.id.layoutResolution)
        dropdownResolution = findViewById(R.id.dropdownResolution)
        layoutFps = findViewById(R.id.layoutFps)
        dropdownFps = findViewById(R.id.dropdownFps)
        setDropdownEnabled(layoutResolution, dropdownResolution, enabled = false)
        setDropdownEnabled(layoutFps, dropdownFps, enabled = false)
        imagePreview = findViewById(R.id.imagePreview)
        editHttpPort = findViewById(R.id.editHttpPort)
        btnApplyHttpPort = findViewById(R.id.btnApplyHttpPort)
        tvHttpState = findViewById(R.id.tvHttpState)
        listAccessUrls = findViewById(R.id.listAccessUrls)
        tvHttpNoUrls = findViewById(R.id.tvHttpNoUrls)
        findViewById<TextView>(R.id.tvQqGroup).setOnClickListener { copyQqGroup() }

        previewController = FramePreviewController(imagePreview) {
            capture?.isStreaming == true
        }

        if (!UvcNative.isLibLoaded) {
            updateStatus(getString(R.string.status_lib_missing))
            FileLogger.log("FATAL: Library not loaded")
        }

        switchAutoStart.isChecked = AutoStartPrefs.isEnabled(this)
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            AutoStartPrefs.setEnabled(this, checked)
            if (checked) {
                capture?.ensureHttpRunning()
                refreshHttpUi(forceList = true)
            }
        }
        btnApplyHttpPort.setOnClickListener { applyHttpPortFromUi() }
        editHttpPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyHttpPortFromUi()
                true
            } else {
                false
            }
        }

        switchPreview.isChecked = false
        previewContainer.visibility = View.GONE
        switchPreview.setOnCheckedChangeListener { _, checked ->
            FileLogger.log("Preview switch: $checked")
            previewController.setEnabled(checked)
            previewContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }

        UsbAutoLaunch.syncFromPrefs(this)
        switchAutoLaunch.isChecked = UsbAutoLaunch.isEnabled(this)
        switchAutoLaunch.setOnCheckedChangeListener { _, checked ->
            UsbAutoLaunch.setEnabled(this, checked)
            UsbAutoLaunch.suppressSystemChooser(this)
        }

        switchBattery.setOnCheckedChangeListener { _, checked ->
            if (checked == BatteryKeepAlive.isExempt(this)) return@setOnCheckedChangeListener
            BatteryKeepAlive.openExemptionUi(this)
            fpsHandler.post { syncBatterySwitch() }
        }

        switchStream.setOnCheckedChangeListener { _, isChecked ->
            onStreamSwitchChanged(isChecked)
        }

        bindDropdowns()
        ensureRuntimePermissions()
        handleUsbAttachIntent(intent)
        fpsHandler.post(fpsRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindCaptureService()
    }

    override fun onResume() {
        super.onResume()
        uiResumed = true
        UsbAutoLaunch.suppressSystemChooser(this)
        syncBatterySwitch()
        if (switchPreview.isChecked) {
            previewController.setEnabled(true)
            previewController.syncNative()
        }
    }

    override fun onPause() {
        uiResumed = false
        previewController.setEnabled(false)
        UsbAutoLaunch.syncFromPrefs(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onCaptureStateChanged() {
        runOnUiThread { renderCaptureState() }
    }

    private fun bindCaptureService() {
        val intent = Intent(this, CaptureService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureRuntimePermissions() {
        val missing = AppPermissions.missing(this)
        if (missing.isEmpty()) return
        FileLogger.log("Requesting permissions: ${missing.toList()}")
        runtimePermissionLauncher.launch(missing)
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = readUsbDeviceExtra(intent) ?: return
        FileLogger.log("USB attach intent: ${device.deviceName} ${device.productName}")
        val service = capture
        if (service == null) {
            pendingAttachDevice = device
            return
        }
        service.onUsbAttachIntent(device)
    }

    private fun readUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun renderCaptureState() {
        val service = capture ?: return
        updateStatus(service.statusText)
        setSwitchChecked(service.isStreaming)
        bindDeviceDropdown(service.uvcDevices, service.currentDevice ?: service.uvcDevices.firstOrNull())
        bindModeDropdowns(service.streamModes, service.selectedMode, service.selectedFps)
        if (!editHttpPort.isFocused) {
            val port = if (service.http.isRunning) {
                UvcNative.nativeGetHttpServerPort()
            } else {
                service.http.port
            }
            val shown = editHttpPort.text?.toString().orEmpty()
            if (shown != port.toString()) {
                editHttpPort.setText(port.toString())
            }
        }
        refreshHttpUi(forceList = false)
        if (!service.isDeviceOpen) {
            previewController.clearFrame()
        } else if (service.isStreaming && switchPreview.isChecked && uiResumed) {
            previewController.syncNative()
        }
    }

    private fun bindDropdowns() {
        dropdownDevice.setOnItemClickListener { _, _, pos, _ ->
            if (suppressDeviceCallback) return@setOnItemClickListener
            val service = capture ?: return@setOnItemClickListener
            val device = service.uvcDevices.getOrNull(pos) ?: return@setOnItemClickListener
            service.selectDevice(device)
        }
        dropdownResolution.setOnItemClickListener { _, _, pos, _ ->
            if (suppressResolutionCallback) return@setOnItemClickListener
            val mode = capture?.streamModes?.getOrNull(pos) ?: return@setOnItemClickListener
            capture?.setResolution(mode)
        }
        dropdownFps.setOnItemClickListener { _, _, _, _ ->
            if (suppressFpsCallback) return@setOnItemClickListener
            val fps = StreamMode.parseFpsLabel(dropdownFps.text?.toString().orEmpty()) ?: return@setOnItemClickListener
            capture?.setFps(fps)
        }
    }

    private fun bindDeviceDropdown(devices: List<UsbDevice>, selected: UsbDevice?) {
        val sig = devices.joinToString("|") { it.deviceName } + "#" + (selected?.deviceName ?: "")
        if (sig == lastDeviceSig) return
        lastDeviceSig = sig
        val labels = devices.map { UvcDeviceFinder.labelFor(it) }
        val selectedIndex = selected?.let { sel ->
            devices.indexOfFirst { it.deviceName == sel.deviceName }
        }?.takeIf { it >= 0 } ?: 0
        suppressDeviceCallback = true
        dropdownDevice.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (labels.isNotEmpty()) {
            dropdownDevice.setText(labels[selectedIndex.coerceAtMost(labels.lastIndex)], false)
        } else {
            dropdownDevice.setText("", false)
        }
        suppressDeviceCallback = false
    }

    private fun bindModeDropdowns(modes: List<StreamMode>, selected: StreamMode?, fps: Int) {
        val sig = modes.joinToString("|") { it.sizeLabel } + "#" + (selected?.sizeLabel ?: "") + "@$fps"
        if (sig == lastModeSig) return
        lastModeSig = sig
        val labels = modes.map { it.sizeLabel }
        suppressResolutionCallback = true
        dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (selected != null && modes.isNotEmpty()) {
            dropdownResolution.setText(selected.sizeLabel, false)
            setDropdownEnabled(layoutResolution, dropdownResolution, modes.size > 1)
            bindFpsDropdown(selected, fps)
        } else {
            dropdownResolution.setText("", false)
            setDropdownEnabled(layoutResolution, dropdownResolution, enabled = false)
            clearFpsDropdown()
        }
        suppressResolutionCallback = false
    }

    private fun bindFpsDropdown(mode: StreamMode, preferFps: Int) {
        val labels = mode.fpsLabels()
        val selectedFps = mode.nearestFps(preferFps)
        suppressFpsCallback = true
        dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        dropdownFps.setText("$selectedFps fps", false)
        setDropdownEnabled(layoutFps, dropdownFps, labels.size > 1)
        suppressFpsCallback = false
    }

    private fun clearFpsDropdown() {
        suppressFpsCallback = true
        dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, emptyList<String>()))
        dropdownFps.setText("", false)
        setDropdownEnabled(layoutFps, dropdownFps, enabled = false)
        suppressFpsCallback = false
    }

    private fun setDropdownEnabled(
        layout: TextInputLayout,
        dropdown: AutoCompleteTextView,
        enabled: Boolean,
    ) {
        layout.isEnabled = enabled
        dropdown.isEnabled = enabled
        dropdown.isClickable = enabled
        layout.endIconMode = if (enabled) {
            TextInputLayout.END_ICON_DROPDOWN_MENU
        } else {
            TextInputLayout.END_ICON_NONE
        }
    }

    private fun applyHttpPortFromUi() {
        val service = capture ?: return
        val raw = editHttpPort.text?.toString()?.trim().orEmpty()
        val p = raw.toIntOrNull()
        if (p == null || p !in 1..65535) {
            updateStatus(getString(R.string.status_bad_port))
            editHttpPort.setText(service.http.port.toString())
            refreshHttpUi()
            return
        }
        val ok = service.applyHttpPort(p)
        editHttpPort.setText(service.http.port.toString())
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
            val http = capture?.http
            val running = http?.isRunning == true
            val port = when {
                http == null -> HttpStreamController.DEFAULT_PORT
                running -> UvcNative.nativeGetHttpServerPort()
                else -> http.port
            }
            val clients = http?.clientCount ?: 0
            val state = if (running) {
                getString(R.string.http_running, port, clients)
            } else {
                getString(R.string.http_stopped, port)
            }
            if (state != lastHttpStateText) {
                lastHttpStateText = state
                tvHttpState.text = state
            }
            if (forceList) {
                lastAccessSignature = ""
            }
            renderAccessList(http?.accessEndpoints().orEmpty())
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

    private fun copyQqGroup() {
        val number = getString(R.string.qq_group_number)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq", number))
        Toast.makeText(this, R.string.qq_group_copied, Toast.LENGTH_SHORT).show()
    }

    private fun onStreamSwitchChanged(isChecked: Boolean) {
        FileLogger.log("Switch changed: $isChecked")
        val service = capture ?: return
        if (isChecked) {
            val label = dropdownDevice.text?.toString()
            val device = service.uvcDevices.firstOrNull { UvcDeviceFinder.labelFor(it) == label }
            service.startCapture(device)
        } else {
            service.stopCapture(userInitiated = true)
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        if (switchStream.isChecked == checked) return
        switchStream.setOnCheckedChangeListener(null)
        switchStream.isChecked = checked
        switchStream.setOnCheckedChangeListener { _, isChecked ->
            onStreamSwitchChanged(isChecked)
        }
    }

    private fun syncBatterySwitch() {
        val exempt = BatteryKeepAlive.isExempt(this)
        if (switchBattery.isChecked == exempt) return
        switchBattery.setOnCheckedChangeListener(null)
        switchBattery.isChecked = exempt
        switchBattery.setOnCheckedChangeListener { _, checked ->
            if (checked == BatteryKeepAlive.isExempt(this)) return@setOnCheckedChangeListener
            BatteryKeepAlive.openExemptionUi(this)
            fpsHandler.post { syncBatterySwitch() }
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
        if (bound) {
            capture?.removeListener(this)
            runCatching { unbindService(connection) }
            bound = false
        }
        capture = null
    }
}
