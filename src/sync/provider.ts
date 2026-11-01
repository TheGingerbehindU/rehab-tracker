import type { RehabDay } from '../types'

export type RemoteRecord = {
  record: RehabDay
  fingerprint: string
}

export type SyncResult =
  | { status: 'synced'; fingerprint: string }
  | { status: 'conflict'; remote: RemoteRecord }
  | { status: 'error'; message: string; retryable: boolean }

export interface CloudSyncProvider {
  readonly name: string
  probe(): Promise<{ available: boolean; message: string }>
  fetchByDate(date: string): Promise<RemoteRecord | null>
  upsert(record: RehabDay, expectedFingerprint?: string): Promise<SyncResult>
}

export class UnavailableGoogleSheetsProvider implements CloudSyncProvider {
  readonly name = 'Google Sheets (not configured)'

  async probe() {
    return {
      available: false,
      message: 'Google Sheets sync needs browser OAuth configuration and a selected spreadsheet.',
    }
  }

  async fetchByDate(): Promise<null> {
    return null
  }

  async upsert(): Promise<SyncResult> {
    return {
      status: 'error',
      message: 'Google Sheets sync is not configured. Your entry remains saved on this device.',
      retryable: false,
    }
  }
}

export type SyncEvent = 'QUEUE' | 'START' | 'SUCCESS' | 'CONFLICT' | 'FAIL'

export function nextSyncStatus(current: RehabDay['syncStatus'], event: SyncEvent): RehabDay['syncStatus'] {
  if (event === 'QUEUE') return 'pending'
  if (event === 'SUCCESS') return 'synced'
  if (event === 'CONFLICT') return 'conflict'
  if (event === 'FAIL') return 'error'
  return current
}
