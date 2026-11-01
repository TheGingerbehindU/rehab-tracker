import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { hasRemoteContent, recordFromSheetRow, sheetValues } from './google'

describe('Google Sheets row mapping', () => {
  it('maps the workbook columns without using formula column I', () => {
    const record = recordFromSheetRow('2026-11-01', [
      46327,
      'Running',
      '4 × 60 m',
      45,
      7,
      0,
      2,
      1,
      1,
      'Same',
      'Achilles',
      'Hops',
      8,
      'Continue as planned',
      'Good surface',
      'Yes',
      '2026-11-01T08:00:00.000Z',
      'session-1',
      '2026-11-02T08:00:00.000Z',
    ])

    expect(record.symptomsBefore).toBe(0)
    expect(record.symptoms24h).toBe(1)
    expect(record.response24h).toBe('Same')
    expect(record.followUpComplete).toBe(true)
    expect(record.id).toBe('session-1')
    expect(record.followUpDueAt).toBe('2026-11-02T08:00:00.000Z')
    expect(sheetValues(record).first).toEqual(['Running', '4 × 60 m', 45, 7, 0, 2, 1])
    expect(sheetValues(record).second).toEqual(['Same', 'Achilles', 'Hops', 8, 'Continue as planned', 'Good surface', 'Yes'])
    expect(sheetValues(record).metadata).toEqual(['2026-11-01T08:00:00.000Z', 'session-1', '2026-11-02T08:00:00.000Z', 'session', '', ''])
  })

  it('treats a prefilled date-only row as empty remote state', () => {
    const remote = recordFromSheetRow('2026-11-01', [46327])
    expect(hasRemoteContent(remote)).toBe(false)

    const edited = { ...blankRehabDay('2026-11-01'), sessionContent: 'Rehab session' }
    expect(hasRemoteContent(edited)).toBe(true)
  })

  it('keeps symptom-event context in the metadata columns', () => {
    const event = {
      ...blankRehabDay('2026-11-01', new Date('2026-11-01T18:00:00.000Z'), 'symptom_event'),
      eventContext: 'Sitting' as const,
      eventChange: 'Worse' as const,
      sessionContent: 'Dinner at a restaurant',
    }
    const values = sheetValues(event)
    const restored = recordFromSheetRow(event.date, [
      event.date,
      ...values.first,
      '',
      ...values.second,
      ...values.metadata,
    ])

    expect(restored.entryType).toBe('symptom_event')
    expect(restored.eventContext).toBe('Sitting')
    expect(restored.eventChange).toBe('Worse')
  })
})
