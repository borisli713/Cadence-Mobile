package com.cadence.shifts.notifications

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that maintains a live Firestore listener for shift & approval alerts
 * even when Cadence is minimized or swiped away from recents.
 */
class ShiftNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerJob: Job? = null
    private var observingUid: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildBackgroundSyncNotification(this)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.SYNC_NOTIFICATION_ID,
                notification,
                fgType
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to enter foreground: ${error.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified || !NotificationHelper.hasNotificationPermission(this)) {
            stopGracefully()
            return START_NOT_STICKY
        }

        val uid = currentUser.uid
        if (observingUid != uid || listenerJob?.isActive != true) {
            startFirestoreAlertStream(uid)
        }

        return START_STICKY
    }

    private fun startFirestoreAlertStream(uid: String) {
        listenerJob?.cancel()
        observingUid = uid

        listenerJob = serviceScope.launch {
            val databaseId = applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)

            val profileFlow = db.collection("users").document(uid).snapshots()
            val alertsFlow = db.collection("notifications")
                .whereEqualTo("recipientUid", uid)
                .snapshots()

            combine(profileFlow, alertsFlow) { profileSnap, alertsSnap ->
                val pushEnabled = profileSnap.toUserProfile()?.pushNotificationsEnabled ?: true
                val records = alertsSnap.documents
                    .mapNotNull { it.toPushNotification() }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                pushEnabled to records
            }.catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Background alert stream error: ${error.message}")
            }.collect { (pushEnabled, records) ->
                if (!pushEnabled || Firebase.auth.currentUser?.uid != uid) {
                    stopGracefully()
                    return@collect
                }
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
            }
        }
    }

    private fun stopGracefully() {
        listenerJob?.cancel()
        observingUid = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listenerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShiftNotifService"
    }
}
