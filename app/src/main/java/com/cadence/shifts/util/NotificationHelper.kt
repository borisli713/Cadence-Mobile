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
import com.cadence.shifts.data.model.PushNotificationRecord

object NotificationHelper {
    private const val CHANNEL_ID = "cadence_shift_alerts"
    private const val CHANNEL_NAME = "Shift & Approval Alerts"
    private const val PREFS_NAME = "cadence_alert_prefs"
    private const val KEY_INITIALIZED_PREFIX = "initialized_"
    private const val KEY_SEEN_IDS_PREFIX = "seen_ids_"
    private const val MAX_STORED_IDS = 150
    private const val RECENT_FIRST_LAUNCH_WINDOW_MS = 10 * 60 * 1000L

    const val EXTRA_OPEN_TAB = "extra_open_tab"
    const val TAB_NOTIFICATIONS = "notifications"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for approved schedule changes, verified reports, and shift fairness assignments."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun dispatchLocalNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_NOTIFICATIONS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    /**
     * Returns newly arrived unread alerts for [uid] that have not yet been surfaced on this device,
     * persisting seen IDs across app restarts so approvals made on the Web App while this app was
     * closed still trigger an alert on next launch without re-notifying old history.
     */
    fun filterUnnotifiedAlerts(
        context: Context,
        uid: String,
        notifications: List<PushNotificationRecord>,
        pushEnabled: Boolean
    ): List<PushNotificationRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initKey = "$KEY_INITIALIZED_PREFIX$uid"
        val seenKey = "$KEY_SEEN_IDS_PREFIX$uid"

        val wasInitialized = prefs.getBoolean(initKey, false)
        val seenIds = prefs.getStringSet(seenKey, emptySet())?.toMutableSet() ?: mutableSetOf()
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
                !record.isRead && record.id !in seenIds
            }
        }

        val updatedSeen = (mine.map { it.id } + seenIds)
            .distinct()
            .take(MAX_STORED_IDS)
            .toSet()

        prefs.edit()
            .putBoolean(initKey, true)
            .putStringSet(seenKey, updatedSeen)
            .apply()

        return if (pushEnabled) toNotify else emptyList()
    }
}
