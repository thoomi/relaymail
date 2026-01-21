package net.thunderbird.feature.applock.impl.domain

import androidx.biometric.BiometricManager

/**
 * Default implementation using Android's BiometricManager.
 */
internal class DefaultBiometricAvailabilityChecker(
    private val biometricManager: BiometricManager,
) : AppLockAvailability {

    override fun isAuthenticationAvailable(): Boolean {
        return BiometricAuthenticator.isAvailable(biometricManager)
    }
}
