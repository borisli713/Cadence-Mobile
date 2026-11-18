package com.cadence.shifts.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Re-schedules background notification sync after device reboot or app update without
 * relying on the user opening Cadence first.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            return
        }

        val appContext = context.applicationContext
        BackgroundSyncScheduler.schedulePeriodicWork(appContext)
        BackgroundSyncScheduler.enqueueImmediateSync(appContext)

        if (BatteryOptimizationHelper.isUnrestrictedBattery(appContext)) {
            BackgroundSyncScheduler.tryStartRealtimeService(appContext)
        }
    }
}
