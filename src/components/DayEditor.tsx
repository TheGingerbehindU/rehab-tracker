import { useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { ArrowLeft, Check, Clock3, Save } from 'lucide-react'
import { blankRehabDay, db, saveRehabDay } from '../db'
import { elapsedHours, displayDate } from '../lib/dates'
import { symptomDelta } from '../lib/derived'
import { parseOptionalNumber, validateDay } from '../lib/validation'
import { RESPONSE_TYPES, SESSION_TYPES, type RehabDay } from '../types'

type Props = {
  date: string
  focusFollowUp?: boolean
  onBack: () => void
  onDateChanged: (date: string) => void
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

export function DayEditor({ date, focusFollowUp = false, onBack, onDateChanged }: Props) {
  const stored = useLiveQuery(() => db.days.where('date').equals(date).first(), [date])
  const [draft, setDraft] = useState<RehabDay>(() => blankRehabDay(date))
  const [saveState, setSaveState] = useState<SaveState>('ready')
  const [saveMessage, setSaveMessage] = useState('Not saved yet')
  const dirty = useRef(false)
  const followUpRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    dirty.current = false
    setDraft(stored ?? blankRehabDay(date))
    setSaveState(stored ? 'saved' : 'ready')
    setSaveMessage(stored ? 'Saved locally' : 'Not saved yet')
  }, [date, stored])

  useEffect(() => {
    if (focusFollowUp) requestAnimationFrame(() => followUpRef.current?.scrollIntoView({ behavior: 'smooth' }))
  }, [focusFollowUp])

  const errors = useMemo(() => validateDay(draft), [draft])
  const delta = symptomDelta(draft)
  const hours = elapsedHours(draft.followUpRecordedAt, draft.date)

  function update<K extends keyof RehabDay>(key: K, value: RehabDay[K]) {
    dirty.current = true
    setSaveState('saving')
    setSaveMessage('Saving…')
    setDraft((current) => ({ ...current, [key]: value }))
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
      if (saved.date !== date) onDateChanged(saved.date)
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
          <p className="context-label">Daily entry</p>
          <h1>{displayDate(draft.date, { weekday: 'long', day: 'numeric', month: 'long' })}</h1>
        </div>
        <span className={`save-indicator ${saveState}`} aria-live="polite">
          {saveState === 'saved' && <Check size={16} aria-hidden="true" />}
          {saveMessage}
        </span>
      </header>

      <div className="editor-grid">
        <section className="form-section training-section">
          <div className="section-heading">
            <span className="section-number">1</span>
            <div><h2>Training</h2><p>What did you ask your body to do?</p></div>
          </div>
          <div className="field-row">
            <label className="field">
              <span>Date</span>
              <input
                type="date"
                value={draft.date}
                aria-invalid={Boolean(errors.date)}
                onChange={(event) => update('date', event.target.value)}
              />
              {errors.date && <span className="field-error">{errors.date}</span>}
            </label>
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

        <section className="form-section follow-up-section" ref={followUpRef}>
          <div className="section-heading">
            <span className="section-number">3</span>
            <div><h2>24-hour response</h2><p>Later is fine—record when you actually check in.</p></div>
          </div>
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
                  <input type="radio" name="response" checked={draft.response24h === response} onChange={() => update('response24h', response)} />
                  <span>{response}</span>
                </label>
              ))}
            </div>
          </fieldset>
          <label className="check-row">
            <input type="checkbox" checked={draft.followUpComplete} onChange={(event) => completeFollowUp(event.target.checked)} />
            <span><strong>Mark follow-up complete</strong><small>{hours === null ? 'The time will be recorded when checked.' : `Recorded roughly ${hours} hours after the session date.`}</small></span>
          </label>
          <label className="field">
            <span>Next-day decision or adjustment</span>
            <input value={draft.nextDayPlan} placeholder="Continue, reduce, rest or change the plan" onChange={(event) => update('nextDayPlan', event.target.value)} />
          </label>
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
