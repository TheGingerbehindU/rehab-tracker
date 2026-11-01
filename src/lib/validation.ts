import { isDateOnly } from './dates'
import type { RehabDay } from '../types'

export type FieldErrors = Partial<Record<keyof RehabDay, string>>

function range(value: number | null, min: number, max: number, label: string): string | undefined {
  if (value === null) return undefined
  if (!Number.isFinite(value) || value < min || value > max) {
    return `${label} must be between ${min} and ${max}.`
  }
  return undefined
}

export function validateDay(day: RehabDay): FieldErrors {
  const errors: FieldErrors = {}
  if (!isDateOnly(day.date)) errors.date = 'Enter a valid calendar date.'
  const values: Array<[keyof RehabDay, string | undefined]> = [
    ['durationMinutes', range(day.durationMinutes, 0, 1440, 'Duration')],
    ['rpe', range(day.rpe, 1, 10, 'Effort')],
    ['symptomsBefore', range(day.symptomsBefore, 0, 10, 'Symptoms before')],
    ['symptomsAfter', range(day.symptomsAfter, 0, 10, 'Symptoms after')],
    ['symptoms24h', range(day.symptoms24h, 0, 10, '24-hour symptoms')],
    ['sleepHours', range(day.sleepHours, 0, 24, 'Sleep')],
  ]
  for (const [key, message] of values) if (message) errors[key] = message
  return errors
}

export function isValidDay(day: RehabDay): boolean {
  return Object.keys(validateDay(day)).length === 0
}

export function parseOptionalNumber(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

