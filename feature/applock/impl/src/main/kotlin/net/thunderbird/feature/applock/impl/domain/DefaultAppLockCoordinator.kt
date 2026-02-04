package net.thunderbird.feature.applock.impl.domain

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AppLockCoordinator, DefaultLifecycleObserver {

    private val _state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
    override val state: StateFlow<AppLockState> = _state.asStateFlow()

    private var nextAttemptId: Long = 0L
    private var pendingDestination: Any? = null

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

        // If disabled by user preference OR temporarily unavailable, set state to Disabled
        // Note: We don't persist isEnabled=false when availability is temporarily unavailable
        // to preserve user preference. App lock will re-enable when availability is restored.
        if (!currentConfig.isEnabled || !biometricAvailable) {
            _state.value = AppLockState.Disabled
            pendingDestination = null
            return
        }

        // Evaluate timeout for Unlocked state
        when (val current = _state.value) {
            is AppLockState.Unlocked -> {
                val lastHiddenAt = current.lastHiddenAtMillis
                if (lastHiddenAt != null && isTimeoutExceeded(lastHiddenAt, currentConfig.timeoutMillis)) {
                    _state.value = AppLockState.Locked
                } else {
                    // Clear the hidden timestamp since we're back in foreground
                    _state.value = current.copy(lastHiddenAtMillis = null)
                }
            }
            AppLockState.Disabled -> {
                // Was disabled, now enabled - require auth
                _state.value = AppLockState.Locked
            }
            // Locked, Unlocking, Failed - keep current state, UI will call ensureUnlocked
            else -> Unit
        }
    }

    override fun onAppBackgrounded() {
        when (val current = _state.value) {
            is AppLockState.Unlocked -> {
                _state.value = current.copy(lastHiddenAtMillis = clock())
            }
            is AppLockState.Unlocking -> {
                // Cancel unlock attempt when backgrounded
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
            pendingDestination = null
        }
    }

    override fun ensureUnlocked(destination: Any?): Boolean {
        return when (_state.value) {
            AppLockState.Disabled, is AppLockState.Unlocked -> {
                // Already unlocked - caller can navigate immediately, no need to store destination
                true
            }
            is AppLockState.Unlocking -> {
                // Already unlocking - update destination if provided, caller should not show duplicate prompt
                if (destination != null) {
                    pendingDestination = destination
                }
                false
            }
            AppLockState.Locked, is AppLockState.Failed -> {
                // Store destination for post-auth navigation, then transition to Unlocking
                if (destination != null) {
                    pendingDestination = destination
                }
                _state.value = AppLockState.Unlocking(nextAttemptId++)
                true
            }
        }
    }

    override fun onSettingsChanged(config: AppLockConfig) {
        configRepository.setConfig(config)
        val biometricAvailable = availability.isAuthenticationAvailable()

        if (!config.isEnabled || !biometricAvailable) {
            _state.value = AppLockState.Disabled
            pendingDestination = null
        } else {
            // Lock was enabled - require auth
            when (_state.value) {
                AppLockState.Disabled -> {
                    _state.value = AppLockState.Locked
                }
                // Keep other states as-is
                else -> Unit
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
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
                is Outcome.Success -> AppLockState.Unlocked(lastHiddenAtMillis = null)
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
    }

    override fun retry() {
        if (_state.value is AppLockState.Failed) {
            _state.value = AppLockState.Unlocking(nextAttemptId++)
        }
    }

    override fun consumePendingDestination(): Any? {
        val destination = pendingDestination
        pendingDestination = null
        return destination
    }

    private fun computeInitialState(config: AppLockConfig, biometricAvailable: Boolean): AppLockState {
        return if (!config.isEnabled || !biometricAvailable) {
            AppLockState.Disabled
        } else {
            AppLockState.Locked
        }
    }

    private fun isTimeoutExceeded(lastHiddenAtMillis: Long, timeoutMillis: Long): Boolean {
        val elapsed = clock() - lastHiddenAtMillis
        return elapsed >= timeoutMillis
    }
}
