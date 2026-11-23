package com.cadence.shifts.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val TAG = "BgSyncScheduler"
    private const val PERIODIC_WORK_NAME = "cadence_notif_periodic_sync"
    private const val IMMEDIATE_WORK_NAME = "cadence_notif_immediate_sync"
    private const val ALARM_REQUEST_CODE = 4105
    private const val UNRESTRICTED_POLL_INTERVAL_MS = 60_000L
    private const val RESTRICTED_POLL_INTERVAL_MS = 5 * 60 * 1000L

    fun ensureBackgroundSync(
        context: Context,
        pushEnabled: Boolean
    ) {
        val appContext = context.applicationContext
        NotificationHelper.ensureChannel(appContext)
        NotificationHelper.clearPersistentSyncNotification(appContext)

        val user = Firebase.auth.currentUser
        if (!pushEnabled || user == null || !user.isEmailVerified) {
            stopBackgroundSync(appContext)
            return
        }

        RealtimeAlertMonitor.startOrUpdate(appContext)
        schedulePeriodicWork(appContext)
        if (NotificationHelper.hasNotificationPermission(appContext)) {
            scheduleNextBackgroundAlarm(appContext, replaceExisting = false)
        } else {
            cancelScheduledSyncAlarm(appContext)
        }
    }

    fun schedulePeriodicWork(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<NotificationSyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule periodic notification sync: ${error.message}")
        }
    }

    fun enqueueImmediateSync(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
            .setConstraints(constraints)
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to enqueue immediate notification sync: ${error.message}")
        }
    }

    fun scheduleNextBackgroundAlarm(
        context: Context,
        replaceExisting: Boolean = true
    ) {
        val appContext = context.applicationContext
        val user = Firebase.auth.currentUser
        if (user == null || !user.isEmailVerified || !NotificationHelper.hasNotificationPermission(appContext)) {
            cancelScheduledSyncAlarm(appContext)
            return
        }

        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val syncIntent = Intent(appContext, ServiceRestartReceiver::class.java)

            if (!replaceExisting) {
                val existing = PendingIntent.getBroadcast(
                    appContext,
                    ALARM_REQUEST_CODE,
                    syncIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (existing != null) return
            }

            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                ALARM_REQUEST_CODE,
                syncIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val delayMs = if (BatteryOptimizationHelper.isUnrestrictedBattery(appContext)) {
                UNRESTRICTED_POLL_INTERVAL_MS
            } else {
                RESTRICTED_POLL_INTERVAL_MS
            }
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule silent background sync alarm: ${error.message}")
        }
    }

    private fun cancelScheduledSyncAlarm(appContext: Context) {
        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val syncIntent = Intent(appContext, ServiceRestartReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                ALARM_REQUEST_CODE,
                syncIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun stopBackgroundSync(context: Context) {
        val appContext = context.applicationContext
        RealtimeAlertMonitor.stop()
        cancelScheduledSyncAlarm(appContext)
        NotificationHelper.clearPersistentSyncNotification(appContext)
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(appContext).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
