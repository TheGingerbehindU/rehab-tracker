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
    ])

    expect(record.symptomsBefore).toBe(0)
    expect(record.symptoms24h).toBe(1)
    expect(record.response24h).toBe('Same')
    expect(record.followUpComplete).toBe(true)
    expect(sheetValues(record).first).toEqual(['Running', '4 × 60 m', 45, 7, 0, 2, 1])
    expect(sheetValues(record).second).toEqual(['Same', 'Achilles', 'Hops', 8, 'Continue as planned', 'Good surface', 'Yes'])
  })

  it('treats a prefilled date-only row as empty remote state', () => {
    const remote = recordFromSheetRow('2026-11-01', [46327])
    expect(hasRemoteContent(remote)).toBe(false)

    const edited = { ...blankRehabDay('2026-11-01'), sessionContent: 'Rehab session' }
    expect(hasRemoteContent(edited)).toBe(true)
  })
})
