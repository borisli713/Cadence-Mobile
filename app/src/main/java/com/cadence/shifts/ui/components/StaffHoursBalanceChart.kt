package com.cadence.shifts.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.ui.theme.Blue600
import com.cadence.shifts.ui.theme.Emerald600
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import kotlin.math.max

@Composable
fun StaffHoursBalanceChart(
    staffDirectory: List<StaffDirectoryEntry>,
    shifts: List<Shift>,
    modifier: Modifier = Modifier
) {
    val rows = remember(staffDirectory, shifts) {
        val scheduledByUid = FairnessEngine.computeScheduledHoursByUid(shifts)
        staffDirectory.map { s ->
            val scheduled = scheduledByUid[s.uid] ?: s.scheduledHoursThisWeek
            val total = s.totalHoursWorked + scheduled
            StaffChartRow(
                name = s.displayName,
                logged = s.totalHoursWorked,
                scheduled = scheduled,
                target = max(1.0, s.weeklyHoursTarget),
                total = total
            )
        }.sortedBy { it.total / it.target }
    }

    CadenceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Staff Hours Balance vs Weekly Target",
                    style = MaterialTheme.typography.titleMedium,
                    color = Slate900
                )
                Text(
                    text = "Emerald = verified logged • Blue = scheduled • Dashed marker = weekly target",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = "No staff roster entries available yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate600,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            val maxAxis = remember(rows) {
                max(45.0, rows.maxOf { max(it.total, it.target) } * 1.1)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.take(10).forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = row.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = Slate900
                            )
                            Text(
                                text = "${FairnessEngine.formatHours(row.logged)}h + ${FairnessEngine.formatHours(row.scheduled)}h / ${row.target.toInt()}h",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                        ) {
                            val w = size.width
                            val h = size.height
                            val radius = CornerRadius(h / 2f, h / 2f)

                            // Track
                            drawRoundRect(
                                color = Slate200,
                                topLeft = Offset.Zero,
                                size = Size(w, h),
                                cornerRadius = radius
                            )

                            // Logged hours (Emerald)
                            val loggedW = ((row.logged / maxAxis) * w).toFloat().coerceIn(0f, w)
                            if (loggedW > 0f) {
                                drawRoundRect(
                                    color = Emerald600,
                                    topLeft = Offset.Zero,
                                    size = Size(loggedW, h),
                                    cornerRadius = radius
                                )
                            }

                            // Scheduled hours (Blue)
                            val schedW = ((row.scheduled / maxAxis) * w).toFloat()
                                .coerceIn(0f, (w - loggedW).coerceAtLeast(0f))
                            if (schedW > 0f) {
                                drawRoundRect(
                                    color = Blue600,
                                    topLeft = Offset(loggedW, 0f),
                                    size = Size(schedW, h),
                                    cornerRadius = radius
                                )
                            }

                            // Target marker
                            val targetX = ((row.target / maxAxis) * w).toFloat().coerceIn(2f, w - 2f)
                            drawLine(
                                color = Slate900,
                                start = Offset(targetX, 0f),
                                end = Offset(targetX, h),
                                strokeWidth = 3f
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class StaffChartRow(
    val name: String,
    val logged: Double,
    val scheduled: Double,
    val target: Double,
    val total: Double
)
