package net.thunderbird.feature.applock.impl.domain

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockAuthenticator
import net.thunderbird.feature.applock.api.AppLockConfig
import net.thunderbird.feature.applock.api.AppLockCoordinator
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockResult
import net.thunderbird.feature.applock.api.AppLockState

/**
 * Coordinates app lock flow: settings, availability, state, and authentication.
 *
 * Uses a pull model where UI explicitly calls [ensureUnlocked] to trigger authentication.
 * No effect bus is used - activities observe [state] and show prompts when appropriate.
 *
 * State is managed in-memory and not persisted. Process death always requires
 * re-authentication when app lock is enabled. The timeout only applies to
 * background-to-foreground transitions within the same process.
 *
 * Registers itself with ProcessLifecycleOwner to track app foreground/background state,
 * and listens for screen-off broadcasts to lock immediately.
 */
internal class DefaultAppLockCoordinator(
    private val configRepository: AppLockConfigRepository,
    private val availability: AppLockAvailability,
    lifecycleHandler: AppLockLifecycleHandler? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : AppLockCoordinator, DefaultLifecycleObserver {

    private val _state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
    override val state: StateFlow<AppLockState> = _state.asStateFlow()

    private var nextAttemptId: Long = 0L
    private val authMutex = Mutex()

    override val config: AppLockConfig
        get() = configRepository.getConfig()

    override val isAuthenticationAvailable: Boolean
        get() = availability.isAuthenticationAvailable()

    init {
        // Initialize state based on current config (cold start)
        val currentConfig = configRepository.getConfig()
        val biometricAvailable = availability.isAuthenticationAvailable()
        _state.value = computeInitialState(currentConfig, biometricAvailable)

        // Register lifecycle observer (null in tests)
        lifecycleHandler?.register(this, ::onScreenOff)
    }

    // DefaultLifecycleObserver callbacks
    override fun onStart(owner: LifecycleOwner) {
        onAppForegrounded()
    }

    override fun onStop(owner: LifecycleOwner) {
        onAppBackgrounded()
    }

    override fun onAppForegrounded() {
        val currentConfig = configRepository.getConfig()
        val biometricAvailable = availability.isAuthenticationAvailable()

        // If disabled by user preference, set state to Disabled
        if (!currentConfig.isEnabled) {
            _state.value = AppLockState.Disabled
            return
        }

        // If enabled but auth unavailable, block access with Unavailable state
        if (!biometricAvailable) {
            _state.value = AppLockState.Unavailable(availability.getUnavailableReason())
            return
        }

        // Evaluate timeout for Unlocked state
        when (val current = _state.value) {
            is AppLockState.Unlocked -> {
                val lastHiddenAt = current.lastHiddenAtElapsedMillis
                if (lastHiddenAt != null && isTimeoutExceeded(lastHiddenAt, currentConfig.timeoutMillis)) {
                    _state.value = AppLockState.Locked
                } else {
                    // Clear the hidden timestamp since we're back in foreground
                    _state.value = current.copy(lastHiddenAtElapsedMillis = null)
                }
            }
            AppLockState.Disabled, is AppLockState.Unavailable -> {
                // Was disabled/unavailable, now enabled and available - require auth
                _state.value = AppLockState.Locked
            }
            // Locked, Unlocking, Failed - keep current state, UI will call ensureUnlocked
            else -> Unit
        }
    }

    override fun onAppBackgrounded() {
        when (val current = _state.value) {
            is AppLockState.Unlocked -> {
                _state.value = current.copy(lastHiddenAtElapsedMillis = clock())
            }
            is AppLockState.Unlocking, is AppLockState.Failed -> {
                // Cancel unlock attempt or clear failure when backgrounded
                // This allows retry on next foreground
                _state.value = AppLockState.Locked
            }
            else -> Unit
        }
    }

    override fun onScreenOff() {
        val currentConfig = configRepository.getConfig()
        if (currentConfig.isEnabled && availability.isAuthenticationAvailable()) {
            when (_state.value) {
                is AppLockState.Unlocked, is AppLockState.Unlocking -> {
                    _state.value = AppLockState.Locked
                }
                else -> Unit
            }
        }
    }

    override fun lockNow() {
        val currentConfig = configRepository.getConfig()
        if (currentConfig.isEnabled && availability.isAuthenticationAvailable()) {
            _state.value = AppLockState.Locked
        }
    }

    override fun ensureUnlocked(): Boolean {
        return when (_state.value) {
            AppLockState.Disabled, is AppLockState.Unlocked -> {
                // Already unlocked
                true
            }
            is AppLockState.Unlocking -> {
                // Already unlocking - caller should not show duplicate prompt
                false
            }
            is AppLockState.Unavailable -> {
                // Auth unavailable - cannot unlock, UI should show guidance
                false
            }
            AppLockState.Locked, is AppLockState.Failed -> {
                // Transition to Unlocking
                _state.value = AppLockState.Unlocking(nextAttemptId++)
                true
            }
        }
    }

    override fun onSettingsChanged(config: AppLockConfig) {
        configRepository.setConfig(config)
        val biometricAvailable = availability.isAuthenticationAvailable()

        if (!config.isEnabled) {
            _state.value = AppLockState.Disabled
        } else if (!biometricAvailable) {
            _state.value = AppLockState.Unavailable(availability.getUnavailableReason())
        } else {
            // Lock was enabled - require auth
            when (_state.value) {
                AppLockState.Disabled, is AppLockState.Unavailable -> {
                    _state.value = AppLockState.Locked
                }
                // Keep other states as-is
                else -> Unit
            }
        }
    }

    override fun refreshAvailability() {
        val currentConfig = configRepository.getConfig()
        val biometricAvailable = availability.isAuthenticationAvailable()

        when (_state.value) {
            is AppLockState.Unavailable -> {
                if (biometricAvailable && currentConfig.isEnabled) {
                    _state.value = AppLockState.Locked
                } else if (!currentConfig.isEnabled) {
                    _state.value = AppLockState.Disabled
                }
            }
            else -> Unit
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
        // Single-flight: reject if already authenticating
        if (!authMutex.tryLock()) {
            return Outcome.Failure(AppLockError.UnableToStart("Authentication already in progress"))
        }

        try {
            val unlocking = _state.value as? AppLockState.Unlocking
                ?: return Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state"))

            val result = try {
                authenticator.authenticate()
            } catch (e: CancellationException) {
                throw e // Rethrow to allow proper coroutine cancellation
            } catch (e: Exception) {
                Outcome.Failure(AppLockError.UnableToStart(e.message ?: "Unknown error"))
            }

            // Only apply result if attemptId still matches (guards against stale results)
            if ((_state.value as? AppLockState.Unlocking)?.attemptId == unlocking.attemptId) {
                _state.value = when (result) {
                    is Outcome.Success -> AppLockState.Unlocked(lastHiddenAtElapsedMillis = null)
                    is Outcome.Failure -> {
                        // System interruptions (rotation, backgrounding) go back to Locked
                        if (result.error is AppLockError.Interrupted) {
                            AppLockState.Locked
                        } else {
                            AppLockState.Failed(result.error)
                        }
                    }
                }
            }

            return result
        } finally {
            authMutex.unlock()
        }
    }

    private fun computeInitialState(config: AppLockConfig, biometricAvailable: Boolean): AppLockState {
        return if (!config.isEnabled) {
            AppLockState.Disabled
        } else if (!biometricAvailable) {
            AppLockState.Unavailable(availability.getUnavailableReason())
        } else {
            AppLockState.Locked
        }
    }

    private fun isTimeoutExceeded(lastHiddenAtMillis: Long, timeoutMillis: Long): Boolean {
        val elapsed = clock() - lastHiddenAtMillis
        return elapsed >= timeoutMillis
    }
}
