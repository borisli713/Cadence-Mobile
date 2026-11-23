package com.cadence.shifts.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException

/**
 * Periodic and on-demand WorkManager sync that delegates to [RealtimeAlertMonitor.pollServerOnce]
 * under its shared mutex so unread notifications and manager work items surface promptly without
 * duplicating alerts or showing an ongoing foreground notification.
 */
class NotificationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            return Result.success()
        }
        if (!NotificationHelper.hasNotificationPermission(applicationContext)) {
            return Result.success()
        }

        return try {
            val keepRunning = RealtimeAlertMonitor.pollServerOnce(applicationContext)
            if (keepRunning) {
                BackgroundSyncScheduler.scheduleNextBackgroundAlarm(applicationContext, forceReschedule = true)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background notification sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NotifSyncWorker"
    }
}
