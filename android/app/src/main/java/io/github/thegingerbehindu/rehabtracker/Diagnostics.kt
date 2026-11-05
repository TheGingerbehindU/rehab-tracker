package io.github.thegingerbehindu.rehabtracker

import android.content.Context
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A plain-text test log the user can copy and send. It records outcomes only:
 * never access tokens, spreadsheet IDs or entry contents.
 */
object Diagnostics {
    private const val PREFS = "diagnostics"
    private const val KEY_LOG = "log"
    private const val KEY_CRASH = "crash"
    private const val MAX_LINES = 300
    private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
    private val BERLIN = ZoneId.of("Europe/Berlin")

    @Synchronized
    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "${TIME.format(ZonedDateTime.now(BERLIN))}  $message"
        val lines = (prefs.getString(KEY_LOG, "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(MAX_LINES)
        // Receivers and workers can end straight after logging, so this write is synchronous.
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).commit()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "").orEmpty()

    fun lastCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASH, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CRASH, error.stackTraceToString().take(8_000))
                .commit()
            previous?.uncaughtException(thread, error)
        }
    }
}
