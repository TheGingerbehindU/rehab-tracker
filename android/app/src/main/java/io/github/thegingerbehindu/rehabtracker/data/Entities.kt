package io.github.thegingerbehindu.rehabtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SessionKind(val label: String) { TRAINING("Training"), REHAB("Rehab") }
enum class SessionPart(val label: String) { TECHNICAL("Technical"), LIFT("Lift"), OTHER("Other") }
enum class SymptomStatus { ACTIVE, RESOLVED }
enum class ReadingSource { SESSION_BEFORE, SESSION_AFTER, EVENT_BEFORE, EVENT_AFTER, EVENT_NOW, CHECK_IN }
enum class CheckInKind { MORNING, AFTER_24H, MANUAL, LEGACY }
enum class SyncStatus { PENDING, SYNCED, CONFLICT, ERROR }

val RESPONSES = listOf("Better", "Same", "Mild flare", "Significant flare")
val EVENT_CONTEXTS = listOf("Sitting", "Standing", "Walking", "Driving / travel", "Resting", "After training", "Other")
val EVENT_CHANGES = listOf("Worse", "Better", "No clear change")

@Entity(tableName = "symptoms")
data class Symptom(
    @PrimaryKey val id: String,
    val name: String,
    val bodyArea: String = "",
    val status: SymptomStatus = SymptomStatus.ACTIVE,
    /** Stays on the forms at 0, for symptoms that only appear under load. */
    val keepAtZero: Boolean = false,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val updatedAt: Long,
    val revision: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

/** Every symptom score in the app is one of these. */
@Entity(tableName = "symptom_readings", indices = [Index("symptomId"), Index("sourceId")])
data class SymptomReading(
    @PrimaryKey val id: String,
    val symptomId: String,
    val score: Double,
    val recordedAt: Long,
    val source: ReadingSource,
    val sourceId: String,
    val updatedAt: Long,
    val revision: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

fun readingId(sourceId: String, source: ReadingSource, symptomId: String) = "$sourceId|$source|$symptomId"

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val kind: SessionKind,
    /** Comma-separated [SessionPart] names; only used for training. */
    val parts: String = "",
    val startedAt: Long,
    val durationMinutes: Int? = null,
    val effort: Double? = null,
    val content: String = "",
    val functionMarker: String = "",
    val symptomDetails: String = "",
    val notes: String = "",
    /** The session type in the web app. Set only on imported sessions. */
    val legacyType: String? = null,
    val updatedAt: Long,
    val revision: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

fun Session.partSet(): Set<SessionPart> =
    parts.split(",").mapNotNull { name -> SessionPart.entries.find { it.name == name } }.toSet()

fun Set<SessionPart>.toPartsColumn(): String = SessionPart.entries.filter { it in this }.joinToString(",") { it.name }

@Entity(tableName = "symptom_events")
data class SymptomEvent(
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val context: String = "Other",
    val change: String? = null,
    val description: String = "",
    val symptomDetails: String = "",
    val affected: String = "",
    val helped: String = "",
    val durationMinutes: Int? = null,
    val updatedAt: Long,
    val revision: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

/** Records what the user noticed, not a cause. No links means "unsure". */
@Entity(tableName = "event_session_links", primaryKeys = ["eventId", "sessionId"], indices = [Index("sessionId")])
data class EventSessionLink(val eventId: String, val sessionId: String)

@Entity(tableName = "check_ins")
data class CheckIn(
    @PrimaryKey val id: String,
    val kind: CheckInKind,
    val scheduledFor: Long? = null,
    val recordedAt: Long,
    val response: String? = null,
    val sleepHours: Double? = null,
    val nextDayPlan: String = "",
    val notes: String = "",
    val updatedAt: Long,
    val revision: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

/** Stored when a check-in is saved, so what it covered doesn't change later. */
@Entity(tableName = "check_in_sessions", primaryKeys = ["checkInId", "sessionId"], indices = [Index("sessionId")])
data class CheckInSession(val checkInId: String, val sessionId: String)
