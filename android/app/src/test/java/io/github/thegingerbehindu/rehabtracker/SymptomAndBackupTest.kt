package io.github.thegingerbehindu.rehabtracker

import io.github.thegingerbehindu.rehabtracker.data.CheckInKind
import io.github.thegingerbehindu.rehabtracker.data.ReadingSource
import io.github.thegingerbehindu.rehabtracker.data.SessionKind
import io.github.thegingerbehindu.rehabtracker.data.SessionPart
import io.github.thegingerbehindu.rehabtracker.data.Symptom
import io.github.thegingerbehindu.rehabtracker.data.SymptomStatus
import io.github.thegingerbehindu.rehabtracker.data.partSet
import io.github.thegingerbehindu.rehabtracker.domain.BackupFormatException
import io.github.thegingerbehindu.rehabtracker.domain.LEGACY_SYMPTOM_ID
import io.github.thegingerbehindu.rehabtracker.domain.backupToJson
import io.github.thegingerbehindu.rehabtracker.domain.findSymptomByName
import io.github.thegingerbehindu.rehabtracker.domain.parseBackup
import io.github.thegingerbehindu.rehabtracker.domain.symptomStatusFor
import io.github.thegingerbehindu.rehabtracker.domain.symptomsForForm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SymptomAndBackupTest {
    private fun symptom(id: String, status: SymptomStatus = SymptomStatus.ACTIVE) =
        Symptom(id = id, name = id, status = status, createdAt = 0, updatedAt = 0)

    @Test
    fun `a symptom resolves at zero unless it is kept`() {
        assertEquals(SymptomStatus.RESOLVED, symptomStatusFor(0.0, keepAtZero = false))
        assertEquals(SymptomStatus.ACTIVE, symptomStatusFor(0.0, keepAtZero = true))
        assertEquals(SymptomStatus.ACTIVE, symptomStatusFor(0.5, keepAtZero = false))
    }

    @Test
    fun `resolved symptoms leave new forms but stay on a form that scored them`() {
        val all = listOf(symptom("knee"), symptom("back", SymptomStatus.RESOLVED))
        assertEquals(listOf("knee"), symptomsForForm(all, emptySet()).map { it.id })
        assertEquals(listOf("knee", "back"), symptomsForForm(all, setOf("back")).map { it.id })
    }

    @Test
    fun `a symptom with an existing name is found so it continues its history`() {
        val all = listOf(symptom("Left knee", SymptomStatus.RESOLVED))
        assertEquals("Left knee", findSymptomByName(all, " left KNEE ")?.id)
    }

    private fun webRecord(id: String, vararg fields: Pair<String, Any?>) = JSONObject().apply {
        put("id", id)
        put("date", "2026-11-01")
        put("sessionAt", "2026-11-01T17:00:00.000Z")
        put("followUpDueAt", "2026-11-02T17:00:00.000Z")
        put("entryType", "session")
        put("sessionType", "Rehab")
        put("sessionContent", "")
        put("symptomDetails", "")
        put("functionMarker", "")
        put("notes", "")
        put("nextDayPlan", "")
        put("followUpComplete", false)
        put("followUpRecordedAt", JSONObject.NULL)
        put("response24h", JSONObject.NULL)
        put("symptoms24h", JSONObject.NULL)
        put("symptomsBefore", JSONObject.NULL)
        put("symptomsAfter", JSONObject.NULL)
        put("durationMinutes", JSONObject.NULL)
        put("rpe", JSONObject.NULL)
        put("sleepHours", JSONObject.NULL)
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }

    private fun webBackup(vararg records: JSONObject, version: Int = 3) = JSONObject()
        .put("schemaVersion", version)
        .put("app", "Rehab Tracker")
        .put("records", JSONArray(records.toList()))
        .toString()

    @Test
    fun `web sessions map to the new types and keep their original label`() {
        val parsed = parseBackup(
            webBackup(
                webRecord("rehab", "sessionType" to "Rehab", "sessionContent" to "Bridges"),
                webRecord("lift", "sessionType" to "Strength", "sessionContent" to "Squats"),
                webRecord("run", "sessionType" to "Running", "sessionContent" to "5k"),
            ),
            now = 1,
        )
        val byId = parsed.bundle.sessions.associateBy { it.id }
        assertEquals(SessionKind.REHAB, byId.getValue("rehab").kind)
        assertEquals(setOf(SessionPart.LIFT), byId.getValue("lift").partSet())
        assertEquals(SessionKind.TRAINING, byId.getValue("run").kind)
        assertEquals(setOf(SessionPart.OTHER), byId.getValue("run").partSet())
        assertEquals("Running", byId.getValue("run").legacyType)
    }

    @Test
    fun `web scores become readings and old answers become a covering check-in`() {
        val parsed = parseBackup(
            webBackup(
                webRecord(
                    "s1",
                    "sessionContent" to "Bridges",
                    "durationMinutes" to 30,
                    "rpe" to 6.5,
                    "symptomsBefore" to 0,
                    "symptomsAfter" to 4,
                    "symptoms24h" to 2,
                    "response24h" to "Better",
                    "followUpComplete" to true,
                    "followUpRecordedAt" to "2026-11-02T18:00:00.000Z",
                    "symptomDetails" to "Right knee",
                ),
            ),
            now = 1,
        )
        val bundle = parsed.bundle
        val session = bundle.sessions.single()
        assertEquals(6.5, session.effort!!, 0.0)
        assertEquals("Right knee", session.symptomDetails)

        val scores = bundle.readings.associate { it.source to it.score }
        assertEquals(0.0, scores.getValue(ReadingSource.SESSION_BEFORE), 0.0)
        assertEquals(4.0, scores.getValue(ReadingSource.SESSION_AFTER), 0.0)
        assertEquals(2.0, scores.getValue(ReadingSource.CHECK_IN), 0.0)
        assertTrue(bundle.readings.all { it.symptomId == LEGACY_SYMPTOM_ID })

        val checkIn = bundle.checkIns.single()
        assertEquals(CheckInKind.LEGACY, checkIn.kind)
        assertEquals("Better", checkIn.response)
        assertEquals(listOf("s1"), bundle.coverage.map { it.sessionId })
    }

    @Test
    fun `unanswered web follow-ups create no check-in`() {
        val parsed = parseBackup(webBackup(webRecord("s1", "sessionContent" to "Bridges")), now = 1)
        assertTrue(parsed.bundle.checkIns.isEmpty())
        assertEquals(1, parsed.bundle.sessions.size)
        assertTrue(parsed.bundle.symptoms.isEmpty())
    }

    @Test
    fun `web symptom events keep their details and start unlinked`() {
        val parsed = parseBackup(
            webBackup(
                webRecord(
                    "e1",
                    "entryType" to "symptom_event",
                    "eventContext" to "Sitting",
                    "eventChange" to "Worse",
                    "sessionContent" to "Long dinner",
                    "functionMarker" to "Standing up",
                    "notes" to "Walking helped",
                    "symptomsBefore" to 1,
                    "symptomsAfter" to 4,
                ),
            ),
            now = 1,
        )
        val event = parsed.bundle.events.single()
        assertEquals("Long dinner", event.description)
        assertEquals("Standing up", event.affected)
        assertEquals("Walking helped", event.helped)
        assertTrue(parsed.bundle.sessions.isEmpty())
        assertTrue(parsed.bundle.links.isEmpty())
        assertEquals(2, parsed.bundle.readings.size)
    }

    @Test
    fun `empty rest rows are skipped and counted, and rest rows with data are kept`() {
        val parsed = parseBackup(
            webBackup(
                webRecord("empty", "sessionType" to "Recovery / rest"),
                webRecord("real", "sessionType" to "Recovery / rest", "symptomsBefore" to 2),
            ),
            now = 1,
        )
        assertEquals(1, parsed.skippedEmpty)
        assertEquals(listOf("real"), parsed.bundle.sessions.map { it.id })
    }

    @Test
    fun `records that can't be dated are counted, not guessed`() {
        val parsed = parseBackup(webBackup(webRecord("bad", "date" to "not-a-date", "sessionAt" to "")), now = 1)
        assertEquals(1, parsed.skippedInvalid)
        assertTrue(parsed.bundle.sessions.isEmpty())
    }

    @Test
    fun `older web backups without entry types still import`() {
        val old = webRecord("s1", "sessionContent" to "Bridges").apply {
            remove("entryType")
            remove("sessionAt")
        }
        val session = parseBackup(webBackup(old, version = 1), now = 1).bundle.sessions.single()
        assertEquals(java.time.Instant.parse("2026-11-01T11:00:00Z").toEpochMilli(), session.startedAt)
    }

    @Test
    fun `an own backup survives a full round trip`() {
        val original = parseBackup(
            webBackup(
                webRecord("s1", "sessionContent" to "Bridges", "symptomsBefore" to 2, "symptoms24h" to 1, "response24h" to "Same"),
                webRecord("e1", "entryType" to "symptom_event", "eventContext" to "Walking", "symptomsAfter" to 3),
            ),
            now = 1,
        ).bundle
        val restored = parseBackup(backupToJson(original, exportedAt = 5), now = 9).bundle
        assertEquals(original, restored)
    }

    @Test
    fun `unrelated files are rejected with a clear error`() {
        listOf("not json", """{"schemaVersion": 99, "records": []}""", """{"hello": "world"}""").forEach { text ->
            try {
                parseBackup(text, now = 1)
                fail("Expected this to be rejected: $text")
            } catch (error: BackupFormatException) {
                assertFalse(error.message.isNullOrBlank())
            }
        }
    }
}
