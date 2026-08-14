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
 * CAMERA / RECORD_AUDIO: many UVC gadgets are classified as camera+mic by OEMs.
 * POST_NOTIFICATIONS: required so the capture foreground service can show its notice.
 */
object AppPermissions {
    private const val PREFS = "runtime_perms"
    private const val KEY_ASKED = "asked_system_prompt"

    fun runtime(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun missing(context: Context): Array<String> =
        runtime().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAskedSystemPrompt(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
    }

    fun markAskedSystemPrompt(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    /** True when the user chose “Don’t ask again” (or the OEM hides the prompt). */
    fun anyPermanentlyDenied(activity: Activity, permissions: Array<String>): Boolean {
        if (!hasAskedSystemPrompt(activity)) return false
        return permissions.any { perm ->
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
