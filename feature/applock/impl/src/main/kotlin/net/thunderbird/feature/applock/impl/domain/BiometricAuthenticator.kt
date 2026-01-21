package net.thunderbird.feature.applock.impl.domain

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockAuthenticator
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockResult

/**
 * An [AppLockAuthenticator] implementation that uses Android's BiometricPrompt API.
 *
 * Supports biometric authentication (fingerprint, face, iris) with automatic
 * fallback to device credentials (PIN, pattern, password).
 *
 * Policy note: device credentials are allowed even when no biometrics are enrolled;
 * the availability check uses the same allowed authenticators mask.
 *
 * Note: This class must be used within a [FragmentActivity] due to BiometricPrompt requirements.
 *
 * @param activity The FragmentActivity context for the BiometricPrompt.
 * @param title The title displayed on the biometric prompt.
 * @param subtitle The subtitle displayed on the biometric prompt.
 */
class BiometricAuthenticator(
    private val activity: FragmentActivity,
    private val title: String,
    private val subtitle: String,
) : AppLockAuthenticator {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun authenticate(): AppLockResult = suspendCancellableCoroutine { continuation ->
        val authenticationCallback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) {
                    continuation.resume(Outcome.Success(Unit))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) {
                    val error = mapErrorCode(errorCode, errString.toString())
                    continuation.resume(Outcome.Failure(error))
                }
            }

            override fun onAuthenticationFailed() {
                // Called on failed attempt but prompt stays open for retry
                // No action needed as user can retry or cancel
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        val executor = ContextCompat.getMainExecutor(activity)

        val prompt = BiometricPrompt(activity, executor, authenticationCallback)

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(
                    Outcome.Failure(AppLockError.UnableToStart(e.message ?: "Unknown error")),
                )
            }
        }

        continuation.invokeOnCancellation {
            prompt.cancelAuthentication()
        }
    }

    private fun mapErrorCode(errorCode: Int, errString: String): AppLockError {
        return when (errorCode) {
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            -> AppLockError.NotAvailable

            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            -> AppLockError.NotEnrolled

            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            -> AppLockError.Canceled

            BiometricPrompt.ERROR_CANCELED,
            -> AppLockError.Interrupted

            BiometricPrompt.ERROR_LOCKOUT -> AppLockError.Lockout(durationSeconds = 0)
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AppLockError.Lockout(durationSeconds = Int.MAX_VALUE)

            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_VENDOR,
            -> AppLockError.Failed

            else -> AppLockError.UnableToStart(errString)
        }
    }

    companion object {
        /**
         * Check if biometric or device credential authentication is available.
         *
         * @param biometricManager The BiometricManager to check availability.
         * @return `true` if authentication is available, `false` otherwise.
         */
        fun isAvailable(biometricManager: BiometricManager): Boolean {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

            return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
