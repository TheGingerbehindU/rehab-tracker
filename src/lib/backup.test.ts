import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { createBackup, parseBackup, recordsToCsv } from './backup'

describe('backup formats', () => {
  it('round-trips the versioned JSON envelope', () => {
    const backup = createBackup([blankRehabDay('2026-11-01')])
    expect(parseBackup(JSON.parse(JSON.stringify(backup))).records).toHaveLength(1)
  })

  it('guards spreadsheet exports from formula injection', () => {
    const csv = recordsToCsv([{ ...blankRehabDay('2026-11-01'), notes: '=HYPERLINK("bad")' }])
    expect(csv).toContain("'=HYPERLINK")
    expect(csv).not.toContain('\n"=HYPERLINK')
  })
})

