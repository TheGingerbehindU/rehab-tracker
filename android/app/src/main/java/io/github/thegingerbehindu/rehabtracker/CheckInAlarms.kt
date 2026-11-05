package io.github.thegingerbehindu.rehabtracker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.thegingerbehindu.rehabtracker.data.RehabDatabase
import io.github.thegingerbehindu.rehabtracker.domain.CheckInInputs
import io.github.thegingerbehindu.rehabtracker.domain.checkInInputs
import io.github.thegingerbehindu.rehabtracker.domain.dueCheckIn
import io.github.thegingerbehindu.rehabtracker.domain.nextCheckInAlarm
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.launch

object AppPrefs {
    private const val PREFS = "app"
    private const val KEY_MORNING_MINUTES = "morning_minutes"
    private const val KEY_REST_DAYS = "morning_on_rest_days"

    fun morningTime(context: Context): LocalTime =
        LocalTime.ofSecondOfDay(prefs(context).getInt(KEY_MORNING_MINUTES, 8 * 60) * 60L)

    fun setMorningTime(context: Context, time: LocalTime) {
        prefs(context).edit().putInt(KEY_MORNING_MINUTES, time.hour * 60 + time.minute).apply()
    }

    fun morningOnRestDays(context: Context): Boolean = prefs(context).getBoolean(KEY_REST_DAYS, true)

    fun setMorningOnRestDays(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REST_DAYS, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object CheckInAlarms {
    private const val NOTIFICATION_ID = 2

    suspend fun currentInputs(context: Context, now: Instant = Instant.now()): CheckInInputs {
        val dao = RehabDatabase.get(context).dao()
        return checkInInputs(
            now = now,
            morningTime = AppPrefs.morningTime(context),
            morningOnRestDays = AppPrefs.morningOnRestDays(context),
            sessions = dao.sessions(),
            coveredIds = dao.coveredSessionIds(),
            checkIns = dao.checkIns(),
            hasOtherEntries = dao.symptomCount() > 0 || dao.eventCount() > 0,
        )
    }

    /** Call after anything that can change when a check-in is due. */
    suspend fun reschedule(context: Context) {
        val next = nextCheckInAlarm(currentInputs(context))
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            100,
            Intent(context, CheckInAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
        }
    }

    suspend fun onAlarm(context: Context) {
        val due = dueCheckIn(currentInputs(context))
        Diagnostics.log(context, "Check-in alarm ran; due: ${due?.kind ?: "nothing"}")
        if (due != null) showNotification(context)
        reschedule(context)
    }

    fun clearNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun showNotification(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .putExtra(Alarms.EXTRA_OPEN_CHECK_IN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            Notification.Builder(context, RehabApp.CHECK_IN_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                // Shown in full on the lock screen, so this text must never contain health details.
                .setContentTitle("Check-in due")
                .setContentText("Tap to record how you feel.")
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class CheckInAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        (appContext as RehabApp).scope.launch {
            try {
                CheckInAlarms.onAlarm(appContext)
            } catch (error: Exception) {
                Diagnostics.log(appContext, "Check-in alarm failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
