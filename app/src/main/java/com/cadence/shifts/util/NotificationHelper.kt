package com.cadence.shifts.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cadence.shifts.MainActivity
import com.cadence.shifts.R
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.PushNotificationRecord
import com.cadence.shifts.data.model.ScheduleChangeRequest
import com.cadence.shifts.data.model.ShiftReport
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.TimeOffRequest
import com.cadence.shifts.domain.FairnessEngine
import kotlinx.coroutines.delay
import java.util.Locale

data class PendingAlertPayload(
    val id: String,
    val title: String,
    val message: String,
    val targetTabRoute: String = NotificationHelper.TAB_NOTIFICATIONS
)

object NotificationHelper {
    private const val CHANNEL_ID = "cadence_shift_alerts"
    private const val LEGACY_SYNC_CHANNEL_ID = "cadence_bg_sync"
    private const val ALERT_GROUP_KEY = "cadence_shift_alerts_group"

    private const val PREFS_NAME = "cadence_alert_prefs"
    private const val KEY_INITIALIZED_PREFIX = "initialized_"
    private const val KEY_SEEN_IDS_PREFIX = "seen_ids_"
    private const val KEY_SEEN_LIST_PREFIX = "seen_list_"
    private const val KEY_MGR_INIT_PREFIX = "mgr_init_"
    private const val KEY_MGR_SEEN_LIST_PREFIX = "mgr_seen_list_"

    private const val MAX_STORED_IDS = 300
    private const val RECENT_FIRST_LAUNCH_WINDOW_MS = 24 * 60 * 60 * 1000L

    const val EXTRA_OPEN_TAB = "extra_open_tab"
    const val TAB_NOTIFICATIONS = "notifications"
    const val TAB_APPROVALS = "approvals"
    const val TAB_TIME_OFF = "time_off"
    const val TAB_REPORTS = "reports"
    const val SYNC_NOTIFICATION_ID = 4102
    private const val GROUP_SUMMARY_ID = 4103

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val loc = LocaleHelper.localizedContext(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alertsChannel = NotificationChannel(
                CHANNEL_ID,
                loc.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = loc.getString(R.string.notif_channel_desc)
                enableVibration(true)
            }
            manager.createNotificationChannel(alertsChannel)
            manager.deleteNotificationChannel(LEGACY_SYNC_CHANNEL_ID)
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(SYNC_NOTIFICATION_ID)
        }
    }

    fun clearPersistentSyncNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(SYNC_NOTIFICATION_ID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.deleteNotificationChannel(LEGACY_SYNC_CHANNEL_ID)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        val osGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!osGranted) return false
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)
    }

    private fun safeNotificationId(rawKey: String): Int {
        var id = rawKey.hashCode()
        if (id == 0 || id == SYNC_NOTIFICATION_ID || id == GROUP_SUMMARY_ID) {
            id += 7919
        }
        return id
    }

    fun dispatchLocalNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        targetTabRoute: String = TAB_NOTIFICATIONS
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val resolvedId = if (
            notificationId == 0 ||
            notificationId == SYNC_NOTIFICATION_ID ||
            notificationId == GROUP_SUMMARY_ID
        ) {
            notificationId + 7919
        } else {
            notificationId
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, targetTabRoute)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            resolvedId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(ALERT_GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        runCatching {
            NotificationManagerCompat.from(context).notify(resolvedId, builder.build())
        }
    }

    private fun postGroupSummary(context: Context, count: Int) {
        if (!hasNotificationPermission(context) || count < 2) return
        ensureChannel(context)
        val loc = LocaleHelper.localizedContext(context)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_NOTIFICATIONS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            GROUP_SUMMARY_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(loc.getString(R.string.notif_summary_title))
            .setContentText(loc.getString(R.string.notif_summary_body_format, count))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(ALERT_GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(GROUP_SUMMARY_ID, summary)
        }
    }

    /**
     * Dispatches all newly arrived notifications with a brief stagger when multiple arrive together
     * so Android's NotificationManager rate-limiter never silently drops notifications in a batch.
     */
    suspend fun dispatchNotificationsSequentially(
        context: Context,
        alerts: List<PushNotificationRecord>
    ) {
        if (alerts.isEmpty() || !hasNotificationPermission(context)) return
        val loc = LocaleHelper.localizedContext(context)
        val fallbackTitle = loc.getString(R.string.notif_fallback_title)
        val fallbackBody = loc.getString(R.string.notif_fallback_body)
        val toSend = alerts.take(15)
        toSend.forEachIndexed { index, alert ->
            dispatchLocalNotification(
                context = context,
                title = alert.title.ifBlank { fallbackTitle },
                message = alert.message.ifBlank { fallbackBody },
                notificationId = safeNotificationId(alert.id),
                targetTabRoute = TAB_NOTIFICATIONS
            )
            if (index < toSend.lastIndex) {
                delay(120L)
            }
        }
        if (toSend.size >= 2) {
            postGroupSummary(context, toSend.size)
        }
    }

    suspend fun dispatchManagerPayloadsSequentially(
        context: Context,
        payloads: List<PendingAlertPayload>
    ) {
        if (payloads.isEmpty() || !hasNotificationPermission(context)) return
        val toSend = payloads.take(15)
        toSend.forEachIndexed { index, payload ->
            dispatchLocalNotification(
                context = context,
                title = payload.title,
                message = payload.message,
                notificationId = safeNotificationId(payload.id),
                targetTabRoute = payload.targetTabRoute
            )
            if (index < toSend.lastIndex) {
                delay(120L)
            }
        }
        if (toSend.size >= 2) {
            postGroupSummary(context, toSend.size)
        }
    }

    private fun readSeenList(prefs: android.content.SharedPreferences, listKey: String, legacySetKey: String? = null): MutableList<String> {
        val raw = prefs.getString(listKey, null)
        if (!raw.isNullOrEmpty()) {
            return raw.split('\n').filter { it.isNotBlank() }.toMutableList()
        }
        if (legacySetKey != null) {
            val legacy = prefs.getStringSet(legacySetKey, null)
            if (!legacy.isNullOrEmpty()) {
                return legacy.filter { it.isNotBlank() }.toMutableList()
            }
        }
        return mutableListOf()
    }

    private fun writeSeenList(editor: android.content.SharedPreferences.Editor, listKey: String, ids: List<String>) {
        val trimmed = ids.distinct().takeLast(MAX_STORED_IDS)
        editor.putString(listKey, trimmed.joinToString("\n"))
    }

    /**
     * Returns newly arrived unread alerts for [uid] that have not yet been surfaced on this device,
     * persisting seen IDs atomically via [SharedPreferences.Editor.commit] so approvals or shift
     * assignments made on the Web App while this app was closed trigger once without drops or duplicates.
     */
    @Synchronized
    fun filterUnnotifiedAlerts(
        context: Context,
        uid: String,
        notifications: List<PushNotificationRecord>,
        pushEnabled: Boolean
    ): List<PushNotificationRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initKey = "$KEY_INITIALIZED_PREFIX$uid"
        val legacySeenKey = "$KEY_SEEN_IDS_PREFIX$uid"
        val seenListKey = "$KEY_SEEN_LIST_PREFIX$uid"

        val wasInitialized = prefs.getBoolean(initKey, false)
        val seenList = readSeenList(prefs, seenListKey, legacySeenKey)
        val seenSet = seenList.toHashSet()
        val nowMs = System.currentTimeMillis()

        val mine = notifications.filter { it.recipientUid == uid }
        val toNotify = if (!wasInitialized) {
            mine.filter { record ->
                if (record.isRead) return@filter false
                val createdMs = record.createdAt?.toDate()?.time ?: nowMs
                (nowMs - createdMs) <= RECENT_FIRST_LAUNCH_WINDOW_MS
            }
        } else {
            mine.filter { record ->
                !record.isRead && record.id !in seenSet
            }
        }

        // Only mark IDs as seen when notifications are actually enabled to be dispatched,
        // or when read/outside initial window — never discard unread notifications while paused.
        val editor = prefs.edit().putBoolean(initKey, true)
        if (pushEnabled) {
            val combined = seenList + mine.asReversed().map { it.id }
            writeSeenList(editor, seenListKey, combined)
        } else if (!wasInitialized) {
            val baselineOnly = seenList + mine.filter { it.isRead }.asReversed().map { it.id }
            writeSeenList(editor, seenListKey, baselineOnly)
        }
        editor.commit()

        return if (pushEnabled) toNotify else emptyList()
    }

    /**
     * Tracks incoming staff requests/reports for managers so managers also receive OS notifications
     * for new pending approvals, time-off requests, and submitted shift reports while backgrounded.
     */
    @Synchronized
    fun filterNewManagerWorkAlerts(
        context: Context,
        uid: String,
        items: List<PendingAlertPayload>,
        pushEnabled: Boolean
    ): List<PendingAlertPayload> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initKey = "$KEY_MGR_INIT_PREFIX$uid"
        val listKey = "$KEY_MGR_SEEN_LIST_PREFIX$uid"

        val wasInitialized = prefs.getBoolean(initKey, false)
        val seenList = readSeenList(prefs, listKey)
        val seenSet = seenList.toHashSet()

        val newItems = if (!wasInitialized) {
            // Seed existing pending backlog on first launch without spamming historical queue
            emptyList()
        } else {
            items.filter { it.id !in seenSet }
        }

        val editor = prefs.edit().putBoolean(initKey, true)
        val combined = seenList + items.map { it.id }
        writeSeenList(editor, listKey, combined)
        editor.commit()

        return if (pushEnabled) newItems else emptyList()
    }

    fun buildManagerWorkPayloads(
        context: Context,
        managerUid: String,
        scheduleChanges: List<ScheduleChangeRequest>,
        timeOffRequests: List<TimeOffRequest>,
        shiftReports: List<ShiftReport>
    ): List<PendingAlertPayload> {
        val loc = LocaleHelper.localizedContext(context)
        val defaultStaff = loc.getString(R.string.notif_default_staff)
        val payloads = mutableListOf<PendingAlertPayload>()
        scheduleChanges
            .filter { it.status == ApprovalStatus.PENDING && it.requesterUid != managerUid }
            .forEach { req ->
                val who = req.requesterName.ifBlank { defaultStaff }
                val what = loc.getString(req.type.labelRes).lowercase(Locale.getDefault())
                payloads += PendingAlertPayload(
                    id = "mgr_chg_${req.id}",
                    title = loc.getString(R.string.notif_mgr_shift_req_title),
                    message = loc.getString(
                        R.string.notif_mgr_shift_req_body,
                        who,
                        what,
                        req.shiftTitle,
                        req.proposedDate
                    ),
                    targetTabRoute = TAB_APPROVALS
                )
            }
        timeOffRequests
            .filter { it.status == ApprovalStatus.PENDING && it.staffUid != managerUid }
            .forEach { req ->
                val who = req.staffName.ifBlank { defaultStaff }
                payloads += PendingAlertPayload(
                    id = "mgr_to_${req.id}",
                    title = loc.getString(R.string.notif_mgr_time_off_title),
                    message = loc.getString(
                        R.string.notif_mgr_time_off_body,
                        who,
                        req.startDate,
                        req.endDate
                    ),
                    targetTabRoute = TAB_TIME_OFF
                )
            }
        shiftReports
            .filter { it.status == ShiftReportStatus.SUBMITTED && it.staffUid != managerUid }
            .forEach { rep ->
                val who = rep.staffName.ifBlank { defaultStaff }
                payloads += PendingAlertPayload(
                    id = "mgr_rep_${rep.id}",
                    title = loc.getString(R.string.notif_mgr_report_title),
                    message = loc.getString(
                        R.string.notif_mgr_report_body,
                        who,
                        FairnessEngine.formatHours(rep.workedHours),
                        rep.shiftTitle,
                        rep.date
                    ),
                    targetTabRoute = TAB_REPORTS
                )
            }
        return payloads
    }
}
