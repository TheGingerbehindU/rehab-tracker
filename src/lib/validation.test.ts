import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { parseOptionalNumber, validateDay } from './validation'

describe('entry validation', () => {
  it('preserves zero while treating an empty value as unknown', () => {
    expect(parseOptionalNumber('0')).toBe(0)
    expect(parseOptionalNumber('')).toBeNull()
  })

  it('accepts boundary values', () => {
    const day = { ...blankRehabDay('2026-11-01'), symptomsBefore: 0, symptoms24h: 10, rpe: 1, sleepHours: 24 }
    expect(validateDay(day)).toEqual({})
  })

  it('reports out-of-range fields', () => {
    const day = { ...blankRehabDay('2026-11-01'), durationMinutes: -1, rpe: 11, symptomsAfter: -0.1, sleepHours: 25 }
    expect(validateDay(day)).toMatchObject({
      durationMinutes: expect.any(String),
      rpe: expect.any(String),
      symptomsAfter: expect.any(String),
      sleepHours: expect.any(String),
    })
  })
})

