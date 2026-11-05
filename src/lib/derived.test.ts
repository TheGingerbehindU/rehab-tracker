import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { dayTone, hasSession, symptomDelta, withFollowUpCompletion } from './derived'

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

  it('completes a check-in only once it has both a score and a response', () => {
    const now = new Date('2026-11-02T08:00:00.000Z')
    const blank = blankRehabDay('2026-11-01')

    expect(withFollowUpCompletion({ ...blank, symptoms24h: 2 }, now).followUpComplete).toBe(false)
    expect(withFollowUpCompletion({ ...blank, response24h: 'Same' }, now).followUpComplete).toBe(false)

    const complete = withFollowUpCompletion({ ...blank, symptoms24h: 0, response24h: 'Same' }, now)
    expect(complete.followUpComplete).toBe(true)
    expect(complete.followUpRecordedAt).toBe(now.toISOString())
  })

  it('keeps an earlier check-in complete with its original time', () => {
    const earlier = '2026-11-02T08:00:00.000Z'
    const existing = { ...blankRehabDay('2026-11-01'), followUpComplete: true, followUpRecordedAt: earlier }

    const responseOnly = withFollowUpCompletion({ ...existing, response24h: 'Better' })
    expect(responseOnly.followUpComplete).toBe(true)
    expect(responseOnly.followUpRecordedAt).toBe(earlier)

    const cleared = withFollowUpCompletion(existing)
    expect(cleared.followUpComplete).toBe(false)
    expect(cleared.followUpRecordedAt).toBeNull()
  })

  it('keeps symptom events out of session follow-ups', () => {
    const event = blankRehabDay('2026-11-01', new Date(), 'symptom_event')
    expect(hasSession(event)).toBe(false)
    expect(dayTone(event)).toBe('event')
  })
})
