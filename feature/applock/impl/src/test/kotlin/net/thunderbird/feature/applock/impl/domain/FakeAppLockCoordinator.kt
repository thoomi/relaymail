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
) : AppLockCoordinator {

    private val _state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
    override val state: StateFlow<AppLockState> = _state.asStateFlow()

    private var _config = AppLockConfig()
    override val config: AppLockConfig
        get() = _config

    override var isAuthenticationAvailable: Boolean = true

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

    var refreshAvailabilityCallCount = 0
        private set

    var lastSettings: AppLockConfig? = null
        private set

    private var currentAuthDeferred: CompletableDeferred<AppLockResult>? = null
    private val pendingAuthDeferreds = ArrayDeque<CompletableDeferred<AppLockResult>>()
    private var nextAttemptId = 0L
    private var stateAfterRefresh: AppLockState? = null
    private var isAuthenticating = false

    /**
     * Makes the next [authenticate] call suspend until [completeAuthenticate] is called.
     * Each call to this method queues one additional suspend point, so calling it twice
     * will make two successive [authenticate] calls each suspend independently.
     */
    fun suspendOnAuthenticate() {
        pendingAuthDeferreds.addLast(CompletableDeferred())
    }

    fun completeAuthenticate(result: AppLockResult) {
        currentAuthDeferred?.complete(result)
    }

    override fun onAppForegrounded() {
        onAppForegroundedCallCount++
    }

    override fun onAppBackgrounded() {
        onAppBackgroundedCallCount++
    }

    override fun onScreenOff() {
        onScreenOffCallCount++
        when {
            _state.value is AppLockState.Unlocked -> _state.value = AppLockState.Locked
            _state.value is AppLockState.Unlocking && !isAuthenticating -> _state.value = AppLockState.Locked
        }
    }

    override fun lockNow() {
        lockNowCallCount++
        _state.value = AppLockState.Locked
    }

    override fun ensureUnlocked(): Boolean {
        ensureUnlockedCallCount++

        return when (_state.value) {
            AppLockState.Disabled, is AppLockState.Unlocked -> true

            is AppLockState.Unlocking -> false

            is AppLockState.Unavailable -> false

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

    override fun refreshAvailability() {
        refreshAvailabilityCallCount++
        stateAfterRefresh?.let { _state.value = it }
    }

    override suspend fun requestEnable(authenticator: AppLockAuthenticator): AppLockResult {
        val result = authenticator.authenticate()
        if (result is Outcome.Success) {
            _config = _config.copy(isEnabled = true)
            _state.value = AppLockState.Unlocked()
        }
        return result
    }

    @Suppress("ReturnCount")
    override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
        if (isAuthenticating) {
            return Outcome.Failure(AppLockError.UnableToStart("Authentication already in progress"))
        }

        authenticateCallCount++
        val unlocking = _state.value as? AppLockState.Unlocking
            ?: return Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state"))

        isAuthenticating = true
        try {
            val deferred = pendingAuthDeferreds.removeFirstOrNull()
            currentAuthDeferred = deferred
            val result = deferred?.await() ?: authResult
            currentAuthDeferred = null
            // Only apply result if attemptId still matches (mirrors DefaultAppLockCoordinator)
            if ((_state.value as? AppLockState.Unlocking)?.attemptId == unlocking.attemptId) {
                _state.value = when {
                    result is Outcome.Success -> AppLockState.Unlocked()

                    result is Outcome.Failure && result.error is AppLockError.Interrupted ->
                        AppLockState.Locked

                    // matches resolveAuthResult() in real coordinator
                    else -> AppLockState.Failed((result as Outcome.Failure).error)
                }
            }
            return result
        } finally {
            currentAuthDeferred = null
            isAuthenticating = false
        }
    }

    fun setAuthResult(result: AppLockResult) {
        authResult = result
    }

    fun setState(state: AppLockState) {
        _state.value = state
    }

    fun setStateAfterRefresh(state: AppLockState?) {
        stateAfterRefresh = state
    }

    fun setConfigEnabled(enabled: Boolean) {
        _config = _config.copy(isEnabled = enabled)
    }

    companion object {
        fun alwaysSucceeds(): FakeAppLockCoordinator = FakeAppLockCoordinator()

        fun alwaysFails(error: AppLockError = AppLockError.Failed): FakeAppLockCoordinator =
            FakeAppLockCoordinator(authResult = Outcome.Failure(error))
    }
}
