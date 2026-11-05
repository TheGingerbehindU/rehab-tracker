package io.github.thegingerbehindu.rehabtracker.domain

import io.github.thegingerbehindu.rehabtracker.data.CheckIn
import io.github.thegingerbehindu.rehabtracker.data.CheckInKind
import io.github.thegingerbehindu.rehabtracker.data.ReadingSource
import io.github.thegingerbehindu.rehabtracker.data.Session
import io.github.thegingerbehindu.rehabtracker.data.Symptom
import io.github.thegingerbehindu.rehabtracker.data.SymptomReading
import io.github.thegingerbehindu.rehabtracker.data.SymptomStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val APP_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

// ---------- Tracked symptoms ----------

fun symptomStatusFor(latestScore: Double, keepAtZero: Boolean): SymptomStatus =
    if (latestScore <= 0.0 && !keepAtZero) SymptomStatus.RESOLVED else SymptomStatus.ACTIVE

/** Active symptoms, plus any already scored on this form so they don't vanish while it's open. */
fun symptomsForForm(all: List<Symptom>, scoredIds: Set<String>): List<Symptom> =
    all.filter { it.status == SymptomStatus.ACTIVE || it.id in scoredIds }

fun findSymptomByName(all: List<Symptom>, name: String): Symptom? =
    all.find { it.name.trim().equals(name.trim(), ignoreCase = true) }

// ---------- Check-ins ----------

private val TWENTY_FOUR_HOURS: Duration = Duration.ofHours(24)

/** A check-in this close before a due time already answers it. */
private val ANSWERED_WINDOW: Duration = Duration.ofHours(3)

data class CheckInInputs(
    val now: Instant,
    val zone: ZoneId = APP_ZONE,
    val morningTime: LocalTime,
    val morningOnRestDays: Boolean,
    val latestSessionAt: Instant?,
    val lastCheckInAt: Instant?,
    val hasUncoveredSession: Boolean,
    val hasAnyEntry: Boolean,
)

data class DueCheckIn(val kind: CheckInKind, val scheduledFor: Instant)

private fun CheckInInputs.answeredSince(dueAt: Instant): Boolean =
    lastCheckInAt != null && lastCheckInAt >= dueAt.minus(ANSWERED_WINDOW)

private fun CheckInInputs.morningOn(date: LocalDate): Instant =
    ZonedDateTime.of(date, morningTime, zone).toInstant()

/** What should be asked right now, if anything. Only one check-in is ever due at a time. */
fun dueCheckIn(inputs: CheckInInputs): DueCheckIn? = with(inputs) {
    if (!hasAnyEntry) return null
    val afterSession = latestSessionAt?.plus(TWENTY_FOUR_HOURS)
    if (afterSession != null && afterSession <= now && !answeredSince(afterSession)) {
        return DueCheckIn(CheckInKind.AFTER_24H, afterSession)
    }
    val morning = morningOn(now.atZone(zone).toLocalDate())
    // A session logged after this morning's time would be scored too soon, so it waits for tomorrow.
    val sessionSinceMorning = latestSessionAt != null && latestSessionAt > morning
    if (morning <= now && !answeredSince(morning) && !sessionSinceMorning && (morningOnRestDays || hasUncoveredSession)) {
        return DueCheckIn(CheckInKind.MORNING, morning)
    }
    return null
}

/** The next moment to re-evaluate. The alarm only notifies if [dueCheckIn] says so then. */
fun nextCheckInAlarm(inputs: CheckInInputs): Instant = with(inputs) {
    val today = now.atZone(zone).toLocalDate()
    val morningToday = morningOn(today)
    val nextMorning = if (morningToday > now) morningToday else morningOn(today.plusDays(1))
    val afterSession = latestSessionAt?.plus(TWENTY_FOUR_HOURS)
    return if (afterSession != null && afterSession > now && afterSession < nextMorning) afterSession else nextMorning
}

/** Sessions no check-in has covered yet. Imported sessions were already handled in the web app. */
fun uncoveredSessions(sessions: List<Session>, coveredIds: Collection<String>): List<Session> {
    val covered = coveredIds.toSet()
    return sessions.filter { it.deletedAt == null && it.legacyType == null && it.id !in covered }.sortedBy { it.startedAt }
}

fun checkInInputs(
    now: Instant,
    morningTime: LocalTime,
    morningOnRestDays: Boolean,
    sessions: List<Session>,
    coveredIds: Collection<String>,
    checkIns: List<CheckIn>,
    hasOtherEntries: Boolean,
): CheckInInputs {
    val live = sessions.filter { it.deletedAt == null && it.legacyType == null }
    return CheckInInputs(
        now = now,
        morningTime = morningTime,
        morningOnRestDays = morningOnRestDays,
        latestSessionAt = live.maxOfOrNull { it.startedAt }?.let(Instant::ofEpochMilli),
        lastCheckInAt = checkIns.filter { it.deletedAt == null }.maxOfOrNull { it.recordedAt }?.let(Instant::ofEpochMilli),
        hasUncoveredSession = uncoveredSessions(sessions, coveredIds).isNotEmpty(),
        hasAnyEntry = sessions.any { it.deletedAt == null } || hasOtherEntries,
    )
}

/** Per symptom, the "before" score from the earliest covered session that has one. */
fun baselineScores(covered: List<Session>, readings: List<SymptomReading>): Map<String, Double> {
    val result = mutableMapOf<String, Double>()
    covered.sortedBy { it.startedAt }.forEach { session ->
        readings.filter { it.sourceId == session.id && it.source == ReadingSource.SESSION_BEFORE && it.deletedAt == null }
            .forEach { result.putIfAbsent(it.symptomId, it.score) }
    }
    return result
}

fun Double.pretty(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
