package com.cadence.shifts.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.SubmissionStatus
import com.cadence.shifts.data.model.WeekSlotConfig
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.StaffHoursBalanceChart
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald50
import com.cadence.shifts.ui.theme.Emerald700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose50
import com.cadence.shifts.ui.theme.Rose700
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.theme.Violet50
import com.cadence.shifts.ui.theme.Violet700
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import com.cadence.shifts.util.CalendarExportHelper
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private enum class ScheduleSubTab(val label: String) {
    CALENDAR("Calendar"),
    TABLE("Shift Table"),
    SUBMISSIONS("Submissions")
}

private enum class ShiftTableFilter(val label: String) {
    ALL("All"),
    MINE("Mine"),
    OPEN("Open"),
    OVERLAPS("Overlaps"),
    COMPLETED("Completed")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleFairnessScreen(
    data: WorkspaceUiData,
    weekSlots: List<WeekSlotConfig>,
    busyAction: String?,
    onNavigateToMySchedule: () -> Unit,
    onArrangeOverlaps: () -> Unit,
    onAssignOpenShifts: () -> Unit,
    onGenerateWeekSchedule: (String) -> Unit,
    onUpdateWeekSlots: (List<WeekSlotConfig>) -> Unit,
    onCreateShift: (String, String, String, String, String, List<String>, Boolean, Boolean) -> Unit,
    onUpdateShift: (String, String, String, String, String, String, String, String, ShiftStatus, String) -> Unit,
    onDeleteShift: (String) -> Unit,
    onSubmitScheduleChange: (Shift, ScheduleChangeType, String, String, String, String, String, String) -> Unit,
    onSubmitAvailability: (List<String>, String, String, String, String) -> Unit,
    onWithdrawSubmission: (String) -> Unit
) {
    val context = LocalContext.current
    val isoFmt = remember { DateTimeFormatter.ISO_LOCAL_DATE }

    var subTab by remember { mutableStateOf(ScheduleSubTab.CALENDAR) }
    var calendarIsMonth by remember { mutableStateOf(false) }
    var myShiftsOnly by remember { mutableStateOf(false) }
    var weekAnchorDate by remember { mutableStateOf(FairnessEngine.getMondayOfWeek(LocalDate.now())) }
    var tableFilter by remember { mutableStateOf(ShiftTableFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    var detailShift by remember { mutableStateOf<Shift?>(null) }
    var changeRequestShift by remember { mutableStateOf<Shift?>(null) }
    var editShift by remember { mutableStateOf<Shift?>(null) }
    var showAddShiftDialog by remember { mutableStateOf(false) }
    var showWeekSlotsDialog by remember { mutableStateOf(false) }
    var showAvailabilityDialog by remember { mutableStateOf(false) }

    val openShiftsCount = remember(data.shifts) { data.shifts.count { it.status == ShiftStatus.OPEN } }
    val filteredShifts = remember(data.shifts, tableFilter, searchQuery, data.profile.uid) {
        val q = searchQuery.trim().lowercase()
        data.shifts.filter { s ->
            val filterMatch = when (tableFilter) {
                ShiftTableFilter.ALL -> true
                ShiftTableFilter.MINE -> s.assignedUid == data.profile.uid
                ShiftTableFilter.OPEN -> s.status == ShiftStatus.OPEN
                ShiftTableFilter.OVERLAPS -> s.status == ShiftStatus.CONFLICT || s.candidateUids.size > 1
                ShiftTableFilter.COMPLETED -> s.status == ShiftStatus.COMPLETED
            }
            val searchMatch = q.isBlank() ||
                s.title.lowercase().contains(q) ||
                s.assignedName.lowercase().contains(q) ||
                s.department.lowercase().contains(q) ||
                s.date.contains(q)
            filterMatch && searchMatch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + SubTab + Toolbars
        item {
            CadenceCard {
                Text("Schedule & Fairness", style = MaterialTheme.typography.headlineSmall, color = Slate900)
                Text(
                    text = "Balanced overlap resolution, weekly shift templates, staff availability matching, and calendar export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleSubTab.entries.forEach { tab ->
                        FilterChip(
                            selected = subTab == tab,
                            onClick = { subTab = tab },
                            label = { Text(tab.label) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showAvailabilityDialog = true },
                        modifier = Modifier.testTag("submit_availability_button")
                    ) {
                        Icon(Icons.Rounded.EventAvailable, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Submit Availability")
                    }

                    OutlinedButton(
                        onClick = onNavigateToMySchedule,
                        modifier = Modifier.testTag("schedule_my_schedule_button")
                    ) {
                        Icon(Icons.Rounded.Today, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("My Schedule (${data.myAssignedShifts.size})")
                    }

                    OutlinedButton(
                        onClick = {
                            if (data.shifts.isNotEmpty()) {
                                CalendarExportHelper.shareIcsFile(context, data.shifts)
                            }
                        },
                        enabled = data.shifts.isNotEmpty()
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export .ics")
                    }
                }

                if (data.isManager) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onGenerateWeekSchedule(weekAnchorDate.format(isoFmt)) },
                            enabled = busyAction == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                            modifier = Modifier.testTag("generate_week_button")
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Generate Week")
                        }

                        Button(
                            onClick = onArrangeOverlaps,
                            enabled = busyAction == null && data.conflictShiftsCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Blue700, contentColor = PureWhite),
                            modifier = Modifier.testTag("arrange_overlaps_button")
                        ) {
                            Icon(Icons.Rounded.Balance, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Arrange Overlaps (${data.conflictShiftsCount})")
                        }

                        FilledTonalButton(
                            onClick = onAssignOpenShifts,
                            enabled = busyAction == null && openShiftsCount > 0,
                            modifier = Modifier.testTag("assign_open_button")
                        ) {
                            Text("Assign Open ($openShiftsCount)")
                        }

                        OutlinedButton(onClick = { showAddShiftDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Shift")
                        }

                        OutlinedButton(onClick = { showWeekSlotsDialog = true }) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Week Slots")
                        }
                    }
                }
            }
        }

        // Sub-Tab Body
        when (subTab) {
            ScheduleSubTab.CALENDAR -> {
                item {
                    CadenceCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    weekAnchorDate = if (calendarIsMonth) weekAnchorDate.minusMonths(1) else weekAnchorDate.minusWeeks(1)
                                }) {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous period")
                                }
                                Text(
                                    text = if (calendarIsMonth) {
                                        "${weekAnchorDate.month.name.take(3)} ${weekAnchorDate.year}"
                                    } else {
                                        "Week of ${weekAnchorDate.format(isoFmt)}"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Slate900
                                )
                                IconButton(onClick = {
                                    weekAnchorDate = if (calendarIsMonth) weekAnchorDate.plusMonths(1) else weekAnchorDate.plusWeeks(1)
                                }) {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Next period")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    weekAnchorDate = FairnessEngine.getMondayOfWeek(LocalDate.now())
                                }) { Text("Today") }
                                FilterChip(
                                    selected = calendarIsMonth,
                                    onClick = { calendarIsMonth = !calendarIsMonth },
                                    label = { Text(if (calendarIsMonth) "Month" else "Week") }
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = myShiftsOnly,
                                onClick = { myShiftsOnly = !myShiftsOnly },
                                label = { Text("My Shifts Only (${data.myAssignedShifts.size})") },
                                modifier = Modifier.testTag("calendar_my_shifts_chip")
                            )
                        }

                        val daysToDisplay = if (calendarIsMonth) {
                            val ym = YearMonth.from(weekAnchorDate)
                            (1..ym.lengthOfMonth()).map { ym.atDay(it) }
                        } else {
                            (0..6).map { weekAnchorDate.plusDays(it.toLong()) }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            daysToDisplay.forEach { day ->
                                val dayIso = day.format(isoFmt)
                                val dayShifts = data.shifts.filter { shift ->
                                    shift.date == dayIso && (
                                        !myShiftsOnly ||
                                            (shift.assignedUid == data.profile.uid && shift.status != ShiftStatus.OPEN)
                                        )
                                }
                                val isToday = day == LocalDate.now()
                                if (!calendarIsMonth || dayShifts.isNotEmpty() || isToday) {
                                    Surface(
                                        color = if (isToday) Blue50.copy(alpha = 0.55f) else Slate100.copy(alpha = 0.6f),
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, if (isToday) Blue700.copy(alpha = 0.35f) else Slate200)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "${day.dayOfWeek.name.take(3)} • $dayIso" + if (isToday) " (Today)" else "",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = Slate900
                                            )
                                            if (dayShifts.isEmpty()) {
                                                Text("No shifts scheduled", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                            } else {
                                                dayShifts.forEach { shift ->
                                                    val (pillBg, pillFg) = when (shift.status) {
                                                        ShiftStatus.CONFLICT -> Rose50 to Rose700
                                                        ShiftStatus.OPEN -> Violet50 to Violet700
                                                        ShiftStatus.COMPLETED -> Emerald50 to Emerald700
                                                        else -> PureWhite to Slate900
                                                    }
                                                    Surface(
                                                        color = pillBg,
                                                        shape = MaterialTheme.shapes.small,
                                                        border = BorderStroke(1.dp, Slate200),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { detailShift = shift }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(shift.title, style = MaterialTheme.typography.labelLarge, color = pillFg)
                                                                Text(
                                                                    text = "${shift.startTime}–${shift.endTime} • ${shift.assignedName.ifBlank { "Open / Blank" }}",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = Slate600
                                                                )
                                                            }
                                                            StatusPill(
                                                                label = shift.status.label,
                                                                tone = when (shift.status) {
                                                                    ShiftStatus.CONFLICT -> BadgeTone.DANGER
                                                                    ShiftStatus.OPEN -> BadgeTone.VIOLET
                                                                    ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
                                                                    ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
                                                                    ShiftStatus.SCHEDULED -> BadgeTone.INFO
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ScheduleSubTab.TABLE -> {
                item {
                    CadenceCard {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search shifts, staff, or department") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShiftTableFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = tableFilter == f,
                                    onClick = { tableFilter = f },
                                    label = { Text(f.label) }
                                )
                            }
                        }
                    }
                }

                if (filteredShifts.isEmpty()) {
                    item {
                        EmptyStatePanel(
                            icon = Icons.Rounded.CalendarMonth,
                            title = "No Shifts Match Filter",
                            subtitle = "Adjust filters or create/generate shifts."
                        )
                    }
                } else {
                    items(filteredShifts, key = { it.id }) { shift ->
                        val tone = when (shift.status) {
                            ShiftStatus.CONFLICT -> BadgeTone.DANGER
                            ShiftStatus.OPEN -> BadgeTone.VIOLET
                            ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
                            ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
                            ShiftStatus.SCHEDULED -> BadgeTone.INFO
                        }
                        CadenceCard(modifier = Modifier.clickable { detailShift = shift }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(shift.title, style = MaterialTheme.typography.titleMedium, color = Slate900)
                                    Text(
                                        text = "${shift.date}  •  ${shift.startTime}–${shift.endTime} (${FairnessEngine.formatHours(shift.durationHours)}h)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Slate600
                                    )
                                }
                                StatusPill(label = shift.status.label, tone = tone)
                            }
                            Text(
                                text = "${shift.assignedName.ifBlank { "Open / Unassigned" }} • ${shift.department}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate900
                            )
                            if (shift.fairnessNote.isNotBlank()) {
                                Text(shift.fairnessNote, style = MaterialTheme.typography.bodySmall, color = Slate600)
                            }
                        }
                    }
                }
            }

            ScheduleSubTab.SUBMISSIONS -> {
                if (data.shiftSubmissions.isEmpty()) {
                    item {
                        EmptyStatePanel(
                            icon = Icons.Rounded.EventAvailable,
                            title = "No Shift Availability Submissions",
                            subtitle = "Staff submit preferred dates & shift windows here so Generate Week fills slots transparently."
                        )
                    }
                } else {
                    items(data.shiftSubmissions, key = { it.id }) { sub ->
                        CadenceCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${sub.staffName} — ${sub.shiftTitle.ifBlank { "Shift Availability" }}", style = MaterialTheme.typography.titleSmall, color = Slate900)
                                    Text("${sub.date}  •  ${sub.startTime}–${sub.endTime}  •  ${sub.department}", style = MaterialTheme.typography.labelSmall, color = Slate600)
                                }
                                StatusPill(
                                    label = sub.status.label,
                                    tone = if (sub.status == SubmissionStatus.SUBMITTED) BadgeTone.SUCCESS else BadgeTone.NEUTRAL
                                )
                            }
                            if (sub.notes.isNotBlank()) {
                                Text(sub.notes, style = MaterialTheme.typography.bodySmall, color = Slate600)
                            }
                            if (sub.status == SubmissionStatus.SUBMITTED && (sub.staffUid == data.profile.uid || data.isManager)) {
                                OutlinedButton(
                                    onClick = { onWithdrawSubmission(sub.id) },
                                    enabled = busyAction == null
                                ) {
                                    Text("Withdraw Submission")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Staff Balance Chart at bottom of Schedule & Fairness
        item {
            StaffHoursBalanceChart(staffDirectory = data.staffDirectory, shifts = data.shifts)
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    // Dialogs
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

    if (showAddShiftDialog) {
        AddShiftDialog(
            data = data,
            onDismiss = { showAddShiftDialog = false },
            onCreate = onCreateShift
        )
    }

    if (showWeekSlotsDialog) {
        WeekSlotsDialog(
            initialSlots = weekSlots,
            onDismiss = { showWeekSlotsDialog = false },
            onSave = onUpdateWeekSlots
        )
    }

    if (showAvailabilityDialog) {
        SubmitAvailabilityDialog(
            defaultDept = data.profile.department,
            onDismiss = { showAvailabilityDialog = false },
            onSubmit = onSubmitAvailability
        )
    }
}
