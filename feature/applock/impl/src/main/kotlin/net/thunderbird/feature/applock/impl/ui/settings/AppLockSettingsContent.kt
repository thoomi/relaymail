package net.thunderbird.feature.applock.impl.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.k9mail.core.ui.compose.designsystem.atom.Checkbox
import app.k9mail.core.ui.compose.designsystem.atom.button.RadioButton
import app.k9mail.core.ui.compose.designsystem.atom.text.TextBodyMedium
import app.k9mail.core.ui.compose.designsystem.atom.text.TextTitleMedium
import app.k9mail.core.ui.compose.designsystem.organism.AlertDialog
import app.k9mail.core.ui.compose.theme2.MainTheme
import net.thunderbird.feature.applock.impl.R
import net.thunderbird.feature.applock.impl.ui.settings.AppLockSettingsContract.Event
import net.thunderbird.feature.applock.impl.ui.settings.AppLockSettingsContract.State

@Composable
internal fun AppLockSettingsContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        AppLockEnableRow(
            isEnabled = state.isEnabled,
            isAvailable = state.isAuthenticationAvailable,
            onEnableChanged = { onEvent(Event.OnEnableChanged(it)) },
        )

        AppLockTimeoutRow(
            timeoutMinutes = state.timeoutMinutes,
            timeoutOptions = state.timeoutOptions,
            onTimeoutChanged = { onEvent(Event.OnTimeoutChanged(it)) },
            enabled = state.isEnabled,
        )
    }
}

@Composable
private fun AppLockEnableRow(
    isEnabled: Boolean,
    isAvailable: Boolean,
    onEnableChanged: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isAvailable) { onEnableChanged(!isEnabled) }
            .padding(horizontal = MainTheme.spacings.double, vertical = MainTheme.spacings.oneHalf),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(
                text = stringResource(R.string.applock_settings_title),
            )
            TextBodyMedium(
                text = if (isAvailable) {
                    stringResource(R.string.applock_settings_summary)
                } else {
                    stringResource(R.string.applock_settings_biometric_not_available)
                },
                color = MainTheme.colors.onSurfaceVariant,
            )
        }
        Checkbox(
            checked = isEnabled,
            onCheckedChange = onEnableChanged,
            enabled = isAvailable,
        )
    }
}

@Composable
private fun AppLockTimeoutRow(
    timeoutMinutes: Int,
    timeoutOptions: List<Int>,
    onTimeoutChanged: (Int) -> Unit,
    enabled: Boolean,
) {
    var showDialog by remember { mutableStateOf(false) }
    val contentColor = if (enabled) {
        MainTheme.colors.onSurface
    } else {
        MainTheme.colors.onSurface.copy(alpha = 0.38f)
    }
    val secondaryColor = if (enabled) {
        MainTheme.colors.onSurfaceVariant
    } else {
        MainTheme.colors.onSurfaceVariant.copy(alpha = 0.38f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(horizontal = MainTheme.spacings.double, vertical = MainTheme.spacings.oneHalf),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(
                text = stringResource(R.string.applock_settings_timeout_title),
                color = contentColor,
            )
            TextBodyMedium(
                text = formatTimeout(timeoutMinutes),
                color = secondaryColor,
            )
        }
    }

    if (showDialog) {
        TimeoutSelectionDialog(
            timeoutMinutes = timeoutMinutes,
            timeoutOptions = timeoutOptions,
            onTimeoutSelected = { minutes ->
                onTimeoutChanged(minutes)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun TimeoutSelectionDialog(
    timeoutMinutes: Int,
    timeoutOptions: List<Int>,
    onTimeoutSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = stringResource(R.string.applock_settings_timeout_title),
        confirmText = stringResource(android.R.string.cancel),
        onConfirmClick = onDismiss,
        onDismissRequest = onDismiss,
    ) {
        Column {
            timeoutOptions.forEach { minutes ->
                RadioButton(
                    selected = minutes == timeoutMinutes,
                    label = formatTimeout(minutes),
                    onClick = { onTimeoutSelected(minutes) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun formatTimeout(minutes: Int): String {
    return when (minutes) {
        0 -> stringResource(R.string.applock_settings_timeout_immediately)
        1 -> stringResource(R.string.applock_settings_timeout_1_minute)
        else -> stringResource(R.string.applock_settings_timeout_n_minutes, minutes)
    }
}
