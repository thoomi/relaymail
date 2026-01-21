package net.thunderbird.feature.applock.impl.domain

/**
 * Checks whether authentication is available on this device.
 */
internal interface AppLockAvailability {
    fun isAuthenticationAvailable(): Boolean
}
