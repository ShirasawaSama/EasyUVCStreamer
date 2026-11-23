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

/** Owns three independent USB sessions. All control operations run on the main thread. */
class CaptureService : Service(), UsbDeviceMonitor.Listener {
    fun interface Listener { fun onCaptureStateChanged() }
    inner class LocalBinder : Binder() { fun service() = this@CaptureService }
    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locks: KeepAliveLocks
    lateinit var usbManager: UsbManager
        private set
    lateinit var http: HttpStreamController
        private set
    private lateinit var usbMonitor: UsbDeviceMonitor
    private class Channel(val session: UvcSession) {
        var device: UsbDevice? = null
        var modes: List<StreamMode> = emptyList()
        var mode: StreamMode? = null
        var fps = 0
        var serial: String? = null
        var wanted = false
        var waitingPermission = false
        var status = "请选择相机"
    }
    private lateinit var channels: MutableList<Channel>
    var selectedChannel = 0
        private set
    private val selected get() = channels[selectedChannel]
    val session get() = selected.session
    val streamModes get() = selected.modes
    val selectedMode get() = selected.mode
    val selectedFps get() = selected.fps
    val statusText get() = selected.status
    val isStreaming get() = session.isStreaming
    val isDeviceOpen get() = session.isDeviceOpen
    val currentDevice get() = selected.device
    var nativeReady = false
        private set
    var uvcDevices: List<UsbDevice> = emptyList()
        private set
    private var bindCount = 0
    private var foreground = false
    private var pendingNotifyText: String? = null
    private val anyStreaming get() = channels.any { it.session.isStreaming }

    fun addListener(listener: Listener) { listeners.add(listener); listener.onCaptureStateChanged() }
    fun removeListener(listener: Listener) { listeners.remove(listener) }
    private val layoutPrefs by lazy { getSharedPreferences("channel_layout", MODE_PRIVATE) }
    var visibleChannels: List<Int> = listOf(0)
        private set

    fun addChannel() {
        val index = channels.indices.firstOrNull { it !in visibleChannels } ?: return
        visibleChannels = visibleChannels + index
        saveLayout(index)
    }

    fun removeChannel(index: Int) {
        if (index == 0 || index !in visibleChannels) return
        val channel = channels[index]
        stopChannel(channel)
        channel.session.close()
        channels[index] = Channel(UvcSession(usbManager, index))
        val position = visibleChannels.indexOf(index)
        visibleChannels = visibleChannels - index
        saveLayout(visibleChannels[position.coerceAtMost(visibleChannels.lastIndex)])
        updateKeepAlive()
    }

    private fun saveLayout(index: Int) {
        selectedChannel = index
        layoutPrefs.edit().remove("mask").putString("order", visibleChannels.joinToString(",")).putInt("selected", index).apply()
        if (nativeReady) UvcNative.nativeSetChannelOrder(visibleChannels.toIntArray())
        notifyUi()
    }

    fun selectChannel(index: Int) {
        if (index !in visibleChannels) return
        layoutPrefs.edit().putInt("selected", index).apply()
        selectedChannel = index
        notifyUi()
    }

    override fun onCreate() {
        super.onCreate()
        locks = KeepAliveLocks(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        channels = MutableList(UvcNative.CHANNEL_COUNT) { Channel(UvcSession(usbManager, it)) }
        val oldMask = layoutPrefs.getInt("mask", 1)
        val saved = layoutPrefs.getString("order", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?: channels.indices.filter { oldMask and (1 shl it) != 0 }
        visibleChannels = (listOf(0) + saved.filter { it in channels.indices }).distinct()
        selectedChannel = layoutPrefs.getInt("selected", 0).takeIf { it in visibleChannels } ?: 0
        http = HttpStreamController(this)
        nativeReady = UvcNative.isLibLoaded && runCatching { UvcNative.nativeInit() == 0 }.getOrDefault(false)
        if (!nativeReady) {
            channels.forEach { it.status = getString(R.string.status_lib_missing) }
            return
        }
        UvcNative.nativeSetChannelOrder(visibleChannels.toIntArray())
        usbMonitor = UsbDeviceMonitor(this, ACTION_USB_PERMISSION, this)
        usbMonitor.register()
        refreshDevices()
        if (AutoStartPrefs.isEnabled(this) || CaptureKeepAlive.wantStreaming(this)) startCapture()
    }

    override fun onBind(intent: Intent?): IBinder { bindCount++; return binder }
    override fun onUnbind(intent: Intent?): Boolean {
        bindCount = (bindCount - 1).coerceAtLeast(0)
        if (!shouldStayAlive()) stopSelf()
        return false
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            channels.forEach { stopChannel(it) }
            updateKeepAlive()
            return START_NOT_STICKY
        }
        startForegroundInternal(pendingNotifyText ?: "UVC 采集")
        if (!shouldStayAlive()) {
            leaveForeground()
            if (bindCount == 0) stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::usbMonitor.isInitialized) usbMonitor.unregister()
        channels.forEach { it.session.close() }
        http.stop()
        locks.release()
        leaveForeground()
        super.onDestroy()
    }

    fun onUsbAttachIntent(device: UsbDevice) { onDeviceAttached(device) }
    fun onRuntimePermissionsReady() {
        refreshDevices()
        channels.filter { it.wanted || it.status == getString(R.string.status_waiting_camera_permission) }
            .forEach { prepare(it) }
    }
    fun clearSelection() {
        stopChannel(selected)
        selected.session.close()
        selected.device = null
        selected.serial = null
        selected.modes = emptyList(); selected.mode = null; selected.fps = 0
        selected.status = "请选择相机"
        updateKeepAlive()
    }
    fun selectDevice(device: UsbDevice) {
        if (channels.any { it !== selected && it.device?.deviceName == device.deviceName }) {
            selected.status = "这台相机已被另一路使用，请选择另一台相机"
            notifyUi()
            return
        }
        if (selected.device?.deviceName != device.deviceName) {
            selected.session.close()
            selected.modes = emptyList()
            selected.mode = null
            selected.fps = 0
            selected.waitingPermission = false
            selected.serial = null
        }
        selected.device = device
        prepare(selected)
    }
    fun setResolution(mode: StreamMode) {
        if (mode !in selected.modes) return
        selected.mode = mode
        selected.fps = mode.nearestFps(selected.fps.takeIf { it > 0 } ?: StreamMode.DEFAULT_STREAM_FPS)
        if (isStreaming) startChannel(selected)
        notifyUi()
    }
    fun setFps(fps: Int) {
        val mode = selected.mode ?: return
        selected.fps = mode.nearestFps(fps)
        if (isStreaming) startChannel(selected)
        notifyUi()
    }
    fun startCapture(prefer: UsbDevice? = null) {
        if (!nativeReady) return
        refreshDevices()
        val target = prefer ?: selected.device ?: uvcDevices.firstOrNull { candidate ->
            channels.none { it !== selected && it.device?.deviceName == candidate.deviceName }
        }
        if (target == null) {
            selected.status = getString(R.string.status_no_device)
            notifyUi(); return
        }
        if (channels.any { it !== selected && it.device?.deviceName == target.deviceName }) {
            selected.status = "这台相机已被另一路使用"
            notifyUi(); return
        }
        selected.device = target
        selected.wanted = true
        CaptureKeepAlive.setWantStreaming(this, true)
        requestKeepAlive("UVC 采集")
        ensureHttpRunning()
        prepare(selected)
    }
    fun stopCapture() {
        stopChannel(selected)
        updateKeepAlive()
    }
    private fun stopChannel(channel: Channel) {
        channel.wanted = false
        channel.waitingPermission = false
        channel.session.stopStream()
        channel.status = getString(R.string.status_idle)
    }
    private fun updateKeepAlive() {
        val wanted = channels.any { it.wanted }
        CaptureKeepAlive.setWantStreaming(this, wanted)
        if (anyStreaming) locks.acquire() else locks.release()
        if (wanted || anyStreaming) enterForeground("UVC 采集中 · ${channels.count { it.session.isStreaming }} 路")
        else {
            leaveForeground()
            if (bindCount == 0) stopSelf()
        }
        notifyUi()
    }
    fun applyHttpPort(port: Int): Boolean = http.applyPort(port).also { notifyUi() }
    fun ensureHttpRunning() { http.ensureStarted(); notifyUi() }

    override fun onDeviceAttached(device: UsbDevice?) {
        handler.postDelayed({
            refreshDevices()
            if (channels[0].device == null && AutoStartPrefs.isEnabled(this)) {
                channels[0].device = uvcDevices.firstOrNull { candidate -> channels.none { it.device?.deviceName == candidate.deviceName } }
                channels[0].wanted = channels[0].device != null
            }
            if (channels.any { it.wanted }) ensureHttpRunning()
            channels.filter { it.wanted }.forEach { prepare(it) }
        }, 350)
    }
    override fun onDeviceDetached(device: UsbDevice) {
        channels.filter { it.device?.deviceName == device.deviceName }.forEach {
            it.session.close()
            it.waitingPermission = false
            it.modes = emptyList(); it.mode = null; it.fps = 0
            it.status = getString(R.string.status_device_detached)
        }
        refreshDevices()
        updateKeepAlive()
    }
    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        val channel = channels.firstOrNull {
            it.waitingPermission && it.device?.deviceName == device?.deviceName
        } ?: return // Ignore permission replies for a stopped or switched channel.
        channel.waitingPermission = false
        val live = device?.deviceName?.let { usbManager.deviceList[it] } ?: return
        if (granted && usbManager.hasPermission(live)) prepare(channel)
        else {
            channel.wanted = false
            channel.status = getString(R.string.status_permission_denied)
            updateKeepAlive()
        }
    }
    private fun refreshDevices() {
        if (!nativeReady) return
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        // A disconnected channel keeps its assignment; never steal the other camera.
        for (channel in channels) {
            val old = channel.device ?: continue
            if (uvcDevices.any { it.deviceName == old.deviceName }) continue
            val candidates = uvcDevices.filter { live ->
                live.vendorId == old.vendorId && live.productId == old.productId &&
                    channels.none { it !== channel && it.device?.deviceName == live.deviceName }
            }
            val serialMatches = candidates.filter { live ->
                channel.serial != null && runCatching { live.serialNumber }.getOrNull() == channel.serial
            }
            val missingSameModel = channels.count {
                it.device?.vendorId == old.vendorId && it.device?.productId == old.productId &&
                    uvcDevices.none { live -> live.deviceName == it.device?.deviceName }
            }
            val replacement = serialMatches.singleOrNull()?.takeIf {
                channels.count { it.serial != null && it.serial == channel.serial } == 1
            }
                ?: candidates.singleOrNull()?.takeIf { missingSameModel == 1 }
            if (replacement != null) channel.device = replacement
        }
        notifyUi()
    }
    private fun prepare(channel: Channel) {
        if (!nativeReady) return
        val assigned = channel.device ?: return
        val device = usbManager.deviceList[assigned.deviceName]
        if (device == null) {
            channel.status = getString(R.string.status_device_detached)
            notifyUi(); return
        }
        if (AppPermissions.missingCameraAccess(this)) {
            channel.status = getString(R.string.status_waiting_camera_permission)
            notifyUi(); return
        }
        if (!usbManager.hasPermission(device)) {
            if (!channel.waitingPermission) {
                channel.waitingPermission = true
                usbMonitor.requestPermission(usbManager, device)
            }
            channel.status = getString(R.string.status_waiting_permission)
            notifyUi(); return
        }
        channel.serial = runCatching { device.serialNumber }.getOrNull()
        if (!channel.session.isDeviceOpen) {
            if (!channel.session.open(device)) {
                channel.wanted = false
                channel.status = getString(R.string.status_open_failed)
                updateKeepAlive(); return
            }
            channel.modes = channel.session.loadStreamModes()
            if (channel.modes.isEmpty()) {
                channel.session.close()
                channel.wanted = false
                channel.status = getString(R.string.status_no_stream_format)
                updateKeepAlive(); return
            }
            val remembered = DeviceHistory.loadMode(this, device)
            channel.mode = channel.modes.firstOrNull { it.width == remembered?.width && it.height == remembered.height }
                ?: channel.modes[StreamMode.preferredIndex(channel.modes)]
            channel.fps = channel.mode!!.nearestFps(remembered?.fps ?: StreamMode.DEFAULT_STREAM_FPS)
            channel.status = getString(R.string.status_resolutions_ready, channel.modes.size)
        }
        if (channel.wanted && !channel.session.isStreaming) startChannel(channel)
        notifyUi()
    }
    private fun startChannel(channel: Channel) {
        val mode = channel.mode ?: return
        val requestedFps = channel.fps
        val attempts = buildList {
            add(mode to requestedFps)
            mode.fpsList.sortedDescending().filter { it < requestedFps }.forEach { add(mode to it) }
            channel.modes.filter { it.format == mode.format && it.width * it.height < mode.width * mode.height }
                .sortedByDescending { it.width * it.height }.forEach { smaller ->
                    smaller.fpsList.filter { it <= requestedFps }.sortedDescending().forEach { add(smaller to it) }
                }
        }.distinct()
        var result = -1
        for ((attempt, fps) in attempts) {
            result = channel.session.startStream(attempt.width, attempt.height, fps, attempt.format.wire)
            if (result != 0) continue
            channel.wanted = true
            channel.mode = attempt; channel.fps = fps
            channel.device?.let { DeviceHistory.saveMode(this, it, attempt.width, attempt.height, fps) }
            channel.status = getString(
                if (attempt != mode || fps != requestedFps) R.string.status_streaming_fallback
                else if (attempt.format.isYuv) R.string.status_streaming_yuv else R.string.status_streaming,
                attempt.width, attempt.height, fps)
            updateKeepAlive()
            return
        }
        channel.wanted = false
        channel.status = getString(R.string.status_stream_error, result, mode.width, mode.height, requestedFps)
        updateKeepAlive()
    }
    private fun shouldStayAlive() = anyStreaming || channels.any { it.wanted }

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
            showStop = shouldStayAlive(),
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

    private fun notifyUi() { handler.post { listeners.forEach { it.onCaptureStateChanged() } } }
    companion object {
        const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
        const val ACTION_KEEP = "com.omoai.simpleuvcstreamer.action.KEEP"
        const val ACTION_STOP = "com.omoai.simpleuvcstreamer.action.STOP"
    }
}
