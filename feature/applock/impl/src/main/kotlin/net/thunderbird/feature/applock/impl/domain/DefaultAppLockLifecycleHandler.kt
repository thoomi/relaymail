package net.thunderbird.feature.applock.impl.domain

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Default implementation that registers with ProcessLifecycleOwner
 * and listens for screen-off broadcasts.
 */
internal class DefaultAppLockLifecycleHandler(
    private val application: Application,
) : AppLockLifecycleHandler {

    override fun register(observer: DefaultLifecycleObserver, onScreenOff: () -> Unit) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    onScreenOff()
                }
            }
        }

        ContextCompat.registerReceiver(
            application,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
