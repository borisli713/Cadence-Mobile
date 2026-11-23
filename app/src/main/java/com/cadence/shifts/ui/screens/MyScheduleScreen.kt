package com.cadence.shifts.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.KpiTile
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Amber600
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald600
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate50
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import com.cadence.shifts.util.CalendarExportHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MyScheduleFilter(@StringRes val labelRes: Int, val tag: String) {
    UPCOMING(R.string.filter_upcoming, "my_schedule_filter_upcoming"),
    ALL(R.string.filter_all_assigned, "my_schedule_filter_all"),
    PENDING_CHANGE(R.string.status_pending_change, "my_schedule_filter_pending"),
    COMPLETED(R.string.status_completed, "my_schedule_filter_completed")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyScheduleScreen(
    data: WorkspaceUiData,
    onNavigateToTeamSchedule: () -> Unit,
    onOpenLogReportForShift: (Shift) -> Unit,
    onSubmitScheduleChange: (Shift, ScheduleChangeType, String, String, String, String, String, String) -> Unit,
    onUpdateShift: (String, String, String, String, String, String, String, String, ShiftStatus, String) -> Unit,
    onDeleteShift: (String) -> Unit
) {
    val context = LocalContext.current
    val todayIso = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }

    var filter by remember { mutableStateOf(MyScheduleFilter.UPCOMING) }
    var searchQuery by remember { mutableStateOf("") }
    var detailShift by remember { mutableStateOf<Shift?>(null) }
    var changeRequestShift by remember { mutableStateOf<Shift?>(null) }
    var editShift by remember { mutableStateOf<Shift?>(null) }

    val myShifts = remember(data.shifts, data.profile.uid) {
        data.shifts
            .filter { it.assignedUid == data.profile.uid && it.status != ShiftStatus.OPEN }
            .sortedWith(compareBy<Shift> { it.date }.thenBy { it.startTime })
    }

    val upcomingShifts = remember(myShifts, todayIso) {
        myShifts.filter { it.date >= todayIso && it.status != ShiftStatus.COMPLETED }
    }
    val completedShifts = remember(myShifts) {
        myShifts.filter { it.status == ShiftStatus.COMPLETED }
    }
    val pendingChangeShifts = remember(myShifts) {
        myShifts.filter { it.status == ShiftStatus.PENDING_CHANGE }
    }

    val nextShift = remember(upcomingShifts) { upcomingShifts.firstOrNull() }
    val upcomingHours = remember(upcomingShifts) { upcomingShifts.sumOf { it.durationHours } }
    val completedHours = remember(completedShifts) { completedShifts.sumOf { it.durationHours } }

    val filteredShifts = remember(myShifts, filter, searchQuery, todayIso) {
        val q = searchQuery.trim().lowercase()
        val base = when (filter) {
            MyScheduleFilter.UPCOMING -> myShifts.filter { it.date >= todayIso && it.status != ShiftStatus.COMPLETED }
            MyScheduleFilter.ALL -> myShifts
            MyScheduleFilter.PENDING_CHANGE -> myShifts.filter { it.status == ShiftStatus.PENDING_CHANGE }
            MyScheduleFilter.COMPLETED -> myShifts.filter { it.status == ShiftStatus.COMPLETED }
        }
        if (q.isBlank()) base else base.filter { shift ->
            shift.title.lowercase().contains(q) ||
                shift.department.lowercase().contains(q) ||
                shift.date.contains(q) ||
                shift.fairnessNote.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        item {
            CadenceCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(listOf(Blue50, Slate50)),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.my_schedule_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Slate900
                            )
                            Text(
                                text = "${data.profile.displayName} • ${data.profile.department}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        Surface(
                            color = PureWhite,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = stringResource(R.string.synced_with_web),
                                    tint = Blue700,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.live_sync),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Blue700
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (myShifts.isNotEmpty()) {
                                CalendarExportHelper.shareIcsFile(context, myShifts, "cadence-my-shifts.ics")
                            }
                        },
                        enabled = myShifts.isNotEmpty(),
                        modifier = Modifier.testTag("my_schedule_export_ics")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.export_my_ics))
                    }

                    OutlinedButton(
                        onClick = onNavigateToTeamSchedule,
                        modifier = Modifier.testTag("my_schedule_team_button")
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.full_team_schedule))
                    }
                }
            }
        }

        // Next Upcoming Shift Spotlight
        nextShift?.let { next ->
            item {
                val relativeBadge = relativeDayBadge(next.date, todayIso)
                CadenceCard(
                    containerColor = PureWhite,
                    borderColor = Blue700.copy(alpha = 0.35f),
                    modifier = Modifier
                        .clickable { detailShift = next }
                        .testTag("my_next_shift_spotlight")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Today,
                                contentDescription = null,
                                tint = Blue700,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.next_upcoming_shift),
                                style = MaterialTheme.typography.labelLarge,
                                color = Blue700
                            )
                        }
                        StatusPill(label = relativeBadge, tone = BadgeTone.INFO)
                    }

                    Text(
                        text = next.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate900
                    )
                    Text(
                        text = stringResource(
                            R.string.next_shift_meta_format,
                            formatShiftDayLabel(next.date),
                            next.startTime,
                            next.endTime,
                            FairnessEngine.formatHours(next.durationHours),
                            next.department
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { changeRequestShift = next },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                            modifier = Modifier.testTag("next_shift_request_change_button")
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_request_change))
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(CalendarExportHelper.buildGoogleCalendarIntent(next))
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_google_calendar))
                        }
                    }
                }
            }
        }

        // KPI Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiTile(
                    title = stringResource(R.string.kpi_upcoming_shifts),
                    value = "${upcomingShifts.size}",
                    subtitle = stringResource(R.string.hours_assigned_format, FairnessEngine.formatHours(upcomingHours)),
                    icon = Icons.Rounded.Schedule,
                    tone = BadgeTone.INFO,
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = stringResource(R.string.kpi_completed_shifts),
                    value = "${completedShifts.size}",
                    subtitle = stringResource(R.string.hours_worked_format, FairnessEngine.formatHours(completedHours)),
                    icon = Icons.Rounded.CheckCircle,
                    tone = BadgeTone.SUCCESS,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Filter & Search Card
        item {
            CadenceCard {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_my_shifts_label)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = searchQuery.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("my_schedule_search")
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MyScheduleFilter.entries.forEach { option ->
                        val count = when (option) {
                            MyScheduleFilter.UPCOMING -> upcomingShifts.size
                            MyScheduleFilter.ALL -> myShifts.size
                            MyScheduleFilter.PENDING_CHANGE -> pendingChangeShifts.size
                            MyScheduleFilter.COMPLETED -> completedShifts.size
                        }
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(stringResource(R.string.filter_count_format, stringResource(option.labelRes), count)) },
                            modifier = Modifier.testTag(option.tag)
                        )
                    }
                }
            }
        }

        if (filteredShifts.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.EventAvailable,
                    title = if (myShifts.isEmpty()) {
                        stringResource(R.string.empty_my_schedule_none_title)
                    } else {
                        stringResource(R.string.empty_my_schedule_filtered_title)
                    },
                    subtitle = if (myShifts.isEmpty()) {
                        stringResource(R.string.empty_my_schedule_none_subtitle)
                    } else {
                        stringResource(R.string.empty_my_schedule_filtered_subtitle)
                    }
                )
            }
        } else {
            items(filteredShifts, key = { it.id }) { shift ->
                AssignedShiftCard(
                    shift = shift,
                    todayIso = todayIso,
                    onClick = { detailShift = shift },
                    onAddToCalendar = {
                        runCatching {
                            context.startActivity(CalendarExportHelper.buildGoogleCalendarIntent(shift))
                        }
                    },
                    onRequestChange = { changeRequestShift = shift },
                    onLogReport = { onOpenLogReportForShift(shift) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    detailShift?.let { s ->
        ShiftDetailDialog(
            shift = s,
            isManager = data.isManager,
            onDismiss = { detailShift = null },
            onRequestChange = { changeRequestShift = it },
            onEditShift = { editShift = it },
            onDeleteShift = onDeleteShift
        )
    }

    changeRequestShift?.let { s ->
        RequestChangeDialog(
            shift = s,
            availableShifts = data.shifts,
            staffDirectory = data.staffDirectory,
            currentUserUid = data.profile.uid,
            currentUserName = data.profile.displayName,
            onDismiss = { changeRequestShift = null },
            onSubmit = { chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason ->
                onSubmitScheduleChange(chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason)
            }
        )
    }

    editShift?.let { s ->
        EditShiftDialog(
            shift = s,
            staffDirectory = data.staffDirectory,
            onDismiss = { editShift = null },
            onSave = onUpdateShift
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignedShiftCard(
    shift: Shift,
    todayIso: String,
    onClick: () -> Unit,
    onAddToCalendar: () -> Unit,
    onRequestChange: () -> Unit,
    onLogReport: () -> Unit
) {
    val statusTone = when (shift.status) {
        ShiftStatus.CONFLICT -> BadgeTone.DANGER
        ShiftStatus.OPEN -> BadgeTone.VIOLET
        ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
        ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
        ShiftStatus.SCHEDULED -> BadgeTone.INFO
    }
    val borderColor = when (shift.status) {
        ShiftStatus.PENDING_CHANGE -> Amber600.copy(alpha = 0.35f)
        ShiftStatus.CONFLICT -> Rose600.copy(alpha = 0.35f)
        ShiftStatus.COMPLETED -> Emerald600.copy(alpha = 0.3f)
        else -> Slate200
    }
    val dayLabel = remember(shift.date) { formatShiftDayLabel(shift.date) }
    val relBadge = relativeDayBadge(shift.date, todayIso)

    CadenceCard(
        borderColor = borderColor,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("my_shift_card_${shift.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(shift.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                Text(
                    text = stringResource(R.string.day_badge_format, dayLabel, relBadge, shift.department),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }
            StatusPill(label = stringResource(shift.status.labelRes), tone = statusTone)
        }

        Surface(
            color = Slate50,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${shift.startTime} – ${shift.endTime}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate900
                )
                Text(
                    text = stringResource(R.string.duration_hours_format, FairnessEngine.formatHours(shift.durationHours)),
                    style = MaterialTheme.typography.labelMedium,
                    color = Blue700
                )
            }
        }

        if (shift.fairnessNote.isNotBlank()) {
            Text(
                text = shift.fairnessNote,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600
            )
        }

        HorizontalDivider(color = Slate100)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onAddToCalendar) {
                Icon(Icons.Rounded.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.btn_calendar))
            }

            if (shift.status != ShiftStatus.COMPLETED) {
                OutlinedButton(
                    onClick = onRequestChange,
                    modifier = Modifier.testTag("my_shift_change_${shift.id}")
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_request_change))
                }

                OutlinedButton(
                    onClick = onLogReport,
                    modifier = Modifier.testTag("my_shift_report_${shift.id}")
                ) {
                    Icon(Icons.Rounded.Assignment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_log_report))
                }
            }
        }
    }
}

internal fun formatShiftDayLabel(isoDate: String): String =
    runCatching {
        val parsed = LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE)
        val fmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
        parsed.format(fmt)
    }.getOrDefault(isoDate)

@Composable
private fun relativeDayBadge(isoDate: String, todayIso: String): String {
    val parsedShift = remember(isoDate) {
        runCatching { LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
    val parsedToday = remember(todayIso) {
        runCatching { LocalDate.parse(todayIso, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
    if (parsedShift == null || parsedToday == null) return isoDate
    return when {
        parsedShift == parsedToday -> stringResource(R.string.rel_today)
        parsedShift == parsedToday.plusDays(1) -> stringResource(R.string.rel_tomorrow)
        parsedShift.isBefore(parsedToday) -> stringResource(R.string.rel_past)
        else -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(parsedToday, parsedShift)
            stringResource(R.string.rel_in_days_format, days)
        }
    }
}
