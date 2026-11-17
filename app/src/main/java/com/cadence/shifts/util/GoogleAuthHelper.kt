package com.cadence.shifts.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.util.Log
import android.util.Patterns
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
        val clientId = try {
            context.getString(R.string.default_web_client_id).trim()
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found.")
            return
        }

        if (clientId.isBlank()) {
            onAuthError("Google Sign-In is not configured (empty Web Client ID).")
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("Unable to open Google account selector without an active screen.")
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
                val userHint = if (detail.contains("cancel", ignoreCase = true) || detail.isBlank()) {
                    "Google Sign-In did not complete. If it closed right after picking an account, verify this build's SHA-1 fingerprint is added in Firebase Console, or sign in with Email & Password."
                } else {
                    "Google Sign-In cancelled ($detail)."
                }
                withContext(Dispatchers.Main) {
                    onAuthCancelled(userHint)
                }
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No Google credentials found on device", e)
                withContext(Dispatchers.Main) {
                    onAuthError("No Google account is signed in on this device, or Sign in with Google prompts are disabled in device settings.")
                }
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Credential provider configuration error", e)
                withContext(Dispatchers.Main) {
                    onAuthError("Google Play Services credential provider is unavailable. Update Google Play Services or sign in with Email & Password.")
                }
            } catch (e: GetCredentialUnsupportedException) {
                Log.e(TAG, "Credential manager unsupported", e)
                withContext(Dispatchers.Main) {
                    onAuthError("Google Sign-In is not supported on this device. Please use Email & Password.")
                }
            } catch (e: GetCredentialInterruptedException) {
                Log.w(TAG, "Google Sign-In interrupted", e)
                withContext(Dispatchers.Main) {
                    onAuthError("Google Sign-In was interrupted. Please try again.")
                }
            } catch (e: GetCredentialCustomException) {
                Log.e(TAG, "Custom Google Sign-In error: type=${e.type} msg=${e.errorMessage}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(
                        "Google Sign-In failed (${e.errorMessage ?: e.type}). " +
                            "If this is a release build, confirm its SHA-1 certificate is registered in Firebase."
                    )
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "GetCredentialException: type=${e.type} msg=${e.errorMessage}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(
                        "Google Sign-In failed (${e.errorMessage ?: e.type}). " +
                            "Confirm Google Sign-In is enabled in Firebase Authentication and this APK's SHA-1 is registered."
                    )
                }
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(TAG, "Invalid Google ID token", e)
                withContext(Dispatchers.Main) {
                    onAuthError("Received an invalid Google ID token. Please try again.")
                }
            } catch (e: FirebaseAuthException) {
                Log.e(TAG, "Firebase Google credential sign-in failed: code=${e.errorCode}", e)
                withContext(Dispatchers.Main) {
                    onAuthError(formatFirebaseAuthError(e, "Firebase rejected Google Sign-In (${e.errorCode})."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed unexpectedly", e)
                withContext(Dispatchers.Main) {
                    onAuthError(e.localizedMessage ?: "Google Sign-In failed.")
                }
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        val trimmedEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Please enter your password."))
        }
        return try {
            val result = Firebase.auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = result.user ?: return Result.failure(IllegalStateException("Sign-in succeeded without a user session."))
            if (!user.isEmailVerified) {
                runCatching { user.reload().await() }
            }
            Result.success(Firebase.auth.currentUser ?: user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Email sign-in failed", e)
            Result.failure(IllegalStateException(formatFirebaseAuthError(e, "Unable to sign in.")))
        }
    }

    suspend fun signUpWithEmail(
        displayName: String,
        email: String,
        password: String
    ): Result<Pair<FirebaseUser, Boolean>> {
        val cleanName = displayName.trim()
        val trimmedEmail = email.trim()
        if (cleanName.length < 2) {
            return Result.failure(IllegalArgumentException("Please enter your full name."))
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }
        return try {
            val result = Firebase.auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val user = result.user ?: return Result.failure(IllegalStateException("Account creation failed."))
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
            Result.failure(IllegalStateException(formatFirebaseAuthError(e, "Unable to create account.")))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val trimmedEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            return Result.failure(IllegalArgumentException("Enter your account email address first to reset your password."))
        }
        return try {
            Firebase.auth.sendPasswordResetEmail(trimmedEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Password reset failed", e)
            Result.failure(IllegalStateException(formatFirebaseAuthError(e, "Unable to send password reset email.")))
        }
    }

    suspend fun resendVerificationEmail(): Result<Unit> {
        val user = Firebase.auth.currentUser
            ?: return Result.failure(IllegalStateException("No signed-in user found."))
        return try {
            user.sendEmailVerification().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(formatFirebaseAuthError(e, "Failed to resend verification email.")))
        }
    }

    suspend fun reloadAndCheckVerified(): Result<Boolean> {
        val user = Firebase.auth.currentUser
            ?: return Result.failure(IllegalStateException("Session expired. Please sign in again."))
        return try {
            user.reload().await()
            user.getIdToken(true).await()
            Result.success(Firebase.auth.currentUser?.isEmailVerified == true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(formatFirebaseAuthError(e, "Unable to refresh verification status.")))
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

    private fun formatFirebaseAuthError(e: Exception, fallback: String): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "That email address format is invalid."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> "Incorrect email/password or invalid Google credential. Please try again."
            "ERROR_USER_NOT_FOUND" -> "No account exists for that email address."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Try signing in instead."
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                "An account with this email already exists using Email & Password. Sign in with your password first."
            "ERROR_WEAK_PASSWORD" -> "Please choose a stronger password (at least 6 characters)."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
            "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in provider is not enabled in Firebase Authentication."
            "ERROR_USER_DISABLED" -> "This account has been disabled by an administrator."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network error while contacting Firebase Authentication. Check your connection."
            else -> e.localizedMessage ?: fallback
        }
    }
}
