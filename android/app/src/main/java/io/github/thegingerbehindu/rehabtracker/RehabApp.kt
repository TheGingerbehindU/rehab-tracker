package io.github.thegingerbehindu.rehabtracker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RehabApp : Application() {
    /** For saves that must finish even if the screen that started them has gone. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashHandler(this)
        val manager = getSystemService(NotificationManager::class.java)
        // A channel's settings can't change after creation, so this replaces the first test channel.
        manager.deleteNotificationChannel("check-ins")
        manager.createNotificationChannel(
            NotificationChannel(CHECK_IN_CHANNEL, "Check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        scope.launch {
            runCatching { CheckInAlarms.reschedule(this@RehabApp) }
                .onFailure { Diagnostics.log(this@RehabApp, "Could not set the check-in alarm: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        const val CHECK_IN_CHANNEL = "check-in-alerts"
    }
}
