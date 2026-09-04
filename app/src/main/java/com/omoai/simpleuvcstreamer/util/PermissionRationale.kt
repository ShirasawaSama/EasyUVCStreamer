package com.omoai.simpleuvcstreamer.util

import android.Manifest
import android.content.Context
import com.omoai.simpleuvcstreamer.R

object PermissionRationale {
    fun message(
        context: Context,
        missing: Collection<String>,
        needBattery: Boolean,
    ): String {
        val lines = mutableListOf(context.getString(R.string.perm_dialog_intro))
        if (AppPermissions.USB_CAMERA in missing || Manifest.permission.CAMERA in missing) {
            lines += context.getString(R.string.perm_rationale_camera)
        }
        if (Manifest.permission.RECORD_AUDIO in missing) {
            lines += context.getString(R.string.perm_rationale_mic)
        }
        if (Manifest.permission.POST_NOTIFICATIONS in missing) {
            lines += context.getString(R.string.perm_rationale_notification)
        }
        if (needBattery) {
            lines += context.getString(R.string.perm_rationale_battery)
        }
        return lines.joinToString("\n\n")
    }
}
