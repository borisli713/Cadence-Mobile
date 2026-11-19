package com.cadence.shifts.util

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cadence.shifts.data.model.NotificationCategory
import com.cadence.shifts.data.model.PushNotificationRecord
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.util.Date

@RunWith(AndroidJUnit4::class)
class BackgroundNotificationPolicyTest {

    @Test
    fun isUnrestrictedBattery_reflectsSystemIgnoringBatteryOptimizationsState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadowPowerManager = Shadows.shadowOf(powerManager)

        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BatteryOptimizationHelper.isUnrestrictedBattery(context))

        shadowPowerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BatteryOptimizationHelper.isUnrestrictedBattery(context))
    }

    @Test
    fun filterUnnotifiedAlerts_surfacesNewUnreadAlertsOnceAcrossBackgroundChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uid = "user_bg_test_${System.currentTimeMillis()}"
        val now = Timestamp(Date())

        val firstLaunchAlert = PushNotificationRecord(
            id = "notif_1",
            recipientUid = uid,
            title = "Shift Approved",
            message = "Your shift change was approved.",
            category = NotificationCategory.SHIFT_APPROVED,
            isRead = false,
            createdAt = now
        )

        val firstBatch = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = uid,
            notifications = listOf(firstLaunchAlert),
            pushEnabled = true
        )
        assertEquals(listOf("notif_1"), firstBatch.map { it.id })

        // Re-running background check with the same alert does not re-notify
        val repeatBatch = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = uid,
            notifications = listOf(firstLaunchAlert),
            pushEnabled = true
        )
        assertTrue(repeatBatch.isEmpty())

        // A newly created alert arriving later from Web App triggers once
        val newBackgroundAlert = firstLaunchAlert.copy(
            id = "notif_2",
            title = "New Shift Assigned",
            message = "Assigned on Web App."
        )
        val secondBatch = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = uid,
            notifications = listOf(firstLaunchAlert, newBackgroundAlert),
            pushEnabled = true
        )
        assertEquals(listOf("notif_2"), secondBatch.map { it.id })
    }

    @Test
    fun filterUnnotifiedAlerts_preservesAllAlertsInLargeBatchAnd24HourWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uid = "user_batch_test_${System.currentTimeMillis()}"
        val twoHoursAgo = Timestamp(Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))

        val sixAlerts = (1..6).map { index ->
            PushNotificationRecord(
                id = "batch_notif_$index",
                recipientUid = uid,
                title = "Shift Update $index",
                message = "Shift update message $index",
                category = NotificationCategory.SHIFT_ASSIGNED,
                isRead = false,
                createdAt = twoHoursAgo
            )
        }

        val firstPass = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = uid,
            notifications = sixAlerts,
            pushEnabled = true
        )
        assertEquals(6, firstPass.size)
        assertEquals(sixAlerts.map { it.id }, firstPass.map { it.id })

        // Re-evaluating with same list should not duplicate any of the 6 alerts
        val secondPass = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = uid,
            notifications = sixAlerts,
            pushEnabled = true
        )
        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun filterNewManagerWorkAlerts_seedsBacklogFirstAndAlertsOnNewRequests() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mgrUid = "mgr_bg_test_${System.currentTimeMillis()}"

        val existingItem = PendingAlertPayload(
            id = "mgr_chg_existing",
            title = "New Shift Request",
            message = "Existing pending item",
            targetTabRoute = NotificationHelper.TAB_APPROVALS
        )

        // First launch seeds existing backlog without spamming
        val initial = NotificationHelper.filterNewManagerWorkAlerts(
            context = context,
            uid = mgrUid,
            items = listOf(existingItem),
            pushEnabled = true
        )
        assertTrue(initial.isEmpty())

        // Newly arriving pending request triggers once
        val newItem = PendingAlertPayload(
            id = "mgr_to_new1",
            title = "New Time-Off Request",
            message = "Alex requested time off",
            targetTabRoute = NotificationHelper.TAB_TIME_OFF
        )
        val afterNew = NotificationHelper.filterNewManagerWorkAlerts(
            context = context,
            uid = mgrUid,
            items = listOf(existingItem, newItem),
            pushEnabled = true
        )
        assertEquals(listOf("mgr_to_new1"), afterNew.map { it.id })

        // Repeat poll does not duplicate
        val repeat = NotificationHelper.filterNewManagerWorkAlerts(
            context = context,
            uid = mgrUid,
            items = listOf(existingItem, newItem),
            pushEnabled = true
        )
        assertTrue(repeat.isEmpty())
    }
}
