import { afterEach, describe, expect, it } from 'vitest'
import { blankRehabDay, RehabDatabase, saveRehabDay } from '../db'
import type { RemoteRecord, SyncResult, CloudSyncProvider } from './provider'
import { syncPending } from './orchestrator'

class TestProvider implements CloudSyncProvider {
  readonly name = 'Test provider'
  remote: RemoteRecord | null = null

  async probe() { return { available: true, message: 'Ready' } }
  async fetch() { return this.remote }
  async upsert(record: ReturnType<typeof blankRehabDay>): Promise<SyncResult> {
    this.remote = { record: { ...record, syncStatus: 'synced' }, fingerprint: `revision-${record.revision}` }
    return { status: 'synced', fingerprint: this.remote.fingerprint }
  }
}

describe('sync orchestrator', () => {
  const databases: RehabDatabase[] = []

  afterEach(async () => {
    for (const database of databases) {
      database.close()
      await database.delete()
    }
    databases.length = 0
  })

  it('removes an unchanged revision from the outbox after a verified write', async () => {
    const database = new RehabDatabase(`sync-${crypto.randomUUID()}`)
    databases.push(database)
    const saved = await saveRehabDay({ ...blankRehabDay('2026-11-01'), sessionContent: 'Session' }, database)

    const result = await syncPending(database, new TestProvider())

    expect(result.synced).toBe(1)
    expect(await database.syncOutbox.count()).toBe(0)
    expect((await database.days.get(saved.id))?.syncStatus).toBe('synced')
  })

  it('keeps a coach-edited row queued as a conflict', async () => {
    const database = new RehabDatabase(`sync-${crypto.randomUUID()}`)
    databases.push(database)
    const saved = await saveRehabDay({ ...blankRehabDay('2026-11-01'), sessionContent: 'Local version' }, database)
    const provider = new TestProvider()
    provider.remote = {
      record: { ...blankRehabDay(saved.date), sessionContent: 'Coach version', syncStatus: 'synced' },
      fingerprint: 'coach-change',
    }

    const result = await syncPending(database, provider)

    expect(result.conflicts).toBe(1)
    expect(await database.syncOutbox.count()).toBe(1)
    expect((await database.days.get(saved.id))?.syncStatus).toBe('conflict')
  })
})
