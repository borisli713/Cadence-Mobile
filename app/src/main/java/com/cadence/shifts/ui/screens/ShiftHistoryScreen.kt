package com.cadence.shifts.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.ScheduleChangeRequest
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.KpiTile
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald50
import com.cadence.shifts.ui.theme.Emerald600
import com.cadence.shifts.ui.theme.Emerald700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose50
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Rose700
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate50
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import com.google.firebase.Timestamp
import java.text.DateFormat
import java.util.Locale

private enum class ShiftHistoryFilter(val label: String, val tag: String) {
    ALL("All", "history_filter_all"),
    APPROVED("Approved", "history_filter_approved"),
    REJECTED("Rejected", "history_filter_rejected")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftHistoryScreen(
    data: WorkspaceUiData
) {
    var filter by remember { mutableStateOf(ShiftHistoryFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val resolvedRequests = remember(data.scheduleChanges) {
        data.scheduleChanges
            .filter { it.status == ApprovalStatus.APPROVED || it.status == ApprovalStatus.DENIED }
            .sortedByDescending { req ->
                val ts = req.updatedAt ?: req.createdAt
                ts?.seconds ?: 0L
            }
    }

    val approvedCount = remember(resolvedRequests) {
        resolvedRequests.count { it.status == ApprovalStatus.APPROVED }
    }
    val rejectedCount = remember(resolvedRequests) {
        resolvedRequests.count { it.status == ApprovalStatus.DENIED }
    }

    val filteredHistory = remember(resolvedRequests, filter, searchQuery) {
        val q = searchQuery.trim().lowercase()
        resolvedRequests.filter { req ->
            val matchesStatus = when (filter) {
                ShiftHistoryFilter.ALL -> true
                ShiftHistoryFilter.APPROVED -> req.status == ApprovalStatus.APPROVED
                ShiftHistoryFilter.REJECTED -> req.status == ApprovalStatus.DENIED
            }
            val matchesSearch = q.isBlank() ||
                req.shiftTitle.lowercase().contains(q) ||
                req.requesterName.lowercase().contains(q) ||
                req.reviewedByName.lowercase().contains(q) ||
                req.type.label.lowercase().contains(q) ||
                req.reason.lowercase().contains(q) ||
                req.reviewNote.lowercase().contains(q) ||
                req.proposedDate.contains(q) ||
                req.currentDate.contains(q)
            matchesStatus && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card with Live Sync Indicator & Filters
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
                                text = "Shift History",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Slate900
                            )
                            Text(
                                text = if (data.isManager) {
                                    "Audit log of team shift requests approved or rejected across Web & Android."
                                } else {
                                    "Complete record of your past approved and rejected shift requests."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        Surface(
                            color = PureWhite,
                            shape = RoundedCornerShape(999.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "Synced with Web App",
                                    tint = Blue700,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Live Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Blue700
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search shift, staff, reviewer, or date") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = searchQuery.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shift_history_search")
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShiftHistoryFilter.entries.forEach { option ->
                        val count = when (option) {
                            ShiftHistoryFilter.ALL -> resolvedRequests.size
                            ShiftHistoryFilter.APPROVED -> approvedCount
                            ShiftHistoryFilter.REJECTED -> rejectedCount
                        }
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text("${option.label} ($count)") },
                            modifier = Modifier.testTag(option.tag)
                        )
                    }
                }
            }
        }

        // Summary KPI Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiTile(
                    title = "Approved Requests",
                    value = "$approvedCount",
                    subtitle = "Applied to schedule",
                    icon = Icons.Rounded.CheckCircle,
                    tone = BadgeTone.SUCCESS,
                    modifier = Modifier.weight(1f)
                )
                KpiTile(
                    title = "Rejected Requests",
                    value = "$rejectedCount",
                    subtitle = "Declined by manager",
                    icon = Icons.Rounded.Cancel,
                    tone = BadgeTone.DANGER,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (filteredHistory.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.History,
                    title = if (resolvedRequests.isEmpty()) {
                        "No Shift History Yet"
                    } else {
                        "No Matching History Entries"
                    },
                    subtitle = if (resolvedRequests.isEmpty()) {
                        "Approved and rejected shift change, swap, drop, and claim requests will appear here with timestamps once reviewed."
                    } else {
                        "Try adjusting your status filter or clearing the search query."
                    }
                )
            }
        } else {
            items(filteredHistory, key = { it.id }) { req ->
                ShiftHistoryItemCard(req = req)
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShiftHistoryItemCard(req: ScheduleChangeRequest) {
    val isApproved = req.status == ApprovalStatus.APPROVED
    val statusLabel = if (isApproved) "Approved" else "Rejected"
    val statusTone = if (isApproved) BadgeTone.SUCCESS else BadgeTone.DANGER
    val accentBg = if (isApproved) Emerald50.copy(alpha = 0.4f) else Rose50.copy(alpha = 0.4f)
    val accentBorder = if (isApproved) Emerald600.copy(alpha = 0.3f) else Rose600.copy(alpha = 0.3f)
    val typeTone = when (req.type) {
        ScheduleChangeType.TIME_CHANGE -> BadgeTone.INFO
        ScheduleChangeType.SWAP -> BadgeTone.VIOLET
        ScheduleChangeType.DROP -> BadgeTone.WARNING
        ScheduleChangeType.CLAIM -> BadgeTone.NEUTRAL
    }

    val decisionTimestamp = remember(req.updatedAt, req.createdAt) {
        formatFirestoreTimestamp(req.updatedAt ?: req.createdAt)
    }
    val submittedTimestamp = remember(req.createdAt) {
        formatFirestoreTimestamp(req.createdAt)
    }

    CadenceCard(
        containerColor = PureWhite,
        borderColor = accentBorder,
        modifier = Modifier.testTag("history_card_${req.id}")
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
                Text(
                    text = req.shiftTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate900
                )
                Text(
                    text = "Requested by ${req.requesterName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusPill(label = req.type.label, tone = typeTone)
                StatusPill(label = statusLabel, tone = statusTone)
            }
        }

        Surface(
            color = accentBg,
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "Original: ${req.currentDate}  •  ${req.currentStartTime}–${req.currentEndTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate600
                )
                val proposedSuffix = if (req.proposedAssigneeName.isNotBlank()) {
                    " → ${req.proposedAssigneeName}"
                } else ""
                Text(
                    text = "Requested: ${req.proposedDate}  •  ${req.proposedStartTime}–${req.proposedEndTime}$proposedSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate900
                )
            }
        }

        if (req.reason.isNotBlank()) {
            Text(
                text = "Reason: ${req.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate900
            )
        }

        if (req.reviewedByName.isNotBlank() || req.reviewNote.isNotBlank()) {
            val reviewer = req.reviewedByName.ifBlank { "Manager" }
            val noteText = if (req.reviewNote.isNotBlank()) {
                "Reviewed by $reviewer: \"${req.reviewNote}\""
            } else {
                "Reviewed by $reviewer"
            }
            Text(
                text = noteText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isApproved) Emerald700 else Rose700
            )
        }

        HorizontalDivider(color = Slate100)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = if (isApproved) Emerald700 else Rose700,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "$statusLabel: $decisionTimestamp",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate900
                )
            }
            if (submittedTimestamp != "Pending timestamp") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Submitted: $submittedTimestamp",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate600
                    )
                }
            }
        }
    }
}

internal fun formatFirestoreTimestamp(timestamp: Timestamp?): String {
    if (timestamp == null) return "Pending timestamp"
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    return formatter.format(timestamp.toDate())
}
