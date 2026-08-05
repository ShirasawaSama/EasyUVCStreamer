package com.omoai.simpleuvcstreamer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions related to USB UVC dongles.
 *
 * Many UVC gadgets also expose a USB mic. OEMs often toast about missing
 * RECORD_AUDIO even when we only capture MJPEG video — request it to clear
 * that warning and unlock future USB audio.
 */
object AppPermissions {
    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    fun missing(context: Context): Array<String> =
        REQUIRED.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
