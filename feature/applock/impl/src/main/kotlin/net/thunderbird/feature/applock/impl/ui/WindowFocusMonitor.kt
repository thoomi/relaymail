package net.thunderbird.feature.applock.impl.ui

import android.view.ViewTreeObserver
import androidx.fragment.app.FragmentActivity

internal interface WindowFocusMonitor {
    val isFocused: Boolean

    fun start(onFocusGained: () -> Unit)

    fun stop()
}

internal class ViewTreeObserverWindowFocusMonitor(
    private val activity: FragmentActivity,
) : WindowFocusMonitor {

    override val isFocused: Boolean
        get() = activity.hasWindowFocus()

    private var onFocusGained: (() -> Unit)? = null
    private var registeredViewTreeObserver: ViewTreeObserver? = null
    private val windowFocusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener(::onWindowFocusChanged)

    override fun start(onFocusGained: () -> Unit) {
        stop()

        this.onFocusGained = onFocusGained
        registeredViewTreeObserver = activity.window.decorView.viewTreeObserver.apply {
            addOnWindowFocusChangeListener(windowFocusChangeListener)
        }
    }

    override fun stop() {
        registeredViewTreeObserver
            ?.takeIf(ViewTreeObserver::isAlive)
            ?.removeOnWindowFocusChangeListener(windowFocusChangeListener)
        registeredViewTreeObserver = null
        onFocusGained = null
    }

    private fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            onFocusGained?.invoke()
        }
    }
}
