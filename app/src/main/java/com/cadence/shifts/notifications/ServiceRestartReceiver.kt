package com.cadence.shifts.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Invoked via AlarmManager after task removal to keep background live notification sync active
 * when battery optimization is set to Unrestricted.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val user = Firebase.auth.currentUser
        if (user == null || !user.isEmailVerified) return

        val appContext = context.applicationContext
        BackgroundSyncScheduler.enqueueImmediateSync(appContext)
        if (BatteryOptimizationHelper.isUnrestrictedBattery(appContext)) {
            BackgroundSyncScheduler.tryStartRealtimeService(appContext)
        }
    }
}
