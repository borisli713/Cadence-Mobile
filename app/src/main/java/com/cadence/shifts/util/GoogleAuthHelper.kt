package com.cadence.shifts.util

import android.app.Activity
import android.content.Context
import android.util.Log
import android.util.Patterns
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.cadence.shifts.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object GoogleAuthHelper {
    private const val TAG = "Auth"

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
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activityContext = (context as? Activity) ?: context
                val result = credentialManager.getCredential(activityContext, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type received.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Google Sign-In failed")
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
            Result.success(user)
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
                    onSignOutComplete()
                }
            }
        }
    }

    private fun formatFirebaseAuthError(e: Exception, fallback: String): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "That email address format is invalid."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> "Incorrect email or password. Please try again."
            "ERROR_USER_NOT_FOUND" -> "No account exists for that email address."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Try signing in instead."
            "ERROR_WEAK_PASSWORD" -> "Please choose a stronger password (at least 6 characters)."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
            "ERROR_OPERATION_NOT_ALLOWED" -> "Email/Password sign-in is not enabled in Firebase Authentication."
            else -> e.localizedMessage ?: fallback
        }
    }
}
