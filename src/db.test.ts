import { afterEach, describe, expect, it } from 'vitest'
import Dexie from 'dexie'
import { blankRehabDay, latestSessionTime, RehabDatabase, saveRehabDay } from './db'

describe('local upsert and outbox', () => {
  const databases: RehabDatabase[] = []

  afterEach(async () => {
    for (const database of databases) {
      database.close()
      await database.delete()
    }
    databases.length = 0
  })

  it('keeps several sessions on one date and one outbox item per session', async () => {
    const database = new RehabDatabase(`test-${crypto.randomUUID()}`)
    databases.push(database)
    const first = await saveRehabDay({ ...blankRehabDay('2026-11-01'), sessionContent: 'First version' }, database)
    await saveRehabDay({ ...first, sessionContent: 'Edited version' }, database)
    const second = await saveRehabDay({ ...blankRehabDay('2026-11-01'), sessionContent: 'Evening session' }, database)

    expect(await database.days.where('date').equals('2026-11-01').count()).toBe(2)
    expect((await database.days.get(first.id))?.sessionContent).toBe('Edited version')
    expect(await database.syncOutbox.count()).toBe(2)
    expect((await database.syncOutbox.get(first.id))?.queuedRevision).toBe(2)
    expect((await database.syncOutbox.get(second.id))?.queuedRevision).toBe(1)
  })

  it('stores symptom events beside sessions on the same date', async () => {
    const database = new RehabDatabase(`test-${crypto.randomUUID()}`)
    databases.push(database)
    await saveRehabDay({ ...blankRehabDay('2026-11-01'), sessionContent: 'Rehab session' }, database)
    await saveRehabDay({ ...blankRehabDay('2026-11-01', new Date(), 'symptom_event'), sessionContent: 'Sitting at dinner' }, database)

    const entries = await database.days.where('date').equals('2026-11-01').toArray()
    expect(entries).toHaveLength(2)
    expect(entries.map((entry) => entry.entryType).sort()).toEqual(['session', 'symptom_event'])
  })

  it('defaults new sessions to the latest training start time or noon', async () => {
    const database = new RehabDatabase(`test-${crypto.randomUUID()}`)
    databases.push(database)
    expect(await latestSessionTime(database)).toBe('12:00')

    await saveRehabDay({ ...blankRehabDay('2026-11-01', new Date(), 'session', '16:30'), sessionContent: 'Afternoon rehab' }, database)
    await saveRehabDay({ ...blankRehabDay('2026-11-02', new Date(), 'symptom_event'), sessionContent: 'Dinner' }, database)

    expect(await latestSessionTime(database)).toBe('16:30')
  })

  it('upgrades the original date-keyed sync queue without losing records', async () => {
    const name = `test-${crypto.randomUUID()}`
    const legacy = new Dexie(name)
    legacy.version(1).stores({
      days: 'id, &date, updatedAt, syncStatus',
      outbox: '&date, recordId, queuedAt',
      settings: '&key',
    })
    const legacyRecord = { ...blankRehabDay('2026-11-01'), sessionContent: 'Legacy rehab entry' } as Record<string, unknown>
    delete legacyRecord.entryType
    delete legacyRecord.eventContext
    delete legacyRecord.eventChange
    await legacy.table('days').put(legacyRecord)
    await legacy.table('outbox').put({ date: '2026-11-01', recordId: legacyRecord.id, queuedAt: legacyRecord.updatedAt })
    legacy.close()

    const database = new RehabDatabase(name)
    databases.push(database)
    const restored = await database.days.get(String(legacyRecord.id))

    expect(restored?.sessionContent).toBe('Legacy rehab entry')
    expect(restored?.entryType).toBe('session')
    expect(await database.syncOutbox.count()).toBe(1)
  })
})
