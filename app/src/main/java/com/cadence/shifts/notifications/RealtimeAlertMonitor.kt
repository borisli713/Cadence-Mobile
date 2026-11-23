package com.cadence.shifts.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toScheduleChangeRequest
import com.cadence.shifts.data.firebase.toShiftReport
import com.cadence.shifts.data.firebase.toTimeOffRequest
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.data.model.PushNotificationRecord
import com.cadence.shifts.data.model.UserRole
import com.cadence.shifts.data.repository.CadenceRepository
import com.cadence.shifts.util.NotificationHelper
import com.cadence.shifts.util.PendingAlertPayload
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Process-wide real-time alert monitor that stays active while the app is open, minimized, or
 * screen-off without requiring a persistent foreground notification.
 *
 * All foreground, minimized, and background WorkManager/AlarmManager alert checks share [syncMutex]
 * so alerts fire once in real time without pauses during Activity ON_STOP or duplicate dispatches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RealtimeAlertMonitor {

    private sealed interface StreamEvent {
        data object PushDisabled : StreamEvent
        data class PersonalAlerts(val records: List<PushNotificationRecord>) : StreamEvent
        data class ManagerWork(val payloads: List<PendingAlertPayload>) : StreamEvent
    }

    private const val TAG = "RealtimeAlertMonitor"
    private const val INITIAL_RETRY_BACKOFF_MS = 2_000L
    private const val MAX_RETRY_BACKOFF_MS = 30_000L
    private const val NETWORK_DEBOUNCE_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val stateLock = Any()

    private val _incomingBannerEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingBannerEvents: SharedFlow<String> = _incomingBannerEvents.asSharedFlow()

    private var monitorJob: Job? = null
    private var activeUid: String? = null
    private var appContextRef: Context? = null
    private var networkRegistered = false
    @Volatile
    private var lastNetworkPollMs: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val ctx = appContextRef ?: return
            val now = System.currentTimeMillis()
            if (now - lastNetworkPollMs < NETWORK_DEBOUNCE_MS) return
            lastNetworkPollMs = now
            scope.launch {
                delay(800L)
                pollServerOnce(ctx)
            }
        }
    }

    fun startOrUpdate(context: Context) {
        val appContext = context.applicationContext
        val user = Firebase.auth.currentUser
        if (user == null || !user.isEmailVerified) {
            stop()
            return
        }

        val uid = user.uid
        val email = user.email.orEmpty()

        synchronized(stateLock) {
            appContextRef = appContext
            ensureNetworkCallbackRegistered(appContext)
            if (activeUid == uid && monitorJob?.isActive == true) {
                return
            }
            monitorJob?.cancel()
            activeUid = uid
            monitorJob = scope.launch {
                runRealtimeLoop(appContext, uid, email)
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            monitorJob?.cancel()
            monitorJob = null
            activeUid = null
        }
    }

    private fun ensureNetworkCallbackRegistered(appContext: Context) {
        if (networkRegistered) return
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }.onFailure { error ->
            Log.d(TAG, "Network callback unavailable: ${error.message}")
        }
    }

    private suspend fun runRealtimeLoop(appContext: Context, uid: String, email: String) {
        val databaseId = appContext.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)
        val isBootstrapAdmin = email.trim().equals(CadenceRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

        var backoffMs = INITIAL_RETRY_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val currentUser = Firebase.auth.currentUser
            if (currentUser == null || !currentUser.isEmailVerified || currentUser.uid != uid) {
                stop()
                return
            }

            try {
                val profileFlow = db.collection("users").document(uid).snapshots()
                val managerDocFlow = db.collection("managers").document(uid).snapshots()

                val roleAndPushFlow = combine(profileFlow, managerDocFlow) { profileSnap, managerSnap ->
                    val profile = profileSnap.toUserProfile()
                    val pushEnabled = profile?.pushNotificationsEnabled ?: true
                    val isManager = isBootstrapAdmin || managerSnap.exists() || profile?.role == UserRole.MANAGER
                    pushEnabled to isManager
                }.distinctUntilChanged()

                val eventFlow = roleAndPushFlow.flatMapLatest { (pushEnabled, isManager) ->
                    if (!pushEnabled) {
                        return@flatMapLatest flowOf(StreamEvent.PushDisabled)
                    }

                    val personalFlow = db.collection("notifications")
                        .whereEqualTo("recipientUid", uid)
                        .snapshots()
                        .map { snap ->
                            val records = snap.documents
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
                        stop()
                        return@collect
                    }
                    when (event) {
                        StreamEvent.PushDisabled -> {
                            processPersonalAlerts(appContext, uid, emptyList(), pushEnabled = false)
                        }
                        is StreamEvent.PersonalAlerts -> {
                            processPersonalAlerts(appContext, uid, event.records, pushEnabled = true)
                        }
                        is StreamEvent.ManagerWork -> {
                            processManagerWork(appContext, uid, event.payloads, pushEnabled = true)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Realtime listener interrupted; retrying in ${backoffMs}ms: ${e.message}")
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        }
    }

    /**
     * Queries Firestore directly from server (with cache fallback) for any unread alerts or
     * incoming manager work items, used by WorkManager, AlarmManager wakeups, and network reconnects.
     */
    suspend fun pollServerOnce(context: Context): Boolean {
        val appContext = context.applicationContext
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            return true
        }

        val uid = currentUser.uid
        val email = currentUser.email.orEmpty()
        val isBootstrapAdmin = email.trim().equals(CadenceRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

        val databaseId = appContext.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)

        val profileSnap = getDocServerOrCache(db.collection("users").document(uid))
        val managerSnap = runCatching { getDocServerOrCache(db.collection("managers").document(uid)) }.getOrNull()
        val profile = profileSnap.toUserProfile()
        val pushEnabled = profile?.pushNotificationsEnabled ?: true
        if (!pushEnabled) {
            BackgroundSyncScheduler.stopBackgroundSync(appContext)
            stop()
            return false
        }

        val isManager = isBootstrapAdmin || managerSnap?.exists() == true || profile?.role == UserRole.MANAGER

        val alertsSnap = queryServerOrCache(
            db.collection("notifications").whereEqualTo("recipientUid", uid)
        )
        val records = alertsSnap.documents
            .mapNotNull { it.toPushNotification() }
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        processPersonalAlerts(appContext, uid, records, pushEnabled = true)

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
            processManagerWork(appContext, uid, payloads, pushEnabled = true)
        }

        // Keep live listener open for as long as the OS lets this process remain resident
        startOrUpdate(appContext)
        return true
    }

    private suspend fun processPersonalAlerts(
        appContext: Context,
        uid: String,
        records: List<PushNotificationRecord>,
        pushEnabled: Boolean
    ) {
        val newAlerts = syncMutex.withLock {
            NotificationHelper.filterUnnotifiedAlerts(
                context = appContext,
                uid = uid,
                notifications = records,
                pushEnabled = pushEnabled
            )
        }
        if (newAlerts.isNotEmpty()) {
            NotificationHelper.dispatchNotificationsSequentially(appContext, newAlerts)
            val latest = newAlerts.first()
            _incomingBannerEvents.tryEmit("${latest.title}: ${latest.message}")
        }
    }

    private suspend fun processManagerWork(
        appContext: Context,
        uid: String,
        payloads: List<PendingAlertPayload>,
        pushEnabled: Boolean
    ) {
        val newItems = syncMutex.withLock {
            NotificationHelper.filterNewManagerWorkAlerts(
                context = appContext,
                uid = uid,
                items = payloads,
                pushEnabled = pushEnabled
            )
        }
        if (newItems.isNotEmpty()) {
            NotificationHelper.dispatchManagerPayloadsSequentially(appContext, newItems)
            val latest = newItems.first()
            _incomingBannerEvents.tryEmit("${latest.title}: ${latest.message}")
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
}
