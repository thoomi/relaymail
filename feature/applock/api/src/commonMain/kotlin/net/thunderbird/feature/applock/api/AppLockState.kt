package net.thunderbird.feature.applock.api

/**
 * Unified state for the app lock feature.
 */
sealed interface AppLockState {
    /**
     * App lock is disabled or unavailable - no authentication required.
     */
    data object Disabled : AppLockState

    /**
     * App lock is enabled and authentication is required.
     */
    data object Locked : AppLockState

    /**
     * Authentication is currently in progress.
     *
     * @property attemptId Internal identifier for correlating auth results.
     */
    data class Unlocking(
        val attemptId: Long,
    ) : AppLockState

    /**
     * User has successfully authenticated.
     *
     * @property lastHiddenAtMillis Timestamp when app went to background, or null if visible.
     */
    data class Unlocked(
        val lastHiddenAtMillis: Long? = null,
    ) : AppLockState

    /**
     * Authentication failed with an error.
     *
     * @property error The error from the failed authentication attempt.
     */
    data class Failed(val error: AppLockError) : AppLockState
}

/**
 * Check if the app is unlocked (authenticated or lock disabled).
 */
fun AppLockState.isUnlocked(): Boolean {
    return this is AppLockState.Unlocked || this is AppLockState.Disabled
}
