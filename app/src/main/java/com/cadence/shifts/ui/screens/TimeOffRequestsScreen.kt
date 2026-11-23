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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.TimeOffRequest
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
import java.time.temporal.ChronoUnit

private enum class TimeOffFilter(@StringRes val labelRes: Int) {
    PENDING(R.string.approval_pending),
    APPROVED(R.string.approval_approved),
    DENIED(R.string.approval_denied),
    ALL(R.string.filter_all)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeOffRequestsScreen(
    data: WorkspaceUiData,
    busyAction: String?,
    onCreateTimeOff: (startDate: String, endDate: String, reason: String) -> Unit,
    onWithdrawTimeOff: (String) -> Unit,
    onDecideTimeOff: (TimeOffRequest, ApprovalStatus, String) -> Unit
) {
    var filter by remember { mutableStateOf(TimeOffFilter.PENDING) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val reviewNotes = remember { mutableStateMapOf<String, String>() }

    val filtered = remember(data.timeOffRequests, filter) {
        data.timeOffRequests.filter { req ->
            when (filter) {
                TimeOffFilter.PENDING -> req.status == ApprovalStatus.PENDING
                TimeOffFilter.APPROVED -> req.status == ApprovalStatus.APPROVED
                TimeOffFilter.DENIED -> req.status == ApprovalStatus.DENIED
                TimeOffFilter.ALL -> true
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
                        Text(stringResource(R.string.time_off_title), style = MaterialTheme.typography.headlineSmall, color = Slate900)
                        Text(
                            text = stringResource(R.string.time_off_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                        modifier = Modifier.testTag("request_time_off_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_request))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeOffFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.EventBusy,
                    title = stringResource(R.string.empty_time_off_title),
                    subtitle = stringResource(R.string.empty_time_off_subtitle)
                )
            }
        } else {
            items(filtered, key = { it.id }) { req ->
                val tone = when (req.status) {
                    ApprovalStatus.APPROVED -> BadgeTone.SUCCESS
                    ApprovalStatus.DENIED -> BadgeTone.DANGER
                    ApprovalStatus.PENDING -> BadgeTone.WARNING
                }
                val days = runCatching {
                    val s = LocalDate.parse(req.startDate)
                    val e = LocalDate.parse(req.endDate)
                    ChronoUnit.DAYS.between(s, e) + 1
                }.getOrDefault(1L)

                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(req.staffName, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = stringResource(
                                    if (days == 1L) R.string.time_off_dates_singular_format else R.string.time_off_dates_plural_format,
                                    req.startDate,
                                    req.endDate,
                                    days,
                                    req.department
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(req.status.labelRes), tone = tone)
                    }

                    Text(
                        text = req.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate900
                    )

                    if (req.reviewNote.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.note_reviewer_format, req.reviewedByName, req.reviewNote),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }

                    if (req.status == ApprovalStatus.PENDING) {
                        if (req.staffUid == data.profile.uid) {
                            OutlinedButton(
                                onClick = { onWithdrawTimeOff(req.id) },
                                enabled = busyAction == null
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.btn_withdraw_request))
                            }
                        }

                        if (data.isManager) {
                            val note = reviewNotes[req.id].orEmpty()
                            OutlinedTextField(
                                value = note,
                                onValueChange = { reviewNotes[req.id] = it },
                                label = { Text(stringResource(R.string.optional_review_note)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onDecideTimeOff(req, ApprovalStatus.APPROVED, note) },
                                    enabled = busyAction == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600, contentColor = PureWhite),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_approve))
                                }
                                OutlinedButton(
                                    onClick = { onDecideTimeOff(req, ApprovalStatus.DENIED, note) },
                                    enabled = busyAction == null,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose600),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.btn_deny))
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showCreateDialog) {
        RequestTimeOffDialog(
            onDismiss = { showCreateDialog = false },
            onSubmit = { s, e, r ->
                onCreateTimeOff(s, e, r)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun RequestTimeOffDialog(
    onDismiss: () -> Unit,
    onSubmit: (startDate: String, endDate: String, reason: String) -> Unit
) {
    val tomorrow = remember { LocalDate.now().plusDays(1).toString() }
    var startDate by remember { mutableStateOf(tomorrow) }
    var endDate by remember { mutableStateOf(tomorrow) }
    var reason by remember { mutableStateOf("") }

    val validDates = startDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        endDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        endDate >= startDate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_request_time_off)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text(stringResource(R.string.start_date_iso)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text(stringResource(R.string.end_date_iso)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.reason_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(startDate, endDate, reason) },
                enabled = validDates && reason.isNotBlank()
            ) {
                Text(stringResource(R.string.submit_request))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
