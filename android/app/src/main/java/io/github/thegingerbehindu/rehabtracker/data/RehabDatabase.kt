package io.github.thegingerbehindu.rehabtracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.thegingerbehindu.rehabtracker.domain.BackupBundle
import io.github.thegingerbehindu.rehabtracker.domain.symptomStatusFor
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RehabDao {
    @Query("SELECT * FROM symptoms WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    abstract fun observeSymptoms(): Flow<List<Symptom>>

    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL ORDER BY startedAt DESC")
    abstract fun observeSessions(): Flow<List<Session>>

    @Query("SELECT * FROM symptom_events WHERE deletedAt IS NULL ORDER BY occurredAt DESC")
    abstract fun observeEvents(): Flow<List<SymptomEvent>>

    @Query("SELECT * FROM check_ins WHERE deletedAt IS NULL ORDER BY recordedAt DESC")
    abstract fun observeCheckIns(): Flow<List<CheckIn>>

    @Query("SELECT * FROM symptom_readings WHERE deletedAt IS NULL")
    abstract fun observeReadings(): Flow<List<SymptomReading>>

    @Query("SELECT cs.sessionId FROM check_in_sessions cs JOIN check_ins c ON c.id = cs.checkInId WHERE c.deletedAt IS NULL")
    abstract fun observeCoveredSessionIds(): Flow<List<String>>

    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL") abstract suspend fun sessions(): List<Session>
    @Query("SELECT * FROM check_ins WHERE deletedAt IS NULL") abstract suspend fun checkIns(): List<CheckIn>
    @Query("SELECT cs.sessionId FROM check_in_sessions cs JOIN check_ins c ON c.id = cs.checkInId WHERE c.deletedAt IS NULL")
    abstract suspend fun coveredSessionIds(): List<String>
    @Query("SELECT COUNT(*) FROM symptoms WHERE deletedAt IS NULL") abstract suspend fun symptomCount(): Int
    @Query("SELECT COUNT(*) FROM symptom_events WHERE deletedAt IS NULL") abstract suspend fun eventCount(): Int

    @Query("SELECT * FROM sessions WHERE id = :id") abstract suspend fun session(id: String): Session?
    @Query("SELECT * FROM symptom_events WHERE id = :id") abstract suspend fun event(id: String): SymptomEvent?
    @Query("SELECT * FROM check_ins WHERE id = :id") abstract suspend fun checkIn(id: String): CheckIn?
    @Query("SELECT * FROM symptoms WHERE id = :id") abstract suspend fun symptom(id: String): Symptom?
    @Query("SELECT * FROM symptom_readings WHERE sourceId = :sourceId") protected abstract suspend fun allReadingsFor(sourceId: String): List<SymptomReading>
    @Query("SELECT * FROM symptom_readings WHERE sourceId = :sourceId AND deletedAt IS NULL") abstract suspend fun readingsFor(sourceId: String): List<SymptomReading>
    @Query("SELECT sessionId FROM event_session_links WHERE eventId = :eventId") abstract suspend fun linkedSessionIds(eventId: String): List<String>
    @Query("SELECT sessionId FROM check_in_sessions WHERE checkInId = :checkInId") abstract suspend fun coveredBy(checkInId: String): List<String>
    @Query("SELECT * FROM sessions WHERE deletedAt IS NULL AND kind = :kind ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun latestSession(kind: SessionKind): Session?
    @Query("SELECT * FROM symptom_readings WHERE symptomId = :symptomId AND deletedAt IS NULL ORDER BY recordedAt DESC, id DESC LIMIT 1")
    protected abstract suspend fun latestReading(symptomId: String): SymptomReading?

    @Upsert protected abstract suspend fun upsertSymptom(value: Symptom)
    @Upsert protected abstract suspend fun upsertReading(value: SymptomReading)
    @Upsert protected abstract suspend fun upsertSession(value: Session)
    @Upsert protected abstract suspend fun upsertEvent(value: SymptomEvent)
    @Upsert protected abstract suspend fun upsertCheckIn(value: CheckIn)

    @Query("DELETE FROM event_session_links WHERE eventId = :eventId") protected abstract suspend fun clearLinks(eventId: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertLinks(values: List<EventSessionLink>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertCoverage(values: List<CheckInSession>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun importSymptoms(values: List<Symptom>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun importReadings(values: List<SymptomReading>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun importSessions(values: List<Session>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun importEvents(values: List<SymptomEvent>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun importCheckIns(values: List<CheckIn>): List<Long>

    @Query("SELECT * FROM symptoms") protected abstract suspend fun allSymptoms(): List<Symptom>
    @Query("SELECT * FROM symptom_readings") protected abstract suspend fun allReadings(): List<SymptomReading>
    @Query("SELECT * FROM sessions") protected abstract suspend fun allSessions(): List<Session>
    @Query("SELECT * FROM symptom_events") protected abstract suspend fun allEvents(): List<SymptomEvent>
    @Query("SELECT * FROM check_ins") protected abstract suspend fun allCheckIns(): List<CheckIn>
    @Query("SELECT * FROM event_session_links") protected abstract suspend fun allLinks(): List<EventSessionLink>
    @Query("SELECT * FROM check_in_sessions") protected abstract suspend fun allCoverage(): List<CheckInSession>

    @Transaction
    open suspend fun saveSession(value: Session, readings: List<SymptomReading>, now: Long) {
        val old = session(value.id)
        upsertSession(value.copy(updatedAt = now, revision = (old?.revision ?: 0) + 1, syncStatus = SyncStatus.PENDING))
        replaceReadings(value.id, readings, now)
    }

    @Transaction
    open suspend fun saveEvent(value: SymptomEvent, readings: List<SymptomReading>, linkedSessionIds: List<String>, now: Long) {
        val old = event(value.id)
        upsertEvent(value.copy(updatedAt = now, revision = (old?.revision ?: 0) + 1, syncStatus = SyncStatus.PENDING))
        clearLinks(value.id)
        insertLinks(linkedSessionIds.map { EventSessionLink(value.id, it) })
        replaceReadings(value.id, readings, now)
    }

    /** New check-ins store what they cover. Editing one keeps its original coverage. */
    @Transaction
    open suspend fun saveCheckIn(value: CheckIn, readings: List<SymptomReading>, coveredSessionIds: List<String>, now: Long) {
        val old = checkIn(value.id)
        upsertCheckIn(value.copy(updatedAt = now, revision = (old?.revision ?: 0) + 1, syncStatus = SyncStatus.PENDING))
        if (old == null) insertCoverage(coveredSessionIds.map { CheckInSession(value.id, it) })
        replaceReadings(value.id, readings, now)
    }

    private suspend fun replaceReadings(sourceId: String, desired: List<SymptomReading>, now: Long) {
        val existing = allReadingsFor(sourceId)
        val desiredIds = desired.map { it.id }.toSet()
        desired.forEach { reading ->
            val old = existing.find { it.id == reading.id }
            upsertReading(reading.copy(updatedAt = now, revision = (old?.revision ?: 0) + 1, syncStatus = SyncStatus.PENDING, deletedAt = null))
        }
        existing.filter { it.id !in desiredIds && it.deletedAt == null }.forEach {
            upsertReading(it.copy(updatedAt = now, revision = it.revision + 1, syncStatus = SyncStatus.PENDING, deletedAt = now))
        }
        recomputeStatuses((existing.map { it.symptomId } + desired.map { it.symptomId }).toSet(), now)
    }

    private suspend fun recomputeStatuses(symptomIds: Set<String>, now: Long) {
        symptomIds.forEach { id ->
            val symptom = symptom(id) ?: return@forEach
            val latest = latestReading(id) ?: return@forEach
            val status = symptomStatusFor(latest.score, symptom.keepAtZero)
            if (status != symptom.status) {
                upsertSymptom(
                    symptom.copy(
                        status = status,
                        resolvedAt = if (status == SymptomStatus.RESOLVED) latest.recordedAt else null,
                        updatedAt = now,
                        revision = symptom.revision + 1,
                        syncStatus = SyncStatus.PENDING,
                    ),
                )
            }
        }
    }

    @Transaction
    open suspend fun addSymptom(value: Symptom) = upsertSymptom(value)

    /** "Back again": continues the same history line. */
    @Transaction
    open suspend fun reactivateSymptom(id: String, now: Long) {
        val symptom = symptom(id) ?: return
        upsertSymptom(symptom.copy(status = SymptomStatus.ACTIVE, resolvedAt = null, updatedAt = now, revision = symptom.revision + 1, syncStatus = SyncStatus.PENDING))
    }

    @Transaction
    open suspend fun updateSymptom(id: String, name: String, keepAtZero: Boolean, now: Long) {
        val symptom = symptom(id) ?: return
        upsertSymptom(symptom.copy(name = name, keepAtZero = keepAtZero, updatedAt = now, revision = symptom.revision + 1, syncStatus = SyncStatus.PENDING))
        recomputeStatuses(setOf(id), now)
    }

    @Transaction
    open suspend fun setSessionDeleted(id: String, deleted: Boolean, now: Long) {
        val value = session(id) ?: return
        val marker = value.deletedAt
        upsertSession(value.copy(deletedAt = if (deleted) now else null, updatedAt = now, revision = value.revision + 1, syncStatus = SyncStatus.PENDING))
        setReadingsDeleted(id, deleted, marker, now)
    }

    @Transaction
    open suspend fun setEventDeleted(id: String, deleted: Boolean, now: Long) {
        val value = event(id) ?: return
        val marker = value.deletedAt
        upsertEvent(value.copy(deletedAt = if (deleted) now else null, updatedAt = now, revision = value.revision + 1, syncStatus = SyncStatus.PENDING))
        setReadingsDeleted(id, deleted, marker, now)
    }

    @Transaction
    open suspend fun setCheckInDeleted(id: String, deleted: Boolean, now: Long) {
        val value = checkIn(id) ?: return
        val marker = value.deletedAt
        upsertCheckIn(value.copy(deletedAt = if (deleted) now else null, updatedAt = now, revision = value.revision + 1, syncStatus = SyncStatus.PENDING))
        setReadingsDeleted(id, deleted, marker, now)
    }

    /** Undo restores only the readings removed by that same delete. */
    private suspend fun setReadingsDeleted(sourceId: String, deleted: Boolean, marker: Long?, now: Long) {
        val readings = allReadingsFor(sourceId)
        readings.filter { if (deleted) it.deletedAt == null else it.deletedAt == marker }.forEach {
            upsertReading(it.copy(deletedAt = if (deleted) now else null, updatedAt = now, revision = it.revision + 1, syncStatus = SyncStatus.PENDING))
        }
        recomputeStatuses(readings.map { it.symptomId }.toSet(), now)
    }

    @Transaction
    open suspend fun exportBundle() = BackupBundle(
        symptoms = allSymptoms(),
        readings = allReadings(),
        sessions = allSessions(),
        events = allEvents(),
        links = allLinks(),
        checkIns = allCheckIns(),
        coverage = allCoverage(),
    )

    /** Importing never overwrites an existing record, so repeating it creates no duplicates. */
    @Transaction
    open suspend fun importBundle(bundle: BackupBundle, now: Long): ImportCounts {
        fun List<Long>.added() = count { it != -1L }
        val symptoms = importSymptoms(bundle.symptoms).added()
        val sessions = importSessions(bundle.sessions).added()
        val events = importEvents(bundle.events).added()
        val checkIns = importCheckIns(bundle.checkIns).added()
        val readings = importReadings(bundle.readings).added()
        insertLinks(bundle.links)
        insertCoverage(bundle.coverage)
        recomputeStatuses(bundle.readings.map { it.symptomId }.toSet(), now)
        return ImportCounts(sessions, events, checkIns, symptoms, readings)
    }
}

data class ImportCounts(val sessions: Int, val events: Int, val checkIns: Int, val symptoms: Int, val readings: Int)

@Database(
    entities = [Symptom::class, SymptomReading::class, Session::class, SymptomEvent::class, EventSessionLink::class, CheckIn::class, CheckInSession::class],
    version = 1,
    exportSchema = false,
)
abstract class RehabDatabase : RoomDatabase() {
    abstract fun dao(): RehabDao

    companion object {
        @Volatile private var instance: RehabDatabase? = null

        fun get(context: Context): RehabDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RehabDatabase::class.java, "rehab-tracker.db")
                .build()
                .also { instance = it }
        }
    }
}
