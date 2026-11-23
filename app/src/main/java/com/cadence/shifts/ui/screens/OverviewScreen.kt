package com.cadence.shifts.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.KpiTile
import com.cadence.shifts.ui.components.PlatformLinksCard
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    data: WorkspaceUiData,
    onNavigateToMySchedule: () -> Unit,
    onNavigateToSchedule: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToTimeOff: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onOpenLogReport: () -> Unit,
    onSubmitScheduleChange: (Shift, ScheduleChangeType, String, String, String, String, String, String) -> Unit
) {
    var selectedChangeShift by remember { mutableStateOf<Shift?>(null) }
    val todayIso = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }
    val myStaffEntry = remember(data.staffDirectory, data.profile.uid) {
        data.staffDirectory.firstOrNull { it.uid == data.profile.uid }
    }
    val myScheduledHours = remember(data.myAssignedShifts) {
        data.myAssignedShifts.sumOf { it.durationHours }
    }
    val myLoggedHours = myStaffEntry?.totalHoursWorked ?: 0.0
    val myPreviewShifts = remember(data.myAssignedShifts, todayIso) {
        val upcoming = data.myAssignedShifts.filter { it.date >= todayIso && it.status != ShiftStatus.COMPLETED }
        (upcoming.ifEmpty { data.myAssignedShifts }).take(3)
    }
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
                            text = stringResource(R.string.welcome_back_format, data.profile.displayName),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Slate900
                        )
                        val workspaceSubtitle = if (data.isManager) {
                            stringResource(R.string.manager_workspace)
                        } else {
                            stringResource(R.string.staff_member)
                        }
                        Text(
                            text = stringResource(R.string.email_role_format, data.profile.department, workspaceSubtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                    StatusPill(
                        label = stringResource(if (data.isManager) R.string.role_manager else R.string.role_staff),
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
                        Text(stringResource(R.string.btn_log_shift_report))
                    }

                    if (data.isManager) {
                        FilledTonalButton(
                            onClick = onNavigateToApprovals,
                            modifier = Modifier.testTag("overview_approvals_button")
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_review_approvals_format, data.pendingApprovalsCount))
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            selectedChangeShift = data.shifts.firstOrNull { it.assignedUid == data.profile.uid }
                                ?: data.shifts.firstOrNull()
                        },
                        enabled = data.shifts.isNotEmpty(),
                        modifier = Modifier.testTag("overview_request_shift_change_button")
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_request_shift_change))
                    }

                    FilledTonalButton(
                        onClick = onNavigateToMySchedule,
                        modifier = Modifier.testTag("overview_my_schedule_button")
                    ) {
                        Icon(Icons.Rounded.Today, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_my_schedule_count, data.myAssignedShifts.size))
                    }

                    OutlinedButton(onClick = onNavigateToSchedule) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_schedule_fairness))
                    }

                    OutlinedButton(onClick = onNavigateToTimeOff) {
                        Icon(Icons.Rounded.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_time_off_count, data.pendingTimeOffCount))
                    }

                    OutlinedButton(
                        onClick = onNavigateToHistory,
                        modifier = Modifier.testTag("overview_shift_history_button")
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.nav_history))
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
                        title = stringResource(R.string.kpi_my_hours_target),
                        value = stringResource(
                            R.string.kpi_hours_value_format,
                            FairnessEngine.formatHours(myLoggedHours + myScheduledHours)
                        ),
                        subtitle = stringResource(
                            R.string.kpi_my_hours_subtitle_format,
                            FairnessEngine.formatHours(myLoggedHours),
                            FairnessEngine.formatHours(data.profile.weeklyHoursTarget)
                        ),
                        icon = Icons.Rounded.Schedule,
                        tone = BadgeTone.INFO,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = stringResource(R.string.kpi_pending_changes),
                        value = "${data.pendingApprovalsCount}",
                        subtitle = stringResource(
                            if (data.isManager) R.string.kpi_ready_accept_all else R.string.kpi_awaiting_review
                        ),
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
                        title = stringResource(R.string.kpi_overlap_conflicts),
                        value = "${data.conflictShiftsCount}",
                        subtitle = stringResource(
                            if (data.conflictShiftsCount > 0) R.string.kpi_tap_schedule_resolve else R.string.kpi_zero_overlaps
                        ),
                        icon = Icons.Rounded.WarningAmber,
                        tone = if (data.conflictShiftsCount > 0) BadgeTone.DANGER else BadgeTone.SUCCESS,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = stringResource(R.string.kpi_unread_alerts),
                        value = "${data.unreadNotificationsCount}",
                        subtitle = stringResource(R.string.kpi_total_alerts_format, data.notifications.size),
                        icon = Icons.Rounded.NotificationsActive,
                        tone = if (data.unreadNotificationsCount > 0) BadgeTone.INFO else BadgeTone.NEUTRAL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // My Assigned Shifts Preview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.section_my_assigned_shifts), style = MaterialTheme.typography.titleMedium, color = Slate900)
                OutlinedButton(
                    onClick = onNavigateToMySchedule,
                    modifier = Modifier.testTag("overview_my_shifts_view_all")
                ) {
                    Text(stringResource(R.string.btn_view_all_count, data.myAssignedShifts.size))
                }
            }
        }

        if (myPreviewShifts.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.Today,
                    title = stringResource(R.string.empty_my_shifts_title),
                    subtitle = stringResource(R.string.empty_my_shifts_subtitle)
                )
            }
        } else {
            items(myPreviewShifts.size, key = { "ov_my_shift_${myPreviewShifts[it].id}" }) { index ->
                val shift = myPreviewShifts[index]
                val tone = when (shift.status) {
                    ShiftStatus.CONFLICT -> BadgeTone.DANGER
                    ShiftStatus.OPEN -> BadgeTone.VIOLET
                    ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
                    ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
                    ShiftStatus.SCHEDULED -> BadgeTone.INFO
                }
                CadenceCard(
                    borderColor = Blue700.copy(alpha = 0.25f),
                    modifier = Modifier
                        .clickable { selectedChangeShift = shift }
                        .testTag("overview_my_shift_card_${shift.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shift.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = stringResource(
                                    R.string.shift_meta_duration_format,
                                    shift.date,
                                    shift.startTime,
                                    shift.endTime,
                                    FairnessEngine.formatHours(shift.durationHours)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(shift.status.labelRes), tone = tone)
                    }
                    Text(
                        text = stringResource(R.string.tap_request_shift_change_format, shift.department),
                        style = MaterialTheme.typography.bodySmall,
                        color = Blue700
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
                Text(stringResource(R.string.section_active_team_schedule), style = MaterialTheme.typography.titleMedium, color = Slate900)
                OutlinedButton(onClick = onNavigateToSchedule) {
                    Text(stringResource(R.string.btn_view_all_count, data.shifts.size))
                }
            }
        }

        if (upcomingShifts.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.CalendarMonth,
                    title = stringResource(R.string.empty_no_shifts_title),
                    subtitle = stringResource(
                        if (data.isManager) R.string.empty_no_shifts_manager else R.string.empty_no_shifts_staff
                    )
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
                CadenceCard(
                    modifier = Modifier
                        .clickable { selectedChangeShift = shift }
                        .testTag("overview_shift_card_${shift.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shift.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = stringResource(
                                    R.string.shift_meta_duration_format,
                                    shift.date,
                                    shift.startTime,
                                    shift.endTime,
                                    FairnessEngine.formatHours(shift.durationHours)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(shift.status.labelRes), tone = tone)
                    }
                    Text(
                        text = if (shift.assignedName.isNotBlank()) {
                            stringResource(R.string.assigned_to_dept_format, shift.assignedName, shift.department)
                        } else {
                            stringResource(R.string.open_unassigned_dept_format, shift.department)
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
                Text(stringResource(R.string.section_recent_status_updates), style = MaterialTheme.typography.titleMedium, color = Slate900)
                OutlinedButton(onClick = onNavigateToNotifications) {
                    Text(stringResource(R.string.nav_alerts))
                }
            }
        }

        if (recentNotifications.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.empty_no_notifs_title),
                    subtitle = stringResource(R.string.empty_no_notifs_subtitle)
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
                            StatusPill(label = stringResource(R.string.badge_unread), tone = BadgeTone.INFO)
                        }
                    }
                    Text(notif.message, style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
        }

        // Latest Shift Reports
        item {
            Text(stringResource(R.string.section_latest_shift_reports), style = MaterialTheme.typography.titleMedium, color = Slate900)
        }
        if (recentReports.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.Assignment,
                    title = stringResource(R.string.empty_no_reports_title),
                    subtitle = stringResource(R.string.empty_no_reports_subtitle)
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
                                text = stringResource(
                                    R.string.overview_report_meta_format,
                                    rep.staffName,
                                    rep.date,
                                    FairnessEngine.formatHours(rep.workedHours)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(rep.status.labelRes), tone = tone)
                    }
                }
            }
        }

        item {
            PlatformLinksCard()
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    selectedChangeShift?.let { shift ->
        RequestChangeDialog(
            shift = shift,
            availableShifts = data.shifts,
            staffDirectory = data.staffDirectory,
            currentUserUid = data.profile.uid,
            currentUserName = data.profile.displayName,
            onDismiss = { selectedChangeShift = null },
            onSubmit = { chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason ->
                onSubmitScheduleChange(chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason)
            }
        )
    }
}
