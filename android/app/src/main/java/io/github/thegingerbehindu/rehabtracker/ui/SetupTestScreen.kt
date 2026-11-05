@file:OptIn(ExperimentalLayoutApi::class)

package io.github.thegingerbehindu.rehabtracker.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import io.github.thegingerbehindu.rehabtracker.Alarms
import io.github.thegingerbehindu.rehabtracker.BackgroundAccessWorker
import io.github.thegingerbehindu.rehabtracker.Diagnostics
import io.github.thegingerbehindu.rehabtracker.GoogleAccess
import io.github.thegingerbehindu.rehabtracker.Settings
import io.github.thegingerbehindu.rehabtracker.SheetsClient
import io.github.thegingerbehindu.rehabtracker.parseSpreadsheetId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun SetupTestScreen(resumeCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var testSheet by remember { mutableStateOf(Settings.testSheet(context)) }
    var token by remember { mutableStateOf<String?>(null) }
    val refreshKey = resumeCount + refresh

    fun note(message: String) {
        Diagnostics.log(context, message)
        status = message
        refresh += 1
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh += 1 }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
            .onSuccess {
                token = it.accessToken
                note(if (it.accessToken != null) "Signed in after Google's prompt" else "Sign-in returned no access")
            }
            .onFailure { note("Sign-in was not completed: ${it.message}") }
    }

    fun withSheets(label: String, action: suspend (SheetsClient) -> String) {
        val current = token
        if (current == null) {
            status = "Sign in first."
            return
        }
        scope.launch {
            note(runCatching { "$label: ${action(SheetsClient(current))}" }.getOrElse { "$label failed: ${it.message}" })
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Setup tests", style = MaterialTheme.typography.headlineSmall)

        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)

        Section("1. Notifications") {
            val state = remember(refreshKey) { readPhoneState(context) }
            Text(state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 15, 60).forEach { minutes ->
                    Button(onClick = {
                        Alarms.scheduleIn(context, minutes)
                        status = "Alarm set. Close the app and lock the phone."
                        refresh += 1
                    }) { Text("Test in $minutes min") }
                }
                OutlinedButton(onClick = {
                    Alarms.cancel(context)
                    refresh += 1
                }) { Text("Cancel") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }) { Text("Allow exact alarms") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("App settings") }
            }
        }

        Section("2. Google access") {
            Button(onClick = {
                scope.launch {
                    runCatching { GoogleAccess.authorize(context) }
                        .onSuccess { result ->
                            if (result.hasResolution()) {
                                signIn.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                            } else {
                                token = result.accessToken
                                note("Signed in without a prompt")
                            }
                        }
                        .onFailure { note("Sign-in failed: ${it.message}") }
                }
            }) { Text(if (token == null) "Sign in with Google" else "Signed in") }

            OutlinedTextField(
                value = testSheet,
                onValueChange = {
                    testSheet = it
                    Settings.setTestSheet(context, it)
                },
                label = { Text("TEST sheet link (gets written to)") },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val id = parseSpreadsheetId(testSheet)
                    if (id == null) status = "That isn't a Google Sheets link." else withSheets("Test sheet write") { client ->
                        if (client.writeAndVerify(id)) "row written and read back" else "row did not read back"
                    }
                }) { Text("Write test row") }
                OutlinedButton(onClick = {
                    withSheets("New sheet") { client ->
                        val id = client.createSpreadsheet("Rehab Tracker Android test")
                        testSheet = id
                        Settings.setTestSheet(context, id)
                        if (client.writeAndVerify(id)) "created, row written and read back" else "created, but row did not read back"
                    }
                }) { Text("Create new test sheet") }
            }
            OutlinedButton(onClick = {
                BackgroundAccessWorker.start(context)
                status = "Background check started."
                refresh += 1
            }) { Text("Start background check") }
        }

        Section("3. Report") {
            val crash = remember(refreshKey) { Diagnostics.lastCrash(context) }
            val log = remember(refreshKey) { Diagnostics.read(context) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val report = buildReport(context)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Rehab Tracker report", report))
                    status = "Report copied. Paste it into the chat with Claude."
                }) { Text("Copy report") }
                OutlinedButton(onClick = {
                    Diagnostics.clear(context)
                    refresh += 1
                }) { Text("Clear") }
            }
            if (crash != null) Text("A crash was recorded and is included in the report.", color = MaterialTheme.colorScheme.error)
            Text(log.ifEmpty { "Nothing recorded yet." }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun readPhoneState(context: Context): String {
    val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val dueAt = Alarms.dueAt(context)
    val pending = if (dueAt > 0) {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/Berlin")).format(Instant.ofEpochMilli(dueAt))
    } else {
        "none"
    }
    return "Notifications allowed: ${yesNo(notifications)}\n" +
        "Exact alarms allowed: ${yesNo(Alarms.canUseExact(context))}\n" +
        "Battery unrestricted: ${yesNo(battery)}\n" +
        "Pending test alarm: $pending"
}

private fun buildReport(context: Context): String {
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    return buildString {
        appendLine("Rehab Tracker $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine(readPhoneState(context))
        appendLine()
        appendLine(Diagnostics.read(context))
        Diagnostics.lastCrash(context)?.let {
            appendLine()
            appendLine("Last crash:")
            appendLine(it)
        }
    }
}

private fun yesNo(value: Boolean) = if (value) "yes" else "no"
