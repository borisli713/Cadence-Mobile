package com.cadence.shifts.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.cadence.shifts.R
import com.cadence.shifts.ui.theme.Amber50
import com.cadence.shifts.ui.theme.Amber700
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
import com.cadence.shifts.ui.theme.Slate500
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.util.GoogleAuthHelper
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch

private enum class AuthMode {
    SIGN_IN,
    SIGN_UP
}

@Composable
fun AuthScreen(
    unverifiedUser: FirebaseUser? = null,
    onAuthSuccess: () -> Unit,
    onVerificationConfirmed: () -> Unit = onAuthSuccess
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var isEmailBusy by remember { mutableStateOf(false) }
    var isGoogleBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    val isBusy = isEmailBusy || isGoogleBusy

    fun submitEmailAuth() {
        focusManager.clearFocus()
        errorMessage = null
        infoMessage = null

        if (authMode == AuthMode.SIGN_UP) {
            if (password != confirmPassword) {
                errorMessage = "Passwords do not match."
                return
            }
            isEmailBusy = true
            scope.launch {
                val res = GoogleAuthHelper.signUpWithEmail(fullName, email, password)
                isEmailBusy = false
                res.onSuccess { (user, sentVerification) ->
                    if (user.isEmailVerified) {
                        onAuthSuccess()
                    } else {
                        infoMessage = if (sentVerification) {
                            "Verification link sent to ${user.email}. Please verify your email, then tap I've Verified My Email."
                        } else {
                            "Account created. Please send and confirm your verification email before continuing."
                        }
                    }
                }.onFailure { err ->
                    errorMessage = err.message ?: "Unable to create account."
                }
            }
        } else {
            isEmailBusy = true
            scope.launch {
                val res = GoogleAuthHelper.signInWithEmail(email, password)
                isEmailBusy = false
                res.onSuccess { user ->
                    if (user.isEmailVerified) {
                        onAuthSuccess()
                    } else {
                        infoMessage = "Please verify ${user.email} before opening the workspace."
                    }
                }.onFailure { err ->
                    errorMessage = err.message ?: "Unable to sign in."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Slate50, Blue50.copy(alpha = 0.55f), Slate50)
                )
            )
    ) {
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
                    .widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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

                if (unverifiedUser != null && !unverifiedUser.isEmailVerified) {
                    EmailVerificationCard(
                        email = unverifiedUser.email.orEmpty(),
                        isBusy = isBusy,
                        errorMessage = errorMessage,
                        infoMessage = infoMessage,
                        onCheckVerified = {
                            errorMessage = null
                            infoMessage = null
                            isEmailBusy = true
                            scope.launch {
                                val res = GoogleAuthHelper.reloadAndCheckVerified()
                                isEmailBusy = false
                                res.onSuccess { verified ->
                                    if (verified) {
                                        onVerificationConfirmed()
                                    } else {
                                        errorMessage = "Email is not verified yet. Open the link in your inbox and try again."
                                    }
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Could not verify email status."
                                }
                            }
                        },
                        onResend = {
                            errorMessage = null
                            infoMessage = null
                            isEmailBusy = true
                            scope.launch {
                                val res = GoogleAuthHelper.resendVerificationEmail()
                                isEmailBusy = false
                                res.onSuccess {
                                    infoMessage = "A fresh verification email has been sent to ${unverifiedUser.email}."
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Failed to resend verification email."
                                }
                            }
                        },
                        onSignOut = {
                            errorMessage = null
                            infoMessage = null
                            GoogleAuthHelper.signOut(credentialManager, scope)
                        }
                    )
                } else {
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
                                    infoMessage = null
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
                                    infoMessage = null
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
                            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

                            AnimatedVisibility(visible = authMode == AuthMode.SIGN_UP) {
                                OutlinedTextField(
                                    value = fullName,
                                    onValueChange = { fullName = it },
                                    label = { Text("Full Name") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Person, contentDescription = null)
                                    },
                                    singleLine = true,
                                    enabled = !isBusy,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Words,
                                        imeAction = ImeAction.Next
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_name_input")
                                )
                            }

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Email, contentDescription = null)
                                },
                                singleLine = true,
                                enabled = !isBusy,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_email_input")
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Lock, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = if (showPassword) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                visualTransformation = if (showPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                supportingText = if (authMode == AuthMode.SIGN_UP) {
                                    { Text("Minimum 6 characters") }
                                } else null,
                                singleLine = true,
                                enabled = !isBusy,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = if (authMode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (authMode == AuthMode.SIGN_IN) submitEmailAuth()
                                    }
                                ),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_password_input")
                            )

                            AnimatedVisibility(visible = authMode == AuthMode.SIGN_UP) {
                                OutlinedTextField(
                                    value = confirmPassword,
                                    onValueChange = { confirmPassword = it },
                                    label = { Text("Confirm Password") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Lock, contentDescription = null)
                                    },
                                    visualTransformation = if (showPassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    singleLine = true,
                                    enabled = !isBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { submitEmailAuth() }
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_confirm_password_input")
                                )
                            }

                            if (authMode == AuthMode.SIGN_IN) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            errorMessage = null
                                            infoMessage = null
                                            isEmailBusy = true
                                            scope.launch {
                                                val res = GoogleAuthHelper.sendPasswordReset(email)
                                                isEmailBusy = false
                                                res.onSuccess {
                                                    infoMessage = "Password reset link sent to ${email.trim()}."
                                                }.onFailure { err ->
                                                    errorMessage = err.message ?: "Unable to send reset link."
                                                }
                                            }
                                        },
                                        enabled = !isBusy,
                                        modifier = Modifier.testTag("forgot_password_button")
                                    ) {
                                        Text("Forgot password?", color = Blue700)
                                    }
                                }
                            }

                            if (!errorMessage.isNullOrBlank()) {
                                AuthBanner(text = errorMessage.orEmpty(), isError = true)
                            }
                            if (!infoMessage.isNullOrBlank()) {
                                AuthBanner(text = infoMessage.orEmpty(), isError = false)
                            }

                            Button(
                                onClick = { submitEmailAuth() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("email_auth_submit_button"),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (authMode == AuthMode.SIGN_IN) Slate900 else Blue600,
                                    contentColor = PureWhite
                                )
                            ) {
                                if (isEmailBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = PureWhite,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (authMode == AuthMode.SIGN_IN) "Signing in…" else "Creating account…",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                } else {
                                    Text(
                                        text = if (authMode == AuthMode.SIGN_IN) {
                                            stringResource(R.string.sign_in_with_email)
                                        } else {
                                            stringResource(R.string.sign_up_with_email)
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f), color = Slate200)
                                Text(
                                    text = "or continue with",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate500
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f), color = Slate200)
                            }

                            OutlinedButton(
                                onClick = {
                                    errorMessage = null
                                    infoMessage = null
                                    isGoogleBusy = true
                                    GoogleAuthHelper.onGoogleSignInClicked(
                                        context = context,
                                        credentialManager = credentialManager,
                                        onAuthSuccess = {
                                            isGoogleBusy = false
                                            onAuthSuccess()
                                        },
                                        onAuthError = { msg ->
                                            isGoogleBusy = false
                                            errorMessage = msg
                                        },
                                        scope = scope,
                                        onAuthCancelled = { hint ->
                                            isGoogleBusy = false
                                            if (!hint.isNullOrBlank()) {
                                                errorMessage = hint
                                            }
                                        }
                                    )
                                },
                                enabled = !isBusy,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, Slate200),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = PureWhite,
                                    contentColor = Slate900
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("google_sign_in_button")
                            ) {
                                if (isGoogleBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Slate900,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Connecting Google…", style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.VerifiedUser,
                                        contentDescription = null,
                                        tint = Blue700,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.sign_in_with_google),
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
}

@Composable
private fun EmailVerificationCard(
    email: String,
    isBusy: Boolean,
    errorMessage: String?,
    infoMessage: String?,
    onCheckVerified: () -> Unit,
    onResend: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = Amber50,
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MarkEmailUnread,
                        contentDescription = null,
                        tint = Amber700,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Email Verification Required",
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber700
                    )
                }
            }

            Text(
                text = stringResource(R.string.verify_email_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900
            )

            Text(
                text = "We sent a verification link to $email. Confirm your email address to unlock your Cadence workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )

            if (!errorMessage.isNullOrBlank()) {
                AuthBanner(text = errorMessage, isError = true)
            }
            if (!infoMessage.isNullOrBlank()) {
                AuthBanner(text = infoMessage, isError = false)
            }

            Button(
                onClick = onCheckVerified,
                enabled = !isBusy,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = PureWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("verify_check_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PureWhite,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.verify_email_check_button))
            }

            OutlinedButton(
                onClick = onResend,
                enabled = !isBusy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("resend_verification_button")
            ) {
                Text(stringResource(R.string.verify_email_resend_button))
            }

            TextButton(
                onClick = onSignOut,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("verify_sign_out_button")
            ) {
                Text("Use another account", color = Slate600)
            }
        }
    }
}

@Composable
private fun AuthBanner(
    text: String,
    isError: Boolean
) {
    Surface(
        color = if (isError) Rose50 else Emerald50,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, (if (isError) Rose700 else Emerald700).copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) Rose700 else Emerald700,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
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
