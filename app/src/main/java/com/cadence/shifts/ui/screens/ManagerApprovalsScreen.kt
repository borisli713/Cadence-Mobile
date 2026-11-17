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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.ScheduleChangeRequest
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald600
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData

private enum class ApprovalFilter(val label: String) {
    PENDING("Pending"),
    APPROVED("Approved"),
    DENIED("Denied"),
    ALL("All")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManagerApprovalsScreen(
    data: WorkspaceUiData,
    busyAction: String?,
    onAcceptAll: () -> Unit,
    onDecide: (ScheduleChangeRequest, ApprovalStatus, String) -> Unit,
    onSubmitScheduleChange: (Shift, ScheduleChangeType, String, String, String, String, String, String) -> Unit
) {
    var filter by remember { mutableStateOf(ApprovalFilter.PENDING) }
    val reviewNotes = remember { mutableStateMapOf<String, String>() }
    var requestDialogShift by remember { mutableStateOf<Shift?>(null) }

    val pendingCount = remember(data.scheduleChanges) {
        data.scheduleChanges.count { it.status == ApprovalStatus.PENDING }
    }

    val filtered = remember(data.scheduleChanges, filter) {
        data.scheduleChanges.filter { req ->
            when (filter) {
                ApprovalFilter.PENDING -> req.status == ApprovalStatus.PENDING
                ApprovalFilter.APPROVED -> req.status == ApprovalStatus.APPROVED
                ApprovalFilter.DENIED -> req.status == ApprovalStatus.DENIED
                ApprovalFilter.ALL -> true
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
                        Text(
                            text = if (data.isManager) "Manager Approvals" else "My Change Requests",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Slate900
                        )
                        Text(
                            text = if (data.isManager) {
                                "Approve individual requests or use one-click Accept All to update shifts & notify staff."
                            } else {
                                "Track your submitted time changes, swaps, drops, and shift claims."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                    if (data.isManager && pendingCount > 0) {
                        Button(
                            onClick = onAcceptAll,
                            enabled = busyAction == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600, contentColor = PureWhite),
                            modifier = Modifier.testTag("accept_all_button")
                        ) {
                            Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Accept All ($pendingCount)")
                        }
                    }
                }

                if (data.shifts.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            requestDialogShift = data.shifts.firstOrNull { it.assignedUid == data.profile.uid }
                                ?: data.shifts.firstOrNull()
                        },
                        enabled = busyAction == null,
                        modifier = Modifier.testTag("approvals_new_request_button")
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Change Request")
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApprovalFilter.entries.forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = {
                                val label = if (item == ApprovalFilter.PENDING) {
                                    "${item.label} ($pendingCount)"
                                } else item.label
                                Text(label)
                            }
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.FactCheck,
                    title = "No Requests in This Queue",
                    subtitle = "All pending schedule changes, swaps, and claims have been handled."
                )
            }
        } else {
            items(filtered, key = { it.id }) { req ->
                val statusTone = when (req.status) {
                    ApprovalStatus.APPROVED -> BadgeTone.SUCCESS
                    ApprovalStatus.DENIED -> BadgeTone.DANGER
                    ApprovalStatus.PENDING -> BadgeTone.WARNING
                }

                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(req.shiftTitle, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = "Requested by ${req.requesterName} • ${req.type.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = req.status.label, tone = statusTone)
                    }

                    Text(
                        text = "Current:  ${req.currentDate} ${req.currentStartTime}-${req.currentEndTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate600
                    )
                    Text(
                        text = "Proposed: ${req.proposedDate} ${req.proposedStartTime}-${req.proposedEndTime}" +
                            if (req.proposedAssigneeName.isNotBlank()) " → ${req.proposedAssigneeName}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Blue700
                    )
                    Text(
                        text = "Reason: ${req.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate900
                    )

                    if (req.reviewNote.isNotBlank()) {
                        Text(
                            text = "Manager Note (${req.reviewedByName}): ${req.reviewNote}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }

                    if (data.isManager && req.status == ApprovalStatus.PENDING) {
                        val noteVal = reviewNotes[req.id].orEmpty()
                        OutlinedTextField(
                            value = noteVal,
                            onValueChange = { reviewNotes[req.id] = it },
                            label = { Text("Optional review note") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onDecide(req, ApprovalStatus.APPROVED, noteVal) },
                                enabled = busyAction == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald600, contentColor = PureWhite),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("approve_change_${req.id}")
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Approve")
                            }
                            OutlinedButton(
                                onClick = { onDecide(req, ApprovalStatus.DENIED, noteVal) },
                                enabled = busyAction == null,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose600),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("deny_change_${req.id}")
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Deny")
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    requestDialogShift?.let { shift ->
        RequestChangeDialog(
            shift = shift,
            availableShifts = data.shifts,
            staffDirectory = data.staffDirectory,
            currentUserUid = data.profile.uid,
            currentUserName = data.profile.displayName,
            onDismiss = { requestDialogShift = null },
            onSubmit = { chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason ->
                onSubmitScheduleChange(chosenShift, type, pDate, pStart, pEnd, pUid, pName, reason)
            }
        )
    }
}
