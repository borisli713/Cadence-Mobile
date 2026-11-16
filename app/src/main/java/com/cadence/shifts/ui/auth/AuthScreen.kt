package com.cadence.shifts.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.cadence.shifts.R
import com.cadence.shifts.ui.theme.Blue100
import com.cadence.shifts.ui.theme.Blue50
import com.cadence.shifts.ui.theme.Blue600
import com.cadence.shifts.ui.theme.Blue700
import com.cadence.shifts.ui.theme.Emerald50
import com.cadence.shifts.ui.theme.Emerald700
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose50
import com.cadence.shifts.ui.theme.Rose700
import com.cadence.shifts.ui.theme.Slate100
import com.cadence.shifts.ui.theme.Slate200
import com.cadence.shifts.ui.theme.Slate50
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.util.GoogleAuthHelper

private enum class AuthMode {
    SIGN_IN,
    SIGN_UP
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Slate50, Blue50.copy(alpha = 0.55f), Slate50)
                )
            )
    ) {
        // Subtle atmospheric accents matching Cadence's slate & blue precision theme
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Blue100.copy(alpha = 0.45f),
                radius = size.minDimension * 0.38f,
                center = Offset(size.width * 0.88f, size.height * 0.14f)
            )
            drawCircle(
                color = Blue50.copy(alpha = 0.7f),
                radius = size.minDimension * 0.32f,
                center = Offset(size.width * 0.12f, size.height * 0.85f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Brand Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Slate900),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = stringResource(R.string.app_name),
                            tint = Blue50,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Slate900
                        )
                        Text(
                            text = stringResource(R.string.tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                }

                // Segmented Sign In / Create Account Switcher
                Surface(
                    color = Slate100,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AuthModeTab(
                            label = stringResource(R.string.auth_mode_sign_in),
                            selected = authMode == AuthMode.SIGN_IN,
                            onClick = {
                                authMode = AuthMode.SIGN_IN
                                errorMessage = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("auth_mode_sign_in")
                        )
                        AuthModeTab(
                            label = stringResource(R.string.auth_mode_sign_up),
                            selected = authMode == AuthMode.SIGN_UP,
                            onClick = {
                                authMode = AuthMode.SIGN_UP
                                errorMessage = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("auth_mode_sign_up")
                        )
                    }
                }

                // Main Auth Card
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Slate200),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = if (authMode == AuthMode.SIGN_IN) Emerald50 else Blue50,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (authMode == AuthMode.SIGN_IN) {
                                        Icons.Rounded.VerifiedUser
                                    } else {
                                        Icons.Rounded.PersonAdd
                                    },
                                    contentDescription = null,
                                    tint = if (authMode == AuthMode.SIGN_IN) Emerald700 else Blue700,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (authMode == AuthMode.SIGN_IN) {
                                        "Verified Workspace Access"
                                    } else {
                                        "New Staff Onboarding"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (authMode == AuthMode.SIGN_IN) Emerald700 else Blue700
                                )
                            }
                        }

                        AnimatedContent(
                            targetState = authMode,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith
                                    fadeOut(animationSpec = tween(160))
                            },
                            label = "auth_mode_copy"
                        ) { mode ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (mode == AuthMode.SIGN_IN) {
                                        stringResource(R.string.sign_in_title)
                                    } else {
                                        stringResource(R.string.sign_up_title)
                                    },
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Slate900
                                )
                                Text(
                                    text = if (mode == AuthMode.SIGN_IN) {
                                        stringResource(R.string.sign_in_subtitle)
                                    } else {
                                        stringResource(R.string.sign_up_subtitle)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate600
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeatureRow(
                                icon = Icons.Rounded.Balance,
                                title = "Fairness Overlap Resolution",
                                subtitle = "Automatically prioritizes staff with lower logged hours vs weekly target."
                            )
                            FeatureRow(
                                icon = Icons.Rounded.CheckCircle,
                                title = "One-Click Manager Accept All",
                                subtitle = "Approve pending shift changes, swaps, and time off requests in batch."
                            )
                            FeatureRow(
                                icon = Icons.Rounded.NotificationsActive,
                                title = "Instant Status Alerts & Reports",
                                subtitle = "Real-time notifications plus verified hours tracking and .ics calendar sync."
                            )
                        }

                        if (!errorMessage.isNullOrBlank()) {
                            Surface(
                                color = Rose50,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, Rose700.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Rose700,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                GoogleAuthHelper.onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onAuthSuccess = {
                                        isLoading = false
                                        onAuthSuccess()
                                    },
                                    onAuthError = { msg ->
                                        isLoading = false
                                        errorMessage = msg
                                    },
                                    scope = scope,
                                    onAuthCancelled = {
                                        isLoading = false
                                    }
                                )
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("google_sign_in_button"),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (authMode == AuthMode.SIGN_IN) Slate900 else Blue600,
                                contentColor = PureWhite
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = PureWhite,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Signing in…", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Text(
                                    text = if (authMode == AuthMode.SIGN_IN) {
                                        stringResource(R.string.sign_in_with_google)
                                    } else {
                                        stringResource(R.string.sign_up_with_google)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.auth_footer_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) PureWhite else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        shadowElevation = if (selected) 1.dp else 0.dp,
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Tab, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Slate900 else Slate600,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = Blue50,
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Blue700,
                modifier = Modifier
                    .padding(8.dp)
                    .size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
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
