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

export const EVENT_CONTEXTS = [
  'Sitting',
  'Standing',
  'Walking',
  'Driving / travel',
  'Resting',
  'After training',
  'Other',
] as const

export const EVENT_CHANGES = ['Worse', 'Better', 'No clear change'] as const

export type SessionType = (typeof SESSION_TYPES)[number]
export type Response24h = (typeof RESPONSE_TYPES)[number]
export type EntryType = 'session' | 'symptom_event'
export type EventContext = (typeof EVENT_CONTEXTS)[number]
export type EventChange = (typeof EVENT_CHANGES)[number]
export type SyncStatus = 'pending' | 'synced' | 'conflict' | 'error'

export type RehabDay = {
  id: string
  date: string
  sessionAt: string
  followUpDueAt: string
  entryType: EntryType
  eventContext: EventContext | null
  eventChange: EventChange | null
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
  schemaVersion: 3
  exportedAt: string
  app: 'Rehab Tracker'
  records: RehabDay[]
}
