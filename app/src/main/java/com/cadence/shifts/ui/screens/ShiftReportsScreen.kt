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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Emerald600
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import java.time.LocalDate

private enum class ReportFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    SUBMITTED(R.string.report_status_submitted),
    VERIFIED(R.string.report_status_verified),
    FLAGGED(R.string.report_status_flagged)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftReportsScreen(
    data: WorkspaceUiData,
    busyAction: String?,
    openModalInitially: Boolean,
    initialShiftForReport: Shift? = null,
    onConsumedInitialModal: () -> Unit,
    onSubmitReport: (
        shiftId: String,
        shiftTitle: String,
        department: String,
        date: String,
        actualStartTime: String,
        actualEndTime: String,
        breakMinutes: Int,
        handoffNotes: String
    ) -> Unit,
    onVerifyOrFlag: (com.cadence.shifts.data.model.ShiftReport, ShiftReportStatus) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(ReportFilter.ALL) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var preselectedShift by remember { mutableStateOf<Shift?>(null) }

    LaunchedEffect(openModalInitially, initialShiftForReport) {
        if (openModalInitially) {
            preselectedShift = initialShiftForReport
            showCreateDialog = true
            onConsumedInitialModal()
        }
    }

    val filteredReports = remember(data.shiftReports, selectedFilter) {
        data.shiftReports.filter { rep ->
            when (selectedFilter) {
                ReportFilter.ALL -> true
                ReportFilter.SUBMITTED -> rep.status == ShiftReportStatus.SUBMITTED
                ReportFilter.VERIFIED -> rep.status == ShiftReportStatus.VERIFIED
                ReportFilter.FLAGGED -> rep.status == ShiftReportStatus.FLAGGED
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CadenceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.shift_reports_title), style = MaterialTheme.typography.headlineSmall, color = Slate900)
                        Text(
                            text = stringResource(R.string.shift_reports_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                        modifier = Modifier.testTag("log_shift_report_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_log_report_short))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(stringResource(filter.labelRes)) }
                        )
                    }
                }
            }
        }

        if (filteredReports.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.Assignment,
                    title = stringResource(R.string.empty_reports_title),
                    subtitle = stringResource(R.string.empty_reports_subtitle)
                )
            }
        } else {
            items(filteredReports, key = { it.id }) { report ->
                val tone = when (report.status) {
                    ShiftReportStatus.VERIFIED -> BadgeTone.SUCCESS
                    ShiftReportStatus.FLAGGED -> BadgeTone.DANGER
                    ShiftReportStatus.SUBMITTED -> BadgeTone.WARNING
                }
                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(report.shiftTitle, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = "${report.staffName} • ${report.department}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(report.status.labelRes), tone = tone)
                    }

                    Text(
                        text = stringResource(
                            R.string.report_detail_format,
                            report.date,
                            report.actualStartTime,
                            report.actualEndTime,
                            report.breakMinutes,
                            FairnessEngine.formatHours(report.workedHours)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate900
                    )

                    if (report.handoffNotes.isNotBlank()) {
                        Text(
                            text = report.handoffNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }

                    if (data.isManager && report.status == ShiftReportStatus.SUBMITTED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onVerifyOrFlag(report, ShiftReportStatus.VERIFIED) },
                                enabled = busyAction == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald600, contentColor = PureWhite),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("verify_report_${report.id}")
                            ) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.btn_verify_hours_format, FairnessEngine.formatHours(report.workedHours)))
                            }
                            OutlinedButton(
                                onClick = { onVerifyOrFlag(report, ShiftReportStatus.FLAGGED) },
                                enabled = busyAction == null,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose600),
                                modifier = Modifier.testTag("flag_report_${report.id}")
                            ) {
                                Icon(Icons.Rounded.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.btn_flag))
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showCreateDialog) {
        LogShiftReportDialog(
            data = data,
            initialShift = preselectedShift,
            onDismiss = {
                showCreateDialog = false
                preselectedShift = null
            },
            onSubmit = { shiftId, title, dept, date, start, end, breakMins, notes ->
                onSubmitReport(shiftId, title, dept, date, start, end, breakMins, notes)
                showCreateDialog = false
                preselectedShift = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogShiftReportDialog(
    data: WorkspaceUiData,
    initialShift: Shift? = null,
    onDismiss: () -> Unit,
    onSubmit: (
        shiftId: String,
        shiftTitle: String,
        department: String,
        date: String,
        actualStartTime: String,
        actualEndTime: String,
        breakMinutes: Int,
        handoffNotes: String
    ) -> Unit
) {
    val selectableShifts = remember(data.shifts, data.profile.uid) {
        data.shifts.filter {
            it.status != ShiftStatus.COMPLETED && (it.assignedUid == data.profile.uid || it.assignedUid.isBlank())
        }
    }

    var selectedShift by remember(initialShift?.id) { mutableStateOf(initialShift) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val defaultTitle = stringResource(R.string.default_shift_morning)
    var shiftTitle by remember(initialShift?.id) { mutableStateOf(initialShift?.title ?: defaultTitle) }
    var department by remember(initialShift?.id) { mutableStateOf(initialShift?.department ?: data.profile.department) }
    var date by remember(initialShift?.id) { mutableStateOf(initialShift?.date ?: LocalDate.now().toString()) }
    var startTime by remember(initialShift?.id) { mutableStateOf(initialShift?.startTime ?: "08:00") }
    var endTime by remember(initialShift?.id) { mutableStateOf(initialShift?.endTime ?: "16:00") }
    var breakText by remember { mutableStateOf("30") }
    var handoffNotes by remember { mutableStateOf("") }

    val breakMinutes = breakText.toIntOrNull()?.coerceIn(0, 180) ?: 0
    val netHours = FairnessEngine.computeShiftHours(startTime, endTime, breakMinutes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_log_report)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectableShifts.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedShift?.let { "${it.title} (${it.date})" } ?: stringResource(R.string.custom_unlinked_shift),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.link_scheduled_shift_optional)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.custom_unlinked_shift)) },
                                onClick = {
                                    selectedShift = null
                                    dropdownExpanded = false
                                }
                            )
                            selectableShifts.forEach { shift ->
                                DropdownMenuItem(
                                    text = { Text("${shift.title} • ${shift.date} ${shift.startTime}-${shift.endTime}") },
                                    onClick = {
                                        selectedShift = shift
                                        shiftTitle = shift.title
                                        department = shift.department
                                        date = shift.date
                                        startTime = shift.startTime
                                        endTime = shift.endTime
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = shiftTitle,
                    onValueChange = { shiftTitle = it },
                    label = { Text(stringResource(R.string.shift_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = department,
                    onValueChange = { department = it },
                    label = { Text(stringResource(R.string.department_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.date_iso_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text(stringResource(R.string.clock_in_hhmm)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text(stringResource(R.string.clock_out_hhmm)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = breakText,
                    onValueChange = { breakText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.break_minutes_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.computed_net_hours_format, FairnessEngine.formatHours(netHours)),
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate900
                )
                OutlinedTextField(
                    value = handoffNotes,
                    onValueChange = { handoffNotes = it },
                    label = { Text(stringResource(R.string.handoff_notes_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        selectedShift?.id.orEmpty(),
                        shiftTitle,
                        department,
                        date,
                        startTime,
                        endTime,
                        breakMinutes,
                        handoffNotes
                    )
                },
                enabled = shiftTitle.isNotBlank() && date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                    startTime.matches(Regex("\\d{2}:\\d{2}")) && endTime.matches(Regex("\\d{2}:\\d{2}"))
            ) {
                Text(stringResource(R.string.submit_report))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
