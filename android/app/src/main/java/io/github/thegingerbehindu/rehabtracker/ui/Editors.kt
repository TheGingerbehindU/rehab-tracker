@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.thegingerbehindu.rehabtracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.thegingerbehindu.rehabtracker.CheckInAlarms
import io.github.thegingerbehindu.rehabtracker.RehabApp
import io.github.thegingerbehindu.rehabtracker.data.*
import io.github.thegingerbehindu.rehabtracker.domain.baselineScores
import io.github.thegingerbehindu.rehabtracker.domain.dueCheckIn
import io.github.thegingerbehindu.rehabtracker.domain.findSymptomByName
import io.github.thegingerbehindu.rehabtracker.domain.pretty
import io.github.thegingerbehindu.rehabtracker.domain.symptomsForForm
import io.github.thegingerbehindu.rehabtracker.domain.uncoveredSessions
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val HOUR_MILLIS = 3_600_000.0

@Composable
private fun rememberSymptomActions(dao: RehabDao, symptoms: List<Symptom>): SymptomActionHandlers {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(symptoms)
    return remember(dao) {
        SymptomActionHandlers(
            onBackAgain = { symptom -> scope.launch { dao.reactivateSymptom(symptom.id, System.currentTimeMillis()) } },
            onNew = { name ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    val existing = findSymptomByName(latest, name)
                    if (existing != null) {
                        dao.reactivateSymptom(existing.id, now)
                    } else {
                        dao.addSymptom(Symptom(id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now))
                    }
                }
            },
        )
    }
}

private class SymptomActionHandlers(val onBackAgain: (Symptom) -> Unit, val onNew: (String) -> Unit)

private fun Map<String, Double>.toReadings(sourceId: String, source: ReadingSource, at: Long, now: Long) = map { (symptomId, score) ->
    SymptomReading(
        id = readingId(sourceId, source, symptomId),
        symptomId = symptomId,
        score = score,
        recordedAt = at,
        source = source,
        sourceId = sourceId,
        updatedAt = now,
    )
}

private fun MutableMap<String, Double>.setScore(symptomId: String, value: Double?) {
    if (value == null) remove(symptomId) else put(symptomId, value)
}

@Composable
fun SessionEditor(
    id: String?,
    kind: SessionKind,
    dao: RehabDao,
    symptoms: List<Symptom>,
    onDone: () -> Unit,
    onDeleted: (String, suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RehabApp
    val scope = rememberCoroutineScope()
    val draftId = remember { id ?: UUID.randomUUID().toString() }
    val actions = rememberSymptomActions(dao, symptoms)

    var existing by remember { mutableStateOf<Session?>(null) }
    var saved by remember { mutableStateOf(id != null) }
    var loaded by remember { mutableStateOf(id == null) }
    var dirty by remember { mutableStateOf(false) }
    var currentKind by remember { mutableStateOf(kind) }
    var parts by remember { mutableStateOf(setOf<SessionPart>()) }
    var startedAt by remember { mutableStateOf(Instant.now()) }
    var duration by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf<Double?>(null) }
    var content by remember { mutableStateOf("") }
    var functionMarker by remember { mutableStateOf("") }
    var symptomDetails by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val before = remember { mutableStateMapOf<String, Double>() }
    val after = remember { mutableStateMapOf<String, Double>() }

    LaunchedEffect(draftId) {
        if (id == null) return@LaunchedEffect
        dao.session(id)?.let { value ->
            existing = value
            currentKind = value.kind
            parts = value.partSet()
            startedAt = Instant.ofEpochMilli(value.startedAt)
            duration = value.durationMinutes?.toString().orEmpty()
            effort = value.effort
            content = value.content
            functionMarker = value.functionMarker
            symptomDetails = value.symptomDetails
            notes = value.notes
        }
        dao.readingsFor(id).forEach { reading ->
            when (reading.source) {
                ReadingSource.SESSION_BEFORE -> before[reading.symptomId] = reading.score
                ReadingSource.SESSION_AFTER -> after[reading.symptomId] = reading.score
                else -> Unit
            }
        }
        loaded = true
    }

    suspend fun save() {
        val now = System.currentTimeMillis()
        val minutes = duration.toDoubleOrNull()?.roundToInt()
        val start = startedAt.toEpochMilli()
        val finish = start + maxOf((minutes ?: 0) * 60_000L, 1_000L)
        val readings = before.toMap().toReadings(draftId, ReadingSource.SESSION_BEFORE, start, now) +
            after.toMap().toReadings(draftId, ReadingSource.SESSION_AFTER, finish, now)
        dao.saveSession(
            Session(
                id = draftId,
                kind = currentKind,
                parts = if (currentKind == SessionKind.TRAINING) parts.toPartsColumn() else "",
                startedAt = start,
                durationMinutes = minutes,
                effort = effort,
                content = content.trim(),
                functionMarker = functionMarker.trim(),
                symptomDetails = symptomDetails.trim(),
                notes = notes.trim(),
                legacyType = existing?.legacyType,
                updatedAt = now,
                revision = existing?.revision ?: 0,
            ),
            readings,
            now,
        )
        saved = true
        CheckInAlarms.reschedule(context)
    }

    fun leave() {
        scope.launch {
            if (dirty) save()
            onDone()
        }
    }

    OnAppHidden {
        if (dirty) {
            dirty = false
            app.scope.launch { save() }
        }
    }

    EditorLayout(
        title = if (id == null) "Log ${currentKind.label}" else currentKind.label,
        onBack = ::leave,
        onSave = {
            dirty = true
            leave()
        },
        onDelete = if (saved) {
            {
                dirty = false
                scope.launch {
                    dao.setSessionDeleted(draftId, true, System.currentTimeMillis())
                    CheckInAlarms.reschedule(context)
                    onDeleted("${currentKind.label} deleted") {
                        dao.setSessionDeleted(draftId, false, System.currentTimeMillis())
                        CheckInAlarms.reschedule(context)
                    }
                }
            }
        } else {
            null
        },
    ) {
        if (!loaded) return@EditorLayout

        DateTimeRow(startedAt) {
            startedAt = it
            dirty = true
        }

        if (id == null) {
            TextButton(onClick = {
                scope.launch {
                    dao.latestSession(currentKind)?.let { last ->
                        parts = last.partSet()
                        duration = last.durationMinutes?.toString().orEmpty()
                        effort = last.effort
                        content = last.content
                        dirty = true
                    }
                }
            }) { Text("Repeat last ${currentKind.label.lowercase()}") }
        }

        if (currentKind == SessionKind.TRAINING) {
            SectionTitle("What was in it?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionPart.entries.forEach { part ->
                    FilterChip(
                        selected = part in parts,
                        onClick = {
                            parts = if (part in parts) parts - part else parts + part
                            dirty = true
                        },
                        label = { Text(part.label) },
                    )
                }
            }
        }

        NumberField("Duration", duration, Modifier.fillMaxWidth(), suffix = "min") {
            duration = it
            dirty = true
        }
        ScoreRow("Effort", effort, min = 1) {
            effort = it
            dirty = true
        }

        SectionTitle("Symptoms")
        val shown = symptomsForForm(symptoms, before.keys + after.keys)
        if (shown.isEmpty()) Text("Nothing tracked right now. Add a symptom if something is bothering you.")
        shown.forEach { symptom ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(symptom.name, style = MaterialTheme.typography.titleSmall)
                ScoreRow("Before", before[symptom.id]) {
                    before.setScore(symptom.id, it)
                    dirty = true
                }
                ScoreRow("After", after[symptom.id]) {
                    after.setScore(symptom.id, it)
                    dirty = true
                }
            }
        }
        SymptomActions(symptoms.filter { it.status == SymptomStatus.RESOLVED && it.id !in before.keys && it.id !in after.keys }, actions.onBackAgain, actions.onNew)

        ExpandableDetails {
            OutlinedTextField(content, { content = it; dirty = true }, label = { Text("What did you do?") }, placeholder = { Text("Exercises, sets, reps and loads") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(functionMarker, { functionMarker = it; dirty = true }, label = { Text("Function or movement marker") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(symptomDetails, { symptomDetails = it; dirty = true }, label = { Text("Where and what did you notice?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it; dirty = true }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun EventEditor(
    id: String?,
    dao: RehabDao,
    symptoms: List<Symptom>,
    sessions: List<Session>,
    onDone: () -> Unit,
    onDeleted: (String, suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RehabApp
    val scope = rememberCoroutineScope()
    val draftId = remember { id ?: UUID.randomUUID().toString() }
    val actions = rememberSymptomActions(dao, symptoms)

    var saved by remember { mutableStateOf(id != null) }
    var loaded by remember { mutableStateOf(id == null) }
    var dirty by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var occurredAt by remember { mutableStateOf(Instant.now()) }
    var eventContext by remember { mutableStateOf<String?>(null) }
    var change by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var symptomDetails by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") }
    var helped by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(setOf<String>()) }
    val scores = remember { mutableStateMapOf<String, Double>() }
    // Scores imported from the web app use separate before/after sources, which are kept as they are.
    var olderReadings by remember { mutableStateOf(listOf<SymptomReading>()) }

    LaunchedEffect(draftId) {
        if (id == null) return@LaunchedEffect
        dao.event(id)?.let { value ->
            revision = value.revision
            occurredAt = Instant.ofEpochMilli(value.occurredAt)
            eventContext = value.context
            change = value.change
            description = value.description
            symptomDetails = value.symptomDetails
            affected = value.affected
            helped = value.helped
            duration = value.durationMinutes?.toString().orEmpty()
        }
        val readings = dao.readingsFor(id)
        readings.filter { it.source == ReadingSource.EVENT_NOW }.forEach { scores[it.symptomId] = it.score }
        olderReadings = readings.filter { it.source != ReadingSource.EVENT_NOW }
        links = dao.linkedSessionIds(id).toSet()
        loaded = true
    }

    suspend fun save() {
        val now = System.currentTimeMillis()
        val at = occurredAt.toEpochMilli()
        dao.saveEvent(
            SymptomEvent(
                id = draftId,
                occurredAt = at,
                context = eventContext ?: "Other",
                change = change,
                description = description.trim(),
                symptomDetails = symptomDetails.trim(),
                affected = affected.trim(),
                helped = helped.trim(),
                durationMinutes = duration.toDoubleOrNull()?.roundToInt(),
                updatedAt = now,
                revision = revision,
            ),
            scores.toMap().toReadings(draftId, ReadingSource.EVENT_NOW, at, now) + olderReadings,
            links.toList(),
            now,
        )
        saved = true
        CheckInAlarms.reschedule(context)
    }

    fun leave() {
        scope.launch {
            if (dirty) save()
            onDone()
        }
    }

    OnAppHidden {
        if (dirty) {
            dirty = false
            app.scope.launch { save() }
        }
    }

    EditorLayout(
        title = if (id == null) "Symptom event" else "Symptom event",
        onBack = ::leave,
        onSave = {
            dirty = true
            leave()
        },
        onDelete = if (saved) {
            {
                dirty = false
                scope.launch {
                    dao.setEventDeleted(draftId, true, System.currentTimeMillis())
                    onDeleted("Symptom event deleted") { dao.setEventDeleted(draftId, false, System.currentTimeMillis()) }
                }
            }
        } else {
            null
        },
    ) {
        if (!loaded) return@EditorLayout

        DateTimeRow(occurredAt) {
            occurredAt = it
            dirty = true
        }

        SectionTitle("What were you doing?")
        ChoiceChips(EVENT_CONTEXTS, eventContext) {
            eventContext = it
            dirty = true
        }

        SectionTitle("What changed?")
        ChoiceChips(EVENT_CHANGES, change) {
            change = it
            dirty = true
        }

        SectionTitle("How are your symptoms now?")
        val shown = symptomsForForm(symptoms, scores.keys)
        if (shown.isEmpty()) Text("Nothing tracked right now. Add the symptom you're feeling.")
        shown.forEach { symptom ->
            ScoreRow(symptom.name, scores[symptom.id]) {
                scores.setScore(symptom.id, it)
                dirty = true
            }
        }
        SymptomActions(symptoms.filter { it.status == SymptomStatus.RESOLVED && it.id !in scores.keys }, actions.onBackAgain, actions.onNew)

        SectionTitle("Could it be linked to a session?")
        val cutoff = occurredAt.toEpochMilli() - 48 * 3_600_000L
        val candidates = sessions.filter { (it.startedAt in cutoff..occurredAt.toEpochMilli()) || it.id in links }
        if (candidates.isEmpty()) {
            Text("No sessions in the last 48 hours.")
        } else {
            Text("Leave these unselected if you're unsure.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.sortedByDescending { it.startedAt }.forEach { session ->
                    val at = Instant.ofEpochMilli(session.startedAt)
                    FilterChip(
                        selected = session.id in links,
                        onClick = {
                            links = if (session.id in links) links - session.id else links + session.id
                            dirty = true
                        },
                        label = { Text("${sessionTitle(session)} · ${DAY_FORMAT.format(at)} ${TIME_FORMAT.format(at)}") },
                    )
                }
            }
        }

        ExpandableDetails {
            OutlinedTextField(description, { description = it; dirty = true }, label = { Text("What happened?") }, placeholder = { Text("For example: sitting at a restaurant for about an hour") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            NumberField("Time in that situation", duration, Modifier.fillMaxWidth(), suffix = "min") {
                duration = it
                dirty = true
            }
            OutlinedTextField(symptomDetails, { symptomDetails = it; dirty = true }, label = { Text("Where and what did you feel?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(affected, { affected = it; dirty = true }, label = { Text("What did it affect?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(helped, { helped = it; dirty = true }, label = { Text("What helped, or what happened afterward?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CheckInEditor(
    id: String?,
    dao: RehabDao,
    symptoms: List<Symptom>,
    sessions: List<Session>,
    readings: List<SymptomReading>,
    onDone: () -> Unit,
    onDeleted: (String, suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RehabApp
    val scope = rememberCoroutineScope()
    val draftId = remember { id ?: UUID.randomUUID().toString() }
    val actions = rememberSymptomActions(dao, symptoms)

    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<CheckIn?>(null) }
    var kind by remember { mutableStateOf(CheckInKind.MANUAL) }
    var scheduledFor by remember { mutableStateOf<Long?>(null) }
    var recordedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var covered by remember { mutableStateOf(listOf<Session>()) }
    var response by remember { mutableStateOf<String?>(null) }
    var sleep by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val scores = remember { mutableStateMapOf<String, Double>() }

    LaunchedEffect(draftId) {
        if (id != null) {
            dao.checkIn(id)?.let { value ->
                existing = value
                kind = value.kind
                scheduledFor = value.scheduledFor
                recordedAt = value.recordedAt
                response = value.response
                sleep = value.sleepHours?.pretty().orEmpty()
                plan = value.nextDayPlan
                notes = value.notes
            }
            dao.readingsFor(id).forEach { scores[it.symptomId] = it.score }
            val coveredIds = dao.coveredBy(id).toSet()
            covered = dao.sessions().filter { it.id in coveredIds }.sortedBy { it.startedAt }
        } else {
            val due = dueCheckIn(CheckInAlarms.currentInputs(context))
            kind = due?.kind ?: CheckInKind.MANUAL
            scheduledFor = due?.scheduledFor?.toEpochMilli()
            covered = uncoveredSessions(dao.sessions(), dao.coveredSessionIds())
        }
        loaded = true
    }

    suspend fun save() {
        val now = System.currentTimeMillis()
        val value = CheckIn(
            id = draftId,
            kind = kind,
            scheduledFor = scheduledFor,
            recordedAt = recordedAt,
            response = response,
            sleepHours = sleep.toDoubleOrNull(),
            nextDayPlan = plan.trim(),
            notes = notes.trim(),
            updatedAt = now,
            revision = existing?.revision ?: 0,
        )
        dao.saveCheckIn(value, scores.toMap().toReadings(draftId, ReadingSource.CHECK_IN, recordedAt, now), covered.map { it.id }, now)
        existing = value
        CheckInAlarms.clearNotification(context)
        CheckInAlarms.reschedule(context)
    }

    fun finish() {
        scope.launch {
            save()
            onDone()
        }
    }

    fun leave() {
        val hasAnswer = response != null
        when {
            !dirty -> onDone()
            hasAnswer -> finish()
            else -> confirmLeave = true
        }
    }

    OnAppHidden {
        if (dirty && response != null) {
            dirty = false
            app.scope.launch { save() }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Finish this check-in?") },
            text = { Text("It's saved once you've chosen how your body responded. If you leave now, what you've entered won't be kept.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; onDone() }) { Text("Leave") } },
        )
    }

    EditorLayout(
        title = "Check-in",
        saveLabel = "Save check-in",
        saveEnabled = response != null,
        onBack = ::leave,
        onSave = ::finish,
        onDelete = if (existing != null) {
            {
                dirty = false
                scope.launch {
                    dao.setCheckInDeleted(draftId, true, System.currentTimeMillis())
                    CheckInAlarms.reschedule(context)
                    onDeleted("Check-in deleted") {
                        dao.setCheckInDeleted(draftId, false, System.currentTimeMillis())
                        CheckInAlarms.reschedule(context)
                    }
                }
            }
        } else {
            null
        },
    ) {
        if (!loaded) return@EditorLayout

        SectionTitle("Since your last check-in")
        if (covered.isEmpty()) {
            Text("No sessions to cover.")
        } else {
            covered.forEach { session ->
                val at = Instant.ofEpochMilli(session.startedAt)
                Text("${sessionTitle(session)} · ${DAY_FORMAT.format(at)} ${TIME_FORMAT.format(at)}")
            }
            val hours = (recordedAt - covered.maxOf { it.startedAt }) / HOUR_MILLIS
            Text("${hours.coerceAtLeast(0.0).roundToInt()} hours since the latest one.", style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("How are your symptoms now?")
        val baselines = baselineScores(covered, readings)
        val shown = symptomsForForm(symptoms, scores.keys)
        if (shown.isEmpty()) Text("Nothing tracked right now.")
        shown.forEach { symptom ->
            val baseline = baselines[symptom.id]
            val score = scores[symptom.id]
            ScoreRow(symptom.name, score) {
                scores.setScore(symptom.id, it)
                dirty = true
            }
            if (baseline != null) {
                val change = score?.let { it - baseline }
                Text(
                    "Before: ${baseline.pretty()}" + if (change != null) " · change ${if (change > 0) "+" else ""}${change.pretty()}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SymptomActions(symptoms.filter { it.status == SymptomStatus.RESOLVED && it.id !in scores.keys }, actions.onBackAgain, actions.onNew)

        SectionTitle("How did your body respond?")
        ChoiceChips(RESPONSES, response) {
            response = it
            dirty = true
        }

        NumberField("Sleep", sleep, Modifier.fillMaxWidth(), suffix = "hours") {
            sleep = it
            dirty = true
        }

        ExpandableDetails {
            OutlinedTextField(plan, { plan = it; dirty = true }, label = { Text("Next-day decision or adjustment") }, placeholder = { Text("Continue, reduce, rest or change the plan") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it; dirty = true }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }
}
