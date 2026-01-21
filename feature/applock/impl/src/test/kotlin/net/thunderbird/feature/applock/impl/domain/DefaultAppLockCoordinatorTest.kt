package net.thunderbird.feature.applock.impl.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockConfig
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockState
import org.junit.Test

class DefaultAppLockCoordinatorTest {

    @Test
    fun `cold start requires auth when enabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `cold start does not require auth when enabled but unavailable`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            biometricAvailable = false,
        )

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onAppForegrounded does nothing when feature is disabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        coordinator.onAppForegrounded()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onAppForegrounded does nothing when auth is unavailable`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            biometricAvailable = false,
        )

        coordinator.onAppForegrounded()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onAppForegrounded keeps Locked state - pull model requires ensureUnlocked`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.onAppForegrounded()

        // In pull model, onAppForegrounded does NOT auto-transition to Unlocking
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `ensureUnlocked transitions Locked to Unlocking`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        val result = coordinator.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
    }

    @Test
    fun `ensureUnlocked returns false when already Unlocking`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        // Second call should return false (already unlocking)
        val result = coordinator.ensureUnlocked()

        assertThat(result).isFalse()
    }

    @Test
    fun `ensureUnlocked returns true when already Unlocked`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())

        val result = coordinator.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `ensureUnlocked stores destination for post-auth navigation`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )
        val destination = "test://deep-link"

        coordinator.ensureUnlocked(destination)

        assertThat(coordinator.consumePendingDestination()).isEqualTo(destination)
    }

    @Test
    fun `consumePendingDestination clears the destination`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )
        val destination = "test://deep-link"

        coordinator.ensureUnlocked(destination)
        coordinator.consumePendingDestination()

        assertThat(coordinator.consumePendingDestination()).isNull()
    }

    @Test
    fun `ensureUnlocked replaces pending destination with new one`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked("first")
        coordinator.ensureUnlocked("second")

        assertThat(coordinator.consumePendingDestination()).isEqualTo("second")
    }

    @Test
    fun `ensureUnlocked transitions Failed to Unlocking`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        val result = coordinator.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
    }

    @Test
    fun `onAppForegrounded locks when timeout exceeded since background`() = runTest {
        var now = 100_000L
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 60_000L),
            clock = { now },
        )

        // Unlock
        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background and advance time past timeout
        coordinator.onAppBackgrounded()
        now += 120_000L

        coordinator.onAppForegrounded()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onAppForegrounded stays Unlocked when timeout not exceeded since background`() = runTest {
        var now = 100_000L
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 60_000L),
            clock = { now },
        )

        // Unlock
        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background but don't advance time past timeout
        coordinator.onAppBackgrounded()
        now += 30_000L

        coordinator.onAppForegrounded()

        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `onAppForegrounded locks immediately when timeout is zero`() = runTest {
        var now = 100_000L
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 0L),
            clock = { now },
        )

        // Unlock
        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background and advance time minimally
        coordinator.onAppBackgrounded()
        now += 1L

        coordinator.onAppForegrounded()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onAppBackgrounded cancels Unlocking state`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        coordinator.onAppBackgrounded()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff locks when Unlocked`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        coordinator.onScreenOff()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff locks when Unlocking`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        coordinator.onScreenOff()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff does nothing when Disabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        coordinator.onScreenOff()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `lockNow transitions to Locked`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.success())
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        coordinator.lockNow()

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `lockNow clears pending destination`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked("destination")
        coordinator.lockNow()

        assertThat(coordinator.consumePendingDestination()).isNull()
    }

    @Test
    fun `onSettingsChanged transitions to Locked when enabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        coordinator.onSettingsChanged(AppLockConfig(isEnabled = true))

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `authenticate updates state on success`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = coordinator.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `authenticate updates state on failure`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = coordinator.authenticate(FakeAuthenticator.failure(AppLockError.Failed))

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.Failed))
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))
    }

    @Test
    fun `authenticate returns error when not in Unlocking state`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        // Don't call ensureUnlocked - state is Locked, not Unlocking
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)

        val result = coordinator.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state")))
    }

    @Test
    fun `authenticate transitions to Locked on Interrupted error`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        val result = coordinator.authenticate(FakeAuthenticator.failure(AppLockError.Interrupted))

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.Interrupted))
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `isEnabled returns true when feature is enabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(coordinator.isEnabled).isTrue()
    }

    @Test
    fun `isEnabled returns false when feature is disabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        assertThat(coordinator.isEnabled).isFalse()
    }

    @Test
    fun `onSettingsChanged disables lock when isEnabled set to false`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)

        coordinator.onSettingsChanged(AppLockConfig(isEnabled = false))

        assertThat(coordinator.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onSettingsChanged clears pending destination when disabled`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked("destination")
        coordinator.onSettingsChanged(AppLockConfig(isEnabled = false))

        assertThat(coordinator.consumePendingDestination()).isNull()
    }

    @Test
    fun `retry transitions from Failed to Unlocking`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        coordinator.retry()

        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
    }

    @Test
    fun `retry does nothing when not in Failed state`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        // State is Locked, not Failed
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)

        coordinator.retry()

        // State should still be Locked
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `retry after failure allows successful authentication`() = runTest {
        val coordinator = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        coordinator.ensureUnlocked()
        coordinator.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(coordinator.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        coordinator.retry()
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = coordinator.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    private fun createCoordinator(
        config: AppLockConfig,
        biometricAvailable: Boolean = true,
        clock: () -> Long = { System.currentTimeMillis() },
    ): DefaultAppLockCoordinator {
        val configRepository = InMemoryAppLockConfigRepository(config)
        val availability = FakeAppLockAvailability(available = biometricAvailable)

        return DefaultAppLockCoordinator(
            configRepository = configRepository,
            availability = availability,
            clock = clock,
        )
    }

    private class InMemoryAppLockConfigRepository(
        private var config: AppLockConfig,
    ) : AppLockConfigRepository {
        override fun getConfig(): AppLockConfig = config

        override fun setConfig(config: AppLockConfig) {
            this.config = config
        }
    }

    private class FakeAppLockAvailability(
        private val available: Boolean,
    ) : AppLockAvailability {
        override fun isAuthenticationAvailable(): Boolean = available
    }
}
