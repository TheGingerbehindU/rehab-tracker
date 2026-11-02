import { describe, expect, it } from 'vitest'
import { addDays, displayDate, followUpTimestamp, isDateOnly, monthDays, nearestHalfHour, shiftMonth, todayInBerlin } from './dates'

describe('date-only helpers', () => {
  it('uses the Europe/Berlin calendar day around midnight', () => {
    expect(todayInBerlin(new Date('2026-03-28T23:30:00.000Z'))).toBe('2026-03-29')
    expect(todayInBerlin(new Date('2026-10-24T22:30:00.000Z'))).toBe('2026-10-25')
  })

  it('crosses month and year boundaries without timezone shifts', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2026-03-29', -1)).toBe('2026-03-28')
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
  })

  it('builds Monday-first calendar grids', () => {
    const november = monthDays('2026-11')
    expect(november[6]).toBe('2026-11-01')
    expect(november).toHaveLength(42)
  })

  it('rejects impossible calendar dates', () => {
    expect(isDateOnly('2026-02-29')).toBe(false)
    expect(isDateOnly('2028-02-29')).toBe(true)
  })

  it('uses European numeric dates and an exact 24-hour session follow-up', () => {
    expect(displayDate('2026-11-01')).toBe('01.11.2026')
    expect(followUpTimestamp('2026-11-01T08:30:00.000Z')).toBe('2026-11-02T08:30:00.000Z')
  })

  it('rounds legacy session times to the nearest available half hour', () => {
    expect(nearestHalfHour('16:12')).toBe('16:00')
    expect(nearestHalfHour('16:22')).toBe('16:30')
    expect(nearestHalfHour('not-a-time')).toBe('12:00')
  })
})
