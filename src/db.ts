import Dexie, { type EntityTable } from 'dexie'
import type { AppSetting, RehabDay, SessionType, SyncOutbox } from './types'
import { followUpTimestamp, sessionTimestamp, sessionTimeValue, todayInBerlin } from './lib/dates'

export class RehabDatabase extends Dexie {
  days!: EntityTable<RehabDay, 'id'>
  outbox!: EntityTable<SyncOutbox, 'date'>
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
      outbox: '&recordId, date, queuedAt',
      settings: '&key',
    }).upgrade(async (transaction) => {
      await transaction.table('days').toCollection().modify((record: RehabDay) => {
        const sessionAt = record.sessionAt || sessionTimestamp(record.date, '12:00')
        record.sessionAt = sessionAt
        record.followUpDueAt = record.followUpDueAt || followUpTimestamp(sessionAt)
      })
    })
  }
}

export const db = new RehabDatabase()

export function blankRehabDay(date: string, now = new Date()): RehabDay {
  const nowIso = now.toISOString()
  const sessionAt = date === todayInBerlin(now)
    ? nowIso
    : sessionTimestamp(date, '12:00')
  return {
    id: crypto.randomUUID(),
    date,
    sessionAt,
    followUpDueAt: followUpTimestamp(sessionAt),
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
  return database.transaction('rw', database.days, database.outbox, async () => {
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
    await database.outbox.put({
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
  await db.transaction('rw', db.days, db.outbox, async () => {
    for (const record of records) await saveRehabDay(record)
  })
  return records.length
}
