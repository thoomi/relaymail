package net.thunderbird.feature.applock.impl.ui

import android.os.Build
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.impl.R
import net.thunderbird.feature.applock.impl.domain.FakeAppLockCoordinator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DefaultAppLockGateTest {

    private lateinit var coordinator: FakeAppLockCoordinator
    private lateinit var gate: DefaultAppLockGate
    private lateinit var scenario: ActivityScenario<TestActivity>

    @Before
    fun setUp() {
        coordinator = FakeAppLockCoordinator()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun `shows plain overlay when state is Locked`() {
        coordinator.setState(AppLockState.Locked)
        // Prevent immediate auth success from removing overlay
        coordinator.suspendOnAuthenticate()

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val overlay = findOverlay(activity)
            assertThat(overlay).isNotNull()
            assertThat(overlay).isInstanceOf<LinearLayout>()
            // Plain overlay has no children
            assertThat((overlay as ViewGroup).childCount).isEqualTo(0)
        }
    }

    @Test
    fun `shows failed overlay with error message when state is Failed`() {
        coordinator.setState(AppLockState.Failed(AppLockError.Failed))

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            assertThat(overlay).isNotNull()
            // Failed overlay has children (error text + retry button)
            assertThat(overlay!!.childCount).isEqualTo(2)

            val errorText = overlay.getChildAt(0) as? TextView
            assertThat(errorText).isNotNull()
            assertThat(errorText!!.text.toString()).contains("failed")
        }
    }

    @Test
    fun `shows permanent lockout message for permanent lockout`() {
        // -1 indicates permanent lockout
        coordinator.setState(AppLockState.Failed(AppLockError.Lockout(durationSeconds = -1)))

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            assertThat(overlay).isNotNull()

            val errorText = overlay!!.getChildAt(0) as? TextView
            assertThat(errorText).isNotNull()
            // Should show permanent lockout message, not "try again in X seconds"
            val text = errorText!!.text.toString()
            assertThat(text).contains("PIN")
        }
    }

    @Test
    fun `shows temporary lockout message with duration`() {
        coordinator.setState(AppLockState.Failed(AppLockError.Lockout(durationSeconds = 30)))

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            assertThat(overlay).isNotNull()

            val errorText = overlay!!.getChildAt(0) as? TextView
            assertThat(errorText).isNotNull()
            assertThat(errorText!!.text.toString()).contains("30")
        }
    }

    @Test
    fun `retry button calls ensureUnlocked`() {
        coordinator.setState(AppLockState.Failed(AppLockError.Failed))
        // Prevent immediate auth success after retry
        coordinator.suspendOnAuthenticate()

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        val initialCallCount = coordinator.ensureUnlockedCallCount

        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            val retryButton = overlay?.getChildAt(1) as? Button
            assertThat(retryButton).isNotNull()
            retryButton!!.performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(coordinator.ensureUnlockedCallCount).isEqualTo(initialCallCount + 1)
    }

    @Test
    fun `replaces failed overlay with plain overlay when state changes to Locked`() {
        coordinator.setState(AppLockState.Failed(AppLockError.Failed))
        // Prevent immediate auth success when state changes to Locked
        coordinator.suspendOnAuthenticate()

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify we have failed overlay
        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            assertThat(overlay!!.childCount).isEqualTo(2) // Failed overlay has children
        }

        // Change state to Locked
        coordinator.setState(AppLockState.Locked)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is now plain (no children)
        scenario.onActivity { activity ->
            val overlay = findOverlay(activity) as? ViewGroup
            assertThat(overlay).isNotNull()
            assertThat(overlay!!.childCount).isEqualTo(0) // Plain overlay
        }
    }

    @Test
    fun `hides overlay when state becomes Unlocked`() {
        coordinator.setState(AppLockState.Locked)
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay exists
        scenario.onActivity { activity ->
            assertThat(findOverlay(activity)).isNotNull()
        }

        // Change state to Unlocked
        coordinator.setState(AppLockState.Unlocked())
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is removed
        scenario.onActivity { activity ->
            assertThat(findOverlay(activity)).isNull()
        }
    }

    @Test
    fun `hides overlay when state becomes Disabled`() {
        coordinator.setState(AppLockState.Locked)
        // Prevent immediate auth success so we can verify overlay exists first
        coordinator.suspendOnAuthenticate()

        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { activity ->
            gate = DefaultAppLockGate(activity, coordinator)
            activity.lifecycle.addObserver(gate)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay exists
        scenario.onActivity { activity ->
            assertThat(findOverlay(activity)).isNotNull()
        }

        // Change state to Disabled
        coordinator.setState(AppLockState.Disabled)
        shadowOf(Looper.getMainLooper()).idle()

        // Verify overlay is removed
        scenario.onActivity { activity ->
            assertThat(findOverlay(activity)).isNull()
        }
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
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
