package net.thunderbird.feature.applock.impl.domain

import kotlinx.coroutines.CompletableDeferred
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
 * Fake implementation of [AppLockCoordinator] for testing.
 */
internal class FakeAppLockCoordinator(
    private var authResult: AppLockResult = Outcome.Success(Unit),
    override var isEnabled: Boolean = false,
) : AppLockCoordinator {

    private val _state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
    override val state: StateFlow<AppLockState> = _state.asStateFlow()

    private var _config = AppLockConfig()
    override val config: AppLockConfig
        get() = _config

    override val isAuthenticationAvailable: Boolean = true

    var onAppForegroundedCallCount = 0
        private set

    var onAppBackgroundedCallCount = 0
        private set

    var onScreenOffCallCount = 0
        private set

    var lockNowCallCount = 0
        private set

    var ensureUnlockedCallCount = 0
        private set

    var authenticateCallCount = 0
        private set

    var lastSettings: AppLockConfig? = null
        private set

    var lastEnsureUnlockedDestination: Any? = null
        private set

    private var authDeferred: CompletableDeferred<AppLockResult>? = null
    private var nextAttemptId = 0L
    private var _pendingDestination: Any? = null

    /**
     * Makes [authenticate] suspend until [completeAuthenticate] is called.
     */
    fun suspendOnAuthenticate() {
        authDeferred = CompletableDeferred()
    }

    fun completeAuthenticate(result: AppLockResult) {
        authDeferred?.complete(result)
    }

    override fun onAppForegrounded() {
        onAppForegroundedCallCount++
    }

    override fun onAppBackgrounded() {
        onAppBackgroundedCallCount++
    }

    override fun onScreenOff() {
        onScreenOffCallCount++
        if (_state.value is AppLockState.Unlocked || _state.value is AppLockState.Unlocking) {
            _state.value = AppLockState.Locked
        }
    }

    override fun lockNow() {
        lockNowCallCount++
        _state.value = AppLockState.Locked
        _pendingDestination = null
    }

    override fun ensureUnlocked(destination: Any?): Boolean {
        ensureUnlockedCallCount++
        lastEnsureUnlockedDestination = destination
        if (destination != null) {
            _pendingDestination = destination
        }

        return when (_state.value) {
            AppLockState.Disabled, is AppLockState.Unlocked -> true
            is AppLockState.Unlocking -> false
            AppLockState.Locked, is AppLockState.Failed -> {
                _state.value = AppLockState.Unlocking(attemptId = nextAttemptId++)
                true
            }
        }
    }

    override fun onSettingsChanged(config: AppLockConfig) {
        lastSettings = config
        _config = config
    }

    override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
        authenticateCallCount++
        val unlocking = _state.value as? AppLockState.Unlocking
            ?: return Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state"))

        val result = authDeferred?.await() ?: authResult
        _state.value = when (result) {
            is Outcome.Success -> AppLockState.Unlocked()
            is Outcome.Failure -> AppLockState.Failed(result.error)
        }
        return result
    }

    override fun retry() {
        if (_state.value is AppLockState.Failed) {
            _state.value = AppLockState.Unlocking(attemptId = nextAttemptId++)
        }
    }

    override fun consumePendingDestination(): Any? {
        val destination = _pendingDestination
        _pendingDestination = null
        return destination
    }

    fun setAuthResult(result: AppLockResult) {
        authResult = result
    }

    fun setState(state: AppLockState) {
        _state.value = state
    }

    companion object {
        fun alwaysSucceeds(): FakeAppLockCoordinator = FakeAppLockCoordinator()

        fun alwaysFails(error: AppLockError = AppLockError.Failed): FakeAppLockCoordinator =
            FakeAppLockCoordinator(authResult = Outcome.Failure(error))
    }
}
