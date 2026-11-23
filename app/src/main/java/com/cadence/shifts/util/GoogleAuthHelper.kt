package com.cadence.shifts.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.util.Log
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.cadence.shifts.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object GoogleAuthHelper {
    private const val TAG = "Auth"

    private fun str(
        context: Context?,
        @StringRes resId: Int,
        fallback: String,
        vararg args: Any
    ): String {
        return if (context != null) {
            val loc = LocaleHelper.localizedContext(context)
            if (args.isEmpty()) loc.getString(resId) else loc.getString(resId, *args)
        } else {
            fallback
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private suspend fun signInFirebaseWithGoogleCredential(credential: Credential): FirebaseUser {
        val idToken = when {
            credential is GoogleIdTokenCredential -> credential.idToken
            credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            }
            else -> throw IllegalStateException("Unexpected credential type: ${credential.type}")
        }

        val authCredential = GoogleAuthProvider.getCredential(idToken, null)
        val authResult = Firebase.auth.signInWithCredential(authCredential).await()
        val user = authResult.user ?: Firebase.auth.currentUser
            ?: throw IllegalStateException("Google Sign-In completed without a Firebase session.")

        // Ensure fresh token claims (e.g. email_verified) before Firestore listeners attach
        runCatching { user.reload().await() }
        runCatching { user.getIdToken(true).await() }
        return Firebase.auth.currentUser ?: user
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val activity = context.findActivity()
        if (activity == null) {
            onUnauthenticated()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(MutableContextWrapper(activity), request)
                signInFirebaseWithGoogleCredential(result.credential)
                withContext(Dispatchers.Main) {
                    onAuthSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Auto sign-in skipped: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    onUnauthenticated()
                }
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: (String?) -> Unit = {}
    ) {
        val loc = LocaleHelper.localizedContext(context)
        val clientId = try {
            context.getString(R.string.default_web_client_id).trim()
        } catch (_: Exception) {
            onAuthError(loc.getString(R.string.auth_err_config_missing))
            return
        }

        if (clientId.isBlank()) {
            onAuthError(loc.getString(R.string.auth_err_empty_client_id))
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            onAuthError(loc.getString(R.string.auth_err_no_activity))
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val primaryRequest = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            val credentialContext = MutableContextWrapper(activity)
            try {
                val result = try {
                    credentialManager.getCredential(credentialContext, primaryRequest)
                } catch (noCred: NoCredentialException) {
                    Log.w(TAG, "GetSignInWithGoogleOption returned NoCredentialException; retrying with GetGoogleIdOption", noCred)
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    credentialManager.getCredential(credentialContext, fallbackRequest)
                }

                signInFirebaseWithGoogleCredential(result.credential)
                withContext(Dispatchers.Main) {
                    onAuthSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                val detail = e.errorMessage?.toString().orEmpty()
                Log.w(TAG, "Google Sign-In cancelled or rejected by Play Services: $detail", e)
                val isUserDismissal = detail.contains("activity is cancelled by the user", ignoreCase = true) ||
                    detail.contains("canceled by the user", ignoreCase = true) ||
                    detail.contains("cancelled by the user", ignoreCase = true)
                withContext(Dispatchers.Main) {
                    if (isUserDismissal) {
                        onAuthCancelled(null)
                    } else {
                        val baseMsg = loc.getString(
                            R.string.auth_err_google_cancelled,
                            detail.ifBlank { loc.getString(R.string.cancel) }
                        )
                        val extraHint = if (detail.contains("16") || detail.contains("reauth", ignoreCase = true)) {
                            " " + loc.getString(R.string.auth_err_sha1_hint)
                        } else {
                            ""
                        }
                        onAuthError(baseMsg + extraHint)
                    }
                }
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No Google credentials found on device", e)
                withContext(Dispatchers.Main) {
                    onAuthError(loc.getString(R.string.auth_err_no_google_credentials))
                }
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Credential provider configuration error", e)
                withContext(Dispatchers.Main) {
                    onAuthError(loc.getString(R.string.auth_err_provider_unavailable))
                }
            } catch (e: GetCredentialUnsupportedException) {
                Log.e(TAG, "Credential manager unsupported", e)
                withContext(Dispatchers.Main) {
                    onAuthError(loc.getString(R.string.auth_err_unsupported))
                }
            } catch (e: GetCredentialInterruptedException) {
                Log.w(TAG, "Google Sign-In interrupted", e)
                withContext(Dispatchers.Main) {
                    onAuthError(loc.getString(R.string.auth_err_interrupted))
                }
            } catch (e: GetCredentialCustomException) {
                Log.e(TAG, "Custom Google Sign-In error: type=${e.type} msg=${e.errorMessage}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(
                        loc.getString(
                            R.string.auth_err_google_failed_sha1,
                            e.errorMessage?.toString() ?: e.type
                        )
                    )
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "GetCredentialException: type=${e.type} msg=${e.errorMessage}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(
                        loc.getString(
                            R.string.auth_err_google_failed_sha1,
                            e.errorMessage?.toString() ?: e.type
                        )
                    )
                }
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(TAG, "Invalid Google ID token", e)
                withContext(Dispatchers.Main) {
                    onAuthError(loc.getString(R.string.auth_err_invalid_token))
                }
            } catch (e: FirebaseAuthException) {
                Log.e(TAG, "Firebase Google credential sign-in failed: code=${e.errorCode}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(
                        formatFirebaseAuthError(
                            loc,
                            e,
                            loc.getString(R.string.auth_err_firebase_rejected, e.errorCode)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed unexpectedly", e)
                withContext(Dispatchers.Main) {
                    onAuthError(e.localizedMessage ?: loc.getString(R.string.auth_err_google_generic))
                }
            }
        }
    }

    suspend fun signInWithEmail(
        email: String,
        password: String,
        context: Context? = null
    ): Result<FirebaseUser> {
        val trimmedEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(
                IllegalArgumentException(str(context, R.string.auth_err_invalid_email, "Please enter a valid email address."))
            )
        }
        if (password.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(str(context, R.string.auth_err_enter_password, "Please enter your password."))
            )
        }
        return try {
            val result = Firebase.auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = result.user ?: return Result.failure(
                IllegalStateException(str(context, R.string.auth_err_no_user_session, "Sign-in succeeded without a user session."))
            )
            if (!user.isEmailVerified) {
                runCatching { user.reload().await() }
            }
            Result.success(Firebase.auth.currentUser ?: user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Email sign-in failed", e)
            Result.failure(
                IllegalStateException(
                    formatFirebaseAuthError(
                        context,
                        e,
                        str(context, R.string.err_unable_sign_in, "Unable to sign in.")
                    )
                )
            )
        }
    }

    suspend fun signUpWithEmail(
        displayName: String,
        email: String,
        password: String,
        context: Context? = null
    ): Result<Pair<FirebaseUser, Boolean>> {
        val cleanName = displayName.trim()
        val trimmedEmail = email.trim()
        if (cleanName.length < 2) {
            return Result.failure(
                IllegalArgumentException(str(context, R.string.auth_err_enter_full_name, "Please enter your full name."))
            )
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(
                IllegalArgumentException(str(context, R.string.auth_err_invalid_email, "Please enter a valid email address."))
            )
        }
        if (password.length < 6) {
            return Result.failure(
                IllegalArgumentException(str(context, R.string.auth_err_password_min_6, "Password must be at least 6 characters."))
            )
        }
        return try {
            val result = Firebase.auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val user = result.user ?: return Result.failure(
                IllegalStateException(str(context, R.string.err_unable_create_account, "Unable to create account."))
            )
            runCatching {
                val updates = UserProfileChangeRequest.Builder()
                    .setDisplayName(cleanName)
                    .build()
                user.updateProfile(updates).await()
            }
            val verificationSent = runCatching {
                user.sendEmailVerification().await()
            }.isSuccess
            Result.success(user to verificationSent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Email sign-up failed", e)
            Result.failure(
                IllegalStateException(
                    formatFirebaseAuthError(
                        context,
                        e,
                        str(context, R.string.err_unable_create_account, "Unable to create account.")
                    )
                )
            )
        }
    }

    suspend fun sendPasswordReset(email: String, context: Context? = null): Result<Unit> {
        val trimmedEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(
                IllegalArgumentException(
                    str(context, R.string.auth_err_enter_reset_email, "Enter your account email address first to reset your password.")
                )
            )
        }
        return try {
            Firebase.auth.sendPasswordResetEmail(trimmedEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Password reset failed", e)
            Result.failure(
                IllegalStateException(
                    formatFirebaseAuthError(
                        context,
                        e,
                        str(context, R.string.err_unable_send_reset, "Unable to send reset link.")
                    )
                )
            )
        }
    }

    suspend fun resendVerificationEmail(context: Context? = null): Result<Unit> {
        val user = Firebase.auth.currentUser
            ?: return Result.failure(
                IllegalStateException(str(context, R.string.auth_err_no_signed_in_user, "No signed-in user found."))
            )
        return try {
            user.sendEmailVerification().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    formatFirebaseAuthError(
                        context,
                        e,
                        str(context, R.string.err_failed_resend_verification, "Failed to resend verification email.")
                    )
                )
            )
        }
    }

    suspend fun reloadAndCheckVerified(context: Context? = null): Result<Boolean> {
        val user = Firebase.auth.currentUser
            ?: return Result.failure(
                IllegalStateException(str(context, R.string.auth_err_session_expired, "Session expired. Please sign in again."))
            )
        return try {
            user.reload().await()
            user.getIdToken(true).await()
            Result.success(Firebase.auth.currentUser?.isEmailVerified == true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    formatFirebaseAuthError(
                        context,
                        e,
                        str(context, R.string.auth_err_refresh_verification, "Unable to refresh verification status.")
                    )
                )
            )
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    withContext(Dispatchers.Main) {
                        onSignOutComplete()
                    }
                }
            }
        }
    }

    private fun formatFirebaseAuthError(context: Context?, e: Exception, fallback: String): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_EMAIL" -> str(context, R.string.auth_fb_invalid_email, "That email address format is invalid.")
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
                str(context, R.string.auth_fb_invalid_credential, "Incorrect email/password or invalid Google credential. Please try again.")
            "ERROR_USER_NOT_FOUND" -> str(context, R.string.auth_fb_user_not_found, "No account exists for that email address.")
            "ERROR_EMAIL_ALREADY_IN_USE" ->
                str(context, R.string.auth_fb_email_in_use, "An account with this email already exists. Try signing in instead.")
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                str(context, R.string.auth_fb_different_credential, "An account with this email already exists using Email & Password. Sign in with your password first.")
            "ERROR_WEAK_PASSWORD" ->
                str(context, R.string.auth_fb_weak_password, "Please choose a stronger password (at least 6 characters).")
            "ERROR_TOO_MANY_REQUESTS" ->
                str(context, R.string.auth_fb_too_many_requests, "Too many attempts. Please wait a moment and try again.")
            "ERROR_OPERATION_NOT_ALLOWED" ->
                str(context, R.string.auth_fb_operation_not_allowed, "This sign-in provider is not enabled in Firebase Authentication.")
            "ERROR_USER_DISABLED" ->
                str(context, R.string.auth_fb_user_disabled, "This account has been disabled by an administrator.")
            "ERROR_NETWORK_REQUEST_FAILED" ->
                str(context, R.string.auth_fb_network, "Network error while contacting Firebase Authentication. Check your connection.")
            else -> e.localizedMessage ?: fallback
        }
    }
}
