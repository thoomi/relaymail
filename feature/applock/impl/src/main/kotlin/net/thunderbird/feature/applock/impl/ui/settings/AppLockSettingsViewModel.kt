package net.thunderbird.feature.applock.impl.ui.settings

import androidx.lifecycle.viewModelScope
import app.k9mail.core.ui.compose.common.mvi.BaseViewModel
import kotlinx.coroutines.launch
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockCoordinator
import net.thunderbird.feature.applock.impl.ui.settings.AppLockSettingsContract.Effect
import net.thunderbird.feature.applock.impl.ui.settings.AppLockSettingsContract.Event
import net.thunderbird.feature.applock.impl.ui.settings.AppLockSettingsContract.State

internal class AppLockSettingsViewModel(
    private val coordinator: AppLockCoordinator,
) : BaseViewModel<State, Event, Effect>(
    initialState = State(
        isEnabled = coordinator.config.isEnabled,
        isAuthenticationAvailable = coordinator.isAuthenticationAvailable,
        timeoutMinutes = (coordinator.config.timeoutMillis / 60_000L).toInt(),
    ),
),
    AppLockSettingsContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            is Event.OnEnableChanged -> handleEnableChanged(event.enabled)
            is Event.OnTimeoutChanged -> handleTimeoutChanged(event.minutes)
            is Event.OnAuthenticatorReady -> handleAuthenticatorReady(event)
            Event.OnBackPressed -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun handleEnableChanged(enabled: Boolean) {
        if (enabled) {
            emitEffect(Effect.RequestAuthentication)
        } else {
            val currentConfig = coordinator.config
            coordinator.onSettingsChanged(currentConfig.copy(isEnabled = false))
            updateState { it.copy(isEnabled = false) }
        }
    }

    private fun handleAuthenticatorReady(event: Event.OnAuthenticatorReady) {
        viewModelScope.launch {
            val result = coordinator.requestEnable(event.authenticator)
            if (result is Outcome.Success) {
                updateState { it.copy(isEnabled = true) }
            }
        }
    }

    private fun handleTimeoutChanged(minutes: Int) {
        val currentConfig = coordinator.config
        val timeoutMillis = minutes * 60_000L
        coordinator.onSettingsChanged(currentConfig.copy(timeoutMillis = timeoutMillis))
        updateState { it.copy(timeoutMinutes = minutes) }
    }
}
