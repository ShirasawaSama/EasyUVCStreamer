package com.omoai.simpleuvcstreamer.usb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.omoai.simpleuvcstreamer.util.FileLogger

/**
 * Toggles system "open app when UVC plugged in" by enabling/disabling an activity-alias
 * that owns the USB_DEVICE_ATTACHED intent-filter (Manifest filters are otherwise static).
 */
object UsbAutoLaunch {

    /** Must match android:name of the activity-alias in AndroidManifest. */
    const val ALIAS_NAME = "com.omoai.simpleuvcstreamer.UsbAttachAlias"

    private const val PREFS = "usb_auto_launch"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        applyComponentState(context, enabled)
        FileLogger.log("USB auto-launch component enabled=$enabled")
    }

    /** Sync PackageManager with saved preference (call on startup / when leaving the UI). */
    fun syncFromPrefs(context: Context) {
        applyComponentState(context, isEnabled(context))
    }

    /**
     * Hide the system "choose an app for this USB device" sheet while this app is
     * already in the foreground. In-app BroadcastReceiver still gets attach/detach.
     */
    fun suppressSystemChooser(context: Context) {
        applyComponentState(context, false)
    }

    private fun applyComponentState(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context, ALIAS_NAME)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val cur = pm.getComponentEnabledSetting(component)
        if (cur == newState) return
        pm.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }
}
