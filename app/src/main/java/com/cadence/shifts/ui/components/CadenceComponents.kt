package com.cadence.shifts.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cadence.shifts.ui.theme.Amber50
import com.cadence.shifts.ui.theme.Amber700
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald50
import com.cadence.shifts.ui.theme.Emerald700
import com.cadence.shifts.ui.theme.Rose50
import com.cadence.shifts.ui.theme.Rose700
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate700
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.theme.Violet50
import com.cadence.shifts.ui.theme.Violet700

enum class BadgeTone {
    NEUTRAL, INFO, SUCCESS, WARNING, DANGER, VIOLET
}

@Composable
fun StatusPill(
    label: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (tone) {
        BadgeTone.NEUTRAL -> Slate100 to Slate700
        BadgeTone.INFO -> Blue50 to Blue700
        BadgeTone.SUCCESS -> Emerald50 to Emerald700
        BadgeTone.WARNING -> Amber50 to Amber700
        BadgeTone.DANGER -> Rose50 to Rose700
        BadgeTone.VIOLET -> Violet50 to Violet700
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CadenceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = Slate200,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun KpiTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    tone: BadgeTone,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (tone) {
        BadgeTone.NEUTRAL -> Slate100 to Slate700
        BadgeTone.INFO -> Blue50 to Blue700
        BadgeTone.SUCCESS -> Emerald50 to Emerald700
        BadgeTone.WARNING -> Amber50 to Amber700
        BadgeTone.DANGER -> Rose50 to Rose700
        BadgeTone.VIOLET -> Violet50 to Violet700
    }
    CadenceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate600
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Slate900
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(bg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = fg,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    CadenceCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(color = Slate100, shape = RoundedCornerShape(12.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Slate600,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Slate900
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600
            )
        }
    }
}
