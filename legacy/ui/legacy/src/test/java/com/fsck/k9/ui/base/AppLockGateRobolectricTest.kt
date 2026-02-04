package com.fsck.k9.ui.base

import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.controller.push.PushController
import com.fsck.k9.ui.base.locale.SystemLocaleManager
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
import net.thunderbird.feature.applock.api.AppLockGate
import net.thunderbird.feature.applock.api.AppLockResult
import net.thunderbird.feature.applock.api.AppLockState
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
            single<AppLockGate.Factory> { FakeAppLockGateFactory() }
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
    fun `FLAG_SECURE is not set when lock is enabled`() {
        coordinator.setConfigEnabled(true)
        coordinator.stateFlow.value = AppLockState.Locked

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()

        val flags = controller.get().window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
    }

    @Test
    fun `FLAG_SECURE is not set when lock is disabled`() {
        coordinator.setConfigEnabled(false)
        coordinator.stateFlow.value = AppLockState.Disabled

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()

        val flags = controller.get().window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
    }

    @Test
    fun `FLAG_SECURE is not set when state becomes unlocked`() {
        coordinator.setConfigEnabled(true)
        coordinator.stateFlow.value = AppLockState.Locked

        val controller = Robolectric.buildActivity(TestGateActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        // Simulate unlock - Disabled is an unlocked state
        coordinator.stateFlow.value = AppLockState.Disabled
        shadowOf(Looper.getMainLooper()).idle()

        val flags = controller.get().window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
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

        override fun onAppForegrounded() = Unit

        override fun onAppBackgrounded() = Unit

        override fun onScreenOff() = Unit

        override fun lockNow() = Unit

        override fun ensureUnlocked(): Boolean = true

        override fun onSettingsChanged(config: AppLockConfig) {
            currentConfig = config
        }

        override suspend fun authenticate(authenticator: AppLockAuthenticator): AppLockResult {
            return Outcome.Success(Unit)
        }

        fun setConfigEnabled(enabled: Boolean) {
            currentConfig = currentConfig.copy(isEnabled = enabled)
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

    private class FakeAppLockGateFactory : AppLockGate.Factory {
        override fun create(activity: FragmentActivity): AppLockGate {
            return FakeAppLockGate()
        }
    }

    private class FakeAppLockGate : AppLockGate
}
