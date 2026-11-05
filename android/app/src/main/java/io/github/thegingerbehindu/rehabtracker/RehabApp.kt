package io.github.thegingerbehindu.rehabtracker

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

class RehabApp : Application() {
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
    }

    companion object {
        const val CHECK_IN_CHANNEL = "check-in-alerts"
    }
}
