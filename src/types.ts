export const SESSION_TYPES = [
  'Strength',
  'Running',
  'Rehab',
  'Recovery / rest',
  'Other',
] as const

export const RESPONSE_TYPES = [
  'Better',
  'Same',
  'Mild flare',
  'Significant flare',
] as const

export type SessionType = (typeof SESSION_TYPES)[number]
export type Response24h = (typeof RESPONSE_TYPES)[number]
export type SyncStatus = 'pending' | 'synced' | 'conflict' | 'error'

export type RehabDay = {
  id: string
  date: string
  sessionAt: string
  followUpDueAt: string
  sessionType: SessionType
  sessionContent: string
  durationMinutes: number | null
  rpe: number | null
  symptomsBefore: number | null
  symptomsAfter: number | null
  symptoms24h: number | null
  response24h: Response24h | null
  symptomDetails: string
  functionMarker: string
  sleepHours: number | null
  nextDayPlan: string
  notes: string
  followUpComplete: boolean
  followUpRecordedAt: string | null
  updatedAt: string
  revision: number
  syncStatus: SyncStatus
  remoteFingerprint?: string
}

export type SyncOutbox = {
  date: string
  recordId: string
  queuedRevision: number
  retryCount: number
  queuedAt: string
}

export type AppSetting = {
  key: string
  value: string | number | boolean | null
}

export type BackupFile = {
  schemaVersion: 2
  exportedAt: string
  app: 'Rehab Tracker'
  records: RehabDay[]
}
