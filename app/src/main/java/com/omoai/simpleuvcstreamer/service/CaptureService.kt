package com.omoai.simpleuvcstreamer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.omoai.simpleuvcstreamer.R
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.AutoStartPrefs
import com.omoai.simpleuvcstreamer.uvc.DeviceHistory
import com.omoai.simpleuvcstreamer.uvc.StreamMode
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import com.omoai.simpleuvcstreamer.uvc.UvcSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns USB capture + HTTP MJPEG. Promoted to a connectedDevice foreground
 * service while the user wants capture, so leaving the UI does not tear down
 * the stream.
 */
class CaptureService : Service(), UsbDeviceMonitor.Listener {

    fun interface Listener {
        fun onCaptureStateChanged()
    }

    inner class LocalBinder : Binder() {
        fun service(): CaptureService = this@CaptureService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locks: KeepAliveLocks

    lateinit var usbManager: UsbManager
        private set
    lateinit var session: UvcSession
        private set
    lateinit var http: HttpStreamController
        private set

    private lateinit var usbMonitor: UsbDeviceMonitor

    var uvcDevices: List<UsbDevice> = emptyList()
        private set
    var streamModes: List<StreamMode> = emptyList()
        private set
    var selectedMode: StreamMode? = null
        private set
    var selectedFps: Int = 0
        private set
    var statusText: String = ""
        private set
    var nativeReady: Boolean = false
        private set

    /** Capture was on when the cable came out — resume after the same camera returns. */
    var resumeStreamOnReattach: Boolean = false
        private set

    private var bindCount = 0
    private var foreground = false
    private var pendingNotifyText: String? = null
    private var pendingStartAfterPermission = false
    private var suppressAutoOpenUntilMs: Long = 0L
    private var permissionWaitDeviceName: String? = null
    private var pendingRefreshDevice: UsbDevice? = null
    private var pendingRefreshAuto = false
    private var permissionRetryDevice: UsbDevice? = null
    private var permissionRetryStart = false

    private val usbRetryRunnable = Runnable {
        refreshDeviceList(
            preferDevice = pendingRefreshDevice,
            forceAutoStream = pendingRefreshAuto || shouldResumeStream(),
        )
    }
    private val permissionRetryRunnable = Runnable {
        val name = permissionRetryDevice?.deviceName ?: return@Runnable
        val live = usbManager.deviceList[name]
        if (live == null) {
            FileLogger.log("USB permission retry aborted — device gone: $name")
            permissionRetryDevice = null
            permissionWaitDeviceName = null
            return@Runnable
        }
        prepareDevice(live, startStream = permissionRetryStart || shouldResumeStream())
    }

    val isStreaming: Boolean get() = session.isStreaming
    val isDeviceOpen: Boolean get() = session.isDeviceOpen
    val currentDevice: UsbDevice? get() = session.currentDevice

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onCaptureStateChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.log("CaptureService onCreate")
        locks = KeepAliveLocks(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        session = UvcSession(usbManager)
        http = HttpStreamController(this)
        statusText = getString(R.string.status_ready)

        if (!UvcNative.isLibLoaded) {
            statusText = getString(R.string.status_lib_missing)
            FileLogger.log("FATAL: Library not loaded")
            notifyUi()
            return
        }
        try {
            val res = UvcNative.nativeInit()
            FileLogger.log("nativeInit result: $res")
            nativeReady = res == 0
            if (!nativeReady) {
                statusText = getString(R.string.status_init_crash)
            }
        } catch (t: Throwable) {
            FileLogger.log("nativeInit CRASHED: ${t.message}")
            nativeReady = false
            statusText = getString(R.string.status_init_crash)
            notifyUi()
            return
        }

        usbMonitor = UsbDeviceMonitor(this, ACTION_USB_PERMISSION, this)
        usbMonitor.register()

        if (AutoStartPrefs.isEnabled(this) || CaptureKeepAlive.wantStreaming(this)) {
            requestKeepAlive(getString(R.string.notify_waiting))
            http.ensureStarted()
        }
        refreshDeviceList(
            forceAutoStream = AutoStartPrefs.isEnabled(this) || CaptureKeepAlive.wantStreaming(this),
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        bindCount++
        FileLogger.log("CaptureService onBind count=$bindCount")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount = (bindCount - 1).coerceAtLeast(0)
        FileLogger.log("CaptureService onUnbind count=$bindCount streaming=$isStreaming")
        if (!shouldStayAlive()) {
            stopSelf()
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.log("CaptureService onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture(userInitiated = true)
                return if (shouldStayAlive()) START_STICKY else START_NOT_STICKY
            }
        }
        val text = pendingNotifyText
            ?: statusText.takeIf { it.isNotBlank() }
            ?: getString(R.string.notify_waiting)
        startForegroundInternal(text)
        if (!shouldStayAlive()) {
            leaveForeground()
            if (bindCount == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (!isStreaming && nativeReady) {
            refreshDeviceList(forceAutoStream = true)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLogger.log("CaptureService onTaskRemoved streaming=$isStreaming")
        if (!shouldStayAlive()) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FileLogger.log("CaptureService onDestroy")
        handler.removeCallbacks(usbRetryRunnable)
        handler.removeCallbacks(permissionRetryRunnable)
        if (::usbMonitor.isInitialized) {
            usbMonitor.unregister()
        }
        if (::locks.isInitialized) {
            locks.release()
        }
        leaveForeground()
        if (::session.isInitialized) {
            runCatching { session.close() }
        }
        if (::http.isInitialized) {
            http.stop()
        }
        super.onDestroy()
    }

    fun onUsbAttachIntent(device: UsbDevice) {
        if (!nativeReady) return
        FileLogger.log("USB attach intent: ${device.deviceName} ${device.productName}")
        if (isStreaming) {
            FileLogger.log("USB attach intent while already streaming — ignore chooser relaunch")
            return
        }
        val wantStream = shouldResumeStream()
        scheduleDeviceRefresh(preferDevice = device, forceAutoStream = wantStream)
    }

    /** Called after the UI finishes the runtime-permission / battery keep-alive prompts. */
    fun onRuntimePermissionsReady() {
        if (!nativeReady) return
        refreshDeviceList(
            forceAutoStream = AutoStartPrefs.isEnabled(this) ||
                CaptureKeepAlive.wantStreaming(this) ||
                resumeStreamOnReattach,
        )
    }

    fun selectDevice(device: UsbDevice) {
        prepareDevice(device, startStream = isStreaming || resumeStreamOnReattach)
    }

    fun setResolution(mode: StreamMode) {
        selectedMode = mode
        selectedFps = mode.nearestFps(selectedFps.takeIf { it > 0 } ?: mode.defaultFps)
        notifyUi()
        if (isStreaming) applySelectedMode()
    }

    fun setFps(fps: Int) {
        val mode = selectedMode ?: return
        selectedFps = mode.nearestFps(fps)
        notifyUi()
        if (isStreaming) applySelectedMode()
    }

    fun startCapture(prefer: UsbDevice? = null) {
        CaptureKeepAlive.setWantStreaming(this, true)
        if (uvcDevices.isEmpty()) {
            FileLogger.log("startCapture: No UVC devices")
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_no_device)
            notifyUi()
            return
        }
        requestKeepAlive(getString(R.string.notify_waiting))
        ensureHttpRunning()
        val device = prefer
            ?: currentDevice
            ?: pickDevice()
            ?: uvcDevices.first()
        prepareDevice(device, startStream = true)
    }

    fun stopCapture(userInitiated: Boolean) {
        if (userInitiated) {
            CaptureKeepAlive.setWantStreaming(this, false)
            resumeStreamOnReattach = false
        }
        session.stopStream()
        statusText = getString(R.string.status_idle)
        locks.release()
        if (!shouldStayAlive()) {
            leaveForeground()
            if (bindCount == 0) stopSelf()
        } else {
            enterForeground(statusText)
        }
        notifyUi()
    }

    fun applyHttpPort(port: Int): Boolean {
        val ok = http.applyPort(port)
        notifyUi()
        return ok
    }

    fun ensureHttpRunning() {
        if (http.isRunning) return
        val ok = http.ensureStarted()
        FileLogger.log("HTTP ensureStarted ok=$ok port=${http.port}")
        notifyUi()
    }

    fun frameCountPerSecond(): Int = session.frameCountPerSecond()

    override fun onDeviceAttached(device: UsbDevice?) {
        if (isStreaming) {
            refreshDeviceList()
            return
        }
        scheduleDeviceRefresh(preferDevice = device, forceAutoStream = shouldResumeStream())
    }

    override fun onDeviceDetached(device: UsbDevice) {
        val waitingPerm =
            permissionWaitDeviceName == device.deviceName ||
                permissionRetryDevice?.deviceName == device.deviceName
        if (waitingPerm) {
            FileLogger.log("USB detach while waiting permission — cancel stale request")
            permissionWaitDeviceName = null
            permissionRetryDevice = null
            pendingStartAfterPermission = false
            handler.removeCallbacks(permissionRetryRunnable)
            suppressAutoOpen(1500L)
        }
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice)) {
            FileLogger.log("Current UVC device detached — stopping cleanly")
            resumeStreamOnReattach = isStreaming || CaptureKeepAlive.wantStreaming(this)
            if (::session.isInitialized) {
                runCatching { session.close() }
                    .onFailure { FileLogger.log("session.close on detach: ${it.message}") }
            }
            streamModes = emptyList()
            selectedMode = null
            selectedFps = 0
            pendingStartAfterPermission = false
            permissionWaitDeviceName = null
            handler.removeCallbacks(usbRetryRunnable)
            handler.removeCallbacks(permissionRetryRunnable)
            suppressAutoOpen(2500L)
            statusText = getString(R.string.status_device_detached)
            if (resumeStreamOnReattach) {
                enterForeground(getString(R.string.notify_waiting_reconnect))
            } else {
                locks.release()
                leaveForeground()
            }
            notifyUi()
        }
        refreshDeviceList()
    }

    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        permissionWaitDeviceName = null
        // Pico flapping: permission callback can arrive after DETACH.
        val live = device?.deviceName?.let { usbManager.deviceList[it] }
        if (live == null) {
            FileLogger.log("USB permission result ignored — device no longer connected")
            pendingStartAfterPermission = false
            notifyUi()
            return
        }
        val hasIt = usbManager.hasPermission(live)
        if ((granted || hasIt)) {
            val shouldStart =
                pendingStartAfterPermission ||
                    CaptureKeepAlive.wantStreaming(this) ||
                    resumeStreamOnReattach
            pendingStartAfterPermission = false
            prepareDevice(live, startStream = shouldStart)
        } else {
            pendingStartAfterPermission = false
            resumeStreamOnReattach = false
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_permission_denied)
            leaveForeground()
            notifyUi()
        }
    }

    private fun refreshDeviceList(
        preferDevice: UsbDevice? = null,
        forceAutoStream: Boolean = false,
    ) {
        if (!nativeReady) {
            notifyUi()
            return
        }
        val previous = uvcDevices
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

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

        if (uvcDevices.isEmpty()) {
            session.close()
            streamModes = emptyList()
            selectedMode = null
            selectedFps = 0
            statusText = if (resumeStreamOnReattach) {
                getString(R.string.status_device_detached)
            } else {
                val rawCount = usbManager.deviceList.size
                if (rawCount > 0) {
                    getString(R.string.status_no_uvc_but_usb, rawCount)
                } else {
                    getString(R.string.status_no_device)
                }
            }
            notifyUi()
            return
        }

        val wantStart = !isStreaming &&
            !isAutoOpenSuppressed() &&
            (forceAutoStream || CaptureKeepAlive.wantStreaming(this) || resumeStreamOnReattach)

        if (wantStart) {
            ensureHttpRunning()
        }
        if (isStreaming) {
            notifyUi()
            return
        }
        val device = pickDevice() ?: uvcDevices.first()
        // Pico: UsbPermissionActivity often hides our virtual display ("crash").
        // Only request USB permission when opening/streaming, or when already granted.
        when {
            wantStart -> prepareDevice(device, startStream = true)
            usbManager.hasPermission(device) -> prepareDevice(device, startStream = false)
            else -> {
                statusText = getString(
                    R.string.status_uvc_listed,
                    device.productName ?: device.deviceName,
                )
                notifyUi()
            }
        }
    }

    private fun pickDevice(): UsbDevice? {
        if (uvcDevices.isEmpty()) return null
        if (isStreaming) {
            val current = session.currentDevice
            uvcDevices.firstOrNull { UvcDeviceFinder.sameDevice(it, current) }?.let { return it }
        }
        return DeviceHistory.pickPreferred(this, uvcDevices) ?: uvcDevices.first()
    }

    private fun prepareDevice(device: UsbDevice, startStream: Boolean) {
        if (usbManager.deviceList[device.deviceName] == null) {
            FileLogger.log("prepareDevice skipped — not in deviceList: ${device.deviceName}")
            return
        }
        if (UvcDeviceFinder.sameDevice(device, session.currentDevice) && session.isDeviceOpen) {
            if (startStream && !isStreaming) {
                applySelectedMode()
            } else {
                notifyUi()
            }
            return
        }

        if (!usbManager.hasPermission(device)) {
            pendingStartAfterPermission = startStream || resumeStreamOnReattach
            if (permissionWaitDeviceName != device.deviceName) {
                permissionWaitDeviceName = device.deviceName
                permissionRetryDevice = device
                permissionRetryStart = pendingStartAfterPermission
                FileLogger.log("USB permission not ready for ${device.deviceName}, retry 300ms")
                handler.removeCallbacks(permissionRetryRunnable)
                handler.postDelayed(permissionRetryRunnable, 300L)
                return
            }
            // Still present?
            if (usbManager.deviceList[device.deviceName] == null) {
                FileLogger.log("Skip USB permission dialog — device already gone")
                permissionWaitDeviceName = null
                permissionRetryDevice = null
                return
            }
            FileLogger.log("Requesting USB permission for ${device.productName}")
            usbMonitor.requestPermission(usbManager, device)
            statusText = getString(R.string.status_waiting_permission)
            notifyUi()
            return
        }
        permissionWaitDeviceName = null
        if (AppPermissions.missingCameraAccess(this)) {
            FileLogger.log("Defer open: camera permission missing (Quest USB_CAMERA / CAMERA)")
            statusText = getString(R.string.status_waiting_camera_permission)
            notifyUi()
            return
        }
        openDeviceAndLoadModes(device, startStream)
    }

    private fun openDeviceAndLoadModes(device: UsbDevice, startStream: Boolean) {
        if (!session.open(device)) {
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_open_failed)
            notifyUi()
            return
        }
        DeviceHistory.touch(this, device)
        val modes = session.loadStreamModes()
        streamModes = modes
        if (modes.isEmpty()) {
            selectedMode = null
            selectedFps = 0
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_no_stream_format)
            notifyUi()
            return
        }
        pickInitialMode(modes, device)
        statusText = getString(R.string.status_resolutions_ready, modes.size)
        notifyUi()
        if (startStream) {
            ensureHttpRunning()
            applySelectedMode()
        }
    }

    private fun pickInitialMode(modes: List<StreamMode>, device: UsbDevice) {
        val remembered = DeviceHistory.loadMode(this, device)
        val rememberedIdx = remembered?.let {
            StreamMode.indexOfSize(modes, it.width, it.height)
        }?.takeIf { it >= 0 }
        val idx = rememberedIdx ?: StreamMode.preferredIndex(modes)
        val mode = modes[idx]
        selectedMode = mode
        selectedFps = when {
            remembered != null &&
                remembered.width == mode.width &&
                remembered.height == mode.height &&
                mode.fpsList.contains(remembered.fps) -> remembered.fps
            else -> mode.defaultFps
        }
        if (remembered != null && rememberedIdx != null) {
            FileLogger.log(
                "Restored remembered mode ${mode.sizeLabel} @${selectedFps}fps for ${DeviceHistory.modelKey(device)}"
            )
        }
    }

    private fun applySelectedMode() {
        val mode = selectedMode
        if (mode == null) {
            FileLogger.log("applySelectedMode: no resolution selected")
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_select_resolution)
            notifyUi()
            return
        }
        val fps = selectedFps.takeIf { mode.fpsList.contains(it) }
            ?: mode.defaultFps.takeIf { mode.fpsList.contains(it) }
            ?: mode.fpsList.firstOrNull()
        if (fps == null) {
            FileLogger.log("applySelectedMode: no fps selected")
            CaptureKeepAlive.setWantStreaming(this, false)
            statusText = getString(R.string.status_select_fps)
            notifyUi()
            return
        }
        if (!session.isDeviceOpen) {
            CaptureKeepAlive.setWantStreaming(this, false)
            notifyUi()
            return
        }

        var lastRes = -1
        for ((attempt, attemptFps) in streamAttempts(mode, fps)) {
            lastRes = session.startStream(
                attempt.width,
                attempt.height,
                attemptFps,
                attempt.format.wire,
            )
            if (lastRes != 0) continue
            val fallback =
                attempt.width != mode.width ||
                    attempt.height != mode.height ||
                    attemptFps != fps
            selectedMode = attempt
            selectedFps = attemptFps
            session.currentDevice?.let {
                DeviceHistory.saveMode(this, it, attempt.width, attempt.height, attemptFps)
            }
            resumeStreamOnReattach = false
            CaptureKeepAlive.setWantStreaming(this, true)
            locks.acquire()
            statusText = if (fallback) {
                if (attempt.format.isYuv) {
                    getString(
                        R.string.status_streaming_fallback_yuv,
                        attempt.width,
                        attempt.height,
                        attemptFps,
                    )
                } else {
                    getString(R.string.status_streaming_fallback, attempt.width, attempt.height, attemptFps)
                }
            } else if (attempt.format.isYuv) {
                getString(R.string.status_streaming_yuv, attempt.width, attempt.height, attemptFps)
            } else {
                getString(R.string.status_streaming, attempt.width, attempt.height, attemptFps)
            }
            enterForeground(statusText)
            notifyUi()
            return
        }

        FileLogger.log("applySelectedMode: all modes failed (last=$lastRes)")
        session.currentDevice?.let { DeviceHistory.clearMode(this, it) }
        CaptureKeepAlive.setWantStreaming(this, false)
        locks.release()
        leaveForeground()
        statusText = getString(R.string.status_stream_error, lastRes, mode.width, mode.height, fps)
        notifyUi()
    }

    private fun streamAttempts(mode: StreamMode, fps: Int): List<Pair<StreamMode, Int>> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Pair<StreamMode, Int>>()
        fun add(m: StreamMode, f: Int) {
            val key = "${m.format.wire}:${m.width}x${m.height}@$f"
            if (!seen.add(key)) return
            out.add(m to f)
        }
        add(mode, fps)
        mode.fpsList.sorted().forEach { add(mode, it) }
        streamModes
            .filter {
                it.format == mode.format &&
                    (it.width != mode.width || it.height != mode.height)
            }
            .sortedBy { it.width * it.height }
            .forEach { other ->
                add(other, other.defaultFps)
                other.fpsList.sorted().forEach { add(other, it) }
            }
        return out
    }

    private fun shouldResumeStream(): Boolean {
        return resumeStreamOnReattach ||
            AutoStartPrefs.isEnabled(this) ||
            CaptureKeepAlive.wantStreaming(this)
    }

    private fun shouldStayAlive(): Boolean {
        return isStreaming ||
            resumeStreamOnReattach ||
            CaptureKeepAlive.wantStreaming(this)
    }

    private fun scheduleDeviceRefresh(preferDevice: UsbDevice?, forceAutoStream: Boolean) {
        handler.removeCallbacks(usbRetryRunnable)
        pendingRefreshDevice = preferDevice
        pendingRefreshAuto = forceAutoStream
        val delay = (suppressAutoOpenUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
        if (delay == 0L) {
            refreshDeviceList(preferDevice = preferDevice, forceAutoStream = forceAutoStream)
            return
        }
        FileLogger.log("Defer USB reopen ${delay}ms (hot-unplug settle)")
        handler.postDelayed(usbRetryRunnable, delay + 50L)
    }

    private fun suppressAutoOpen(ms: Long) {
        suppressAutoOpenUntilMs = System.currentTimeMillis() + ms
    }

    private fun isAutoOpenSuppressed(): Boolean {
        return System.currentTimeMillis() < suppressAutoOpenUntilMs
    }

    private fun requestKeepAlive(text: String? = null) {
        if (text != null) pendingNotifyText = text
        val intent = Intent(this, CaptureService::class.java).setAction(ACTION_KEEP)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            FileLogger.log("startForegroundService: ${t.message}")
            startForegroundInternal(
                pendingNotifyText ?: statusText.ifBlank { getString(R.string.notify_waiting) },
            )
        }
    }

    private fun enterForeground(text: String) {
        pendingNotifyText = text
        if (foreground) {
            startForegroundInternal(text)
            return
        }
        requestKeepAlive(text)
    }

    private fun startForegroundInternal(text: String) {
        val notification = CaptureNotification.build(
            this,
            text,
            showStop = isStreaming || resumeStreamOnReattach || CaptureKeepAlive.wantStreaming(this),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    CaptureNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(CaptureNotification.NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (t: Throwable) {
            FileLogger.log("startForeground: ${t.message}")
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun notifyUi() {
        handler.post {
            listeners.forEach { it.onCaptureStateChanged() }
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
        const val ACTION_KEEP = "com.omoai.simpleuvcstreamer.action.KEEP"
        const val ACTION_STOP = "com.omoai.simpleuvcstreamer.action.STOP"
    }
}
