package com.cadence.shifts.notifications

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val TAG = "BgSyncScheduler"
    private const val PERIODIC_WORK_NAME = "cadence_notif_periodic_sync"
    private const val IMMEDIATE_WORK_NAME = "cadence_notif_immediate_sync"

    fun ensureBackgroundSync(
        context: Context,
        pushEnabled: Boolean,
        allowForegroundServiceStart: Boolean = true
    ) {
        val appContext = context.applicationContext
        NotificationHelper.ensureChannel(appContext)

        val user = Firebase.auth.currentUser
        if (!pushEnabled || user == null || !user.isEmailVerified) {
            stopBackgroundSync(appContext)
            return
        }

        schedulePeriodicWork(appContext)

        if (allowForegroundServiceStart && NotificationHelper.hasNotificationPermission(appContext)) {
            tryStartRealtimeService(appContext)
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

    fun tryStartRealtimeService(context: Context) {
        val appContext = context.applicationContext
        if (!NotificationHelper.hasNotificationPermission(appContext)) return
        val intent = Intent(appContext, ShiftNotificationService::class.java)
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure { error ->
            Log.w(TAG, "Unable to start real-time alert service right now: ${error.message}")
        }
    }

    fun stopBackgroundSync(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(appContext).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
        runCatching {
            appContext.stopService(Intent(appContext, ShiftNotificationService::class.java))
        }
    }
}
