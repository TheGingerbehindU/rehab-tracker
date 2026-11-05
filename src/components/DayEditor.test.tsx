import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { blankRehabDay, db, saveRehabDay } from '../db'
import { DayEditor } from './DayEditor'

const DATE = '2026-11-01'
const CONTENT_PLACEHOLDER = 'Exercises, sets, reps and loads'

function renderNew() {
  return render(<DayEditor date={DATE} entryType="session" onBack={vi.fn()} onSaved={vi.fn()} />)
}

describe('DayEditor saving', () => {
  beforeEach(async () => {
    // jsdom has no layout, so it doesn't implement scrolling.
    Element.prototype.scrollIntoView = vi.fn()
    await db.days.clear()
    await db.syncOutbox.clear()
  })

  afterEach(cleanup)

  it('saves edits when leaving before the autosave timer fires', async () => {
    const { unmount } = renderNew()
    await waitFor(() => expect(screen.getByPlaceholderText(CONTENT_PLACEHOLDER)).toBeEnabled())
    fireEvent.change(screen.getByPlaceholderText(CONTENT_PLACEHOLDER), { target: { value: 'Quick exit' } })
    unmount()

    await waitFor(async () => expect((await db.days.toArray()).map((day) => day.sessionContent)).toEqual(['Quick exit']))
  })

  it('saves edits when the app is hidden', async () => {
    renderNew()
    await waitFor(() => expect(screen.getByPlaceholderText(CONTENT_PLACEHOLDER)).toBeEnabled())
    fireEvent.change(screen.getByPlaceholderText(CONTENT_PLACEHOLDER), { target: { value: 'App closed' } })

    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    fireEvent(document, new Event('visibilitychange'))
    visibility.mockRestore()

    await waitFor(async () => expect((await db.days.toArray()).map((day) => day.sessionContent)).toEqual(['App closed']))
  })

  it('does not save an invalid entry on exit', async () => {
    const { unmount } = renderNew()
    await waitFor(() => expect(screen.getByLabelText(/Effort/)).toBeEnabled())
    fireEvent.change(screen.getByLabelText(/Effort/), { target: { value: '11' } })
    unmount()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(await db.days.count()).toBe(0)
  })

  it('does not create a duplicate when typing before an existing record has loaded', async () => {
    const saved = await saveRehabDay({ ...blankRehabDay(DATE), sessionContent: 'Original' })
    const { unmount } = render(<DayEditor date={DATE} recordId={saved.id} entryType="session" onBack={vi.fn()} onSaved={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText(CONTENT_PLACEHOLDER), { target: { value: 'Typed too early' } })

    await screen.findByText('Saved locally')
    unmount()
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect((await db.days.toArray()).map((day) => day.id)).toEqual([saved.id])
  })

  it('keeps typing that arrives while a database refresh is loading', async () => {
    const saved = await saveRehabDay({ ...blankRehabDay(DATE), sessionContent: 'Original' })
    render(<DayEditor date={DATE} recordId={saved.id} entryType="session" onBack={vi.fn()} onSaved={vi.fn()} />)
    await screen.findByText('Saved locally')
    const content = screen.getByPlaceholderText(CONTENT_PLACEHOLDER)

    fireEvent.change(content, { target: { value: 'Still typing' } })
    await db.days.update(saved.id, { notes: 'Refreshed from elsewhere' })
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(content).toHaveValue('Still typing')
  })

  it('completes a check-in once it has a score and a response', async () => {
    const saved = await saveRehabDay({ ...blankRehabDay(DATE), sessionContent: 'Bridges', symptomsBefore: 3 })
    render(<DayEditor date={DATE} recordId={saved.id} entryType="session" focusFollowUp onBack={vi.fn()} onSaved={vi.fn()} />)
    await screen.findByText('Saved locally')

    fireEvent.change(screen.getByLabelText(/Symptoms now/), { target: { value: '2' } })
    expect(screen.getByRole('status')).toHaveTextContent('Add a score and a response to finish')

    fireEvent.click(screen.getByLabelText('Better'))
    expect(screen.getByRole('status')).toHaveTextContent('Check-in complete')

    await waitFor(async () => {
      const stored = await db.days.get(saved.id)
      expect(stored?.followUpComplete).toBe(true)
      expect(stored?.followUpRecordedAt).not.toBeNull()
    })
  })
})
