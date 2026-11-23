package com.cadence.shifts.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Re-schedules silent background notification sync after device reboot or app update without
 * showing a persistent ongoing notification or requiring the user to open Cadence first.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = context.applicationContext
        NotificationHelper.clearPersistentSyncNotification(appContext)

        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            return
        }

        BackgroundSyncScheduler.schedulePeriodicWork(appContext)
        BackgroundSyncScheduler.enqueueImmediateSync(appContext)
        BackgroundSyncScheduler.scheduleNextBackgroundAlarm(appContext, replaceExisting = true)
    }
}
