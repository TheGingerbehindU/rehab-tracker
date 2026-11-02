import { db } from '../db'
import { googleClientConfig, GoogleDriveSession } from './google'
import { syncPending, type SyncSummary } from './orchestrator'

export const GOOGLE_SETTING_KEYS = {
  id: 'google.spreadsheetId',
  name: 'google.spreadsheetName',
  url: 'google.spreadsheetUrl',
  lastSync: 'google.lastSuccessfulSync',
} as const

let sharedSession: GoogleDriveSession | null | undefined
let activeSync: Promise<SyncSummary | null> | null = null
let automaticTimer: number | null = null
let automaticRequested = false

export function googleDriveSession(): GoogleDriveSession | null {
  if (sharedSession !== undefined) return sharedSession
  const config = googleClientConfig()
  sharedSession = config ? new GoogleDriveSession(config) : null
  return sharedSession
}

export function disconnectGoogleDrive() {
  googleDriveSession()?.disconnect()
}

async function performSync(authorize: boolean): Promise<SyncSummary | null> {
  if (typeof navigator !== 'undefined' && !navigator.onLine) return null
  const selected = await db.settings.get(GOOGLE_SETTING_KEYS.id)
  const spreadsheetId = typeof selected?.value === 'string' ? selected.value : ''
  if (!spreadsheetId) return null

  const session = googleDriveSession()
  if (!session) return null
  if (!session.connected) {
    if (!authorize) return null
    await session.authorize(import.meta.env.VITE_GOOGLE_LOGIN_HINT?.trim())
  }

  const provider = session.provider(spreadsheetId)
  const probe = await provider.probe()
  if (!probe.available) throw new Error(probe.message)
  const result = await syncPending(db, provider)
  if (result.errors === 0 && result.conflicts === 0) {
    await db.settings.put({ key: GOOGLE_SETTING_KEYS.lastSync, value: new Date().toISOString() })
  }
  return result
}

export function syncSelectedGoogleSheet(options: { authorize?: boolean } = {}): Promise<SyncSummary | null> {
  if (activeSync) return activeSync
  activeSync = performSync(Boolean(options.authorize)).finally(() => {
    activeSync = null
  })
  return activeSync
}

export function scheduleAutomaticGoogleSync(delay = 1_500) {
  automaticRequested = true
  if (automaticTimer !== null) window.clearTimeout(automaticTimer)
  automaticTimer = window.setTimeout(async () => {
    automaticTimer = null
    automaticRequested = false
    try {
      await syncSelectedGoogleSheet()
    } catch {
      // Automatic sync stays quiet; the queued count and manual sync remain visible.
    } finally {
      if (automaticRequested) scheduleAutomaticGoogleSync()
    }
  }, delay)
}
