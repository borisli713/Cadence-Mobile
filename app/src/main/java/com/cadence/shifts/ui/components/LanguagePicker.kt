package com.cadence.shifts.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cadence.shifts.R
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate50
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.util.AppLanguageOption
import com.cadence.shifts.util.LocaleHelper

@Composable
fun LanguageSelectorPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOption = remember(context.resources.configuration) {
        LocaleHelper.resolveDisplayOption(context)
    }
    val displayText = if (currentOption.nativeLabel.isNotBlank()) {
        currentOption.nativeLabel
    } else {
        stringResource(currentOption.labelRes)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .testTag("auth_language_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = stringResource(R.string.language_label),
                tint = Blue700,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium,
                color = Slate900,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun LanguageSettingsRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOption = remember(context.resources.configuration) {
        LocaleHelper.resolveDisplayOption(context)
    }
    val subtitleText = if (currentOption.nativeLabel.isNotBlank()) {
        "${currentOption.nativeLabel} • ${stringResource(currentOption.labelRes)}"
    } else {
        stringResource(currentOption.labelRes)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate200),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .minimumInteractiveComponentSize()
            .testTag("profile_language_button")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = null,
                tint = Blue700,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.language_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Slate900
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Slate600,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun LanguagePickerDialog(
    onDismiss: () -> Unit,
    onLanguageSelected: (AppLanguageOption) -> Unit
) {
    val context = LocalContext.current
    val selectedTag = remember(context.resources.configuration) {
        LocaleHelper.getSelectedLanguageTag(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = null,
                tint = Blue700
            )
        },
        title = {
            Text(
                text = stringResource(R.string.language_picker_title),
                color = Slate900
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.language_picker_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LocaleHelper.SUPPORTED_LANGUAGES.forEach { option ->
                        val isSelected = option.tag == selectedTag
                        val primaryLabel = if (option.nativeLabel.isNotBlank()) {
                            option.nativeLabel
                        } else {
                            stringResource(option.labelRes)
                        }
                        val secondaryLabel = if (option.nativeLabel.isNotBlank()) {
                            stringResource(option.labelRes)
                        } else {
                            null
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) Blue50 else Slate100.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, if (isSelected) Blue700.copy(alpha = 0.45f) else Slate200),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onLanguageSelected(option) }
                                .testTag("language_option_${option.tag.ifBlank { "system" }}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = primaryLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Blue700 else Slate900
                                    )
                                    if (secondaryLabel != null) {
                                        Text(
                                            text = secondaryLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate600
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Blue700,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
