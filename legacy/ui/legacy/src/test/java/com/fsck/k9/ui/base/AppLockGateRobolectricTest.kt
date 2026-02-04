package com.fsck.k9.ui.base

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.controller.push.PushController
import com.fsck.k9.ui.base.locale.SystemLocaleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.core.preference.display.coreSettings.DisplayCoreSettings
import net.thunderbird.core.preference.display.coreSettings.DisplayCoreSettingsPreferenceManager
import net.thunderbird.core.ui.theme.api.Theme
import net.thunderbird.core.ui.theme.api.ThemeManager
import net.thunderbird.core.ui.theme.api.ThemeProvider
import net.thunderbird.feature.applock.api.AppLockAuthenticator
import net.thunderbird.feature.applock.api.AppLockConfig
import net.thunderbird.feature.applock.api.AppLockCoordinator
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockGate
import net.thunderbird.feature.applock.api.AppLockResult
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.impl.ui.DefaultAppLockGateFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

class AppLockGateRobolectricTest : K9RobolectricTest() {

    private lateinit var testModule: Module
    private lateinit var coordinator: FakeAppLockCoordinator

    @Before
    fun setUp() {
        coordinator = FakeAppLockCoordinator()

        val pushController = mock<PushController>()
        val systemLocaleManager = mock<SystemLocaleManager>()
        val displayCoreSettingsPreferenceManager = FakeDisplayCoreSettingsPreferenceManager()
        val appLanguageManager = AppLanguageManager(
            systemLocaleManager = systemLocaleManager,
            coroutineScope = TestScope(),
            displayCoreSettingsPreferenceManager = displayCoreSettingsPreferenceManager,
        )

        testModule = module {
            single<AppLockCoordinator> { coordinator }
            single<AppLockGate.Factory> { DefaultAppLockGateFactory(coordinator) }
            single<ThemeProvider> { FakeThemeProvider() }
            single<ThemeManager> { FakeThemeManager() }
            single<PushController> { pushController }
            single<AppLanguageManager> { appLanguageManager }
        }

        loadKoinModules(testModule)
    }

    @After
    fun tearDown() {
        unloadKoinModules(testModule)
    }

    @Test
    fun `shows lock overlay when state is Locked`() {
        // Prevent immediate auth success from removing overlay
        coordinator.suspendOnAuthenticate()
        coordinator.setConfigEnabled(true)
        coordinator.stateFlow.value = AppLockState.Locked

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
    }

    @Test
    fun `no overlay when lock is disabled`() {
        coordinator.setConfigEnabled(false)
        coordinator.stateFlow.value = AppLockState.Disabled

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        val overlay = findOverlay(activity)
        assertThat(overlay).isNull()
    }

    @Test
    fun `hides overlay when state becomes Unlocked`() {
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()
        coordinator.setConfigEnabled(true)
        coordinator.stateFlow.value = AppLockState.Locked

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay exists
        assertThat(findOverlay(controller.get())).isNotNull()

        // Change state to Unlocked
        coordinator.stateFlow.value = AppLockState.Unlocked()
        shadowOf(Looper.getMainLooper()).idle()

        val overlay = findOverlay(controller.get())
        assertThat(overlay).isNull()
    }

    @Test
    fun `hides overlay when state becomes Disabled`() {
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()
        coordinator.setConfigEnabled(true)
        coordinator.stateFlow.value = AppLockState.Locked

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay exists
        assertThat(findOverlay(controller.get())).isNotNull()

        // Change state to Disabled
        coordinator.stateFlow.value = AppLockState.Disabled
        shadowOf(Looper.getMainLooper()).idle()

        val overlay = findOverlay(controller.get())
        assertThat(overlay).isNull()
    }

    private fun findOverlay(activity: TestGateActivity): View? {
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        for (i in contentView.childCount - 1 downTo 0) {
            val child = contentView.getChildAt(i)
            if (child is LinearLayout) return child
        }
        return null
    }

    private class TestGateActivity : BaseActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }

    private class FakeAppLockCoordinator : AppLockCoordinator {
        private var currentConfig = AppLockConfig(isEnabled = false)
        val stateFlow = MutableStateFlow<AppLockState>(AppLockState.Disabled)

        override val state: StateFlow<AppLockState> = stateFlow
        override val config: AppLockConfig
            get() = currentConfig
        override val isAuthenticationAvailable: Boolean = true

        private var authDeferred: CompletableDeferred<AppLockResult>? = null
        private var authResult: AppLockResult = Outcome.Success(Unit)
        private var nextAttemptId = 0L

        override fun onAppForegrounded() = Unit

        override fun onAppBackgrounded() = Unit

        override fun onScreenOff() = Unit

        override fun lockNow() = Unit

        override fun ensureUnlocked(): Boolean {
            return when (stateFlow.value) {
                AppLockState.Disabled, is AppLockState.Unlocked -> true
                is AppLockState.Unlocking -> false
                is AppLockState.Unavailable -> false
                AppLockState.Locked, is AppLockState.Failed -> {
                    stateFlow.value = AppLockState.Unlocking(attemptId = nextAttemptId++)
                    true
                }
            }
        }

        override fun onSettingsChanged(config: AppLockConfig) {
            currentConfig = config
        }

        override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
            val unlocking = stateFlow.value as? AppLockState.Unlocking
                ?: return Outcome.Failure(AppLockError.UnableToStart("Not in Unlocking state"))

            val result = authDeferred?.await() ?: authResult
            stateFlow.value = when (result) {
                is Outcome.Success -> AppLockState.Unlocked()
                is Outcome.Failure -> AppLockState.Failed(result.error)
            }
            return result
        }

        fun setConfigEnabled(enabled: Boolean) {
            currentConfig = currentConfig.copy(isEnabled = enabled)
        }

        /**
         * Makes [authenticate] suspend until [completeAuthenticate] is called.
         */
        fun suspendOnAuthenticate() {
            authDeferred = CompletableDeferred()
        }

        fun completeAuthenticate(result: AppLockResult) {
            authDeferred?.complete(result)
        }
    }

    private class FakeThemeProvider : ThemeProvider {
        override val appThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
        override val appLightThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat_Light
        override val appDarkThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
        override val dialogThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat_Dialog
        override val translucentDialogThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
    }

    private class FakeThemeManager : ThemeManager {
        override val appTheme: Theme = Theme.LIGHT
        override val messageViewTheme: Theme = Theme.LIGHT
        override val messageComposeTheme: Theme = Theme.LIGHT
        override val appThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
        override val messageViewThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
        override val messageComposeThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
        override val dialogThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat_Dialog
        override val translucentDialogThemeResourceId: Int = androidx.appcompat.R.style.Theme_AppCompat
    }

    private class FakeDisplayCoreSettingsPreferenceManager : DisplayCoreSettingsPreferenceManager {
        private var config = DisplayCoreSettings()

        override fun save(config: DisplayCoreSettings) {
            this.config = config
        }

        override fun getConfig(): DisplayCoreSettings = config

        override fun getConfigFlow(): Flow<DisplayCoreSettings> = flowOf(config)
    }

}
