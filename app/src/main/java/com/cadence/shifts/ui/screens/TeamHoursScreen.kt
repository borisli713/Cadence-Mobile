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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.data.model.UserRole
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.components.BadgeTone
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.components.EmptyStatePanel
import com.cadence.shifts.ui.components.StaffHoursBalanceChart
import com.cadence.shifts.ui.components.StatusPill
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamHoursScreen(
    data: WorkspaceUiData,
    busyAction: String?,
    onSeedSampleData: () -> Unit,
    onAddRosterStaff: (name: String, dept: String, weeklyTarget: Double, role: UserRole) -> Unit,
    onToggleManagerRole: (StaffDirectoryEntry, Boolean) -> Unit
) {
    var showAddStaffDialog by remember { mutableStateOf(false) }

    val sortedLedger = remember(data.staffDirectory, data.shifts) {
        val scheduledByUid = FairnessEngine.computeScheduledHoursByUid(data.shifts)
        data.staffDirectory.map { entry ->
            val scheduled = scheduledByUid[entry.uid] ?: entry.scheduledHoursThisWeek
            val target = max(1.0, entry.weeklyHoursTarget)
            val ratio = (entry.totalHoursWorked + scheduled) / target
            Triple(entry, scheduled, ratio)
        }.sortedBy { it.third }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CadenceCard {
                Text("Team & Hours Fairness Ledger", style = MaterialTheme.typography.headlineSmall, color = Slate900)
                Text(
                    text = "Staff are ordered by lowest utilization ratio first so overlapping or open shifts go to members with fewer hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )

                if (data.isManager) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showAddStaffDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                            modifier = Modifier.testTag("add_roster_staff_button")
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Roster Staff")
                        }

                        OutlinedButton(
                            onClick = onSeedSampleData,
                            enabled = busyAction == null,
                            modifier = Modifier.testTag("seed_sample_button")
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Load Sample Overlaps & Requests")
                        }
                    }
                }
            }
        }

        item {
            StaffHoursBalanceChart(staffDirectory = data.staffDirectory, shifts = data.shifts)
        }

        if (sortedLedger.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Rounded.Groups,
                    title = "No Team Members Found",
                    subtitle = "Managers can add roster members or load sample workspace data above."
                )
            }
        } else {
            items(sortedLedger, key = { it.first.uid }) { (member, scheduled, ratio) ->
                val pct = (ratio * 100.0).roundToInt()
                val tone = when {
                    pct < 60 -> BadgeTone.SUCCESS
                    pct <= 100 -> BadgeTone.INFO
                    else -> BadgeTone.WARNING
                }
                CadenceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.displayName, style = MaterialTheme.typography.titleMedium, color = Slate900)
                            Text(
                                text = "${member.department} • ${if (member.role == UserRole.MANAGER) "Manager" else "Staff"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = "$pct% Ratio", tone = tone)
                    }

                    Text(
                        text = "Logged: ${FairnessEngine.formatHours(member.totalHoursWorked)}h  •  Scheduled: ${FairnessEngine.formatHours(scheduled)}h  •  Target: ${FairnessEngine.formatHours(member.weeklyHoursTarget)}h",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate900
                    )

                    if (data.isManager && member.uid != data.profile.uid && !member.isSampleRoster) {
                        val grantManager = member.role != UserRole.MANAGER
                        OutlinedButton(
                            onClick = { onToggleManagerRole(member, grantManager) },
                            enabled = busyAction == null
                        ) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (grantManager) "Promote to Manager" else "Set Role to Staff")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showAddStaffDialog) {
        AddRosterStaffDialog(
            onDismiss = { showAddStaffDialog = false },
            onAdd = { name, dept, target, role ->
                onAddRosterStaff(name, dept, target, role)
                showAddStaffDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRosterStaffDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, dept: String, weeklyTarget: Double, role: UserRole) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("Operations") }
    var targetText by remember { mutableStateOf("40") }
    var role by remember { mutableStateOf(UserRole.STAFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Roster Staff Member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dept,
                    onValueChange = { dept = it },
                    label = { Text("Department") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Weekly Hours Target") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserRole.entries.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = { Text(r.value.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = (targetText.toDoubleOrNull() ?: 40.0).coerceIn(1.0, 80.0)
                    onAdd(name, dept, target, role)
                },
                enabled = name.isNotBlank() && dept.isNotBlank()
            ) {
                Text("Add Member")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
