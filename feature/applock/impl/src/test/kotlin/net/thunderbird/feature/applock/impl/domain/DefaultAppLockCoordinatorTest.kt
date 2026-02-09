package net.thunderbird.feature.applock.impl.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockAuthenticator
import net.thunderbird.feature.applock.api.AppLockConfig
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockResult
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.api.UnavailableReason
import net.thunderbird.feature.applock.api.isUnlocked
import org.junit.Test

class DefaultAppLockCoordinatorTest {

    @Test
    fun `cold start requires auth when enabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `cold start returns Unavailable when enabled but auth unavailable`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            biometricAvailable = false,
            unavailableReason = UnavailableReason.NOT_ENROLLED,
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Unavailable(UnavailableReason.NOT_ENROLLED))
    }

    @Test
    fun `onAppForegrounded does nothing when feature is disabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        testSubject.onAppForegrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onAppForegrounded transitions to Unavailable when auth is unavailable`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            biometricAvailable = false,
            unavailableReason = UnavailableReason.NO_HARDWARE,
        )

        testSubject.onAppForegrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Unavailable(UnavailableReason.NO_HARDWARE))
    }

    @Test
    fun `onAppForegrounded keeps Locked state - pull model requires ensureUnlocked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.onAppForegrounded()

        // In pull model, onAppForegrounded does NOT auto-transition to Unlocking
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `ensureUnlocked transitions Locked to Unlocking`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        val result = testSubject.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()
    }

    @Test
    fun `ensureUnlocked returns false when already Unlocking`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        // Second call should return false (already unlocking)
        val result = testSubject.ensureUnlocked()

        assertThat(result).isFalse()
    }

    @Test
    fun `ensureUnlocked returns true when already Unlocked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())

        val result = testSubject.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `ensureUnlocked transitions Failed to Unlocking`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        val result = testSubject.ensureUnlocked()

        assertThat(result).isTrue()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()
    }

    @Test
    fun `onAppForegrounded locks when timeout exceeded since background`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 60_000L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background and advance time past timeout
        testSubject.onAppBackgrounded()
        now += 120_000L

        testSubject.onAppForegrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onAppForegrounded stays Unlocked when timeout not exceeded since background`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 60_000L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background but don't advance time past timeout
        testSubject.onAppBackgrounded()
        now += 30_000L

        testSubject.onAppForegrounded()

        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `onAppForegrounded locks immediately when timeout is zero`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 0L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Go to background and advance time minimally
        testSubject.onAppBackgrounded()
        now += 1L

        testSubject.onAppForegrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onAppBackgrounded cancels Unlocking state`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        testSubject.onAppBackgrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff locks when Unlocked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        testSubject.onScreenOff()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff locks when Unlocking`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        testSubject.onScreenOff()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onScreenOff does nothing when Disabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        testSubject.onScreenOff()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `lockNow transitions to Locked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        testSubject.lockNow()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onSettingsChanged transitions to Locked when enabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        testSubject.onSettingsChanged(AppLockConfig(isEnabled = true))

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `authenticate updates state on success`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = testSubject.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `authenticate updates state on failure`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = testSubject.authenticate(FakeAuthenticator.failure(AppLockError.Failed))

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.Failed))
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))
    }

    @Test
    fun `authenticate returns error when not in Unlocking state`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        // Don't call ensureUnlocked - state is Locked, not Unlocking
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)

        val result = testSubject.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state")))
    }

    @Test
    fun `authenticate transitions to Locked on Interrupted error`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        val result = testSubject.authenticate(FakeAuthenticator.failure(AppLockError.Interrupted))

        assertThat(result).isEqualTo(Outcome.Failure(AppLockError.Interrupted))
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `isEnabled returns true when feature is enabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(testSubject.isEnabled).isTrue()
    }

    @Test
    fun `isEnabled returns false when feature is disabled`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = false),
        )

        assertThat(testSubject.isEnabled).isFalse()
    }

    @Test
    fun `onSettingsChanged disables lock when isEnabled set to false`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)

        testSubject.onSettingsChanged(AppLockConfig(isEnabled = false))

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `ensureUnlocked after failure allows successful authentication`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        // ensureUnlocked transitions Failed -> Unlocking
        testSubject.ensureUnlocked()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocking>()

        val result = testSubject.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `ensureUnlocked returns false when Unavailable`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            biometricAvailable = false,
            unavailableReason = UnavailableReason.NOT_ENROLLED,
        )
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unavailable>()

        val result = testSubject.ensureUnlocked()

        assertThat(result).isFalse()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unavailable>()
    }

    @Test
    fun `isUnlocked returns false for Unavailable state`() = runTest {
        val state = AppLockState.Unavailable(UnavailableReason.NOT_ENROLLED)

        assertThat(state.isUnlocked()).isFalse()
    }

    @Test
    fun `authenticate rejects concurrent calls`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()

        // First call - use a suspending authenticator
        val suspendingAuthenticator = SuspendingAuthenticator()
        val firstJob = launch {
            testSubject.authenticate(suspendingAuthenticator)
        }

        // Wait for first call to start
        suspendingAuthenticator.awaitStarted()

        // Second concurrent call should be rejected
        val result = testSubject.authenticate(FakeAuthenticator.success())

        assertThat(result).isEqualTo(
            Outcome.Failure(AppLockError.UnableToStart("Authentication already in progress")),
        )

        // Complete first call
        suspendingAuthenticator.complete(Outcome.Success(Unit))
        firstJob.join()
    }

    @Test
    fun `refreshAvailability transitions Unavailable to Locked when auth available`() = runTest {
        val availability = MutableAppLockAvailability(available = false, reason = UnavailableReason.NOT_ENROLLED)
        val testSubject = createCoordinatorWithMutableAvailability(
            config = AppLockConfig(isEnabled = true),
            availability = availability,
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Unavailable(UnavailableReason.NOT_ENROLLED))

        // User sets up authentication in device settings
        availability.setAvailable(true)
        testSubject.refreshAvailability()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `refreshAvailability does nothing when not in Unavailable state`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)

        testSubject.refreshAvailability()

        // State should remain Locked
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `refreshAvailability transitions to Disabled when lock is disabled`() = runTest {
        val configRepository = MutableAppLockConfigRepository(AppLockConfig(isEnabled = true))
        val availability = MutableAppLockAvailability(available = false, reason = UnavailableReason.NOT_ENROLLED)
        val testSubject = DefaultAppLockCoordinator(
            configRepository = configRepository,
            availability = availability,
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Unavailable(UnavailableReason.NOT_ENROLLED))

        // User disabled app lock while in unavailable state
        configRepository.setConfig(AppLockConfig(isEnabled = false))
        testSubject.refreshAvailability()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)
    }

    @Test
    fun `onExternalIntentLaunching sets exemption when unlocked`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        assertThat(testSubject.hasExternalIntentExemption).isFalse()

        testSubject.onExternalIntentLaunching()

        assertThat(testSubject.hasExternalIntentExemption).isTrue()
    }

    @Test
    fun `onExternalIntentLaunching does nothing when locked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)

        testSubject.onExternalIntentLaunching()

        assertThat(testSubject.hasExternalIntentExemption).isFalse()
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `external intent exemption allows return without re-auth`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 0L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Mark external intent and go to background
        testSubject.onExternalIntentLaunching()
        testSubject.onAppBackgrounded()
        now += 1000L // Advance time past zero timeout

        // Return from external intent
        testSubject.onAppForegrounded()

        // Should stay unlocked due to exemption
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
    }

    @Test
    fun `external intent exemption is consumed after foreground`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 0L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())

        // Mark external intent and go to background
        testSubject.onExternalIntentLaunching()
        testSubject.onAppBackgrounded()
        now += 1000L

        // Return from external intent - exemption consumed
        testSubject.onAppForegrounded()
        assertThat(testSubject.state.value).isInstanceOf<AppLockState.Unlocked>()
        assertThat(testSubject.hasExternalIntentExemption).isFalse()

        // Background again without marking external intent
        testSubject.onAppBackgrounded()
        now += 1000L

        // Now should lock - no exemption
        testSubject.onAppForegrounded()
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `external intent exemption expires after grace period`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true, timeoutMillis = 0L),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())

        // Mark external intent and go to background
        testSubject.onExternalIntentLaunching()
        testSubject.onAppBackgrounded()

        // Advance time past grace period (5 minutes = 300,000ms)
        now += 6 * 60 * 1000L // 6 minutes

        // Return from external intent
        testSubject.onAppForegrounded()

        // Should lock - exemption expired
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onAppBackgrounded transitions Failed to Locked`() = runTest {
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
        )

        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.failure(AppLockError.Failed))
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Failed(AppLockError.Failed))

        testSubject.onAppBackgrounded()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
    }

    @Test
    fun `onSettingsChanged rejects enabling when auth unavailable`() = runTest {
        val availability = MutableAppLockAvailability(available = true)
        val testSubject = createCoordinatorWithMutableAvailability(
            config = AppLockConfig(isEnabled = false),
            availability = availability,
        )
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)

        // Make auth unavailable then try to enable
        availability.setAvailable(false)
        testSubject.onSettingsChanged(AppLockConfig(isEnabled = true))

        // State should remain Disabled - enabling was rejected
        assertThat(testSubject.state.value).isEqualTo(AppLockState.Disabled)
        // Config should not be persisted
        assertThat(testSubject.config.isEnabled).isFalse()
    }

    @Test
    fun `screen off clears external intent exemption`() = runTest {
        var now = 100_000L
        val testSubject = createCoordinator(
            config = AppLockConfig(isEnabled = true),
            clock = { now },
        )

        // Unlock
        testSubject.ensureUnlocked()
        testSubject.authenticate(FakeAuthenticator.success())

        // Mark external intent
        testSubject.onExternalIntentLaunching()
        assertThat(testSubject.hasExternalIntentExemption).isTrue()

        // Screen off should lock and clear exemption
        testSubject.onScreenOff()

        assertThat(testSubject.state.value).isEqualTo(AppLockState.Locked)
        assertThat(testSubject.hasExternalIntentExemption).isFalse()
    }

    private class SuspendingAuthenticator : AppLockAuthenticator {
        private val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val result = kotlinx.coroutines.CompletableDeferred<AppLockResult>()

        suspend fun awaitStarted() = started.await()
        fun complete(value: AppLockResult) = result.complete(value)

        override suspend fun authenticate(): AppLockResult {
            started.complete(Unit)
            return result.await()
        }
    }

    private fun createCoordinator(
        config: AppLockConfig,
        biometricAvailable: Boolean = true,
        unavailableReason: UnavailableReason = UnavailableReason.NO_HARDWARE,
        clock: () -> Long = { System.currentTimeMillis() },
    ): DefaultAppLockCoordinator {
        val configRepository = InMemoryAppLockConfigRepository(config)
        val availability = FakeAppLockAvailability(available = biometricAvailable, reason = unavailableReason)

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
        private val reason: UnavailableReason = UnavailableReason.NO_HARDWARE,
    ) : AppLockAvailability {
        override fun isAuthenticationAvailable(): Boolean = available
        override fun getUnavailableReason(): UnavailableReason = reason
    }

    private class MutableAppLockAvailability(
        private var available: Boolean,
        private var reason: UnavailableReason = UnavailableReason.NO_HARDWARE,
    ) : AppLockAvailability {
        fun setAvailable(available: Boolean) {
            this.available = available
        }

        override fun isAuthenticationAvailable(): Boolean = available
        override fun getUnavailableReason(): UnavailableReason = reason
    }

    private class MutableAppLockConfigRepository(
        private var config: AppLockConfig,
    ) : AppLockConfigRepository {
        override fun getConfig(): AppLockConfig = config

        override fun setConfig(config: AppLockConfig) {
            this.config = config
        }
    }

    private fun createCoordinatorWithMutableAvailability(
        config: AppLockConfig,
        availability: MutableAppLockAvailability,
        clock: () -> Long = { System.currentTimeMillis() },
    ): DefaultAppLockCoordinator {
        val configRepository = InMemoryAppLockConfigRepository(config)

        return DefaultAppLockCoordinator(
            configRepository = configRepository,
            availability = availability,
            clock = clock,
        )
    }
}
