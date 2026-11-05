package io.github.thegingerbehindu.rehabtracker.domain

import io.github.thegingerbehindu.rehabtracker.data.CheckIn
import io.github.thegingerbehindu.rehabtracker.data.CheckInKind
import io.github.thegingerbehindu.rehabtracker.data.CheckInSession
import io.github.thegingerbehindu.rehabtracker.data.EventSessionLink
import io.github.thegingerbehindu.rehabtracker.data.ReadingSource
import io.github.thegingerbehindu.rehabtracker.data.Session
import io.github.thegingerbehindu.rehabtracker.data.SessionKind
import io.github.thegingerbehindu.rehabtracker.data.SessionPart
import io.github.thegingerbehindu.rehabtracker.data.Symptom
import io.github.thegingerbehindu.rehabtracker.data.SymptomEvent
import io.github.thegingerbehindu.rehabtracker.data.SymptomReading
import io.github.thegingerbehindu.rehabtracker.data.SymptomStatus
import io.github.thegingerbehindu.rehabtracker.data.SyncStatus
import io.github.thegingerbehindu.rehabtracker.data.readingId
import io.github.thegingerbehindu.rehabtracker.data.toPartsColumn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class BackupBundle(
    val symptoms: List<Symptom> = emptyList(),
    val readings: List<SymptomReading> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val events: List<SymptomEvent> = emptyList(),
    val links: List<EventSessionLink> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val coverage: List<CheckInSession> = emptyList(),
)

data class ParsedBackup(val bundle: BackupBundle, val skippedEmpty: Int, val skippedInvalid: Int)

class BackupFormatException(message: String) : Exception(message)

const val BACKUP_SCHEMA_VERSION = 4
const val LEGACY_SYMPTOM_ID = "general-before-tracking"
const val LEGACY_SYMPTOM_NAME = "General (before tracking)"

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else get(key).toString()
private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else get(key).toString()
private fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
private fun JSONObject.long(key: String): Long? = if (isNull(key)) null else optLong(key)
private fun JSONObject.int(key: String): Int? = number(key)?.roundToInt()
private fun JSONObject.items(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

/** Reads a backup from this app (version 4) or from the web app (versions 1 to 3). */
fun parseBackup(text: String, now: Long): ParsedBackup {
    val root = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw BackupFormatException("This file isn't a Rehab Tracker backup.")
    }
    return when (root.optInt("schemaVersion", 0)) {
        1, 2, 3 -> parseWebBackup(root, now)
        BACKUP_SCHEMA_VERSION -> ParsedBackup(parseOwnBackup(root), 0, 0)
        else -> throw BackupFormatException("This backup version isn't supported.")
    }
}

// ---------- Web app backups ----------

private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private fun mapWebType(type: String): Pair<SessionKind, Set<SessionPart>> = when (type) {
    "Rehab" -> SessionKind.REHAB to emptySet()
    "Strength" -> SessionKind.TRAINING to setOf(SessionPart.LIFT)
    else -> SessionKind.TRAINING to setOf(SessionPart.OTHER)
}

private fun parseWebBackup(root: JSONObject, now: Long): ParsedBackup {
    if (root.optJSONArray("records") == null) throw BackupFormatException("This backup has no entries.")
    val sessions = mutableListOf<Session>()
    val events = mutableListOf<SymptomEvent>()
    val checkIns = mutableListOf<CheckIn>()
    val coverage = mutableListOf<CheckInSession>()
    val readings = mutableListOf<SymptomReading>()
    var skippedEmpty = 0
    var skippedInvalid = 0

    fun reading(sourceId: String, source: ReadingSource, score: Double?, at: Long) {
        if (score == null) return
        readings += SymptomReading(
            id = readingId(sourceId, source, LEGACY_SYMPTOM_ID),
            symptomId = LEGACY_SYMPTOM_ID,
            score = score,
            recordedAt = at,
            source = source,
            sourceId = sourceId,
            updatedAt = now,
        )
    }

    root.items("records").forEach { record ->
        val id = record.text("id")
        val date = runCatching { LocalDate.parse(record.text("date")) }.getOrNull()
        val at = parseInstant(record.text("sessionAt"))
            ?: date?.let { ZonedDateTime.of(it, LocalTime.NOON, APP_ZONE).toInstant() }
        if (id.isEmpty() || at == null) {
            skippedInvalid += 1
            return@forEach
        }
        val startedAt = at.toEpochMilli()
        val before = record.number("symptomsBefore")
        val after = record.number("symptomsAfter")
        val duration = record.int("durationMinutes")

        if (record.text("entryType") == "symptom_event") {
            events += SymptomEvent(
                id = id,
                occurredAt = startedAt,
                context = record.textOrNull("eventContext") ?: "Other",
                change = record.textOrNull("eventChange"),
                description = record.text("sessionContent"),
                symptomDetails = record.text("symptomDetails"),
                affected = record.text("functionMarker"),
                helped = record.text("notes"),
                durationMinutes = duration,
                updatedAt = now,
            )
            reading(id, ReadingSource.EVENT_BEFORE, before, startedAt - 1_000)
            reading(id, ReadingSource.EVENT_AFTER, after, startedAt)
            return@forEach
        }

        val type = record.text("sessionType")
        val effort = record.number("rpe")
        val score24h = record.number("symptoms24h")
        val response = record.textOrNull("response24h")
        val sleep = record.number("sleepHours")
        val plan = record.text("nextDayPlan")
        val complete = record.optBoolean("followUpComplete", false)
        val content = record.text("sessionContent")

        // The web app's old model could hold a "rest" row for a day with nothing recorded on it.
        val isEmptyRest = type == "Recovery / rest" && content.isBlank() && duration == null && effort == null &&
            before == null && after == null && score24h == null && response == null
        if (isEmptyRest) {
            skippedEmpty += 1
            return@forEach
        }

        val (kind, parts) = mapWebType(type)
        sessions += Session(
            id = id,
            kind = kind,
            parts = parts.toPartsColumn(),
            startedAt = startedAt,
            durationMinutes = duration,
            effort = effort,
            content = content,
            functionMarker = record.text("functionMarker"),
            symptomDetails = record.text("symptomDetails"),
            notes = record.text("notes"),
            legacyType = type.ifEmpty { "Unknown" },
            updatedAt = now,
        )
        reading(id, ReadingSource.SESSION_BEFORE, before, startedAt)
        reading(id, ReadingSource.SESSION_AFTER, after, startedAt + maxOf((duration ?: 0) * 60_000L, 1_000L))

        if (score24h != null || response != null || sleep != null || plan.isNotBlank() || complete) {
            val checkInId = "legacy-$id"
            val due = parseInstant(record.text("followUpDueAt"))?.toEpochMilli() ?: (startedAt + 86_400_000L)
            val recordedAt = parseInstant(record.text("followUpRecordedAt"))?.toEpochMilli() ?: due
            checkIns += CheckIn(
                id = checkInId,
                kind = CheckInKind.LEGACY,
                scheduledFor = due,
                recordedAt = recordedAt,
                response = response,
                sleepHours = sleep,
                nextDayPlan = plan,
                updatedAt = now,
            )
            coverage += CheckInSession(checkInId, id)
            reading(checkInId, ReadingSource.CHECK_IN, score24h, recordedAt)
        }
    }

    val symptoms = if (readings.isEmpty()) {
        emptyList()
    } else {
        listOf(Symptom(id = LEGACY_SYMPTOM_ID, name = LEGACY_SYMPTOM_NAME, createdAt = readings.minOf { it.recordedAt }, updatedAt = now))
    }
    return ParsedBackup(BackupBundle(symptoms, readings, sessions, events, checkIns = checkIns, coverage = coverage), skippedEmpty, skippedInvalid)
}

// ---------- This app's backups ----------

private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T =
    enumValues<T>().firstOrNull { it.name == text(key) } ?: throw BackupFormatException("This backup has an unknown value for $key.")

private fun JSONObject.syncFields(revision: Int, status: SyncStatus, updatedAt: Long, deletedAt: Long?) = apply {
    put("revision", revision)
    put("syncStatus", status.name)
    put("updatedAt", updatedAt)
    put("deletedAt", deletedAt)
}

fun backupToJson(bundle: BackupBundle, exportedAt: Long): String {
    fun <T> array(values: List<T>, map: (T) -> JSONObject) = JSONArray().apply { values.forEach { put(map(it)) } }
    return JSONObject()
        .put("schemaVersion", BACKUP_SCHEMA_VERSION)
        .put("app", "Rehab Tracker")
        .put("exportedAt", Instant.ofEpochMilli(exportedAt).toString())
        .put("symptoms", array(bundle.symptoms) {
            JSONObject().put("id", it.id).put("name", it.name).put("bodyArea", it.bodyArea).put("status", it.status.name)
                .put("keepAtZero", it.keepAtZero).put("createdAt", it.createdAt).put("resolvedAt", it.resolvedAt)
                .syncFields(it.revision, it.syncStatus, it.updatedAt, it.deletedAt)
        })
        .put("readings", array(bundle.readings) {
            JSONObject().put("id", it.id).put("symptomId", it.symptomId).put("score", it.score).put("recordedAt", it.recordedAt)
                .put("source", it.source.name).put("sourceId", it.sourceId)
                .syncFields(it.revision, it.syncStatus, it.updatedAt, it.deletedAt)
        })
        .put("sessions", array(bundle.sessions) {
            JSONObject().put("id", it.id).put("kind", it.kind.name).put("parts", it.parts).put("startedAt", it.startedAt)
                .put("durationMinutes", it.durationMinutes).put("effort", it.effort).put("content", it.content)
                .put("functionMarker", it.functionMarker).put("symptomDetails", it.symptomDetails).put("notes", it.notes)
                .put("legacyType", it.legacyType)
                .syncFields(it.revision, it.syncStatus, it.updatedAt, it.deletedAt)
        })
        .put("events", array(bundle.events) {
            JSONObject().put("id", it.id).put("occurredAt", it.occurredAt).put("context", it.context).put("change", it.change)
                .put("description", it.description).put("symptomDetails", it.symptomDetails).put("affected", it.affected)
                .put("helped", it.helped).put("durationMinutes", it.durationMinutes)
                .syncFields(it.revision, it.syncStatus, it.updatedAt, it.deletedAt)
        })
        .put("links", array(bundle.links) { JSONObject().put("eventId", it.eventId).put("sessionId", it.sessionId) })
        .put("checkIns", array(bundle.checkIns) {
            JSONObject().put("id", it.id).put("kind", it.kind.name).put("scheduledFor", it.scheduledFor).put("recordedAt", it.recordedAt)
                .put("response", it.response).put("sleepHours", it.sleepHours).put("nextDayPlan", it.nextDayPlan).put("notes", it.notes)
                .syncFields(it.revision, it.syncStatus, it.updatedAt, it.deletedAt)
        })
        .put("coverage", array(bundle.coverage) { JSONObject().put("checkInId", it.checkInId).put("sessionId", it.sessionId) })
        .toString(2)
}

private fun parseOwnBackup(root: JSONObject) = BackupBundle(
    symptoms = root.items("symptoms").map {
        Symptom(
            id = it.text("id"), name = it.text("name"), bodyArea = it.text("bodyArea"), status = it.enumValue<SymptomStatus>("status"),
            keepAtZero = it.optBoolean("keepAtZero", false), createdAt = it.long("createdAt") ?: 0, resolvedAt = it.long("resolvedAt"),
            updatedAt = it.long("updatedAt") ?: 0, revision = it.optInt("revision"), syncStatus = it.enumValue<SyncStatus>("syncStatus"), deletedAt = it.long("deletedAt"),
        )
    },
    readings = root.items("readings").map {
        SymptomReading(
            id = it.text("id"), symptomId = it.text("symptomId"), score = it.number("score") ?: 0.0, recordedAt = it.long("recordedAt") ?: 0,
            source = it.enumValue<ReadingSource>("source"), sourceId = it.text("sourceId"),
            updatedAt = it.long("updatedAt") ?: 0, revision = it.optInt("revision"), syncStatus = it.enumValue<SyncStatus>("syncStatus"), deletedAt = it.long("deletedAt"),
        )
    },
    sessions = root.items("sessions").map {
        Session(
            id = it.text("id"), kind = it.enumValue<SessionKind>("kind"), parts = it.text("parts"), startedAt = it.long("startedAt") ?: 0,
            durationMinutes = it.int("durationMinutes"), effort = it.number("effort"), content = it.text("content"),
            functionMarker = it.text("functionMarker"), symptomDetails = it.text("symptomDetails"), notes = it.text("notes"), legacyType = it.textOrNull("legacyType"),
            updatedAt = it.long("updatedAt") ?: 0, revision = it.optInt("revision"), syncStatus = it.enumValue<SyncStatus>("syncStatus"), deletedAt = it.long("deletedAt"),
        )
    },
    events = root.items("events").map {
        SymptomEvent(
            id = it.text("id"), occurredAt = it.long("occurredAt") ?: 0, context = it.text("context"), change = it.textOrNull("change"),
            description = it.text("description"), symptomDetails = it.text("symptomDetails"), affected = it.text("affected"), helped = it.text("helped"),
            durationMinutes = it.int("durationMinutes"),
            updatedAt = it.long("updatedAt") ?: 0, revision = it.optInt("revision"), syncStatus = it.enumValue<SyncStatus>("syncStatus"), deletedAt = it.long("deletedAt"),
        )
    },
    links = root.items("links").map { EventSessionLink(it.text("eventId"), it.text("sessionId")) },
    checkIns = root.items("checkIns").map {
        CheckIn(
            id = it.text("id"), kind = it.enumValue<CheckInKind>("kind"), scheduledFor = it.long("scheduledFor"), recordedAt = it.long("recordedAt") ?: 0,
            response = it.textOrNull("response"), sleepHours = it.number("sleepHours"), nextDayPlan = it.text("nextDayPlan"), notes = it.text("notes"),
            updatedAt = it.long("updatedAt") ?: 0, revision = it.optInt("revision"), syncStatus = it.enumValue<SyncStatus>("syncStatus"), deletedAt = it.long("deletedAt"),
        )
    },
    coverage = root.items("coverage").map { CheckInSession(it.text("checkInId"), it.text("sessionId")) },
)
