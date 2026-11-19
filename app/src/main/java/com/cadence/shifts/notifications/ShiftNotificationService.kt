package com.cadence.shifts.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toScheduleChangeRequest
import com.cadence.shifts.data.firebase.toShiftReport
import com.cadence.shifts.data.firebase.toTimeOffRequest
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.data.model.PushNotificationRecord
import com.cadence.shifts.data.model.UserRole
import com.cadence.shifts.data.repository.CadenceRepository
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.cadence.shifts.util.NotificationHelper
import com.cadence.shifts.util.PendingAlertPayload
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Foreground service that maintains a self-healing Firestore listener + periodic server heartbeat
 * for shift, approval, and manager request alerts even when Cadence is minimized or swiped away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftNotificationService : Service() {

    private sealed interface StreamEvent {
        data object StopSync : StreamEvent
        data class PersonalAlerts(val records: List<PushNotificationRecord>) : StreamEvent
        data class ManagerWork(val payloads: List<PendingAlertPayload>) : StreamEvent
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchMutex = Mutex()

    private var listenerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var observingUid: String? = null
    private var observingEmail: String = ""
    @Volatile
    private var lastKnownPushEnabled: Boolean = true
    private var networkCallbackRegistered = false
    @Volatile
    private var lastNetworkPollMs: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val uid = observingUid ?: return
            val email = observingEmail
            val now = System.currentTimeMillis()
            if (now - lastNetworkPollMs < NETWORK_DEBOUNCE_MS) return
            lastNetworkPollMs = now
            serviceScope.launch {
                // Give TLS/Auth handshake a brief moment after radio reconnect
                delay(1_000L)
                pollServerOnce(uid, email)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkObserver()
    }

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
        val email = currentUser.email.orEmpty()
        if (observingUid != uid || listenerJob?.isActive != true || heartbeatJob?.isActive != true) {
            startAlertMonitoring(uid, email)
        }

        return START_STICKY
    }

    private fun registerNetworkObserver() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Unable to register network callback: ${error.message}")
        }
    }

    private fun startAlertMonitoring(uid: String, email: String) {
        listenerJob?.cancel()
        heartbeatJob?.cancel()
        observingUid = uid
        observingEmail = email

        listenerJob = serviceScope.launch {
            val databaseId = applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            val isBootstrapAdmin = email.trim().equals(CadenceRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

            var backoffMs = INITIAL_RETRY_BACKOFF_MS
            while (currentCoroutineContext().isActive) {
                try {
                    if (Firebase.auth.currentUser?.uid != uid) {
                        stopGracefully()
                        return@launch
                    }

                    val profileFlow = db.collection("users").document(uid).snapshots()
                    val managerDocFlow = db.collection("managers").document(uid).snapshots()
                    val roleAndPushFlow = combine(profileFlow, managerDocFlow) { profileSnap, managerSnap ->
                        val profile = profileSnap.toUserProfile()
                        val pushEnabled = profile?.pushNotificationsEnabled ?: true
                        val isManager = isBootstrapAdmin || managerSnap.exists() || profile?.role == UserRole.MANAGER
                        pushEnabled to isManager
                    }.distinctUntilChanged()

                    val eventFlow = roleAndPushFlow.flatMapLatest { (pushEnabled, isManager) ->
                        lastKnownPushEnabled = pushEnabled
                        if (!pushEnabled) {
                            return@flatMapLatest flowOf(StreamEvent.StopSync)
                        }

                        val personalFlow = db.collection("notifications")
                            .whereEqualTo("recipientUid", uid)
                            .snapshots()
                            .map { alertsSnap ->
                                val records = alertsSnap.documents
                                    .mapNotNull { it.toPushNotification() }
                                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                                StreamEvent.PersonalAlerts(records) as StreamEvent
                            }

                        if (!isManager) {
                            personalFlow
                        } else {
                            val changesFlow = db.collection("scheduleChanges")
                                .whereEqualTo("status", "pending")
                                .snapshots()
                            val timeOffFlow = db.collection("timeOffRequests")
                                .whereEqualTo("status", "pending")
                                .snapshots()
                            val reportsFlow = db.collection("shiftReports")
                                .whereEqualTo("status", "submitted")
                                .snapshots()

                            val managerWorkFlow = combine(
                                changesFlow,
                                timeOffFlow,
                                reportsFlow
                            ) { chgSnap, toSnap, repSnap ->
                                val payloads = NotificationHelper.buildManagerWorkPayloads(
                                    managerUid = uid,
                                    scheduleChanges = chgSnap.documents.mapNotNull { it.toScheduleChangeRequest() },
                                    timeOffRequests = toSnap.documents.mapNotNull { it.toTimeOffRequest() },
                                    shiftReports = repSnap.documents.mapNotNull { it.toShiftReport() }
                                )
                                StreamEvent.ManagerWork(payloads) as StreamEvent
                            }

                            merge(personalFlow, managerWorkFlow)
                        }
                    }

                    eventFlow.collect { event ->
                        backoffMs = INITIAL_RETRY_BACKOFF_MS
                        if (Firebase.auth.currentUser?.uid != uid) {
                            stopGracefully()
                            return@collect
                        }
                        when (event) {
                            StreamEvent.StopSync -> {
                                stopGracefully()
                                return@collect
                            }
                            is StreamEvent.PersonalAlerts -> dispatchPersonal(uid, event.records)
                            is StreamEvent.ManagerWork -> dispatchManagerWork(uid, event.payloads)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Background alert stream interrupted; retrying in ${backoffMs}ms: ${e.message}")
                }

                if (Firebase.auth.currentUser?.uid != uid || !lastKnownPushEnabled) {
                    stopGracefully()
                    return@launch
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            }
        }

        heartbeatJob = serviceScope.launch {
            // Immediate server catch-up when service boots/restarts, then periodic server heartbeat
            pollServerOnce(uid, email)
            while (currentCoroutineContext().isActive) {
                delay(SERVER_POLL_INTERVAL_MS)
                pollServerOnce(uid, email)
            }
        }
    }

    private suspend fun pollServerOnce(uid: String, email: String) {
        val currentUser = Firebase.auth.currentUser ?: return
        if (!currentUser.isEmailVerified || currentUser.uid != uid) return
        if (!NotificationHelper.hasNotificationPermission(applicationContext)) return

        runCatching {
            val databaseId = applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            val isBootstrapAdmin = email.trim().equals(CadenceRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

            val profileSnap = db.collection("users").document(uid).get(Source.SERVER).await()
            val managerSnap = runCatching { db.collection("managers").document(uid).get(Source.SERVER).await() }.getOrNull()
            val profile = profileSnap.toUserProfile()
            val pushEnabled = profile?.pushNotificationsEnabled ?: true
            lastKnownPushEnabled = pushEnabled
            if (!pushEnabled) {
                stopGracefully()
                return
            }
            val isManager = isBootstrapAdmin || managerSnap?.exists() == true || profile?.role == UserRole.MANAGER

            val alertsSnap = queryServerOrCache(
                db.collection("notifications").whereEqualTo("recipientUid", uid)
            )
            val records = alertsSnap.documents
                .mapNotNull { it.toPushNotification() }
                .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            dispatchPersonal(uid, records)

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
                dispatchManagerWork(uid, payloads)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.d(TAG, "Server heartbeat poll skipped: ${error.message}")
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

    private suspend fun dispatchPersonal(uid: String, records: List<PushNotificationRecord>) {
        val newAlerts = dispatchMutex.withLock {
            NotificationHelper.filterUnnotifiedAlerts(
                context = applicationContext,
                uid = uid,
                notifications = records,
                pushEnabled = true
            )
        }
        if (newAlerts.isNotEmpty()) {
            NotificationHelper.dispatchNotificationsSequentially(applicationContext, newAlerts)
        }
    }

    private suspend fun dispatchManagerWork(uid: String, payloads: List<PendingAlertPayload>) {
        val newItems = dispatchMutex.withLock {
            NotificationHelper.filterNewManagerWorkAlerts(
                context = applicationContext,
                uid = uid,
                items = payloads,
                pushEnabled = true
            )
        }
        if (newItems.isNotEmpty()) {
            NotificationHelper.dispatchManagerPayloadsSequentially(applicationContext, newItems)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val user = Firebase.auth.currentUser
        if (user != null &&
            user.isEmailVerified &&
            lastKnownPushEnabled &&
            NotificationHelper.hasNotificationPermission(applicationContext)
        ) {
            if (BatteryOptimizationHelper.isUnrestrictedBattery(applicationContext)) {
                BackgroundSyncScheduler.scheduleRestartAfterTaskRemoval(applicationContext)
            } else {
                BackgroundSyncScheduler.enqueueImmediateSync(applicationContext)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopGracefully() {
        listenerJob?.cancel()
        heartbeatJob?.cancel()
        observingUid = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (networkCallbackRegistered) {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(networkCallback)
            }
            networkCallbackRegistered = false
        }
        listenerJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShiftNotifService"
        private const val INITIAL_RETRY_BACKOFF_MS = 3_000L
        private const val MAX_RETRY_BACKOFF_MS = 30_000L
        private const val SERVER_POLL_INTERVAL_MS = 60_000L
        private const val NETWORK_DEBOUNCE_MS = 3_000L
    }
}
