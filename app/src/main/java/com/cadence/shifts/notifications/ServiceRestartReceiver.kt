package com.cadence.shifts.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/**
 * Invoked silently via AlarmManager to trigger an immediate WorkManager Firestore check and
 * schedule the next silent background poll without keeping a persistent foreground notification.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        NotificationHelper.clearPersistentSyncNotification(appContext)

        val user = Firebase.auth.currentUser
        if (user == null || !user.isEmailVerified) return

        BackgroundSyncScheduler.enqueueImmediateSync(appContext)
        BackgroundSyncScheduler.scheduleNextBackgroundAlarm(appContext, replaceExisting = true)
    }
}
