import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { dayTone, symptomDelta } from './derived'

describe('derived response values', () => {
  it('calculates a delta only when both values exist', () => {
    expect(symptomDelta({ symptomsBefore: 2, symptoms24h: 4 })).toBe(2)
    expect(symptomDelta({ symptomsBefore: 0, symptoms24h: 0 })).toBe(0)
    expect(symptomDelta({ symptomsBefore: 2, symptoms24h: null })).toBeNull()
  })

  it('maps recorded responses to neutral UI tones', () => {
    expect(dayTone({ ...blankRehabDay('2026-11-01'), sessionContent: '3 × 8', response24h: 'Mild flare' })).toBe('mild')
    expect(dayTone({ ...blankRehabDay('2026-11-01'), sessionContent: '3 × 8' })).toBe('pending')
  })
})

