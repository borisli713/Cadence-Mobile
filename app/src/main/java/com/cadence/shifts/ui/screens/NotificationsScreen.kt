package com.cadence.shifts.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.NotificationCategory
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Amber600
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsScreen(
    data: WorkspaceUiData,
    hasOsNotificationPermission: Boolean,
    isUnrestrictedBattery: Boolean,
    busyAction: String?,
    onRequestOsPermission: () -> Unit,
    onOpenBatteryDialog: () -> Unit,
    onSendTestAlert: () -> Unit,
    onMarkAllRead: () -> Unit,
    onToggleRead: (id: String, isRead: Boolean) -> Unit
) {
    val bgSyncActive = data.profile.pushNotificationsEnabled && hasOsNotificationPermission
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CadenceCard {
                Text(stringResource(R.string.notifications_title), style = MaterialTheme.typography.headlineSmall, color = Slate900)
                Text(
                    text = stringResource(R.string.notifications_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = stringResource(if (bgSyncActive) R.string.bg_sync_active else R.string.bg_sync_paused),
                        tone = if (bgSyncActive) BadgeTone.INFO else BadgeTone.NEUTRAL
                    )
                    StatusPill(
                        label = stringResource(if (isUnrestrictedBattery) R.string.battery_unrestricted else R.string.battery_restricted),
                        tone = if (isUnrestrictedBattery) BadgeTone.SUCCESS else BadgeTone.WARNING
                    )
                }
                if (!isUnrestrictedBattery) {
                    Text(
                        text = stringResource(R.string.battery_warning_inline),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber600
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!hasOsNotificationPermission) {
                        Button(
                            onClick = onRequestOsPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Blue700, contentColor = PureWhite),
                            modifier = Modifier.testTag("enable_os_notifications_button")
                        ) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_enable_device_alerts))
                        }
                    }
                    if (!isUnrestrictedBattery) {
                        FilledTonalButton(
                            onClick = onOpenBatteryDialog,
                            modifier = Modifier.testTag("notifications_battery_fix_button")
                        ) {
                            Icon(Icons.Rounded.BatteryAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_set_battery_unrestricted))
                        }
                    }
                    OutlinedButton(
                        onClick = onSendTestAlert,
                        enabled = busyAction == null,
                        modifier = Modifier.testTag("test_alert_button")
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_test_alert))
                    }
                    if (data.unreadNotificationsCount > 0) {
                        OutlinedButton(
                            onClick = onMarkAllRead,
                            enabled = busyAction == null
                        ) {
                            Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_mark_all_read_count, data.unreadNotificationsCount))
                        }
                    }
                }
            }
        }

        if (data.notifications.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.NotificationsOff,
                    title = stringResource(R.string.empty_notifications_title),
                    subtitle = stringResource(R.string.empty_notifications_subtitle)
                )
            }
        } else {
            items(data.notifications, key = { it.id }) { notif ->
                val tone = when (notif.category) {
                    NotificationCategory.SHIFT_APPROVED,
                    NotificationCategory.REPORT_VERIFIED,
                    NotificationCategory.TIME_OFF_APPROVED -> BadgeTone.SUCCESS
                    NotificationCategory.SHIFT_DENIED,
                    NotificationCategory.TIME_OFF_DENIED -> BadgeTone.DANGER
                    NotificationCategory.FAIRNESS_REBALANCED -> BadgeTone.VIOLET
                    NotificationCategory.SHIFT_ASSIGNED -> BadgeTone.INFO
                }
                CadenceCard(
                    containerColor = if (!notif.isRead) Blue50.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
                    borderColor = if (!notif.isRead) Blue700.copy(alpha = 0.35f) else Slate200
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(label = stringResource(notif.category.labelRes), tone = tone)
                        TextButton(onClick = { onToggleRead(notif.id, !notif.isRead) }) {
                            Icon(Icons.Rounded.MarkEmailRead, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(if (notif.isRead) R.string.btn_mark_unread else R.string.btn_mark_read))
                        }
                    }
                    Text(notif.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                    Text(notif.message, style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
