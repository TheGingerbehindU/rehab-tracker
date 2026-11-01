import type { BackupFile, RehabDay } from '../types'
import { followUpTimestamp, sessionTimestamp } from './dates'

export function createBackup(records: RehabDay[]): BackupFile {
  return {
    schemaVersion: 3,
    exportedAt: new Date().toISOString(),
    app: 'Rehab Tracker',
    records,
  }
}

export function parseBackup(value: unknown): BackupFile {
  if (!value || typeof value !== 'object') throw new Error('This is not a Rehab Tracker backup.')
  const candidate = value as Partial<BackupFile> & { schemaVersion?: number; records?: Array<Partial<RehabDay>> }
  if (![1, 2, 3].includes(candidate.schemaVersion ?? 0) || !Array.isArray(candidate.records)) {
    throw new Error('This backup version is not supported.')
  }
  const records = candidate.records.map((record) => {
    const sessionAt = record.sessionAt || sessionTimestamp(record.date || '1970-01-01', '12:00')
    return {
      ...record,
      sessionAt,
      followUpDueAt: record.followUpDueAt || followUpTimestamp(sessionAt),
      entryType: record.entryType || 'session',
      eventContext: record.eventContext || null,
      eventChange: record.eventChange || null,
    } as RehabDay
  })
  return { ...candidate, schemaVersion: 3, records } as BackupFile
}

const CSV_FIELDS: Array<[string, keyof RehabDay]> = [
  ['Entry type', 'entryType'],
  ['Date', 'date'],
  ['Session time', 'sessionAt'],
  ['Event context', 'eventContext'],
  ['Symptom change', 'eventChange'],
  ['Day / activity type', 'sessionType'],
  ['Session content (exercises, sets, reps, loads)', 'sessionContent'],
  ['Duration (min)', 'durationMinutes'],
  ['Effort (RPE 1–10)', 'rpe'],
  ['Symptoms before (0–10)', 'symptomsBefore'],
  ['Immediately after (0–10)', 'symptomsAfter'],
  ['24h later (0–10)', 'symptoms24h'],
  ['24h response', 'response24h'],
  ['Symptom location / type', 'symptomDetails'],
  ['Function / movement marker', 'functionMarker'],
  ['Sleep (hours)', 'sleepHours'],
  ['Next-day decision / adjustment', 'nextDayPlan'],
  ['Other context / notes', 'notes'],
  ['24h follow-up done?', 'followUpComplete'],
]

function safeCsvCell(value: unknown): string {
  let text = value === null || value === undefined ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return `"${text.replaceAll('"', '""')}"`
}

export function recordsToCsv(records: RehabDay[]): string {
  const lines = [CSV_FIELDS.map(([header]) => safeCsvCell(header)).join(',')]
  for (const record of [...records].sort((a, b) => a.date.localeCompare(b.date))) {
    lines.push(CSV_FIELDS.map(([, key]) => safeCsvCell(record[key])).join(','))
  }
  return lines.join('\r\n')
}

export function downloadFile(filename: string, content: string, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
