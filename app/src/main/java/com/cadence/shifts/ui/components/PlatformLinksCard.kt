package com.cadence.shifts.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.cadence.shifts.ui.theme.Slate500
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.util.AppUpdateChecker

object CadenceExternalLinks {
    const val WEB_APP_URL = "https://cadence.ngnltet.org/"
    const val WINDOWS_APP_URL = "https://borisli713.github.io/Cadence-PC/"
    const val GITHUB_PROJECT_URL = "https://github.com/borisli713/Cadence-Mobile"
}

@Composable
fun PlatformLinksCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (compact) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = Slate50,
            border = BorderStroke(1.dp, Slate200)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlatformLinksBody(compact = true)
            }
        }
    } else {
        CadenceCard(modifier = modifier) {
            PlatformLinksBody(compact = false)
        }
    }
}

@Composable
private fun PlatformLinksBody(compact: Boolean) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.platforms_section_title),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = Slate900
        )
        Text(
            text = stringResource(R.string.platforms_section_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = Slate600
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlatformLinkButton(
            title = stringResource(R.string.platform_web_title),
            subtitle = stringResource(R.string.platform_web_subtitle),
            contentDescriptionText = stringResource(R.string.platform_web_cd),
            iconContainerColor = Blue50,
            compact = compact,
            testTagName = "platform_web_button",
            onClick = { AppUpdateChecker.openUrl(context, CadenceExternalLinks.WEB_APP_URL) }
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = Blue700,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
        }

        PlatformLinkButton(
            title = stringResource(R.string.platform_windows_title),
            subtitle = stringResource(R.string.platform_windows_subtitle),
            contentDescriptionText = stringResource(R.string.platform_windows_cd),
            iconContainerColor = Blue50,
            compact = compact,
            testTagName = "platform_windows_button",
            onClick = { AppUpdateChecker.openUrl(context, CadenceExternalLinks.WINDOWS_APP_URL) }
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_windows_logo),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
        }

        PlatformLinkButton(
            title = stringResource(R.string.platform_github_title),
            subtitle = stringResource(R.string.platform_github_subtitle),
            contentDescriptionText = stringResource(R.string.platform_github_cd),
            iconContainerColor = Slate100,
            compact = compact,
            testTagName = "platform_github_button",
            onClick = { AppUpdateChecker.openUrl(context, CadenceExternalLinks.GITHUB_PROJECT_URL) }
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_github_logo),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
        }
    }
}

@Composable
private fun PlatformLinkButton(
    title: String,
    subtitle: String,
    contentDescriptionText: String,
    iconContainerColor: Color,
    compact: Boolean,
    testTagName: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 48.dp else 54.dp)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = contentDescriptionText }
            .testTag(testTagName)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 32.dp else 36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = Slate500,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
