import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { followUpCalendar } from './reminders'

describe('calendar reminders', () => {
  it('creates a private 24-hour reminder for one session', () => {
    const record = { ...blankRehabDay('2026-11-01', new Date('2026-11-01T08:00:00.000Z')), sessionContent: 'Private exercise details' }
    const calendar = followUpCalendar(record)
    expect(calendar).toContain('DTSTART:20261102T110000Z')
    expect(calendar).toContain('SUMMARY:Rehab Tracker: 24h follow-up')
    expect(calendar).not.toContain(record.sessionContent)
  })
})
