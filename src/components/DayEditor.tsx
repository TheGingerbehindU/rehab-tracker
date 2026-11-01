import { useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { ArrowLeft, CalendarPlus, Check, Clock3, Save } from 'lucide-react'
import { blankRehabDay, db, saveRehabDay } from '../db'
import { displayDate, displayDateTime, elapsedHours, followUpTimestamp, isDateOnly, sessionTimestamp, sessionTimeValue } from '../lib/dates'
import { symptomDelta } from '../lib/derived'
import { downloadFollowUpReminder } from '../lib/reminders'
import { parseOptionalNumber, validateDay } from '../lib/validation'
import { EVENT_CHANGES, EVENT_CONTEXTS, RESPONSE_TYPES, SESSION_TYPES, type EntryType, type RehabDay } from '../types'

type Props = {
  date: string
  recordId?: string
  entryType?: EntryType
  focusFollowUp?: boolean
  onBack: () => void
  onSaved: (record: RehabDay) => void
}

type SaveState = 'ready' | 'saving' | 'saved' | 'error'

type NumberFieldProps = {
  label: string
  name: keyof RehabDay
  value: number | null
  min: number
  max: number
  step?: number
  suffix?: string
  error?: string
  onChange: (value: number | null) => void
}

function NumberField({ label, value, min, max, step = 1, suffix, error, onChange }: NumberFieldProps) {
  return (
    <label className="field number-field">
      <span>{label}</span>
      <span className="number-wrap">
        <input
          type="number"
          inputMode="decimal"
          value={value ?? ''}
          min={min}
          max={max}
          step={step}
          placeholder="—"
          aria-invalid={Boolean(error)}
          onChange={(event) => onChange(parseOptionalNumber(event.target.value))}
        />
        {suffix && <small>{suffix}</small>}
      </span>
      {error && <span className="field-error">{error}</span>}
    </label>
  )
}

function EuropeanDateField({ value, error, onChange }: { value: string; error?: string; onChange(value: string): void }) {
  const [input, setInput] = useState(() => displayDate(value))
  const [inputError, setInputError] = useState<string | null>(null)

  function commit(next: string) {
    setInput(next)
    const match = next.match(/^(\d{1,2})[./](\d{1,2})[./](\d{4})$/)
    if (!match) return
    const iso = `${match[3]}-${match[2].padStart(2, '0')}-${match[1].padStart(2, '0')}`
    if (!isDateOnly(iso)) return
    setInputError(null)
    onChange(iso)
  }

  function validateInput() {
    const match = input.match(/^(\d{1,2})[./](\d{1,2})[./](\d{4})$/)
    const iso = match ? `${match[3]}-${match[2].padStart(2, '0')}-${match[1].padStart(2, '0')}` : ''
    if (!isDateOnly(iso)) setInputError('Use DD.MM.YYYY and enter a valid date.')
  }

  return (
    <label className="field">
      <span>Date</span>
      <input inputMode="numeric" autoComplete="off" value={input} placeholder="DD.MM.YYYY" aria-invalid={Boolean(error || inputError)} onChange={(event) => commit(event.target.value)} onBlur={validateInput} />
      {(error || inputError) && <span className="field-error">{error || inputError}</span>}
    </label>
  )
}

function EuropeanTimeField({ value, entryType, onChange }: { value: string; entryType: EntryType; onChange(value: string): void }) {
  const [input, setInput] = useState(() => sessionTimeValue(value))
  const [inputError, setInputError] = useState<string | null>(null)

  function commit(next: string) {
    setInput(next)
    const match = next.match(/^(\d{1,2}):(\d{2})$/)
    if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) return
    setInputError(null)
    onChange(`${match[1].padStart(2, '0')}:${match[2]}`)
  }

  return (
    <label className="field">
      <span>{entryType === 'symptom_event' ? 'Event time' : 'Session time'}</span>
      <input inputMode="numeric" autoComplete="off" value={input} placeholder="HH:MM" aria-invalid={Boolean(inputError)} onChange={(event) => commit(event.target.value)} onBlur={() => {
        if (!/^([01]?\d|2[0-3]):[0-5]\d$/.test(input)) setInputError('Use 24-hour time, for example 18:30.')
      }} />
      <small className="field-hint">{entryType === 'symptom_event' ? '24-hour time · use when you noticed the change.' : '24-hour time · starts the follow-up timer.'}</small>
      {inputError && <span className="field-error">{inputError}</span>}
    </label>
  )
}

export function DayEditor({ date, recordId, entryType = 'session', focusFollowUp = false, onBack, onSaved }: Props) {
  const stored = useLiveQuery(() => recordId ? db.days.get(recordId) : undefined, [recordId])
  const [draft, setDraft] = useState<RehabDay>(() => blankRehabDay(date, new Date(), entryType))
  const [saveState, setSaveState] = useState<SaveState>('ready')
  const [saveMessage, setSaveMessage] = useState('Not saved yet')
  const [currentTime, setCurrentTime] = useState(() => Date.now())
  const dirty = useRef(false)
  const followUpRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (recordId && stored === undefined) return
    dirty.current = false
    setDraft(stored ?? blankRehabDay(date, new Date(), entryType))
    setSaveState(stored ? 'saved' : 'ready')
    setSaveMessage(stored ? 'Saved locally' : 'Not saved yet')
  }, [date, entryType, recordId, stored])

  useEffect(() => {
    if (focusFollowUp) requestAnimationFrame(() => followUpRef.current?.scrollIntoView({ behavior: 'smooth' }))
  }, [focusFollowUp])

  useEffect(() => {
    const timer = window.setInterval(() => setCurrentTime(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const errors = useMemo(() => validateDay(draft), [draft])
  const delta = symptomDelta(draft)
  const hours = elapsedHours(draft.followUpRecordedAt, draft.sessionAt)
  const followUpDue = new Date(draft.followUpDueAt).getTime() <= currentTime

  function update<K extends keyof RehabDay>(key: K, value: RehabDay[K]) {
    dirty.current = true
    setSaveState('saving')
    setSaveMessage('Saving…')
    setDraft((current) => ({ ...current, [key]: value }))
  }

  function updateDate(value: string) {
    const sessionAt = sessionTimestamp(value, sessionTimeValue(draft.sessionAt))
    dirty.current = true
    setDraft((current) => ({ ...current, date: value, sessionAt, followUpDueAt: followUpTimestamp(sessionAt) }))
  }

  function updateTime(value: string) {
    const sessionAt = sessionTimestamp(draft.date, value)
    dirty.current = true
    setDraft((current) => ({ ...current, sessionAt, followUpDueAt: followUpTimestamp(sessionAt) }))
  }

  async function persist(current = draft) {
    if (Object.keys(validateDay(current)).length) {
      setSaveState('error')
      setSaveMessage('Check the highlighted fields')
      return
    }
    try {
      setSaveState('saving')
      setSaveMessage('Saving…')
      const saved = await saveRehabDay(current)
      dirty.current = false
      setDraft(saved)
      setSaveState('saved')
      setSaveMessage('Saved locally · Waiting to sync')
      onSaved(saved)
    } catch (error) {
      setSaveState('error')
      setSaveMessage(error instanceof Error ? error.message : 'Could not save this entry')
    }
  }

  useEffect(() => {
    if (!dirty.current) return
    const timer = window.setTimeout(() => void persist(draft), 650)
    return () => window.clearTimeout(timer)
    // Persist is intentionally driven by the latest draft snapshot.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft])

  function completeFollowUp(complete: boolean) {
    dirty.current = true
    setDraft((current) => ({
      ...current,
      followUpComplete: complete,
      followUpRecordedAt: complete ? current.followUpRecordedAt ?? new Date().toISOString() : null,
    }))
  }

  return (
    <main className="editor-page page-shell">
      <header className="editor-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="Back">
          <ArrowLeft aria-hidden="true" />
        </button>
        <div>
          <p className="context-label">{draft.entryType === 'symptom_event' ? (recordId ? 'Symptom event' : 'New symptom event') : (recordId ? 'Session' : 'New session')} · {displayDate(draft.date, { weekday: 'long' })}</p>
          <h1>{displayDate(draft.date)}</h1>
        </div>
        <span className={`save-indicator ${saveState}`} aria-live="polite">
          {saveState === 'saved' && <Check size={16} aria-hidden="true" />}
          {saveMessage}
        </span>
      </header>

      <div className="editor-grid">
        {draft.entryType === 'session' ? <>
        <section className="form-section training-section">
          <div className="section-heading">
            <span className="section-number">1</span>
            <div><h2>Training</h2><p>What did you ask your body to do?</p></div>
          </div>
          <div className="field-row session-when-row">
            <EuropeanDateField key={draft.date} value={draft.date} error={errors.date} onChange={updateDate} />
            <EuropeanTimeField key={draft.sessionAt} value={draft.sessionAt} entryType={draft.entryType} onChange={updateTime} />
            <label className="field">
              <span>Session type</span>
              <select value={draft.sessionType} onChange={(event) => update('sessionType', event.target.value as RehabDay['sessionType'])}>
                {SESSION_TYPES.map((type) => <option key={type}>{type}</option>)}
              </select>
            </label>
          </div>
          <label className="field">
            <span>Session content</span>
            <textarea
              rows={5}
              value={draft.sessionContent}
              placeholder="Exercises, sets, reps and loads"
              onChange={(event) => update('sessionContent', event.target.value)}
            />
          </label>
          <div className="metric-grid">
            <NumberField name="durationMinutes" label="Duration" value={draft.durationMinutes} min={0} max={1440} suffix="min" error={errors.durationMinutes} onChange={(value) => update('durationMinutes', value)} />
            <NumberField name="rpe" label="Effort" value={draft.rpe} min={1} max={10} suffix="/ 10" error={errors.rpe} onChange={(value) => update('rpe', value)} />
            <NumberField name="sleepHours" label="Sleep" value={draft.sleepHours} min={0} max={24} step={0.25} suffix="hours" error={errors.sleepHours} onChange={(value) => update('sleepHours', value)} />
          </div>
        </section>

        <section className="form-section">
          <div className="section-heading">
            <span className="section-number">2</span>
            <div><h2>Before and after</h2><p>Use your own consistent 0–10 scale.</p></div>
          </div>
          <div className="metric-grid two">
            <NumberField name="symptomsBefore" label="Symptoms before" value={draft.symptomsBefore} min={0} max={10} suffix="/ 10" error={errors.symptomsBefore} onChange={(value) => update('symptomsBefore', value)} />
            <NumberField name="symptomsAfter" label="Immediately after" value={draft.symptomsAfter} min={0} max={10} suffix="/ 10" error={errors.symptomsAfter} onChange={(value) => update('symptomsAfter', value)} />
          </div>
          <label className="field">
            <span>Symptom location or type</span>
            <input value={draft.symptomDetails} placeholder="Where and what did you notice?" onChange={(event) => update('symptomDetails', event.target.value)} />
          </label>
          <label className="field">
            <span>Function or movement marker</span>
            <input value={draft.functionMarker} placeholder="For example: stairs, squat depth, walking" onChange={(event) => update('functionMarker', event.target.value)} />
          </label>
        </section>

        <section className={`form-section follow-up-section ${followUpDue || draft.followUpComplete || focusFollowUp ? '' : 'follow-up-waiting'}`} ref={followUpRef}>
          <div className="section-heading">
            <span className="section-number">3</span>
            <div><h2>24-hour response</h2><p>{followUpDue || draft.followUpComplete || focusFollowUp ? 'This check-in belongs only to this session.' : `Due ${displayDateTime(draft.followUpDueAt)}.`}</p></div>
          </div>
          {!followUpDue && !draft.followUpComplete && !focusFollowUp ? (
            <div className="follow-up-scheduled">
              <Clock3 aria-hidden="true" />
              <div><strong>Check-in scheduled</strong><p>The response fields will appear when 24 hours have passed.</p></div>
              <button className="secondary-button" type="button" onClick={() => downloadFollowUpReminder(draft)}><CalendarPlus aria-hidden="true" /> Add phone reminder</button>
            </div>
          ) : (
            <>
              <div className="follow-up-score">
                <NumberField name="symptoms24h" label="Symptoms now" value={draft.symptoms24h} min={0} max={10} suffix="/ 10" error={errors.symptoms24h} onChange={(value) => update('symptoms24h', value)} />
                <div className="delta-readout">
                  <span>Change vs before</span>
                  <strong>{delta === null ? '—' : `${delta > 0 ? '+' : ''}${delta}`}</strong>
                </div>
              </div>
              <fieldset className="choice-field">
                <legend>How did the body respond?</legend>
                <div className="segmented-choices">
                  {RESPONSE_TYPES.map((response) => (
                    <label key={response}>
                      <input type="radio" name={`response-${draft.id}`} checked={draft.response24h === response} onChange={() => update('response24h', response)} />
                      <span>{response}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <label className="check-row">
                <input type="checkbox" checked={draft.followUpComplete} onChange={(event) => completeFollowUp(event.target.checked)} />
                <span><strong>Mark follow-up complete</strong><small>{hours === null ? 'The actual check-in time will be recorded.' : `Recorded ${hours} hours after this session.`}</small></span>
              </label>
              <label className="field">
                <span>Next-day decision or adjustment</span>
                <input value={draft.nextDayPlan} placeholder="Continue, reduce, rest or change the plan" onChange={(event) => update('nextDayPlan', event.target.value)} />
              </label>
            </>
          )}
          {draft.revision > 0 && (
            <div className="reminder-row">
              <span>Need a phone alert at {displayDateTime(draft.followUpDueAt)}?</span>
              <button className="text-button" type="button" onClick={() => downloadFollowUpReminder(draft)}><CalendarPlus aria-hidden="true" /> Add calendar reminder</button>
            </div>
          )}
        </section>

        <section className="form-section notes-section">
          <div className="section-heading">
            <span className="section-number">4</span>
            <div><h2>Notes</h2><p>Anything else that may help when you look back.</p></div>
          </div>
          <label className="field">
            <span>Other context</span>
            <textarea rows={4} value={draft.notes} placeholder="Travel, stress, medication, surface or other context" onChange={(event) => update('notes', event.target.value)} />
          </label>
        </section>
        </> : <>
          <section className="form-section event-section">
            <div className="section-heading">
              <span className="section-number">1</span>
              <div><h2>What happened?</h2><p>Capture the situation while it is still fresh.</p></div>
            </div>
            <div className="field-row session-when-row">
              <EuropeanDateField key={draft.date} value={draft.date} error={errors.date} onChange={updateDate} />
              <EuropeanTimeField key={draft.sessionAt} value={draft.sessionAt} entryType={draft.entryType} onChange={updateTime} />
              <label className="field">
                <span>Context</span>
                <select value={draft.eventContext ?? 'Other'} onChange={(event) => update('eventContext', event.target.value as RehabDay['eventContext'])}>
                  {EVENT_CONTEXTS.map((context) => <option key={context}>{context}</option>)}
                </select>
              </label>
            </div>
            <label className="field">
              <span>What were you doing?</span>
              <textarea
                rows={4}
                value={draft.sessionContent}
                maxLength={1000}
                placeholder="For example: Sitting at a restaurant for about an hour"
                onChange={(event) => update('sessionContent', event.target.value)}
              />
            </label>
            <NumberField name="durationMinutes" label="Time in that situation" value={draft.durationMinutes} min={0} max={1440} suffix="min" error={errors.durationMinutes} onChange={(value) => update('durationMinutes', value)} />
          </section>

          <section className="form-section event-change-section">
            <div className="section-heading">
              <span className="section-number">2</span>
              <div><h2>What changed?</h2><p>Use the same 0–10 symptom scale each time.</p></div>
            </div>
            <fieldset className="choice-field">
              <legend>Compared with before this situation</legend>
              <div className="segmented-choices event-choices">
                {EVENT_CHANGES.map((change) => (
                  <label key={change}>
                    <input type="radio" name={`event-change-${draft.id}`} checked={draft.eventChange === change} onChange={() => update('eventChange', change)} />
                    <span>{change}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="metric-grid two">
              <NumberField name="symptomsBefore" label="Before" value={draft.symptomsBefore} min={0} max={10} suffix="/ 10" error={errors.symptomsBefore} onChange={(value) => update('symptomsBefore', value)} />
              <NumberField name="symptomsAfter" label="When you noticed it" value={draft.symptomsAfter} min={0} max={10} suffix="/ 10" error={errors.symptomsAfter} onChange={(value) => update('symptomsAfter', value)} />
            </div>
            <label className="field">
              <span>Where and what did you feel?</span>
              <input maxLength={300} value={draft.symptomDetails} placeholder="For example: Right leg felt tight and tense" onChange={(event) => update('symptomDetails', event.target.value)} />
            </label>
            <label className="field">
              <span>What did it affect?</span>
              <input maxLength={300} value={draft.functionMarker} placeholder="For example: Standing up or walking normally" onChange={(event) => update('functionMarker', event.target.value)} />
            </label>
          </section>

          <section className="form-section notes-section">
            <div className="section-heading">
              <span className="section-number">3</span>
              <div><h2>What happened next?</h2><p>Small details can reveal patterns later.</p></div>
            </div>
            <label className="field">
              <span>What helped, or what happened afterward?</span>
              <textarea rows={4} maxLength={1000} value={draft.notes} placeholder="Walking, changing position, resting, medication or anything else" onChange={(event) => update('notes', event.target.value)} />
            </label>
            <div className="event-follow-up-note">
              <Clock3 aria-hidden="true" />
              <div><strong>No automatic 24-hour check-in</strong><p>Symptom events stay in the same timeline, but only training and rehab sessions create a 24-hour response.</p></div>
            </div>
          </section>
        </>}
      </div>

      <div className="sticky-save">
        <span><Clock3 size={17} aria-hidden="true" /> Changes save automatically</span>
        <button className="primary-button" type="button" onClick={() => void persist()} disabled={saveState === 'saving'}>
          <Save size={18} aria-hidden="true" /> Save now
        </button>
      </div>
    </main>
  )
}
