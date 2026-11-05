package io.github.thegingerbehindu.rehabtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class RehabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashHandler(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHECK_IN_CHANNEL, "Check-ins", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHECK_IN_CHANNEL = "check-ins"
    }
}
