package com.omoai.simpleuvcstreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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
import com.omoai.simpleuvcstreamer.usb.UsbAutoLaunch
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.AutoStartPrefs
import com.omoai.simpleuvcstreamer.uvc.DeviceHistory
import com.omoai.simpleuvcstreamer.uvc.StreamMode
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
    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchAutoLaunch: MaterialSwitch
    private lateinit var switchPreview: MaterialSwitch
    private lateinit var previewContainer: MaterialCardView
    private lateinit var dropdownDevice: AutoCompleteTextView
    private lateinit var dropdownResolution: AutoCompleteTextView
    private lateinit var dropdownFps: AutoCompleteTextView
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
    private var streamModes: List<StreamMode> = emptyList()
    private var suppressDeviceCallback = false
    private var suppressResolutionCallback = false
    private var suppressFpsCallback = false
    private var pendingStartAfterPermission = false
    private var lastAccessSignature: String = ""
    /** Consume once: auto-start stream after app launch when [AutoStartPrefs] is on. */
    private var pendingLaunchAutoStart = false
    /** After hot-unplug, suppress auto reopen briefly so bump/reconnect can settle. */
    private var suppressAutoOpenUntilMs: Long = 0L

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
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchAutoLaunch = findViewById(R.id.switchAutoLaunch)
        switchPreview = findViewById(R.id.switchPreview)
        previewContainer = findViewById(R.id.previewContainer)
        dropdownDevice = findViewById(R.id.dropdownDevice)
        dropdownResolution = findViewById(R.id.dropdownResolution)
        dropdownFps = findViewById(R.id.dropdownFps)
        imagePreview = findViewById(R.id.imagePreview)
        editHttpPort = findViewById(R.id.editHttpPort)
        btnApplyHttpPort = findViewById(R.id.btnApplyHttpPort)
        tvHttpState = findViewById(R.id.tvHttpState)
        listAccessUrls = findViewById(R.id.listAccessUrls)
        tvHttpNoUrls = findViewById(R.id.tvHttpNoUrls)
        findViewById<TextView>(R.id.tvQqGroup).setOnClickListener { copyQqGroup() }

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
        switchAutoStart.isChecked = AutoStartPrefs.isEnabled(this)
        if (switchAutoStart.isChecked) {
            pendingLaunchAutoStart = true
            val httpOk = httpStream.ensureStarted()
            FileLogger.log("HTTP auto-start ok=$httpOk port=${httpStream.port}")
        } else {
            FileLogger.log("HTTP auto-start skipped (option off)")
        }
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            AutoStartPrefs.setEnabled(this, checked)
            if (checked && !httpStream.isRunning) {
                httpStream.ensureStarted()
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
        refreshHttpUi(forceList = true)

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
        handleUsbAttachIntent(intent, autoStart = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent, autoStart = true)
    }

    /**
     * Launched by the system when a filtered UVC device is plugged in.
     * In that path USB permission is usually already granted.
     */
    private fun handleUsbAttachIntent(intent: Intent?, autoStart: Boolean) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = readUsbDeviceExtra(intent) ?: return
        FileLogger.log("USB attach intent: ${device.deviceName} ${device.productName}")
        if (!::session.isInitialized || !UvcNative.isLibLoaded) return
        val wantStream = autoStart &&
            AutoStartPrefs.isEnabled(this) &&
            !session.isStreaming &&
            !isAutoOpenSuppressed()
        refreshDeviceList(preferDevice = device, forceAutoStream = wantStream)
    }

    private fun readUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
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

    private fun copyQqGroup() {
        val number = getString(R.string.qq_group_number)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qq", number))
        Toast.makeText(this, R.string.qq_group_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDeviceAttached() {
        if (session.isStreaming) {
            refreshDeviceList()
            return
        }
        val auto = AutoStartPrefs.isEnabled(this) && !isAutoOpenSuppressed()
        refreshDeviceList(forceAutoStream = auto)
    }

    override fun onDeviceDetached(device: UsbDevice) {
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice)) {
            FileLogger.log("Current UVC device detached — stopping cleanly")
            // Turn off stream switch first so refreshDeviceList will not reopen.
            setSwitchChecked(false)
            suppressAutoOpen(2500L)
            pendingStartAfterPermission = false
            pendingLaunchAutoStart = false
            runCatching { session.close() }
                .onFailure { FileLogger.log("session.close on detach: ${it.message}") }
            clearModes()
            updateStatus(getString(R.string.status_device_detached))
        }
        refreshDeviceList()
    }

    private fun suppressAutoOpen(ms: Long) {
        suppressAutoOpenUntilMs = System.currentTimeMillis() + ms
    }

    private fun isAutoOpenSuppressed(): Boolean {
        return System.currentTimeMillis() < suppressAutoOpenUntilMs
    }

    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        if (granted && device != null) {
            val shouldStart = pendingStartAfterPermission || switchStream.isChecked
            pendingStartAfterPermission = false
            openDeviceAndLoadModes(device, startStream = shouldStart)
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
            val mode = streamModes.getOrNull(pos) ?: return@setOnItemClickListener
            bindFpsDropdown(mode, preferFps = mode.defaultFps)
            if (session.isStreaming) {
                applySelectedMode()
            }
        }
        dropdownFps.setOnItemClickListener { _, _, _, _ ->
            if (suppressFpsCallback) return@setOnItemClickListener
            if (session.isStreaming) {
                applySelectedMode()
            }
        }
    }

    private fun refreshDeviceList(
        preferDevice: UsbDevice? = null,
        forceAutoStream: Boolean = false,
    ) {
        val previous = uvcDevices
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

        // Newly appeared devices count as "just connected" for history ranking.
        for (device in uvcDevices) {
            val isNew = previous.none { it.deviceName == device.deviceName }
            if (isNew) {
                DeviceHistory.touch(this, device)
            }
        }
        preferDevice?.let { preferred ->
            uvcDevices.firstOrNull { it.deviceName == preferred.deviceName }
                ?.let { DeviceHistory.touch(this, it) }
        }

        val selected = pickDeviceForUi()
        val labels = uvcDevices.map { UvcDeviceFinder.labelFor(it) }
        val selectedIndex = selected?.let { sel ->
            uvcDevices.indexOfFirst { it.deviceName == sel.deviceName }
        }?.takeIf { it >= 0 } ?: 0

        suppressDeviceCallback = true
        dropdownDevice.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (labels.isNotEmpty()) {
            dropdownDevice.setText(labels[selectedIndex], false)
        } else {
            dropdownDevice.setText("", false)
        }
        suppressDeviceCallback = false

        if (uvcDevices.isEmpty()) {
            pendingLaunchAutoStart = false
            session.close()
            clearModes()
            updateStatus(getString(R.string.status_no_device))
            return
        }

        val launchAuto = pendingLaunchAutoStart && AutoStartPrefs.isEnabled(this)
        pendingLaunchAutoStart = false
        val wantStart = !session.isStreaming &&
            !isAutoOpenSuppressed() &&
            (switchStream.isChecked || forceAutoStream || launchAuto)

        if (wantStart) {
            ensureHttpRunning()
        }

        prepareDevice(selected ?: uvcDevices.first(), startStream = wantStart)
    }

    /**
     * While streaming, keep the current device if still plugged.
     * Otherwise prefer the most recently used model among connected devices.
     */
    private fun pickDeviceForUi(): UsbDevice? {
        if (uvcDevices.isEmpty()) return null
        if (session.isStreaming) {
            val current = session.currentDevice
            uvcDevices.firstOrNull { UvcDeviceFinder.sameDevice(it, current) }?.let { return it }
        }
        return DeviceHistory.pickPreferred(this, uvcDevices) ?: uvcDevices.first()
    }

    private fun ensureHttpRunning() {
        if (httpStream.isRunning) return
        val ok = httpStream.ensureStarted()
        FileLogger.log("HTTP ensureStarted ok=$ok port=${httpStream.port}")
        refreshHttpUi(forceList = true)
    }

    private fun prepareDevice(device: UsbDevice, startStream: Boolean) {
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice) && session.isDeviceOpen) {
            when {
                startStream && !session.isStreaming -> applySelectedMode()
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

        openDeviceAndLoadModes(device, startStream)
    }

    private fun openDeviceAndLoadModes(device: UsbDevice, startStream: Boolean) {
        if (!session.open(device)) {
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_open_failed))
            return
        }
        DeviceHistory.touch(this, device)

        val modes = loadModesIntoDropdown(session.loadStreamModes())
        if (modes.isEmpty()) {
            updateStatus(getString(R.string.status_no_mjpeg))
            setSwitchChecked(false)
            return
        }

        updateStatus(getString(R.string.status_resolutions_ready, modes.size))
        if (startStream) {
            ensureHttpRunning()
            applySelectedMode()
        }
    }

    private fun loadModesIntoDropdown(modes: List<StreamMode>): List<StreamMode> {
        streamModes = modes
        val labels = modes.map { it.sizeLabel }
        suppressResolutionCallback = true
        dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (modes.isNotEmpty()) {
            val remembered = session.currentDevice?.let { DeviceHistory.loadMode(this, it) }
            val rememberedIdx = remembered?.let {
                StreamMode.indexOfSize(modes, it.width, it.height)
            }?.takeIf { it >= 0 }
            val idx = rememberedIdx ?: StreamMode.preferredIndex(modes)
            val mode = modes[idx]
            val preferFps = when {
                remembered != null &&
                    remembered.width == mode.width &&
                    remembered.height == mode.height &&
                    mode.fpsList.contains(remembered.fps) -> remembered.fps
                else -> mode.defaultFps
            }
            dropdownResolution.setText(mode.sizeLabel, false)
            bindFpsDropdown(mode, preferFps = preferFps)
            if (remembered != null && rememberedIdx != null) {
                val key = session.currentDevice?.let { DeviceHistory.modelKey(it) } ?: "?"
                FileLogger.log(
                    "Restored remembered mode ${mode.sizeLabel} @${preferFps}fps for $key"
                )
            }
        } else {
            dropdownResolution.setText("", false)
            clearFpsDropdown()
        }
        suppressResolutionCallback = false
        return modes
    }

    private fun bindFpsDropdown(mode: StreamMode, preferFps: Int) {
        val labels = mode.fpsLabels()
        val selectedFps = mode.nearestFps(preferFps)
        suppressFpsCallback = true
        dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        dropdownFps.setText("$selectedFps fps", false)
        suppressFpsCallback = false
    }

    private fun clearFpsDropdown() {
        suppressFpsCallback = true
        dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, emptyList<String>()))
        dropdownFps.setText("", false)
        suppressFpsCallback = false
    }

    private fun clearModes() {
        streamModes = emptyList()
        suppressResolutionCallback = true
        dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, emptyList<String>()))
        dropdownResolution.setText("", false)
        suppressResolutionCallback = false
        clearFpsDropdown()
    }

    private fun startStreaming() {
        if (uvcDevices.isEmpty()) {
            FileLogger.log("startStreaming: No UVC devices")
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_no_device))
            return
        }
        ensureHttpRunning()
        val label = dropdownDevice.text?.toString()
        val index = uvcDevices.indexOfFirst { UvcDeviceFinder.labelFor(it) == label }.coerceAtLeast(0)
        val device = uvcDevices.getOrNull(index) ?: run {
            setSwitchChecked(false)
            return
        }
        prepareDevice(device, startStream = true)
    }

    private fun selectedMode(): StreamMode? {
        val label = dropdownResolution.text?.toString()?.trim().orEmpty()
        return streamModes.firstOrNull { it.sizeLabel == label }
    }

    private fun selectedFps(mode: StreamMode): Int? {
        val fromUi = StreamMode.parseFpsLabel(dropdownFps.text?.toString().orEmpty())
        if (fromUi != null && mode.fpsList.contains(fromUi)) return fromUi
        return mode.defaultFps.takeIf { mode.fpsList.contains(it) } ?: mode.fpsList.firstOrNull()
    }

    private fun selectModeInUi(mode: StreamMode, fps: Int) {
        suppressResolutionCallback = true
        dropdownResolution.setText(mode.sizeLabel, false)
        suppressResolutionCallback = false
        bindFpsDropdown(mode, preferFps = fps)
    }

    private fun rememberSuccess(width: Int, height: Int, fps: Int) {
        val device = session.currentDevice ?: return
        DeviceHistory.saveMode(this, device, width, height, fps)
    }

    private fun applySelectedMode() {
        val mode = selectedMode()
        if (mode == null) {
            FileLogger.log("applySelectedMode: no resolution selected")
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_select_resolution))
            return
        }
        val fps = selectedFps(mode)
        if (fps == null) {
            FileLogger.log("applySelectedMode: no fps selected")
            setSwitchChecked(false)
            updateStatus(getString(R.string.status_select_fps))
            return
        }
        if (!session.isDeviceOpen) {
            setSwitchChecked(false)
            return
        }

        var lastRes = -1
        for ((attempt, attemptFps) in streamAttempts(mode, fps)) {
            lastRes = session.startStream(attempt.width, attempt.height, attemptFps)
            if (lastRes != 0) continue
            val fallback =
                attempt.width != mode.width ||
                    attempt.height != mode.height ||
                    attemptFps != fps
            selectModeInUi(attempt, attemptFps)
            rememberSuccess(attempt.width, attempt.height, attemptFps)
            setSwitchChecked(true)
            updateStatus(
                if (fallback) {
                    getString(
                        R.string.status_streaming_fallback,
                        attempt.width,
                        attempt.height,
                        attemptFps,
                    )
                } else {
                    getString(R.string.status_streaming, attempt.width, attempt.height, attemptFps)
                },
            )
            return
        }

        FileLogger.log("applySelectedMode: all modes failed (last=$lastRes)")
        session.currentDevice?.let { DeviceHistory.clearMode(this, it) }
        setSwitchChecked(false)
        updateStatus(
            getString(R.string.status_stream_error, lastRes, mode.width, mode.height, fps),
        )
    }

    private fun streamAttempts(mode: StreamMode, fps: Int): List<Pair<StreamMode, Int>> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Pair<StreamMode, Int>>()
        fun add(m: StreamMode, f: Int) {
            val key = "${m.width}x${m.height}@$f"
            if (!seen.add(key)) return
            out.add(m to f)
        }
        add(mode, fps)
        mode.fpsList.sorted().forEach { add(mode, it) }
        streamModes
            .filter { it.width != mode.width || it.height != mode.height }
            .sortedBy { it.width * it.height }
            .forEach { other ->
                add(other, other.defaultFps)
                other.fpsList.sorted().forEach { add(other, it) }
            }
        return out
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
