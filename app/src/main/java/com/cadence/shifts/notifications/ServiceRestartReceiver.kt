package com.cadence.shifts.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invoked silently via AlarmManager to check Firestore immediately via goAsync() and keep
 * [RealtimeAlertMonitor] alive without displaying a persistent foreground notification.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        NotificationHelper.clearPersistentSyncNotification(appContext)

        val user = Firebase.auth.currentUser
        if (user == null || !user.isEmailVerified || !NotificationHelper.hasNotificationPermission(appContext)) {
            return
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            var completed = false
            try {
                val polled = withTimeoutOrNull(POLL_TIMEOUT_MS) {
                    runCatching { RealtimeAlertMonitor.pollServerOnce(appContext) }.getOrNull()
                }
                completed = polled != null
                if (polled != false) {
                    BackgroundSyncScheduler.scheduleNextBackgroundAlarm(appContext, replaceExisting = true)
                }
            } finally {
                if (!completed) {
                    BackgroundSyncScheduler.enqueueImmediateSync(appContext)
                    BackgroundSyncScheduler.scheduleNextBackgroundAlarm(appContext, replaceExisting = true)
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 6_500L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
