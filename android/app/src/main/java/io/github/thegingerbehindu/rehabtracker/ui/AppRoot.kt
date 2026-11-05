@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.thegingerbehindu.rehabtracker.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thegingerbehindu.rehabtracker.AppPrefs
import io.github.thegingerbehindu.rehabtracker.CheckInAlarms
import io.github.thegingerbehindu.rehabtracker.R
import io.github.thegingerbehindu.rehabtracker.data.*
import io.github.thegingerbehindu.rehabtracker.domain.APP_ZONE
import io.github.thegingerbehindu.rehabtracker.domain.BackupFormatException
import io.github.thegingerbehindu.rehabtracker.domain.backupToJson
import io.github.thegingerbehindu.rehabtracker.domain.checkInInputs
import io.github.thegingerbehindu.rehabtracker.domain.dueCheckIn
import io.github.thegingerbehindu.rehabtracker.domain.parseBackup
import io.github.thegingerbehindu.rehabtracker.domain.pretty
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM", java.util.Locale.ENGLISH).withZone(APP_ZONE)
private val LONG_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", java.util.Locale.ENGLISH)

private enum class Tab(val label: String, val icon: Int) {
    TODAY("Today", R.drawable.ic_today),
    HISTORY("History", R.drawable.ic_history),
    SETTINGS("Settings", R.drawable.ic_settings),
}

private sealed interface Route {
    data object Tabs : Route
    data class EditSession(val id: String?, val kind: SessionKind) : Route
    data class EditEvent(val id: String?) : Route
    data class EditCheckIn(val id: String?) : Route
    data object Symptoms : Route
    data object SetupTests : Route
}

fun sessionTitle(session: Session): String {
    val parts = session.partSet().joinToString(" + ") { it.label }
    return when {
        session.legacyType != null -> session.legacyType
        session.kind == SessionKind.TRAINING && parts.isNotEmpty() -> "Training: $parts"
        else -> session.kind.label
    }
}

private enum class EntryType(val color: Color) {
    TRAINING(Color(0xFF2457D6)),
    REHAB(Color(0xFF0F7F6E)),
    EVENT(Color(0xFF6F3FB5)),
    CHECK_IN(Color(0xFFA85A00)),
}

private data class TimelineEntry(val id: String, val type: EntryType, val at: Long, val title: String, val detail: String)

private fun buildTimeline(
    sessions: List<Session>,
    events: List<SymptomEvent>,
    checkIns: List<CheckIn>,
    readings: List<SymptomReading>,
    symptoms: List<Symptom>,
): List<TimelineEntry> {
    val names = symptoms.associate { it.id to it.name }
    val bySource = readings.groupBy { it.sourceId }

    fun scoreSummary(sourceId: String): String = bySource[sourceId].orEmpty()
        .groupBy { it.symptomId }
        .entries
        .joinToString(", ") { (symptomId, values) ->
            "${names[symptomId] ?: "Symptom"} ${values.sortedBy { it.recordedAt }.joinToString("→") { it.score.pretty() }}"
        }

    fun join(vararg values: String?) = values.filterNot { it.isNullOrBlank() }.joinToString(" · ")

    val sessionEntries = sessions.map { session ->
        TimelineEntry(
            id = session.id,
            type = if (session.kind == SessionKind.REHAB) EntryType.REHAB else EntryType.TRAINING,
            at = session.startedAt,
            title = sessionTitle(session),
            detail = join(
                session.durationMinutes?.let { "$it min" },
                session.effort?.let { "effort ${it.pretty()}" },
                scoreSummary(session.id),
                session.content.takeIf { session.durationMinutes == null && session.effort == null },
            ),
        )
    }
    val eventEntries = events.map { event ->
        TimelineEntry(
            id = event.id,
            type = EntryType.EVENT,
            at = event.occurredAt,
            title = "Symptom event: ${event.context}",
            detail = join(event.change, scoreSummary(event.id), event.description),
        )
    }
    val checkInEntries = checkIns.map { checkIn ->
        TimelineEntry(
            id = checkIn.id,
            type = EntryType.CHECK_IN,
            at = checkIn.recordedAt,
            title = "Check-in",
            detail = join(checkIn.response, scoreSummary(checkIn.id), checkIn.sleepHours?.let { "${it.pretty()} h sleep" }),
        )
    }
    return (sessionEntries + eventEntries + checkInEntries).sortedByDescending { it.at }
}

@Composable
fun AppRoot(dao: RehabDao, resumeCount: Int, openCheckInToken: Int, setupTests: @Composable (onBack: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var tab by remember { mutableStateOf(Tab.TODAY) }

    val symptoms by dao.observeSymptoms().collectAsState(initial = emptyList())
    val sessions by dao.observeSessions().collectAsState(initial = emptyList())
    val events by dao.observeEvents().collectAsState(initial = emptyList())
    val checkIns by dao.observeCheckIns().collectAsState(initial = emptyList())
    val readings by dao.observeReadings().collectAsState(initial = emptyList())
    val coveredIds by dao.observeCoveredSessionIds().collectAsState(initial = emptyList())

    LaunchedEffect(openCheckInToken) {
        if (openCheckInToken > 0) route = Route.EditCheckIn(null)
    }

    val onDeleted: (String, suspend () -> Unit) -> Unit = { message, undo ->
        route = Route.Tabs
        scope.launch {
            if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) undo()
        }
    }
    val close = { route = Route.Tabs }

    val timeline = remember(sessions, events, checkIns, readings, symptoms) {
        buildTimeline(sessions, events, checkIns, readings, symptoms)
    }

    fun openEntry(entry: TimelineEntry) {
        route = when (entry.type) {
            EntryType.EVENT -> Route.EditEvent(entry.id)
            EntryType.CHECK_IN -> Route.EditCheckIn(entry.id)
            EntryType.REHAB -> Route.EditSession(entry.id, SessionKind.REHAB)
            EntryType.TRAINING -> Route.EditSession(entry.id, SessionKind.TRAINING)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route == Route.Tabs) {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (val current = route) {
                Route.Tabs -> when (tab) {
                    Tab.TODAY -> TodayScreen(
                        timeline = timeline,
                        sessions = sessions,
                        coveredIds = coveredIds,
                        checkIns = checkIns,
                        hasOtherEntries = symptoms.isNotEmpty() || events.isNotEmpty(),
                        refreshKey = resumeCount,
                        navigate = { route = it },
                        openEntry = ::openEntry,
                    )
                    Tab.HISTORY -> HistoryScreen(timeline, ::openEntry)
                    Tab.SETTINGS -> SettingsScreen(dao, navigate = { route = it })
                }
                is Route.EditSession -> SessionEditor(current.id, current.kind, dao, symptoms, close, onDeleted)
                is Route.EditEvent -> EventEditor(current.id, dao, symptoms, sessions, close, onDeleted)
                is Route.EditCheckIn -> CheckInEditor(current.id, dao, symptoms, sessions, readings, close, onDeleted)
                Route.Symptoms -> SymptomsScreen(dao, symptoms, close)
                Route.SetupTests -> {
                    BackHandler(onBack = close)
                    setupTests(close)
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(
    timeline: List<TimelineEntry>,
    sessions: List<Session>,
    coveredIds: List<String>,
    checkIns: List<CheckIn>,
    hasOtherEntries: Boolean,
    refreshKey: Int,
    navigate: (Route) -> Unit,
    openEntry: (TimelineEntry) -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(refreshKey) {
        while (true) {
            now = Instant.now()
            delay(60_000)
        }
    }
    val due = dueCheckIn(
        checkInInputs(
            now = now,
            morningTime = AppPrefs.morningTime(context),
            morningOnRestDays = AppPrefs.morningOnRestDays(context),
            sessions = sessions,
            coveredIds = coveredIds,
            checkIns = checkIns,
            hasOtherEntries = hasOtherEntries,
        ),
    )
    val today = now.atZone(APP_ZONE).toLocalDate()
    val todayEntries = timeline.filter { Instant.ofEpochMilli(it.at).atZone(APP_ZONE).toLocalDate() == today }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(today.format(LONG_DAY_FORMAT), style = MaterialTheme.typography.titleLarge)

        if (due != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1D6), contentColor = Color(0xFF3A2500)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Check-in due", style = MaterialTheme.typography.titleMedium)
                    Text("How is your body feeling since your last sessions?")
                    Button(onClick = { navigate(Route.EditCheckIn(null)) }, modifier = Modifier.fillMaxWidth()) { Text("Check in now") }
                }
            }
        }

        Button(onClick = { navigate(Route.EditSession(null, SessionKind.TRAINING)) }, modifier = Modifier.fillMaxWidth()) { Text("Log Training") }
        Button(
            onClick = { navigate(Route.EditSession(null, SessionKind.REHAB)) },
            colors = ButtonDefaults.buttonColors(containerColor = EntryType.REHAB.color, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log Rehab") }
        OutlinedButton(onClick = { navigate(Route.EditEvent(null)) }, modifier = Modifier.fillMaxWidth()) { Text("Symptom event") }
        if (due == null) {
            TextButton(onClick = { navigate(Route.EditCheckIn(null)) }) { Text("Check in now") }
        }

        Text("Today", style = MaterialTheme.typography.titleMedium)
        if (todayEntries.isEmpty()) Text("Nothing logged yet today.")
        todayEntries.forEach { TimelineRow(it, openEntry) }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, onOpen: (TimelineEntry) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(entry) }) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(TIME_FORMAT.format(Instant.ofEpochMilli(entry.at)), style = MaterialTheme.typography.labelLarge, color = entry.type.color)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                if (entry.detail.isNotBlank()) {
                    Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(timeline: List<TimelineEntry>, openEntry: (TimelineEntry) -> Unit) {
    val byDay: Map<LocalDate, List<TimelineEntry>> = remember(timeline) {
        timeline.groupBy { Instant.ofEpochMilli(it.at).atZone(APP_ZONE).toLocalDate() }
    }
    if (timeline.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("Your sessions, symptom events and check-ins will collect here.") }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byDay.forEach { (day, entries) ->
            item(key = day.toString()) {
                Text(day.format(LONG_DAY_FORMAT), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(entries, key = { it.id }) { TimelineRow(it, openEntry) }
        }
    }
}

@Composable
private fun SettingsScreen(dao: RehabDao, navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var morning by remember { mutableStateOf(AppPrefs.morningTime(context)) }
    var restDays by remember { mutableStateOf(AppPrefs.morningOnRestDays(context)) }
    var message by remember { mutableStateOf("") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val now = System.currentTimeMillis()
                val text = backupToJson(dao.exportBundle(), now)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                "Backup saved."
            }.getOrElse { "Could not save the backup: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                val now = System.currentTimeMillis()
                val parsed = parseBackup(text, now)
                val added = dao.importBundle(parsed.bundle, now)
                CheckInAlarms.reschedule(context)
                buildString {
                    append("Imported ${added.sessions} sessions, ${added.events} symptom events and ${added.checkIns} check-ins.")
                    val already = parsed.bundle.sessions.size + parsed.bundle.events.size + parsed.bundle.checkIns.size -
                        added.sessions - added.events - added.checkIns
                    if (already > 0) append(" $already were already here.")
                    if (parsed.skippedEmpty > 0) append(" ${parsed.skippedEmpty} empty rest rows were skipped.")
                    if (parsed.skippedInvalid > 0) append(" ${parsed.skippedInvalid} entries couldn't be read and were skipped.")
                }
            } catch (error: BackupFormatException) {
                error.message.orEmpty()
            } catch (error: Exception) {
                "Could not import this file: ${error.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)

        SectionTitle("Check-ins")
        OutlinedButton(onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val time = LocalTime.of(hour, minute)
                    morning = time
                    AppPrefs.setMorningTime(context, time)
                    scope.launch { CheckInAlarms.reschedule(context) }
                },
                morning.hour,
                morning.minute,
                true,
            ).show()
        }) { Text("Morning check-in: ${"%02d:%02d".format(morning.hour, morning.minute)}") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = restDays, onCheckedChange = {
                restDays = it
                AppPrefs.setMorningOnRestDays(context, it)
                scope.launch { CheckInAlarms.reschedule(context) }
            })
            Text("Also ask on rest days")
        }

        SectionTitle("Symptoms")
        OutlinedButton(onClick = { navigate(Route.Symptoms) }) { Text("Manage symptoms") }

        SectionTitle("Backup")
        Text("Android won't back this data up for you, so export regularly. Keep backups somewhere private.")
        OutlinedButton(onClick = { exporter.launch("rehab-tracker-backup-${LocalDate.now(APP_ZONE)}.json") }) { Text("Export backup") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Import backup") }
        Text("Import also reads backups from the web app. Importing twice doesn't create duplicates.", style = MaterialTheme.typography.bodySmall)

        SectionTitle("Testing")
        OutlinedButton(onClick = { navigate(Route.SetupTests) }) { Text("Setup tests") }
    }
}

@Composable
private fun SymptomsScreen(dao: RehabDao, symptoms: List<Symptom>, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Symptom?>(null) }
    var name by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Symptoms", style = MaterialTheme.typography.titleLarge)
        }
        Text("Symptoms leave your forms when they reach 0. Turn on \"Keep at 0\" for one that only appears under load.")
        if (symptoms.isEmpty()) Text("No symptoms yet. Add one when you log a session.")
        symptoms.forEach { symptom ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(symptom.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (symptom.status == SymptomStatus.ACTIVE) "Being tracked" else "Resolved", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = symptom.keepAtZero, onCheckedChange = {
                            scope.launch { dao.updateSymptom(symptom.id, symptom.name, it, System.currentTimeMillis()) }
                        })
                        Text("Keep at 0")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            name = symptom.name
                            renaming = symptom
                        }) { Text("Rename") }
                        if (symptom.status == SymptomStatus.RESOLVED) {
                            OutlinedButton(onClick = { scope.launch { dao.reactivateSymptom(symptom.id, System.currentTimeMillis()) } }) { Text("Back again") }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { symptom ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename symptom") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { dao.updateSymptom(symptom.id, name.trim(), symptom.keepAtZero, System.currentTimeMillis()) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
