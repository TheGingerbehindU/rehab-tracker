package io.github.thegingerbehindu.rehabtracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Settings {
    private const val PREFS = "settings"
    private const val KEY_TEST_SHEET = "test_sheet"

    fun testSheet(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEST_SHEET, "").orEmpty()

    fun setTestSheet(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TEST_SHEET, value).apply()
    }
}

/**
 * Checks, while the app is closed, whether Google still grants access without
 * asking the user. Repeating it over several days shows whether sign-in lasts.
 */
class BackgroundAccessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val auth = GoogleAccess.authorize(applicationContext)
            val token = auth.accessToken
            if (auth.hasResolution() || token == null) {
                Diagnostics.log(applicationContext, "Background check: Google wants the user to sign in again")
                return Result.success()
            }
            val sheetId = parseSpreadsheetId(Settings.testSheet(applicationContext))
            if (sheetId == null) {
                Diagnostics.log(applicationContext, "Background check: signed in without a prompt; no test sheet set")
                return Result.success()
            }
            SheetsClient(token).readTitle(sheetId)
            Diagnostics.log(applicationContext, "Background check: read the test sheet without a prompt")
        } catch (error: Exception) {
            Diagnostics.log(applicationContext, "Background check failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        return Result.success()
    }

    companion object {
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundAccessWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("background-access", ExistingPeriodicWorkPolicy.UPDATE, request)
            Diagnostics.log(context, "Background check started: runs about every 12 hours")
        }
    }
}
