import Dexie, { type EntityTable } from 'dexie'
import type { AppSetting, EntryType, RehabDay, SessionType, SyncOutbox } from './types'
import { followUpTimestamp, sessionTimestamp, sessionTimeValue, todayInBerlin } from './lib/dates'

export class RehabDatabase extends Dexie {
  days!: EntityTable<RehabDay, 'id'>
  syncOutbox!: EntityTable<SyncOutbox, 'recordId'>
  settings!: EntityTable<AppSetting, 'key'>

  constructor(name = 'rehab-tracker') {
    super(name)
    this.version(1).stores({
      days: 'id, &date, updatedAt, syncStatus',
      outbox: '&date, recordId, queuedAt',
      settings: '&key',
    })
    this.version(2).stores({
      days: 'id, date, sessionAt, followUpDueAt, updatedAt, syncStatus',
      outbox: '&date, recordId, queuedAt',
      settings: '&key',
    }).upgrade(async (transaction) => {
      await transaction.table('days').toCollection().modify((record: RehabDay) => {
        const sessionAt = record.sessionAt || sessionTimestamp(record.date, '12:00')
        record.sessionAt = sessionAt
        record.followUpDueAt = record.followUpDueAt || followUpTimestamp(sessionAt)
      })
    })
    this.version(3).stores({
      days: 'id, date, sessionAt, followUpDueAt, entryType, updatedAt, syncStatus',
      outbox: null,
      syncOutbox: '&recordId, date, queuedAt',
      settings: '&key',
    }).upgrade(async (transaction) => {
      const queue = transaction.table<SyncOutbox>('syncOutbox')
      const days = transaction.table<RehabDay>('days')
      const records = await days.toArray()
      for (const record of records) {
        record.entryType = record.entryType || 'session'
        record.eventContext = record.eventContext || null
        record.eventChange = record.eventChange || null
        await days.put(record)
        await queue.put({
          date: record.date,
          recordId: record.id,
          queuedRevision: record.revision,
          retryCount: 0,
          queuedAt: record.updatedAt,
        })
      }
    })
  }
}

export const db = new RehabDatabase()

export function blankRehabDay(date: string, now = new Date(), entryType: EntryType = 'session'): RehabDay {
  const nowIso = now.toISOString()
  const sessionAt = date === todayInBerlin(now)
    ? nowIso
    : sessionTimestamp(date, '12:00')
  return {
    id: crypto.randomUUID(),
    date,
    sessionAt,
    followUpDueAt: followUpTimestamp(sessionAt),
    entryType,
    eventContext: entryType === 'symptom_event' ? 'Sitting' : null,
    eventChange: null,
    sessionType: 'Rehab' as SessionType,
    sessionContent: '',
    durationMinutes: null,
    rpe: null,
    symptomsBefore: null,
    symptomsAfter: null,
    symptoms24h: null,
    response24h: null,
    symptomDetails: '',
    functionMarker: '',
    sleepHours: null,
    nextDayPlan: '',
    notes: '',
    followUpComplete: false,
    followUpRecordedAt: null,
    updatedAt: nowIso,
    revision: 0,
    syncStatus: 'pending',
  }
}

export async function saveRehabDay(input: RehabDay, database = db): Promise<RehabDay> {
  return database.transaction('rw', database.days, database.syncOutbox, async () => {
    const existingById = await database.days.get(input.id)
    const sessionAt = input.sessionAt || sessionTimestamp(input.date, sessionTimeValue(input.updatedAt))
    const saved: RehabDay = {
      ...input,
      id: existingById?.id ?? input.id,
      sessionAt,
      followUpDueAt: followUpTimestamp(sessionAt),
      updatedAt: new Date().toISOString(),
      revision: Math.max(input.revision, existingById?.revision ?? 0) + 1,
      syncStatus: 'pending',
    }
    await database.days.put(saved)
    await database.syncOutbox.put({
      date: saved.date,
      recordId: saved.id,
      queuedRevision: saved.revision,
      retryCount: 0,
      queuedAt: saved.updatedAt,
    })
    return saved
  })
}

export async function importDays(records: RehabDay[]): Promise<number> {
  await db.transaction('rw', db.days, db.syncOutbox, async () => {
    for (const record of records) await saveRehabDay(record)
  })
  return records.length
}
