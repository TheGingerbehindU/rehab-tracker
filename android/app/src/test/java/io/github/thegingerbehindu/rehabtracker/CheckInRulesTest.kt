package io.github.thegingerbehindu.rehabtracker

import io.github.thegingerbehindu.rehabtracker.data.CheckInKind
import io.github.thegingerbehindu.rehabtracker.data.ReadingSource
import io.github.thegingerbehindu.rehabtracker.data.Session
import io.github.thegingerbehindu.rehabtracker.data.SessionKind
import io.github.thegingerbehindu.rehabtracker.data.SymptomReading
import io.github.thegingerbehindu.rehabtracker.domain.APP_ZONE
import io.github.thegingerbehindu.rehabtracker.domain.CheckInInputs
import io.github.thegingerbehindu.rehabtracker.domain.baselineScores
import io.github.thegingerbehindu.rehabtracker.domain.dueCheckIn
import io.github.thegingerbehindu.rehabtracker.domain.nextCheckInAlarm
import io.github.thegingerbehindu.rehabtracker.domain.uncoveredSessions
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInRulesTest {
    /** Berlin wall-clock time. November 2026 is a winter-time month with no clock change. */
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11): Instant =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(APP_ZONE).toInstant()

    private fun inputs(
        now: Instant,
        latestSession: Instant? = null,
        lastCheckIn: Instant? = null,
        uncovered: Boolean = latestSession != null,
        onRestDays: Boolean = true,
        hasEntry: Boolean = true,
    ) = CheckInInputs(
        now = now,
        morningTime = LocalTime.of(8, 0),
        morningOnRestDays = onRestDays,
        latestSessionAt = latestSession,
        lastCheckInAt = lastCheckIn,
        hasUncoveredSession = uncovered,
        hasAnyEntry = hasEntry,
    )

    private fun session(id: String, startedAt: Instant, legacy: String? = null) = Session(
        id = id,
        kind = SessionKind.REHAB,
        startedAt = startedAt.toEpochMilli(),
        legacyType = legacy,
        updatedAt = 0,
    )

    @Test
    fun `nothing is due in an app with no entries`() {
        assertNull(dueCheckIn(inputs(now = at(10, 9), hasEntry = false)))
    }

    @Test
    fun `rehab in the morning and training in the afternoon share one morning check-in`() {
        val due = dueCheckIn(inputs(now = at(11, 8, 1), latestSession = at(10, 17), lastCheckIn = at(10, 8)))
        assertEquals(CheckInKind.MORNING, due?.kind)
        assertEquals(at(11, 8), due?.scheduledFor)

        val sessions = listOf(session("training", at(10, 17)), session("rehab", at(10, 8, 30)))
        assertEquals(listOf("rehab", "training"), uncoveredSessions(sessions, emptyList()).map { it.id })
    }

    @Test
    fun `a true 24-hour reading follows when no newer session was logged`() {
        val due = dueCheckIn(inputs(now = at(11, 17), latestSession = at(10, 17), lastCheckIn = at(11, 8)))
        assertEquals(CheckInKind.AFTER_24H, due?.kind)
    }

    @Test
    fun `the 24-hour check-in stops being due once a newer session is logged`() {
        assertNull(dueCheckIn(inputs(now = at(11, 17), latestSession = at(11, 16), lastCheckIn = at(11, 8))))
    }

    @Test
    fun `daily training still gets a daily morning check-in`() {
        val first = dueCheckIn(inputs(now = at(11, 8), latestSession = at(10, 18), lastCheckIn = at(10, 8)))
        val second = dueCheckIn(inputs(now = at(12, 8), latestSession = at(11, 17), lastCheckIn = at(11, 8)))
        assertEquals(CheckInKind.MORNING, first?.kind)
        assertEquals(CheckInKind.MORNING, second?.kind)
    }

    @Test
    fun `a session at the same time as the morning check-in asks only once`() {
        val unanswered = dueCheckIn(inputs(now = at(11, 8), latestSession = at(10, 8), lastCheckIn = at(10, 7)))
        assertEquals(CheckInKind.AFTER_24H, unanswered?.kind)
        assertNull(dueCheckIn(inputs(now = at(11, 8, 5), latestSession = at(10, 8), lastCheckIn = at(11, 8, 2))))
    }

    @Test
    fun `an answer shortly before a due time counts for it`() {
        assertNull(dueCheckIn(inputs(now = at(11, 9), latestSession = at(10, 9), lastCheckIn = at(11, 8))))
    }

    @Test
    fun `rest days ask in the morning by default and can be turned off`() {
        val input = inputs(now = at(12, 9), latestSession = at(10, 17), lastCheckIn = at(11, 17, 30), uncovered = false)
        assertEquals(CheckInKind.MORNING, dueCheckIn(input)?.kind)
        assertNull(dueCheckIn(input.copy(morningOnRestDays = false)))
    }

    @Test
    fun `an entry added late is still covered by the next check-in`() {
        val lateEntry = session("late", at(8, 10))
        val covered = session("covered", at(9, 10))
        assertEquals(listOf("late"), uncoveredSessions(listOf(lateEntry, covered), listOf("covered")).map { it.id })
    }

    @Test
    fun `imported sessions never ask for a check-in`() {
        assertEquals(emptyList<Session>(), uncoveredSessions(listOf(session("old", at(1, 10), legacy = "Rehab")), emptyList()))
    }

    @Test
    fun `the next alarm is the earlier of the morning and the 24-hour time`() {
        assertEquals(at(11, 8), nextCheckInAlarm(inputs(now = at(10, 20), latestSession = at(10, 19))))
        assertEquals(at(11, 7), nextCheckInAlarm(inputs(now = at(10, 20), latestSession = at(10, 7))))
        assertEquals(at(11, 8), nextCheckInAlarm(inputs(now = at(11, 7, 30), latestSession = at(9, 7))))
    }

    @Test
    fun `the morning time holds across daylight-saving changes`() {
        // Clocks go back on 25 October 2026 and forward on 28 March 2027.
        val autumn = nextCheckInAlarm(inputs(now = at(24, 20, month = 10)))
        assertEquals(at(25, 8, month = 10), autumn)
        assertEquals(8, autumn.atZone(APP_ZONE).hour)

        val springNow = LocalDateTime.of(2027, 3, 27, 20, 0).atZone(APP_ZONE).toInstant()
        val spring = nextCheckInAlarm(inputs(now = springNow))
        assertEquals(LocalDateTime.of(2027, 3, 28, 8, 0), spring.atZone(APP_ZONE).toLocalDateTime())
    }

    @Test
    fun `the baseline is the earliest covered before score per symptom`() {
        fun before(sessionId: String, symptom: String, score: Double) = SymptomReading(
            id = "$sessionId-$symptom", symptomId = symptom, score = score, recordedAt = 0,
            source = ReadingSource.SESSION_BEFORE, sourceId = sessionId, updatedAt = 0,
        )
        val covered = listOf(session("afternoon", at(10, 17)), session("morning", at(10, 8)))
        val readings = listOf(before("afternoon", "knee", 5.0), before("morning", "knee", 2.0), before("afternoon", "back", 1.0))
        assertEquals(mapOf("knee" to 2.0, "back" to 1.0), baselineScores(covered, readings))
    }
}
