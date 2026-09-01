package com.omoai.simpleuvcstreamer.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permissions related to USB UVC dongles and keep-alive.
 *
 * USB_CAMERA / RECORD_AUDIO: USB cameras may expose both video and audio interfaces.
 * POST_NOTIFICATIONS: required so the capture foreground service can show its notice.
 */
object AppPermissions {
    const val USB_CAMERA = "horizonos.permission.USB_CAMERA"

    private const val PREFS = "runtime_perms"
    private const val KEY_ASKED_PERMISSIONS = "asked_permissions"

    fun runtime(context: Context): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        // Horizon OS defines a dedicated runtime permission for external USB cameras.
        // Other Android systems do not know this permission and should not be prompted for it.
        @Suppress("DEPRECATION")
        if (runCatching { context.packageManager.getPermissionInfo(USB_CAMERA, 0) }.isSuccess) {
            list.add(USB_CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun missing(context: Context): Array<String> =
        runtime(context).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun askedPermissions(context: Context): Set<String> {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ASKED_PERMISSIONS, emptySet())
            .orEmpty()
    }

    fun markAskedSystemPrompt(context: Context, permissions: Array<String>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putStringSet(KEY_ASKED_PERMISSIONS, askedPermissions(context) + permissions)
            .apply()
    }

    /** True when the user chose “Don’t ask again” (or the OEM hides the prompt). */
    fun anyPermanentlyDenied(activity: Activity, permissions: Array<String>): Boolean {
        val asked = askedPermissions(activity)
        return permissions.any { perm ->
            perm in asked &&
            !isGranted(activity, perm) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
        )
    }
}
