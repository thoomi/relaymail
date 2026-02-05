package net.thunderbird.feature.applock.impl.ui

import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.api.UnavailableReason
import net.thunderbird.feature.applock.impl.R
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
    private lateinit var gate: DefaultAppLockGate

    @Before
    fun setUp() {
        coordinator = FakeAppLockCoordinator()
    }

    private fun launchActivity(state: AppLockState): ActivityController<TestActivity> {
        coordinator.setState(state)
        val controller = Robolectric.buildActivity(TestActivity::class.java)
        controller.create()
        val activity = controller.get()
        gate = DefaultAppLockGate(activity, coordinator)
        activity.lifecycle.addObserver(gate)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    @Test
    fun `shows plain overlay when state is Locked`() {
        // Prevent immediate auth success from removing overlay
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        val overlay = findOverlay(activity)
        assertThat(overlay).isNotNull()
        assertThat(overlay!!).isInstanceOf<LinearLayout>()
        // Plain overlay has no children
        assertThat((overlay as ViewGroup).childCount).isEqualTo(0)
    }

    @Test
    fun `shows failed overlay with error message when state is Failed`() {
        val controller = launchActivity(AppLockState.Failed(AppLockError.Failed))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()
        // Failed overlay has children (error text + retry button)
        assertThat(overlay!!.childCount).isEqualTo(2)

        val errorText = overlay.getChildAt(0) as? TextView
        assertThat(errorText).isNotNull()
        assertThat(errorText!!.text.toString()).contains("failed")
    }

    @Test
    fun `shows permanent lockout message for permanent lockout`() {
        // -1 indicates permanent lockout
        val controller = launchActivity(AppLockState.Failed(AppLockError.Lockout(durationSeconds = -1)))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val errorText = overlay!!.getChildAt(0) as? TextView
        assertThat(errorText).isNotNull()
        // Should show permanent lockout message, not "try again in X seconds"
        val text = errorText!!.text.toString()
        assertThat(text).contains("PIN")
    }

    @Test
    fun `shows temporary lockout message with duration`() {
        val controller = launchActivity(AppLockState.Failed(AppLockError.Lockout(durationSeconds = 30)))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val errorText = overlay!!.getChildAt(0) as? TextView
        assertThat(errorText).isNotNull()
        assertThat(errorText!!.text.toString()).contains("30")
    }

    @Test
    fun `retry button calls ensureUnlocked`() {
        // Prevent immediate auth success after retry
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Failed(AppLockError.Failed))
        val activity = controller.get()

        val initialCallCount = coordinator.ensureUnlockedCallCount

        val overlay = findOverlay(activity) as? ViewGroup
        val retryButton = overlay?.getChildAt(1) as? Button
        assertThat(retryButton).isNotNull()
        retryButton!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.ensureUnlockedCallCount).isEqualTo(initialCallCount + 1)
    }

    @Test
    fun `replaces failed overlay with plain overlay when state changes to Locked`() {
        // Prevent immediate auth success when state changes to Locked
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Failed(AppLockError.Failed))
        val activity = controller.get()

        // Verify we have failed overlay
        var overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay!!.childCount).isEqualTo(2) // Failed overlay has children

        // Change state to Locked
        coordinator.setState(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is now plain (no children)
        overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.childCount).isEqualTo(0) // Plain overlay
    }

    @Test
    fun `hides overlay when state becomes Unlocked`() {
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        // Verify overlay exists
        assertThat(findOverlay(activity)).isNotNull()

        // Change state to Unlocked
        coordinator.setState(AppLockState.Unlocked())
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is removed
        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `hides overlay when state becomes Disabled`() {
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        // Verify overlay exists
        assertThat(findOverlay(activity)).isNotNull()

        // Change state to Disabled
        coordinator.setState(AppLockState.Disabled)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is removed
        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `auth relaunches after pause-resume while Unlocking`() {
        // Suspend authentication so we can control the flow
        coordinator.suspendOnAuthenticate()

        val controller = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        val initialAuthCount = coordinator.authenticateCallCount

        // Pause and resume - should relaunch auth since it was interrupted
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        // Auth should have been relaunched
        assertThat(coordinator.authenticateCallCount).isEqualTo(initialAuthCount + 1)
    }

    @Test
    fun `no duplicate auth on pause-resume when auth already completed`() {
        // Let auth complete immediately
        val controller = launchActivity(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        val authCountAfterUnlock = coordinator.authenticateCallCount

        // State should be Unlocked after successful auth
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocked>()

        // Pause and resume
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        // No additional auth calls since already unlocked
        assertThat(coordinator.authenticateCallCount).isEqualTo(authCountAfterUnlock)
    }

    @Test
    fun `shows temporary unavailable hint when auth hardware is unavailable`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val hintText = overlay!!.getChildAt(1) as? TextView
        assertThat(hintText).isNotNull()
        assertThat(hintText!!.text.toString()).contains("temporarily unavailable")
    }

    @Test
    fun `temporary unavailable shows retry button and refreshes availability`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val retryButton = overlay!!.getChildAt(2) as? Button
        assertThat(retryButton).isNotNull()
        assertThat(
            retryButton!!.text.toString(),
        ).isEqualTo(activity.getString(R.string.applock_button_try_again))

        val initialRefreshCalls = coordinator.refreshAvailabilityCallCount
        retryButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.refreshAvailabilityCallCount).isEqualTo(initialRefreshCalls + 1)
    }

    @Test
    fun `retry from unavailable requests unlock when refresh transitions to locked`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.UNKNOWN))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val retryButton = overlay!!.getChildAt(2) as? Button
        assertThat(retryButton).isNotNull()

        coordinator.setStateAfterRefresh(AppLockState.Locked)
        val initialEnsureUnlockedCalls = coordinator.ensureUnlockedCallCount
        retryButton!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.ensureUnlockedCallCount).isEqualTo(initialEnsureUnlockedCalls + 1)
    }

    @Test
    fun `shows unknown unavailable hint when reason is unknown`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.UNKNOWN))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val hintText = overlay!!.getChildAt(1) as? TextView
        assertThat(hintText).isNotNull()
        assertThat(hintText!!.text.toString()).contains("Unable to determine")
    }

    @Test
    fun `no hardware unavailable shows close app button only`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.NO_HARDWARE))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()
        assertThat(overlay!!.childCount).isEqualTo(3)

        val closeButton = overlay.getChildAt(2) as? Button
        assertThat(closeButton).isNotNull()
        assertThat(closeButton!!.text.toString()).isEqualTo(activity.getString(R.string.applock_button_close_app))
    }

    @Test
    fun `unavailable overlay shows close app button and closes app`() {
        val controller = launchActivity(AppLockState.Unavailable(UnavailableReason.NO_HARDWARE))
        val activity = controller.get()

        val overlay = findOverlay(activity) as? ViewGroup
        assertThat(overlay).isNotNull()

        val closeButton = overlay!!.getChildAt(2) as? Button
        assertThat(closeButton).isNotNull()
        assertThat(closeButton!!.text.toString()).isEqualTo(activity.getString(R.string.applock_button_close_app))

        closeButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun `shows privacy overlay when paused and app lock is enabled`() {
        coordinator.setConfigEnabled(true)
        val controller = launchActivity(AppLockState.Unlocked())
        val activity = controller.get()

        // Verify no overlay when unlocked and in foreground
        assertThat(findOverlay(activity)).isNull()

        // Pause the activity
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        // Privacy overlay should be shown
        assertThat(findOverlay(activity)).isNotNull()
    }

    @Test
    fun `no privacy overlay when paused and app lock is disabled`() {
        coordinator.setConfigEnabled(false)
        val controller = launchActivity(AppLockState.Disabled)
        val activity = controller.get()

        // Pause the activity
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        // No overlay should be shown (app lock disabled)
        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `hides privacy overlay when resumed while still unlocked`() {
        coordinator.setConfigEnabled(true)
        val controller = launchActivity(AppLockState.Unlocked())
        val activity = controller.get()

        // Pause - shows privacy overlay
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(findOverlay(activity)).isNotNull()

        // Resume - hides privacy overlay since still unlocked
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(findOverlay(activity)).isNull()
    }

    @Test
    fun `no privacy overlay when paused while Unlocking`() {
        coordinator.setConfigEnabled(true)
        coordinator.suspendOnAuthenticate()
        val controller = launchActivity(AppLockState.Locked)
        val activity = controller.get()

        // Should be in Unlocking state with lock overlay showing
        assertThat(coordinator.state.value).isInstanceOf<AppLockState.Unlocking>()
        assertThat(findOverlay(activity)).isNotNull()

        // Pause while Unlocking - should keep existing lock overlay, not switch to privacy behavior
        controller.pause()
        shadowOf(Looper.getMainLooper()).idle()

        // Overlay should still be present (lock overlay, not privacy overlay)
        assertThat(findOverlay(activity)).isNotNull()
    }

    private fun findOverlay(activity: FragmentActivity): android.view.View? {
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        // The overlay is added as the last child of the content view
        for (i in contentView.childCount - 1 downTo 0) {
            val child = contentView.getChildAt(i)
            if (child is LinearLayout) {
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
