package com.cadence.shifts.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.data.model.WeekSlotConfig
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import com.cadence.shifts.util.CalendarExportHelper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun ShiftDetailDialog(
    shift: Shift,
    isManager: Boolean,
    onDismiss: () -> Unit,
    onRequestChange: (Shift) -> Unit,
    onEditShift: (Shift) -> Unit,
    onDeleteShift: (String) -> Unit
) {
    val context = LocalContext.current
    val tone = when (shift.status) {
        ShiftStatus.CONFLICT -> BadgeTone.DANGER
        ShiftStatus.OPEN -> BadgeTone.VIOLET
        ShiftStatus.PENDING_CHANGE -> BadgeTone.WARNING
        ShiftStatus.COMPLETED -> BadgeTone.SUCCESS
        ShiftStatus.SCHEDULED -> BadgeTone.INFO
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(shift.title, modifier = Modifier.weight(1f))
                StatusPill(label = stringResource(shift.status.labelRes), tone = tone)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${shift.date}  •  ${shift.startTime}–${shift.endTime} (${FairnessEngine.formatHours(shift.durationHours)}h)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate900
                )
                Text(
                    text = stringResource(R.string.dept_format, shift.department),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
                Text(
                    text = if (shift.assignedName.isNotBlank()) {
                        stringResource(R.string.assigned_to_format, shift.assignedName)
                    } else {
                        stringResource(R.string.open_unassigned)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate900
                )
                if (shift.candidateUids.size > 1) {
                    Text(
                        text = stringResource(R.string.overlapping_candidates_format, shift.candidateUids.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
                if (shift.fairnessNote.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.fairness_note_format, shift.fairnessNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = CalendarExportHelper.buildGoogleCalendarIntent(shift)
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_add_to_google_calendar))
                }

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onRequestChange(shift)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (shift.status == ShiftStatus.OPEN) {
                                R.string.btn_claim_open_shift
                            } else {
                                R.string.btn_request_change_swap
                            }
                        )
                    )
                }

                if (isManager) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onEditShift(shift)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.btn_edit))
                        }
                        OutlinedButton(
                            onClick = {
                                onDeleteShift(shift.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose600),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.btn_delete))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
fun AddShiftDialog(
    data: WorkspaceUiData,
    onDismiss: () -> Unit,
    onCreate: (
        title: String,
        dept: String,
        date: String,
        start: String,
        end: String,
        candidates: List<String>,
        asConflict: Boolean,
        leaveOpen: Boolean
    ) -> Unit
) {
    val defaultTitle = stringResource(R.string.default_shift_morning)
    val defaultDept = stringResource(R.string.default_dept_operations)
    var title by remember { mutableStateOf(defaultTitle) }
    var department by remember { mutableStateOf(defaultDept) }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var startTime by remember { mutableStateOf("08:00") }
    var endTime by remember { mutableStateOf("16:00") }
    var leaveOpen by remember { mutableStateOf(false) }
    var createAsConflict by remember { mutableStateOf(false) }
    val selectedUids = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_create_shift)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.shift_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = department, onValueChange = { department = it }, label = { Text(stringResource(R.string.department_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text(stringResource(R.string.date_iso_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text(stringResource(R.string.start_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text(stringResource(R.string.end_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.leave_open_blank), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = leaveOpen, onCheckedChange = { leaveOpen = it })
                }

                if (!leaveOpen) {
                    Text(stringResource(R.string.candidate_staff_count, selectedUids.size), style = MaterialTheme.typography.labelMedium, color = Slate600)
                    data.staffDirectory.forEach { member ->
                        val checked = member.uid in selectedUids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedUids.remove(member.uid) else selectedUids.add(member.uid)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedUids.add(member.uid) else selectedUids.remove(member.uid)
                                }
                            )
                            Text(
                                text = stringResource(
                                    R.string.staff_hours_option_format,
                                    member.displayName,
                                    FairnessEngine.formatHours(member.totalHoursWorked)
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (selectedUids.size >= 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.save_as_overlap_conflict), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = createAsConflict, onCheckedChange = { createAsConflict = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(title, department, date, startTime, endTime, selectedUids.toList(), createAsConflict, leaveOpen)
                    onDismiss()
                },
                enabled = title.isNotBlank() && date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            ) {
                Text(stringResource(R.string.dialog_create_shift))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditShiftDialog(
    shift: Shift,
    staffDirectory: List<StaffDirectoryEntry>,
    onDismiss: () -> Unit,
    onSave: (
        shiftId: String,
        title: String,
        dept: String,
        date: String,
        start: String,
        end: String,
        assignedUid: String,
        assignedName: String,
        status: ShiftStatus,
        note: String
    ) -> Unit
) {
    var title by remember { mutableStateOf(shift.title) }
    var dept by remember { mutableStateOf(shift.department) }
    var date by remember { mutableStateOf(shift.date) }
    var start by remember { mutableStateOf(shift.startTime) }
    var end by remember { mutableStateOf(shift.endTime) }
    var assignedUid by remember { mutableStateOf(shift.assignedUid) }
    var assignedName by remember { mutableStateOf(shift.assignedName) }
    var status by remember { mutableStateOf(shift.status) }
    var note by remember { mutableStateOf(shift.fairnessNote) }
    var staffMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_edit_shift)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dept, onValueChange = { dept = it }, label = { Text(stringResource(R.string.department_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text(stringResource(R.string.date_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text(stringResource(R.string.start_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text(stringResource(R.string.end_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                }

                ExposedDropdownMenuBox(
                    expanded = staffMenuExpanded,
                    onExpandedChange = { staffMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = assignedName.ifBlank { stringResource(R.string.open_unassigned) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.assigned_staff_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = staffMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = staffMenuExpanded,
                        onDismissRequest = { staffMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_unassigned)) },
                            onClick = {
                                assignedUid = ""
                                assignedName = ""
                                status = ShiftStatus.OPEN
                                staffMenuExpanded = false
                            }
                        )
                        staffDirectory.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.staff_dept_option_format, member.displayName, member.department)) },
                                onClick = {
                                    assignedUid = member.uid
                                    assignedName = member.displayName
                                    if (status == ShiftStatus.OPEN) status = ShiftStatus.SCHEDULED
                                    staffMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.fairness_shift_note_label)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveStatus = if (assignedUid.isBlank()) ShiftStatus.OPEN else status
                    onSave(shift.id, title, dept, date, start, end, assignedUid, assignedName, effectiveStatus, note)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save_short))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RequestChangeDialog(
    shift: Shift,
    availableShifts: List<Shift> = listOf(shift),
    staffDirectory: List<StaffDirectoryEntry>,
    currentUserUid: String,
    currentUserName: String,
    onDismiss: () -> Unit,
    onSubmit: (
        selectedShift: Shift,
        type: ScheduleChangeType,
        proposedDate: String,
        proposedStart: String,
        proposedEnd: String,
        proposedAssigneeUid: String,
        proposedAssigneeName: String,
        reason: String
    ) -> Unit
) {
    val shiftOptions = remember(availableShifts, shift, currentUserUid) {
        val base = if (availableShifts.any { it.id == shift.id }) availableShifts else listOf(shift) + availableShifts
        val eligible = base.filter { option ->
            option.status == ShiftStatus.OPEN ||
                (option.assignedUid == currentUserUid && option.status != ShiftStatus.COMPLETED)
        }
        if (eligible.isNotEmpty()) eligible else listOf(shift)
    }
    var selectedShift by remember(shift.id) { mutableStateOf(shiftOptions.firstOrNull { it.id == shift.id } ?: shiftOptions.first()) }
    var shiftDropdownExpanded by remember { mutableStateOf(false) }

    val allowedTypesForSelected = remember(selectedShift, currentUserUid) {
        when {
            selectedShift.status == ShiftStatus.OPEN -> listOf(ScheduleChangeType.CLAIM)
            selectedShift.assignedUid == currentUserUid -> listOf(
                ScheduleChangeType.TIME_CHANGE,
                ScheduleChangeType.SWAP,
                ScheduleChangeType.DROP
            )
            else -> emptyList()
        }
    }

    val defaultType = if (selectedShift.status == ShiftStatus.OPEN) ScheduleChangeType.CLAIM else ScheduleChangeType.TIME_CHANGE
    var type by remember(shift.id) { mutableStateOf(defaultType) }
    var proposedDate by remember(shift.id) { mutableStateOf(selectedShift.date) }
    var proposedStart by remember(shift.id) { mutableStateOf(selectedShift.startTime) }
    var proposedEnd by remember(shift.id) { mutableStateOf(selectedShift.endTime) }
    var swapUid by remember(shift.id) { mutableStateOf(if (defaultType == ScheduleChangeType.CLAIM) currentUserUid else "") }
    var swapName by remember(shift.id) { mutableStateOf(if (defaultType == ScheduleChangeType.CLAIM) currentUserName else "") }
    var reason by remember { mutableStateOf("") }
    var swapDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_request_change)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = shiftDropdownExpanded,
                    onExpandedChange = { shiftDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "${selectedShift.title} (${selectedShift.date} ${selectedShift.startTime}-${selectedShift.endTime})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.select_shift_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.shift_status_support_format,
                                    stringResource(selectedShift.status.labelRes),
                                    selectedShift.assignedName.ifBlank { stringResource(R.string.open_unassigned) }
                                )
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shiftDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = shiftDropdownExpanded,
                        onDismissRequest = { shiftDropdownExpanded = false }
                    ) {
                        shiftOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = "${option.date} • ${option.startTime}-${option.endTime} • ${stringResource(option.status.labelRes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Slate600
                                        )
                                    }
                                },
                                onClick = {
                                    selectedShift = option
                                    proposedDate = option.date
                                    proposedStart = option.startTime
                                    proposedEnd = option.endTime
                                    val nextDefault = if (option.status == ShiftStatus.OPEN) {
                                        ScheduleChangeType.CLAIM
                                    } else {
                                        ScheduleChangeType.TIME_CHANGE
                                    }
                                    type = nextDefault
                                    if (nextDefault == ScheduleChangeType.CLAIM) {
                                        swapUid = currentUserUid
                                        swapName = currentUserName
                                    } else if (swapUid == option.assignedUid) {
                                        swapUid = ""
                                        swapName = ""
                                    }
                                    shiftDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleChangeType.entries.forEach { t ->
                        val chipEnabled = t in allowedTypesForSelected
                        FilterChip(
                            selected = type == t,
                            enabled = chipEnabled,
                            onClick = {
                                type = t
                                when (t) {
                                    ScheduleChangeType.CLAIM -> {
                                        swapUid = currentUserUid
                                        swapName = currentUserName
                                    }
                                    ScheduleChangeType.TIME_CHANGE,
                                    ScheduleChangeType.DROP -> {
                                        swapUid = ""
                                        swapName = ""
                                    }
                                    ScheduleChangeType.SWAP -> {
                                        if (swapUid == currentUserUid && selectedShift.assignedUid == currentUserUid) {
                                            swapUid = ""
                                            swapName = ""
                                        }
                                    }
                                }
                            },
                            label = { Text(stringResource(t.labelRes)) }
                        )
                    }
                }

                val isTimeChangeValid = type != ScheduleChangeType.TIME_CHANGE || (
                    proposedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                        proposedStart.matches(Regex("\\d{2}:\\d{2}")) &&
                        proposedEnd.matches(Regex("\\d{2}:\\d{2}")) &&
                        FairnessEngine.computeShiftHours(proposedStart, proposedEnd) >= 0.25
                    )

                if (type == ScheduleChangeType.TIME_CHANGE) {
                    OutlinedTextField(value = proposedDate, onValueChange = { proposedDate = it }, label = { Text(stringResource(R.string.proposed_date_iso)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = proposedStart, onValueChange = { proposedStart = it }, label = { Text(stringResource(R.string.start_hhmm)) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = proposedEnd, onValueChange = { proposedEnd = it }, label = { Text(stringResource(R.string.end_hhmm)) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    if (!isTimeChangeValid) {
                        Text(
                            text = stringResource(R.string.time_change_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = Rose600
                        )
                    }
                }

                if (type == ScheduleChangeType.SWAP) {
                    ExposedDropdownMenuBox(expanded = swapDropdown, onExpandedChange = { swapDropdown = it }) {
                        OutlinedTextField(
                            value = swapName.ifBlank { stringResource(R.string.select_swap_teammate) },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.swap_with_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = swapDropdown) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = swapDropdown, onDismissRequest = { swapDropdown = false }) {
                            staffDirectory.filter { it.uid != selectedShift.assignedUid }.forEach { member ->
                                DropdownMenuItem(
                                    text = { Text(member.displayName) },
                                    onClick = {
                                        swapUid = member.uid
                                        swapName = member.displayName
                                        swapDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.reason_label)) },
                    placeholder = { Text(stringResource(R.string.reason_placeholder)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val isTimeValid = type != ScheduleChangeType.TIME_CHANGE || (
                proposedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                    proposedStart.matches(Regex("\\d{2}:\\d{2}")) &&
                    proposedEnd.matches(Regex("\\d{2}:\\d{2}")) &&
                    FairnessEngine.computeShiftHours(proposedStart, proposedEnd) >= 0.25
                )
            Button(
                onClick = {
                    onSubmit(selectedShift, type, proposedDate, proposedStart, proposedEnd, swapUid, swapName, reason)
                    onDismiss()
                },
                enabled = type in allowedTypesForSelected &&
                    reason.isNotBlank() &&
                    isTimeValid &&
                    (type != ScheduleChangeType.SWAP || swapUid.isNotBlank())
            ) {
                Text(stringResource(R.string.submit_request))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubmitAvailabilityDialog(
    defaultDept: String,
    onDismiss: () -> Unit,
    onSubmit: (dates: List<String>, startTime: String, endTime: String, shiftTitle: String, notes: String) -> Unit
) {
    val fmt = remember { DateTimeFormatter.ISO_LOCAL_DATE }
    val today = remember { LocalDate.now() }
    val nextSevenDays = remember(today) { (1..7).map { today.plusDays(it.toLong()) } }

    val selectedDates = remember { mutableStateListOf<String>(today.plusDays(1).format(fmt)) }
    val defaultAvailTitle = stringResource(R.string.default_avail_title)
    var shiftTitle by remember { mutableStateOf(defaultAvailTitle) }
    var startTime by remember { mutableStateOf("08:00") }
    var endTime by remember { mutableStateOf("16:00") }
    var notes by remember { mutableStateOf("") }
    val chipFmt = remember { DateTimeFormatter.ofPattern("EEE M/d", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_submit_availability)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.select_dates_dept_format, defaultDept), style = MaterialTheme.typography.labelMedium, color = Slate600)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        selectedDates.clear()
                        val mon = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                        (0..4).forEach { selectedDates.add(mon.plusDays(it.toLong()).format(fmt)) }
                    }) {
                        Text(stringResource(R.string.next_mon_fri))
                    }
                    OutlinedButton(onClick = {
                        selectedDates.clear()
                        nextSevenDays.forEach { selectedDates.add(it.format(fmt)) }
                    }) {
                        Text(stringResource(R.string.next_7_days))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    nextSevenDays.forEach { d ->
                        val iso = d.format(fmt)
                        val label = d.format(chipFmt)
                        FilterChip(
                            selected = iso in selectedDates,
                            onClick = {
                                if (iso in selectedDates) selectedDates.remove(iso) else selectedDates.add(iso)
                            },
                            label = { Text(label) }
                        )
                    }
                }

                OutlinedTextField(value = shiftTitle, onValueChange = { shiftTitle = it }, label = { Text(stringResource(R.string.preferred_role_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text(stringResource(R.string.start_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text(stringResource(R.string.end_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.notes_optional)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(selectedDates.toList(), startTime, endTime, shiftTitle, notes)
                    onDismiss()
                },
                enabled = selectedDates.isNotEmpty()
            ) {
                Text(stringResource(R.string.submit_count, selectedDates.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun WeekSlotsDialog(
    initialSlots: List<WeekSlotConfig>,
    onDismiss: () -> Unit,
    onSave: (List<WeekSlotConfig>) -> Unit
) {
    val editable = remember(initialSlots) { mutableStateListOf(*initialSlots.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_week_slots)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                editable.forEachIndexed { idx, slot ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.slot_label_format, slot.label), style = MaterialTheme.typography.titleSmall, color = Slate900)
                        OutlinedTextField(
                            value = slot.title,
                            onValueChange = { editable[idx] = slot.copy(title = it) },
                            label = { Text(stringResource(R.string.shift_title_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = slot.startTime,
                                onValueChange = { editable[idx] = slot.copy(startTime = it) },
                                label = { Text(stringResource(R.string.start_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = slot.endTime,
                                onValueChange = { editable[idx] = slot.copy(endTime = it) },
                                label = { Text(stringResource(R.string.end_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(editable.toList())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite)
            ) {
                Text(stringResource(R.string.save_templates))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
