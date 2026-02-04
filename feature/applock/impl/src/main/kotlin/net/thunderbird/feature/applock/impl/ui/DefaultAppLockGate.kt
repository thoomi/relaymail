package net.thunderbird.feature.applock.impl.ui

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.thunderbird.core.outcome.Outcome
import net.thunderbird.feature.applock.api.AppLockCoordinator
import net.thunderbird.feature.applock.api.AppLockError
import net.thunderbird.feature.applock.api.AppLockGate
import net.thunderbird.feature.applock.api.AppLockState
import net.thunderbird.feature.applock.api.UnavailableReason
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
    private var authenticationJob: Job? = null
    private var isResumed: Boolean = false

    override fun onStart(owner: LifecycleOwner) {
        // Start observing state changes to update overlay and trigger auth if needed
        stateObserverJob = activity.lifecycleScope.launch {
            coordinator.state.collect { state ->
                // Update overlay based on state
                when {
                    state.isUnlocked() -> hideLockOverlay()
                    state is AppLockState.Failed -> showFailedOverlay(state.error)
                    state is AppLockState.Unavailable -> showUnavailableOverlay(state.reason)
                    else -> showLockOverlay()
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
        authenticationJob?.cancel()
        authenticationJob = null
    }

    override fun onStop(owner: LifecycleOwner) {
        stateObserverJob?.cancel()
        stateObserverJob = null
        authenticationJob?.cancel()
        authenticationJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        hideLockOverlay()
        lastAttemptId = null
        authenticationJob?.cancel()
        authenticationJob = null
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
            AppLockState.Locked -> {
                // Request unlock - coordinator will transition to Unlocking
                if (coordinator.ensureUnlocked()) {
                    val newState = coordinator.state.value
                    if (newState is AppLockState.Unlocking) {
                        lastAttemptId = newState.attemptId
                        launchAuthentication()
                    }
                }
            }
            is AppLockState.Failed -> {
                // Don't auto-retry on failure to prevent infinite prompt loop.
                // User can close app and reopen to retry. Overlay remains visible.
            }
            is AppLockState.Unavailable -> {
                // Auth unavailable - show guidance overlay, no auth to trigger
            }
            AppLockState.Disabled, is AppLockState.Unlocked -> {
                // Nothing to do
            }
        }
    }

    private fun launchAuthentication() {
        // Don't launch if already in progress
        if (authenticationJob?.isActive == true) return

        val authenticator = BiometricAuthenticator(
            activity = activity,
            title = activity.getString(R.string.applock_prompt_title),
            subtitle = activity.getString(R.string.applock_prompt_subtitle),
        )

        authenticationJob = activity.lifecycleScope.launch {
            try {
                val result = coordinator.authenticate(authenticator)
                if (result is Outcome.Failure && result.error is AppLockError.Canceled) {
                    activity.finishAffinity()
                }
            } finally {
                authenticationJob = null
            }
        }
    }

    private fun showLockOverlay() {
        // Check if we already have a plain lock overlay (no children)
        // If we have a failed overlay (with children), replace it
        val existingOverlay = lockOverlay as? ViewGroup
        if (existingOverlay != null && existingOverlay.childCount == 0) {
            return // Already showing plain lock overlay
        }

        // Remove any existing overlay (e.g., failed overlay with error text)
        hideLockOverlay()

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = LinearLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(getThemeBackgroundColor())
            isFocusable = true
            isClickable = true
        }

        contentView.addView(overlay)
        lockOverlay = overlay
    }

    private fun showFailedOverlay(error: AppLockError) {
        hideLockOverlay()

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = LinearLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getThemeBackgroundColor())
            isFocusable = true
            isClickable = true

            val errorMessage = TextView(activity).apply {
                text = getErrorMessage(error)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(48, 16, 48, 16)
            }
            addView(errorMessage)

            val retryButton = Button(activity).apply {
                text = activity.getString(R.string.applock_button_unlock)
                setOnClickListener { onRetryClicked() }
            }
            addView(retryButton)
        }

        contentView.addView(overlay)
        lockOverlay = overlay
    }

    private fun showUnavailableOverlay(reason: UnavailableReason) {
        hideLockOverlay()

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val overlay = LinearLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getThemeBackgroundColor())
            isFocusable = true
            isClickable = true

            val titleView = TextView(activity).apply {
                text = activity.getString(R.string.applock_requirements_title)
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(48, 16, 48, 8)
            }
            addView(titleView)

            val hintView = TextView(activity).apply {
                text = getUnavailableHint(reason)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(48, 8, 48, 16)
            }
            addView(hintView)

            val openSettingsButton = Button(activity).apply {
                text = activity.getString(R.string.applock_button_open_settings)
                setOnClickListener { openSecuritySettings() }
            }
            addView(openSettingsButton)
        }

        contentView.addView(overlay)
        lockOverlay = overlay
    }

    private fun getUnavailableHint(reason: UnavailableReason): String {
        return when (reason) {
            UnavailableReason.NO_HARDWARE -> activity.getString(R.string.applock_error_not_available)
            UnavailableReason.NOT_ENROLLED -> activity.getString(R.string.applock_requirements_hint)
        }
    }

    private fun openSecuritySettings() {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        activity.startActivity(intent)
    }

    private fun onRetryClicked() {
        // Just request unlock - the state collector will observe the transition
        // to Unlocking and trigger authentication via triggerAuthenticationIfNeeded()
        coordinator.ensureUnlocked()
    }

    private fun getErrorMessage(error: AppLockError): String {
        return when (error) {
            is AppLockError.NotAvailable -> activity.getString(R.string.applock_error_not_available)
            is AppLockError.NotEnrolled -> activity.getString(R.string.applock_error_not_enrolled)
            is AppLockError.Failed -> activity.getString(R.string.applock_error_failed)
            is AppLockError.Canceled -> activity.getString(R.string.applock_error_canceled)
            is AppLockError.Interrupted -> activity.getString(R.string.applock_error_failed)
            is AppLockError.Lockout -> {
                when {
                    error.durationSeconds < 0 -> {
                        // Permanent lockout - user must unlock device to reset
                        activity.getString(R.string.applock_error_lockout_permanent)
                    }
                    error.durationSeconds > 0 -> {
                        // Temporary lockout with known duration
                        activity.getString(R.string.applock_error_lockout, error.durationSeconds)
                    }
                    else -> {
                        // Temporary lockout with unknown duration
                        activity.getString(R.string.applock_error_lockout_unknown)
                    }
                }
            }
            is AppLockError.UnableToStart -> {
                activity.getString(R.string.applock_error_unable_to_start, error.message)
            }
        }
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
