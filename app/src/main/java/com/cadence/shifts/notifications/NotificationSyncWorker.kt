package com.cadence.shifts.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Periodic WorkManager fallback that checks Firestore for unread notifications even if the OS
 * killed the live listener service, and restarts live sync when battery is unrestricted.
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

        val uid = currentUser.uid
        return try {
            val databaseId = applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)

            val profileSnap = db.collection("users").document(uid).get().await()
            val pushEnabled = profileSnap.toUserProfile()?.pushNotificationsEnabled ?: true
            if (!pushEnabled) {
                BackgroundSyncScheduler.stopBackgroundSync(applicationContext)
                return Result.success()
            }

            val alertsSnap = db.collection("notifications")
                .whereEqualTo("recipientUid", uid)
                .get()
                .await()

            val records = alertsSnap.documents
                .mapNotNull { it.toPushNotification() }
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }

            val newAlerts = NotificationHelper.filterUnnotifiedAlerts(
                context = applicationContext,
                uid = uid,
                notifications = records,
                pushEnabled = true
            )

            newAlerts.take(3).forEach { alert ->
                NotificationHelper.dispatchLocalNotification(
                    context = applicationContext,
                    title = alert.title,
                    message = alert.message,
                    notificationId = alert.id.hashCode()
                )
            }

            // If user granted unrestricted battery usage, keep the real-time listener active
            if (BatteryOptimizationHelper.isUnrestrictedBattery(applicationContext)) {
                BackgroundSyncScheduler.tryStartRealtimeService(applicationContext)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic notification sync failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NotifSyncWorker"
    }
}
