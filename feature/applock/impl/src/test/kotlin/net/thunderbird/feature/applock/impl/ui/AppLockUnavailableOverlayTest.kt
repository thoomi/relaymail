package net.thunderbird.feature.applock.impl.ui

import androidx.compose.ui.test.performClick
import app.k9mail.core.ui.compose.testing.ComposeTest
import app.k9mail.core.ui.compose.testing.onNodeWithText
import app.k9mail.core.ui.compose.testing.setContentWithTheme
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.feature.applock.impl.R
import org.junit.Test

class AppLockUnavailableOverlayTest : ComposeTest() {

    @Test
    fun `shows hint for temporarily unavailable`() {
        val hintMessage = getString(R.string.applock_error_temporarily_unavailable)

        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = hintMessage,
                actionButtonText = getString(R.string.applock_button_try_again),
                onActionClick = {},
                onCloseClick = {},
            )
        }

        onNodeWithText(hintMessage, substring = true).assertExists()
    }

    @Test
    fun `shows hint for unknown unavailable`() {
        val hintMessage = getString(R.string.applock_error_unknown_unavailable)

        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = hintMessage,
                actionButtonText = getString(R.string.applock_button_try_again),
                onActionClick = {},
                onCloseClick = {},
            )
        }

        onNodeWithText(hintMessage, substring = true).assertExists()
    }

    @Test
    fun `no hardware shows close app button and no action button`() {
        val hintMessage = getString(R.string.applock_error_not_available)

        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = hintMessage,
                actionButtonText = null,
                onActionClick = null,
                onCloseClick = {},
            )
        }

        onNodeWithText(R.string.applock_button_close_app).assertExists()
        onNodeWithText(R.string.applock_button_try_again).assertDoesNotExist()
    }

    @Test
    fun `temporarily unavailable shows try again action button`() {
        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = getString(R.string.applock_error_temporarily_unavailable),
                actionButtonText = getString(R.string.applock_button_try_again),
                onActionClick = {},
                onCloseClick = {},
            )
        }

        onNodeWithText(R.string.applock_button_try_again).assertExists()
    }

    @Test
    fun `action button click triggers onActionClick callback`() {
        var actionClickCount = 0

        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = getString(R.string.applock_error_temporarily_unavailable),
                actionButtonText = getString(R.string.applock_button_try_again),
                onActionClick = { actionClickCount++ },
                onCloseClick = {},
            )
        }

        onNodeWithText(R.string.applock_button_try_again).performClick()

        assertThat(actionClickCount).isEqualTo(1)
    }

    @Test
    fun `close button click triggers onCloseClick callback`() {
        var closeClickCount = 0

        setContentWithTheme {
            AppLockUnavailableOverlay(
                hintMessage = getString(R.string.applock_error_not_available),
                actionButtonText = null,
                onActionClick = null,
                onCloseClick = { closeClickCount++ },
            )
        }

        onNodeWithText(R.string.applock_button_close_app).performClick()

        assertThat(closeClickCount).isEqualTo(1)
    }
}
