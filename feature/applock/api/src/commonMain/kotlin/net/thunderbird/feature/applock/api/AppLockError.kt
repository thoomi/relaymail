package net.thunderbird.feature.applock.api

/**
 * Authentication error types that can occur during the authentication process.
 */
sealed interface AppLockError {
    /**
     * Device authentication is not available on this device.
     */
    data object NotAvailable : AppLockError

    /**
     * User has not enrolled any biometric credentials or device credentials.
     */
    data object NotEnrolled : AppLockError

    /**
     * Authentication attempt failed.
     */
    data object Failed : AppLockError

    /**
     * User explicitly canceled the authentication dialog.
     */
    data object Canceled : AppLockError

    /**
     * Authentication was interrupted by the system (e.g., app went to background,
     * configuration change, or fragment lifecycle). Should retry silently.
     */
    data object Interrupted : AppLockError

    /**
     * Too many failed attempts, user is temporarily locked out.
     *
     * @property durationSeconds The duration of the lockout in seconds, or 0 if unknown.
     */
    data class Lockout(val durationSeconds: Int) : AppLockError

    /**
     * Unable to start the authentication system.
     *
     * @property message A description of why authentication could not start.
     */
    data class UnableToStart(val message: String) : AppLockError
}
