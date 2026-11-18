package com.cadence.shifts.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    /**
     * Returns true if the app is exempt from Android Doze/battery optimization restrictions
     * ("Unrestricted" battery usage on modern Android), or on API levels prior to M.
     */
    fun isUnrestrictedBattery(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens Cadence's App Info screen where users can set App battery usage -> Unrestricted.
     * Falls back to the system battery optimization settings list if unavailable.
     */
    fun openAppBatterySettings(context: Context): Boolean {
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(appDetailsIntent)
            true
        }.getOrElse {
            openBatteryOptimizationList(context)
        }
    }

    /**
     * Opens the system Battery Optimization settings list as an alternative route on OEM devices.
     */
    fun openBatteryOptimizationList(context: Context): Boolean {
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(listIntent)
            true
        }.getOrDefault(false)
    }
}
