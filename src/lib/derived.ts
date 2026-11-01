import type { RehabDay } from '../types'

export function symptomDelta(day: Pick<RehabDay, 'symptomsBefore' | 'symptoms24h'>): number | null {
  if (day.symptomsBefore === null || day.symptoms24h === null) return null
  return day.symptoms24h - day.symptomsBefore
}

export function hasSession(day: RehabDay): boolean {
  if (day.entryType === 'symptom_event') return false
  return hasEntry(day)
}

export function hasEntry(day: RehabDay): boolean {
  return Boolean(
    day.entryType === 'symptom_event' ||
    day.sessionContent.trim() ||
      day.durationMinutes !== null ||
      day.rpe !== null ||
      day.symptomsBefore !== null ||
      day.symptomsAfter !== null,
  )
}

export type DayTone = 'rest' | 'pending' | 'favorable' | 'mild' | 'major' | 'logged' | 'event'

export function dayTone(day: RehabDay): DayTone {
  if (day.entryType === 'symptom_event') return 'event'
  if (day.sessionType === 'Recovery / rest' && !hasSession(day)) return 'rest'
  if (day.response24h === 'Significant flare') return 'major'
  if (day.response24h === 'Mild flare') return 'mild'
  if (day.response24h === 'Better' || day.response24h === 'Same') return 'favorable'
  if (hasSession(day) && !day.followUpComplete) return 'pending'
  return 'logged'
}

export function toneLabel(tone: DayTone): string {
  return {
    rest: 'Rest',
    pending: 'Follow-up pending',
    favorable: 'Settled response',
    mild: 'Mild flare',
    major: 'Significant flare',
    logged: 'Logged',
    event: 'Symptom event',
  }[tone]
}
