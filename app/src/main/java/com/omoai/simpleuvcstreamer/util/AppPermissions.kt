package com.omoai.simpleuvcstreamer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions related to USB UVC dongles.
 *
 * Many UVC gadgets also expose a USB mic. OEMs often toast about missing
 * RECORD_AUDIO even when we only capture MJPEG video — request it to clear
 * that warning and unlock future USB audio.
 */
object AppPermissions {
    fun required(): Array<String> {
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
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
