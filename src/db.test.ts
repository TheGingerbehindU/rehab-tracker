import { afterEach, describe, expect, it } from 'vitest'
import { blankRehabDay, RehabDatabase, saveRehabDay } from './db'

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
    expect(await database.outbox.count()).toBe(2)
    expect((await database.outbox.get(first.id))?.queuedRevision).toBe(2)
    expect((await database.outbox.get(second.id))?.queuedRevision).toBe(1)
  })
})
