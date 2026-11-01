import { describe, expect, it } from 'vitest'
import { blankRehabDay } from '../db'
import { createBackup, parseBackup, recordsToCsv } from './backup'

describe('backup formats', () => {
  it('round-trips the versioned JSON envelope', () => {
    const backup = createBackup([blankRehabDay('2026-11-01')])
    expect(parseBackup(JSON.parse(JSON.stringify(backup))).records).toHaveLength(1)
  })

  it('upgrades older records to session entries', () => {
    const legacy = createBackup([blankRehabDay('2026-11-01')]) as unknown as { schemaVersion: number; records: Array<Record<string, unknown>> }
    legacy.schemaVersion = 2
    delete legacy.records[0].entryType
    delete legacy.records[0].eventContext
    delete legacy.records[0].eventChange
    expect(parseBackup(legacy).records[0].entryType).toBe('session')
  })

  it('guards spreadsheet exports from formula injection', () => {
    const csv = recordsToCsv([{ ...blankRehabDay('2026-11-01'), notes: '=HYPERLINK("bad")' }])
    expect(csv).toContain("'=HYPERLINK")
    expect(csv).not.toContain('\n"=HYPERLINK')
  })
})
