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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
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
                Text(stringResource(R.string.team_title), style = MaterialTheme.typography.headlineSmall, color = Slate900)
                Text(
                    text = stringResource(R.string.team_subtitle),
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
                            Text(stringResource(R.string.btn_add_roster_staff))
                        }

                        OutlinedButton(
                            onClick = onSeedSampleData,
                            enabled = busyAction == null,
                            modifier = Modifier.testTag("seed_sample_button")
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_load_sample_data))
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
                    title = stringResource(R.string.empty_team_title),
                    subtitle = stringResource(R.string.empty_team_subtitle)
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
                                text = "${member.department} • ${stringResource(member.role.labelRes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                        StatusPill(label = stringResource(R.string.ratio_percent_format, pct), tone = tone)
                    }

                    Text(
                        text = stringResource(
                            R.string.team_hours_line_format,
                            FairnessEngine.formatHours(member.totalHoursWorked),
                            FairnessEngine.formatHours(scheduled),
                            FairnessEngine.formatHours(member.weeklyHoursTarget)
                        ),
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
                            Text(stringResource(if (grantManager) R.string.btn_promote_manager else R.string.btn_set_staff))
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
    val defaultDept = stringResource(R.string.default_dept_operations)
    var name by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf(defaultDept) }
    var targetText by remember { mutableStateOf("40") }
    var role by remember { mutableStateOf(UserRole.STAFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_roster_member)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.full_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dept,
                    onValueChange = { dept = it },
                    label = { Text(stringResource(R.string.department_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.weekly_hours_target_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserRole.entries.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = { Text(stringResource(r.labelRes)) }
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
                Text(stringResource(R.string.btn_add_member))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
