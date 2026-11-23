package com.cadence.shifts.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toScheduleChangeRequest
import com.cadence.shifts.data.firebase.toShiftReport
import com.cadence.shifts.data.firebase.toTimeOffRequest
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.data.model.UserRole
import com.cadence.shifts.data.repository.CadenceRepository
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Periodic and on-demand WorkManager sync that queries Firestore from server for unread
 * notifications and incoming manager requests, then restarts live sync when battery is unrestricted.
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
        val email = currentUser.email.orEmpty()
        val isBootstrapAdmin = email.trim().equals(CadenceRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

        return try {
            val databaseId = applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)

            val profileSnap = getDocServerOrCache(db.collection("users").document(uid))
            val managerSnap = runCatching { getDocServerOrCache(db.collection("managers").document(uid)) }.getOrNull()
            val profile = profileSnap.toUserProfile()
            val pushEnabled = profile?.pushNotificationsEnabled ?: true
            if (!pushEnabled) {
                BackgroundSyncScheduler.stopBackgroundSync(applicationContext)
                return Result.success()
            }
            val isManager = isBootstrapAdmin || managerSnap?.exists() == true || profile?.role == UserRole.MANAGER

            val alertsSnap = queryServerOrCache(
                db.collection("notifications").whereEqualTo("recipientUid", uid)
            )
            val records = alertsSnap.documents
                .mapNotNull { it.toPushNotification() }
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }

            val newAlerts = NotificationHelper.filterUnnotifiedAlerts(
                context = applicationContext,
                uid = uid,
                notifications = records,
                pushEnabled = true
            )
            NotificationHelper.dispatchNotificationsSequentially(applicationContext, newAlerts)

            if (isManager) {
                val chgSnap = queryServerOrCache(
                    db.collection("scheduleChanges").whereEqualTo("status", "pending")
                )
                val toSnap = queryServerOrCache(
                    db.collection("timeOffRequests").whereEqualTo("status", "pending")
                )
                val repSnap = queryServerOrCache(
                    db.collection("shiftReports").whereEqualTo("status", "submitted")
                )
                val payloads = NotificationHelper.buildManagerWorkPayloads(
                    managerUid = uid,
                    scheduleChanges = chgSnap.documents.mapNotNull { it.toScheduleChangeRequest() },
                    timeOffRequests = toSnap.documents.mapNotNull { it.toTimeOffRequest() },
                    shiftReports = repSnap.documents.mapNotNull { it.toShiftReport() }
                )
                val newMgrAlerts = NotificationHelper.filterNewManagerWorkAlerts(
                    context = applicationContext,
                    uid = uid,
                    items = payloads,
                    pushEnabled = true
                )
                NotificationHelper.dispatchManagerPayloadsSequentially(applicationContext, newMgrAlerts)
            }

            BackgroundSyncScheduler.scheduleNextBackgroundAlarm(applicationContext, replaceExisting = true)

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Periodic notification sync failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun getDocServerOrCache(ref: DocumentReference): DocumentSnapshot {
        return try {
            ref.get(Source.SERVER).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ref.get().await()
        }
    }

    private suspend fun queryServerOrCache(query: Query): QuerySnapshot {
        return try {
            query.get(Source.SERVER).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            query.get().await()
        }
    }

    companion object {
        private const val TAG = "NotifSyncWorker"
    }
}
