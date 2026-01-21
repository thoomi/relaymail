package net.thunderbird.feature.applock.impl.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockCoordinator
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockGate
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.api.isUnlocked
import net.thunderbird.feature.applock.impl.R
import net.thunderbird.feature.applock.impl.domain.BiometricAuthenticator

/**
 * Default implementation of [AppLockGate] that handles lock overlay and biometric authentication.
 *
 * This class observes the app lock coordinator state and:
 * - Shows/hides a lock overlay based on lock state
 * - Triggers biometric authentication when the activity resumes in a locked state
 * - Handles authentication results (success finishes normally, cancel closes app)
 */
internal class DefaultAppLockGate(
    private val activity: FragmentActivity,
    private val coordinator: AppLockCoordinator,
) : AppLockGate {

    private var lockOverlay: View? = null
    private var lastAttemptId: Long? = null
    private var stateObserverJob: Job? = null
    private var isResumed: Boolean = false

    override fun onStart(owner: LifecycleOwner) {
        // Start observing state changes to update overlay and trigger auth if needed
        stateObserverJob = activity.lifecycleScope.launch {
            coordinator.state.collect { state ->
                // Update overlay visibility
                if (state.isUnlocked()) {
                    hideLockOverlay()
                } else {
                    showLockOverlay()
                }

                // Trigger authentication if activity is resumed and we're in a locked state
                if (isResumed) {
                    triggerAuthenticationIfNeeded()
                }
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        isResumed = true
        triggerAuthenticationIfNeeded()
    }

    override fun onPause(owner: LifecycleOwner) {
        isResumed = false
    }

    override fun onStop(owner: LifecycleOwner) {
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        hideLockOverlay()
        lastAttemptId = null
    }

    private fun triggerAuthenticationIfNeeded() {
        when (val state = coordinator.state.value) {
            is AppLockState.Unlocking -> {
                val attemptId = state.attemptId
                if (attemptId != lastAttemptId) {
                    lastAttemptId = attemptId
                    launchAuthentication()
                }
            }
            AppLockState.Locked, is AppLockState.Failed -> {
                // Request unlock - coordinator will transition to Unlocking
                if (coordinator.ensureUnlocked()) {
                    val newState = coordinator.state.value
                    if (newState is AppLockState.Unlocking) {
                        lastAttemptId = newState.attemptId
                        launchAuthentication()
                    }
                }
            }
            AppLockState.Disabled, is AppLockState.Unlocked -> {
                // Nothing to do
            }
        }
    }

    private fun launchAuthentication() {
        val authenticator = BiometricAuthenticator(
            activity = activity,
            title = activity.getString(R.string.applock_prompt_title),
            subtitle = activity.getString(R.string.applock_prompt_subtitle),
        )

        activity.lifecycleScope.launch {
            val result = coordinator.authenticate(authenticator)
            if (result is Outcome.Failure && result.error is AppLockError.Canceled) {
                activity.finishAffinity()
            }
        }
    }

    private fun showLockOverlay() {
        if (lockOverlay != null) return

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(getThemeBackgroundColor())
            isFocusable = true
            isClickable = true
        }

        contentView.addView(overlay)
        lockOverlay = overlay
    }

    private fun hideLockOverlay() {
        lockOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            lockOverlay = null
        }
    }

    private fun getThemeBackgroundColor(): Int {
        val typedArray = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val color = typedArray.getColor(0, 0xFF000000.toInt())
        typedArray.recycle()
        return color
    }
}

/**
 * Factory for creating [DefaultAppLockGate] instances.
 */
internal class DefaultAppLockGateFactory(
    private val coordinator: AppLockCoordinator,
) : AppLockGate.Factory {
    override fun create(activity: FragmentActivity): AppLockGate {
        return DefaultAppLockGate(activity, coordinator)
    }
}
