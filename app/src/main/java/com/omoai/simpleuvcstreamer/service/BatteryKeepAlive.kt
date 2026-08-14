package com.omoai.simpleuvcstreamer.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.omoai.simpleuvcstreamer.util.FileLogger

object BatteryKeepAlive {
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openExemptionUi(activity: Activity) {
        val pkg = activity.packageName
        try {
            if (!isExempt(activity)) {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                    },
                )
            } else {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (t: Throwable) {
            FileLogger.log("Battery exemption UI failed: ${t.message}")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$pkg")
                    },
                )
            } catch (t2: Throwable) {
                FileLogger.log("App details UI failed: ${t2.message}")
            }
        }
    }
}
