import Dexie, { type EntityTable } from 'dexie'
import type { AppSetting, RehabDay, SessionType, SyncOutbox } from './types'

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
  }
}

export const db = new RehabDatabase()

export function blankRehabDay(date: string): RehabDay {
  const now = new Date().toISOString()
  return {
    id: crypto.randomUUID(),
    date,
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
    updatedAt: now,
    revision: 0,
    syncStatus: 'pending',
  }
}

export async function saveRehabDay(input: RehabDay, database = db): Promise<RehabDay> {
  return database.transaction('rw', database.days, database.outbox, async () => {
    const existingById = await database.days.get(input.id)
    const existingByDate = await database.days.where('date').equals(input.date).first()
    if (existingByDate && existingByDate.id !== input.id) {
      throw new Error('An entry already exists for this date.')
    }
    if (existingById && existingById.date !== input.date) {
      await database.days.delete(existingById.id)
      await database.outbox.delete(existingById.date)
    }
    const existing = existingById ?? existingByDate
    const saved: RehabDay = {
      ...input,
      id: existing?.id ?? input.id,
      updatedAt: new Date().toISOString(),
      revision: Math.max(input.revision, existing?.revision ?? 0) + 1,
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
