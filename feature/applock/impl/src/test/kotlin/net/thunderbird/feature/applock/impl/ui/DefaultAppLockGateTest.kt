package net.thunderbird.feature.applock.impl.ui

import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.core.ui.theme.api.FeatureThemeProvider
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.api.UnavailableReason
import net.thunderbird.feature.applock.impl.domain.FakeAppLockCoordinator
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DefaultAppLockGateTest {

    private lateinit var coordinator: FakeAppLockCoordinator
    private lateinit var testSubject: DefaultAppLockGate

    private val themeProvider = object : FeatureThemeProvider {
        @Composable
        override fun WithTheme(content: @Composable () -> Unit) = content()

        @Composable
        override fun WithTheme(darkTheme: Boolean, content: @Composable () -> Unit) = content()
    }

    @Before
    fun setUp() {
        coordinator = FakeAppLockCoordinator()
    }

    private fun launchActivity(state: AppLockState): ActivityController<TestActivity> {
        coordinator.setState(state)
        val controller = Robolectric.buildActivity(TestActivity::class.java)
        controller.create()
        val activity = controller.get()
        testSubject = DefaultAppLockGate(activity, coordinator, themeProvider)
        activity.lifecycle.addObserver(testSubject)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    @Test
    fun `should show plain overlay when state is Locked`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_plain")
    }

    @Test
    fun `should show content overlay when state is Failed`() {
        val controller = launchActivity(AppLockState.Failed(AppLockError.Failed))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should show content overlay when permanent lockout`() {
        val controller = launchActivity(AppLockState.Failed(AppLockError.Lockout(durationSeconds = -1)))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should show content overlay when temporary lockout`() {
        val controller = launchActivity(AppLockState.Failed(AppLockError.Lockout(durationSeconds = 30)))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should replace failed overlay with plain overlay when state changes to Locked`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Failed(AppLockError.Failed))
        val activity = controller.get()

        var overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")

        coordinator.setState(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_plain")
    }

    @Test
    fun `should hide overlay when state becomes Unlocked`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        assertThat(findOverlay(activity)).isNotNull()

        coordinator.setState(AppLockState.Unlocked())
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `should hide overlay when state becomes Disabled`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        assertThat(findOverlay(activity)).isNotNull()

        coordinator.setState(AppLockState.Disabled)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `should relaunch auth after pause-resume when Unlocking`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        val initialAuthCount = coordinator.authenticateCallCount

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.authenticateCallCount).isEqualTo(initialAuthCount + 1)
    }

    @Test
    fun `should not trigger duplicate auth on pause-resume when auth already completed`() {
        val controller = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        val authCountAfterUnlock = coordinator.authenticateCallCount

        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.authenticateCallCount).isEqualTo(authCountAfterUnlock)
    }

    @Test
    fun `should show content overlay when temporarily unavailable`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should show content overlay when unknown unavailable`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.UNKNOWN))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should show content overlay when no hardware unavailable`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.NO_HARDWARE))
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.tag).isEqualTo("applock_overlay_content")
    }

    @Test
    fun `should show privacy overlay when paused and app lock is enabled`() {
        coordinator.setConfigEnabled(true)
        val controller = launchActivity(AppLockState.Unlocked())
        val activity = controller.get()

        assertThat(findOverlay(activity)).isNull()

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activity)).isNotNull()
    }

    @Test
    fun `should not show privacy overlay when paused and app lock is disabled`() {
        coordinator.setConfigEnabled(false)
        val controller = launchActivity(AppLockState.Disabled)
        val activity = controller.get()

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `should hide privacy overlay when resumed while still unlocked`() {
        coordinator.setConfigEnabled(true)
        val controller = launchActivity(AppLockState.Unlocked())
        val activity = controller.get()

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(findOverlay(activity)).isNotNull()

        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `should show overlay and relaunch auth after activity recreation when Unlocking`() {
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
        val authCountBeforeRecreate = coordinator.authenticateCallCount

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        coordinator.suspendOnAuthenticate()

        val newController = Robolectric.buildActivity(TestActivity::class.java)
        newController.create()
        val newActivity = newController.get()
        val newGate = DefaultAppLockGate(newActivity, coordinator, themeProvider)
        newActivity.lifecycle.addObserver(newGate)
        newController.start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(newActivity)).isNotNull()
        assertThat(coordinator.authenticateCallCount).isEqualTo(authCountBeforeRecreate + 1)
    }

    @Test
    fun `should hide activity B overlay when activity A unlocks`() {
        coordinator.suspendOnAuthenticate()

        val controllerA = launchActivity(AppLockState.Locked)
        val activityA = controllerA.get()
        shadowOf(Looper.getMainLooper()).idle()

        val controllerB = Robolectric.buildActivity(TestActivity::class.java)
        controllerB.create()
        val activityB = controllerB.get()
        val gateB = DefaultAppLockGate(activityB, coordinator, themeProvider)
        activityB.lifecycle.addObserver(gateB)
        controllerB.start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activityA)).isNotNull()
        assertThat(findOverlay(activityB)).isNotNull()

        coordinator.completeAuthenticate(Outcome.Success(Unit))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activityA)).isNull()
        assertThat(findOverlay(activityB)).isNull()
    }

    @Test
    fun `should not show duplicate auth prompt when second activity starts`() {
        coordinator.suspendOnAuthenticate()

        val controllerA = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        val authCountAfterA = coordinator.authenticateCallCount

        val controllerB = Robolectric.buildActivity(TestActivity::class.java)
        controllerB.create()
        val activityB = controllerB.get()
        val gateB = DefaultAppLockGate(activityB, coordinator, themeProvider)
        activityB.lifecycle.addObserver(gateB)
        controllerB.start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.authenticateCallCount).isEqualTo(authCountAfterA)
    }

    @Test
    fun `should not show privacy overlay when paused while Unlocking`() {
        coordinator.setConfigEnabled(true)
        coordinator.suspendOnAuthenticate()
        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
        assertThat(findOverlay(activity)).isNotNull()

        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(findOverlay(activity)).isNotNull()
    }

    private fun findOverlay(activity: FragmentActivity): android.view.View? {
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        for (i in contentView.childCount - 1 downTo 0) {
            val child = contentView.getChildAt(i)
            val tag = child.tag
            if (tag == "applock_overlay_plain" || tag == "applock_overlay_content") {
                return child
            }
        }
        return null
    }

    class TestActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
