import type { RehabDatabase } from '../db'
import type { RehabDay } from '../types'
import type { CloudSyncProvider } from './provider'
import { hasRemoteContent } from './google'

export type SyncSummary = {
  synced: number
  conflicts: number
  errors: number
  message: string
}

async function mark(database: RehabDatabase, record: RehabDay, syncStatus: RehabDay['syncStatus']) {
  await database.days.put({ ...record, syncStatus })
}

export async function syncPending(database: RehabDatabase, provider: CloudSyncProvider): Promise<SyncSummary> {
  const outbox = await database.outbox.orderBy('queuedAt').toArray()
  const summary = { synced: 0, conflicts: 0, errors: 0 }

  for (const queued of outbox) {
    const local = await database.days.get(queued.recordId)
    if (!local) {
      await database.outbox.delete(queued.recordId)
      continue
    }

    try {
      const remote = await provider.fetch(local)
      const changedRemotely = Boolean(
        remote && (
          (local.remoteFingerprint && remote.fingerprint !== local.remoteFingerprint)
          || (!local.remoteFingerprint && hasRemoteContent(remote.record))
        ),
      )

      if (changedRemotely) {
        await mark(database, local, 'conflict')
        summary.conflicts += 1
        continue
      }

      const result = await provider.upsert(local, remote?.fingerprint)
      if (result.status === 'conflict') {
        await mark(database, local, 'conflict')
        summary.conflicts += 1
        continue
      }
      if (result.status === 'error') {
        await database.transaction('rw', database.days, database.outbox, async () => {
          await mark(database, local, 'error')
          await database.outbox.put({ ...queued, retryCount: queued.retryCount + 1 })
        })
        summary.errors += 1
        continue
      }

      await database.transaction('rw', database.days, database.outbox, async () => {
        const latest = await database.days.get(local.id)
        if (!latest) return
        const unchanged = latest.revision === queued.queuedRevision
        await database.days.put({
          ...latest,
          remoteFingerprint: result.fingerprint,
          syncStatus: unchanged ? 'synced' : 'pending',
        })
        if (unchanged) await database.outbox.delete(queued.recordId)
      })
      summary.synced += 1
    } catch {
      await mark(database, local, 'error')
      summary.errors += 1
    }
  }

  const parts = []
  if (summary.synced) parts.push(`${summary.synced} synced`)
  if (summary.conflicts) parts.push(`${summary.conflicts} ${summary.conflicts === 1 ? 'conflict needs' : 'conflicts need'} review`)
  if (summary.errors) parts.push(`${summary.errors} ${summary.errors === 1 ? 'entry remains' : 'entries remain'} queued`)
  return { ...summary, message: parts.join(' · ') || 'Everything is already up to date.' }
}
