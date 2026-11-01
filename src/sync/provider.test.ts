import { describe, expect, it } from 'vitest'
import { nextSyncStatus } from './provider'

describe('sync state transitions', () => {
  it('covers queue, success, conflict and error transitions', () => {
    expect(nextSyncStatus('synced', 'QUEUE')).toBe('pending')
    expect(nextSyncStatus('pending', 'START')).toBe('pending')
    expect(nextSyncStatus('pending', 'SUCCESS')).toBe('synced')
    expect(nextSyncStatus('pending', 'CONFLICT')).toBe('conflict')
    expect(nextSyncStatus('pending', 'FAIL')).toBe('error')
  })
})

