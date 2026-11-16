package com.cadence.shifts.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.KpiTile
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    data: WorkspaceUiData,
    onNavigateToSchedule: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    onNavigateToTimeOff: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onOpenLogReport: () -> Unit
) {
    val myStaffEntry = remember(data.staffDirectory, data.profile.uid) {
        data.staffDirectory.firstOrNull { it.uid == data.profile.uid }
    }
    val myScheduledHours = remember(data.shifts, data.profile.uid) {
        data.shifts.filter { it.assignedUid == data.profile.uid && it.status != ShiftStatus.OPEN }
            .sumOf { it.durationHours }
    }
    val myLoggedHours = myStaffEntry?.totalHoursWorked ?: 0.0
    val upcomingShifts = remember(data.shifts) { data.shifts.take(5) }
    val recentNotifications = remember(data.notifications) { data.notifications.take(4) }
    val recentReports = remember(data.shiftReports) { data.shiftReports.take(4) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Workspace Greeting & Quick Actions
        item {
            CadenceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Welcome back, ${data.profile.displayName}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Slate900
                        )
                        Text(
                            text = "${data.profile.department} • ${if (data.isManager) "Manager Workspace" else "Staff Member"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                    StatusPill(
                        label = if (data.isManager) "Manager" else "Staff",
                        tone = if (data.isManager) BadgeTone.INFO else BadgeTone.NEUTRAL
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenLogReport,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                        modifier = Modifier.testTag("overview_log_report_button")
                    ) {
                        Icon(Icons.Rounded.Assignment, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Log Shift Report")
                    }

                    if (data.isManager) {
                        FilledTonalButton(
                            onClick = onNavigateToApprovals,
                            modifier = Modifier.testTag("overview_approvals_button")
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Review Approvals (${data.pendingApprovalsCount})")
                        }
                    }

                    OutlinedButton(onClick = onNavigateToSchedule) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Schedule & Fairness")
                    }

                    OutlinedButton(onClick = onNavigateToTimeOff) {
                        Icon(Icons.Rounded.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Time Off (${data.pendingTimeOffCount})")
                    }
                }
            }
        }

        // KPI Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiTile(
                        title = "My Hours vs Target",
                        value = "${FairnessEngine.formatHours(myLoggedHours + myScheduledHours)}h",
                        subtitle = "${FairnessEngine.formatHours(myLoggedHours)}h verified • ${FairnessEngine.formatHours(data.profile.weeklyHoursTarget)}h goal",
                        icon = Icons.Rounded.Schedule,
                        tone = BadgeTone.INFO,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "Pending Changes",
                        value = "${data.pendingApprovalsCount}",
                        subtitle = if (data.isManager) "Ready for Accept All" else "Awaiting review",
                        icon = Icons.Rounded.FactCheck,
                        tone = if (data.pendingApprovalsCount > 0) BadgeTone.WARNING else BadgeTone.SUCCESS,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KpiTile(
                        title = "Overlap Conflicts",
                        value = "${data.conflictShiftsCount}",
                        subtitle = if (data.conflictShiftsCount > 0) "Tap Schedule to resolve" else "Zero overlaps",
                        icon = Icons.Rounded.WarningAmber,
                        tone = if (data.conflictShiftsCount > 0) BadgeTone.DANGER else BadgeTone.SUCCESS,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "Unread Alerts",
                        value = "${data.unreadNotificationsCount}",
                        subtitle = "${data.notifications.size} total alerts",
                        icon = Icons.Rounded.NotificationsActive,
                        tone = if (data.unreadNotificationsCount > 0) BadgeTone.INFO else BadgeTone.NEUTRAL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Active Team Schedule Preview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Active Team Schedule", style = MaterialTheme.typography.titleMedium, color = Slate900)
                OutlinedButton(onClick = onNavigateToSchedule) {
                    Text("View All (${data.shifts.size})")
                }
            }
        }

        if (upcomingShifts.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.CalendarMonth,
                    title = "No Shifts Scheduled Yet",
                    subtitle = if (data.isManager) {
                        "Head to Schedule or Team to generate a weekly schedule or load sample shifts."
                    } else {
                        "Submit shift availability under Schedule so your manager can assign shifts fairly."
                    }
                )
            }
        } else {
            items(upcomingShifts.size, key = { "ov_shift_${upcomingShifts[it].id}" }) { index ->
                val shift = upcomingShifts[index]
                val tone = when (shift.status) {
                    ShiftStatus.CONFLICT -> BadgeTone.DANGER
                    ShiftStatus.OPEN -> BadgeTone.VIOLET
                    ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
                    ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
                    ShiftStatus.SCHEDULED -> BadgeTone.INFO
                }
                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shift.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = "${shift.date}  •  ${shift.startTime}-${shift.endTime} (${FairnessEngine.formatHours(shift.durationHours)}h)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = shift.status.label, tone = tone)
                    }
                    Text(
                        text = if (shift.assignedName.isNotBlank()) {
                            "Assigned: ${shift.assignedName} • ${shift.department}"
                        } else {
                            "Open / Unassigned • ${shift.department}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            }
        }

        // Recent Alerts
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent Status Updates", style = MaterialTheme.typography.titleMedium, color = Slate900)
                OutlinedButton(onClick = onNavigateToNotifications) {
                    Text("Alerts")
                }
            }
        }

        if (recentNotifications.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "No Recent Notifications",
                    subtitle = "Schedule approvals, report verifications, and fairness assignments appear here."
                )
            }
        } else {
            items(recentNotifications.size, key = { "ov_notif_${recentNotifications[it].id}" }) { index ->
                val notif = recentNotifications[index]
                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(notif.title, style = MaterialTheme.typography.titleSmall, color = Slate900, modifier = Modifier.weight(1f))
                        if (!notif.isRead) {
                            StatusPill(label = "Unread", tone = BadgeTone.INFO)
                        }
                    }
                    Text(notif.message, style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
        }

        // Latest Shift Reports
        item {
            Text("Latest Shift Reports", style = MaterialTheme.typography.titleMedium, color = Slate900)
        }
        if (recentReports.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.Assignment,
                    title = "No Shift Reports Logged",
                    subtitle = "Tap Log Shift Report above to record completed hours and handoff notes."
                )
            }
        } else {
            items(recentReports.size, key = { "ov_rep_${recentReports[it].id}" }) { index ->
                val rep = recentReports[index]
                val tone = when (rep.status) {
                    ShiftReportStatus.VERIFIED -> BadgeTone.SUCCESS
                    ShiftReportStatus.FLAGGED -> BadgeTone.DANGER
                    ShiftReportStatus.SUBMITTED -> BadgeTone.WARNING
                }
                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rep.shiftTitle, style = MaterialTheme.typography.titleSmall, color = Slate900)
                            Text(
                                text = "${rep.staffName} • ${rep.date} • ${FairnessEngine.formatHours(rep.workedHours)}h",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = rep.status.label, tone = tone)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
