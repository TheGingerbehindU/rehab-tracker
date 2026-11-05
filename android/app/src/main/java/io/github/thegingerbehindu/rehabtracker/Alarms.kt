package io.github.thegingerbehindu.rehabtracker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Describes how late an alarm fired, for the test log. */
fun describeLateness(scheduledAtMillis: Long, firedAtMillis: Long): String {
    val seconds = (firedAtMillis - scheduledAtMillis) / 1_000
    return when {
        seconds < 60 -> "${seconds}s late"
        else -> "${seconds / 60}m ${seconds % 60}s late"
    }
}

object Alarms {
    private const val PREFS = "alarms"
    private const val KEY_DUE_AT = "due_at"
    const val EXTRA_OPEN_CHECK_IN = "open_check_in"

    fun dueAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DUE_AT, 0L)

    fun canUseExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun scheduleIn(context: Context, minutes: Int) {
        val dueAt = System.currentTimeMillis() + minutes * 60_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DUE_AT, dueAt).commit()
        set(context, dueAt)
        Diagnostics.log(context, "Test alarm set for $minutes min (exact allowed: ${canUseExact(context)})")
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DUE_AT).commit()
        Diagnostics.log(context, "Test alarm cancelled")
    }

    /** Alarms are wiped by a reboot, so the pending one is set again from storage. */
    fun restoreAfterBoot(context: Context) {
        val dueAt = dueAt(context)
        Diagnostics.log(context, "Phone restarted or app updated; pending test alarm: ${dueAt > 0}")
        if (dueAt > 0) set(context, maxOf(dueAt, System.currentTimeMillis() + 1_000))
    }

    fun onFired(context: Context) {
        val now = System.currentTimeMillis()
        val dueAt = dueAt(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DUE_AT).commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        Diagnostics.log(
            context,
            "Alarm fired ${if (dueAt > 0) describeLateness(dueAt, now) else "(no due time)"}; notifications allowed: ${manager.areNotificationsEnabled()}",
        )

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_CHECK_IN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            1,
            Notification.Builder(context, RehabApp.CHECK_IN_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Rehab Tracker test")
                .setContentText("Tap to confirm this opens the check-in.")
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                // Shown in full on the lock screen, so this text must never contain health details.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun set(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context))
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alarms.onFired(context)
}

/** Restarting the phone or updating the app clears alarms. Starting the app sets the check-in alarm again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Alarms.restoreAfterBoot(context)
        }
    }
}
