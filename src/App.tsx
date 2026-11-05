import { useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import {
  Activity,
  ArrowRight,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  Clock3,
  Download,
  FileJson,
  Home,
  LineChart,
  MessageSquareWarning,
  Plus,
  Settings,
  ShieldCheck,
  Upload,
} from 'lucide-react'
import { DayEditor } from './components/DayEditor'
import { GoogleSyncSettings } from './components/GoogleSyncSettings'
import { db, importDays } from './db'
import { createBackup, downloadFile, parseBackup, recordsToCsv } from './lib/backup'
import { displayDate, displayMonth, displayTime, monthDays, monthKey, shiftMonth, todayInBerlin } from './lib/dates'
import { dayTone, hasSession, symptomDelta, toneLabel } from './lib/derived'
import { isDateOnly } from './lib/dates'
import { GOOGLE_SETTING_KEYS, scheduleAutomaticGoogleSync } from './sync/googleRuntime'
import type { EntryType, RehabDay } from './types'
import './index.css'

type View = 'home' | 'calendar' | 'progress' | 'settings'
type EditorState = { date: string; recordId?: string; entryType?: EntryType; followUp?: boolean; overview?: boolean } | null

const NAV_ITEMS: Array<{ id: View; label: string; icon: typeof Home }> = [
  { id: 'home', label: 'Today', icon: Home },
  { id: 'calendar', label: 'Calendar', icon: CalendarDays },
  { id: 'progress', label: 'Progress', icon: LineChart },
  { id: 'settings', label: 'Settings', icon: Settings },
]

function StatusPill({ day }: { day: RehabDay }) {
  const tone = dayTone(day)
  return <span className={`status-pill tone-${tone}`}>{toneLabel(tone)}</span>
}

const TONE_PRIORITY = { rest: 0, logged: 1, event: 2, favorable: 3, pending: 4, mild: 5, major: 6 } as const

function strongestDay(days: RehabDay[]): RehabDay | undefined {
  return [...days].sort((a, b) => TONE_PRIORITY[dayTone(b)] - TONE_PRIORITY[dayTone(a)])[0]
}

function DayOverview({ date, days, openEditor, onBack }: { date: string; days: RehabDay[]; openEditor: (state: EditorState) => void; onBack: () => void }) {
  const entries = days.filter((day) => day.date === date).sort((a, b) => a.sessionAt.localeCompare(b.sessionAt))
  return (
    <main className="page-shell day-overview-page">
      <header className="editor-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="Back"><ChevronLeft aria-hidden="true" /></button>
        <div><p className="context-label">{displayDate(date, { weekday: 'long' })}</p><h1>{displayDate(date)}</h1></div>
        <div className="overview-actions">
          <button className="primary-button" type="button" onClick={() => openEditor({ date, entryType: 'session' })}><Plus aria-hidden="true" /> Add session</button>
          <button className="secondary-button" type="button" onClick={() => openEditor({ date, entryType: 'symptom_event' })}><MessageSquareWarning aria-hidden="true" /> Symptom event</button>
        </div>
      </header>
      <section className="day-session-list" aria-label={`Timeline for ${displayDate(date)}`}>
        {entries.length ? entries.map((entry, index) => (
          <article className={`day-session-row ${entry.entryType === 'symptom_event' ? 'is-event' : ''}`} key={entry.id}>
            <span className="session-time"><Clock3 aria-hidden="true" />{displayTime(entry.sessionAt)}</span>
            <div><strong>{entry.entryType === 'symptom_event' ? entry.eventContext || 'Symptom event' : entry.sessionType}</strong><p>{entry.sessionContent || `${entry.entryType === 'symptom_event' ? 'Symptom event' : 'Session'} ${index + 1}`}</p></div>
            <StatusPill day={entry} />
            <button className="secondary-button" type="button" onClick={() => openEditor({ date, recordId: entry.id, entryType: entry.entryType })}>Open</button>
          </article>
        )) : (
          <div className="empty-state"><CalendarDays aria-hidden="true" /><h2>No entries on this date</h2><p>Add a training session or record a situation that changed your symptoms.</p><div className="empty-actions"><button className="primary-button" type="button" onClick={() => openEditor({ date, entryType: 'session' })}><Plus aria-hidden="true" /> Add session</button><button className="secondary-button" type="button" onClick={() => openEditor({ date, entryType: 'symptom_event' })}><MessageSquareWarning aria-hidden="true" /> Symptom event</button></div></div>
        )}
      </section>
    </main>
  )
}

function AppHeader({ pending, sheetSelected }: { pending: number; sheetSelected: boolean }) {
  const status = pending
    ? `${pending} ${pending === 1 ? 'entry' : 'entries'} waiting to sync`
    : sheetSelected ? 'Google Sheet up to date' : 'Saved on this device'
  return (
    <header className="app-header">
      <a className="brand" href="#top" aria-label="Rehab Tracker home">
        <span className="brand-mark"><Activity aria-hidden="true" /></span>
        <span><strong>Rehab Tracker</strong><small>Private training log</small></span>
      </a>
      <div className={`local-status ${!pending && sheetSelected ? 'is-synced' : ''}`}>
        <span className="status-dot" />
        {status}
      </div>
    </header>
  )
}

function HomeView({ days, openEditor }: { days: RehabDay[]; openEditor: (state: EditorState) => void }) {
  const today = todayInBerlin()
  const [currentTime, setCurrentTime] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setCurrentTime(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  const todayEntries = days.filter((day) => day.date === today).sort((a, b) => a.sessionAt.localeCompare(b.sessionAt))
  const todaySessions = todayEntries.filter(hasSession)
  const todayEvents = todayEntries.filter((day) => day.entryType === 'symptom_event')
  const todayTone = strongestDay(todayEntries)
  const pendingFollowUps = days
    .filter((day) => hasSession(day) && !day.followUpComplete && new Date(day.followUpDueAt).getTime() <= currentTime)
    .sort((a, b) => a.followUpDueAt.localeCompare(b.followUpDueAt))
  const recent = [...days].filter((day) => day.date < today).sort((a, b) => b.sessionAt.localeCompare(a.sessionAt)).slice(0, 5)
  const totalDuration = todaySessions.reduce((sum, entry) => sum + (entry.durationMinutes ?? 0), 0)

  return (
    <main className="page-shell home-page" id="top">
      {pendingFollowUps.length > 0 && (
        <section className="follow-up-card" aria-label="Due follow-ups">
          <div className="follow-up-heading"><div className="follow-up-icon"><span>24h</span></div><div><p className="context-label">{pendingFollowUps.length === 1 ? 'Follow-up due now' : `${pendingFollowUps.length} follow-ups due now`}</p><h2>Check how each session settled.</h2></div></div>
          <div className="follow-up-list">
            {pendingFollowUps.map((followUp) => (
              <div className="follow-up-row" key={followUp.id}>
                <div><strong>{followUp.sessionType}</strong><span>{displayDate(followUp.date)} at {displayTime(followUp.sessionAt)}</span></div>
                <button className="secondary-button" type="button" onClick={() => openEditor({ date: followUp.date, recordId: followUp.id, entryType: 'session', followUp: true })}>Check in <ArrowRight size={18} aria-hidden="true" /></button>
              </div>
            ))}
          </div>
        </section>
      )}

      <section className="today-hero">
        <div className="today-date">
          <p>{displayDate(today, { weekday: 'long' })}</p>
          <strong>{today.slice(-2)}</strong>
          <span>{displayDate(today, { month: 'long', year: 'numeric' })}</span>
        </div>
        <div className="today-copy">
          <p className="context-label">Today</p>
          <h1>{todayEntries.length ? `${todayEntries.length} ${todayEntries.length === 1 ? 'entry' : 'entries'} in today’s timeline.` : 'What has your body experienced today?'}</h1>
          <p>{todayEntries.length ? 'Add training and everyday symptom changes as they happen.' : 'Log a session, or capture an ordinary situation that changed your symptoms.'}</p>
          <div className="hero-actions">
            <button className="primary-button large" type="button" onClick={() => openEditor({ date: today, entryType: 'session' })}><Plus aria-hidden="true" /> Log a session</button>
            <button className="secondary-button large event-button" type="button" onClick={() => openEditor({ date: today, entryType: 'symptom_event' })}><MessageSquareWarning aria-hidden="true" /> Log symptom event</button>
            {todayEntries.length > 0 && <button className="text-button view-timeline" type="button" onClick={() => openEditor({ date: today, overview: true })}>View today’s timeline <ArrowRight aria-hidden="true" /></button>}
          </div>
        </div>
        {todayTone && <div className="today-summary"><StatusPill day={todayTone} /><dl><div><dt>Sessions</dt><dd>{todaySessions.length}</dd></div><div><dt>Symptom events</dt><dd>{todayEvents.length}</dd></div><div><dt>Training time</dt><dd>{totalDuration || '—'}{totalDuration > 0 && ' min'}</dd></div></dl></div>}
      </section>

      <section className="section-block recent-block">
        <div className="block-title"><div><p className="context-label">Recent entries</p><h2>Your body timeline</h2></div></div>
        {recent.length === 0 ? (
          <div className="empty-state"><CalendarDays aria-hidden="true" /><h3>No earlier entries yet</h3><p>Your previous sessions, symptom events and follow-ups will collect here.</p></div>
        ) : (
          <div className="recent-list">
            {recent.map((day) => (
              <button key={day.id} type="button" className="recent-row" onClick={() => openEditor({ date: day.date, recordId: day.id })}>
                <span className="date-tile"><strong>{day.date.slice(-2)}</strong><small>{displayDate(day.date, { month: 'short' })}</small></span>
                <span className="recent-detail"><strong>{day.entryType === 'symptom_event' ? day.eventContext || 'Symptom event' : day.sessionType} · {displayTime(day.sessionAt)}</strong><small>{day.sessionContent || (day.entryType === 'symptom_event' ? 'No event details added' : 'No session details added')}</small></span>
                <StatusPill day={day} />
                <ChevronRight aria-hidden="true" />
              </button>
            ))}
          </div>
        )}
      </section>

      <aside className="privacy-note"><ShieldCheck aria-hidden="true" /><div><strong>Private by default</strong><p>Your entries stay in this browser’s local storage. Export a backup regularly until cloud sync has passed its access check.</p></div></aside>
    </main>
  )
}

function CalendarView({ days, openEditor }: { days: RehabDay[]; openEditor: (state: EditorState) => void }) {
  const today = todayInBerlin()
  const [month, setMonth] = useState(monthKey(today))
  const byDate = useMemo(() => {
    const grouped = new Map<string, RehabDay[]>()
    for (const day of days) grouped.set(day.date, [...(grouped.get(day.date) ?? []), day])
    return grouped
  }, [days])
  const cells = monthDays(month)

  return (
    <main className="page-shell calendar-page">
      <div className="page-title-row"><div><p className="context-label">Calendar</p><h1>See the pattern, entry by entry.</h1><p>Each day can hold several sessions and symptom events in one timeline.</p></div><button className="primary-button" type="button" onClick={() => openEditor({ date: today, entryType: 'session' })}><Plus aria-hidden="true" /> Add session</button></div>
      <section className="calendar-panel">
        <header className="calendar-toolbar">
          <button className="icon-button" type="button" onClick={() => setMonth(shiftMonth(month, -1))} aria-label="Previous month"><ChevronLeft /></button>
          <div><h2>{displayMonth(month)}</h2>{month !== monthKey(today) && <button type="button" className="text-button" onClick={() => setMonth(monthKey(today))}>Return to today</button>}</div>
          <button className="icon-button" type="button" onClick={() => setMonth(shiftMonth(month, 1))} aria-label="Next month"><ChevronRight /></button>
        </header>
        <div className="weekdays" aria-hidden="true">{['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day) => <span key={day}>{day}</span>)}</div>
        <div className="month-grid">
          {cells.map((date, index) => {
            if (!date) return <span className="calendar-cell empty" key={`empty-${index}`} />
            const entries = byDate.get(date) ?? []
            const representative = strongestDay(entries)
            const tone = representative ? dayTone(representative) : undefined
            return (
              <button type="button" key={date} className={`calendar-cell ${date === today ? 'is-today' : ''} ${entries.length ? 'has-entry' : ''}`} onClick={() => openEditor({ date, overview: true })} aria-label={`${displayDate(date)}${entries.length ? `, ${entries.length} entries, ${toneLabel(tone!)}` : ', no entries'}`}>
                <span className="day-number">{Number(date.slice(-2))}</span>
                {tone && <span className={`day-marker tone-${tone}`}><span />{entries.length > 1 ? `${entries.length} entries` : toneLabel(tone)}</span>}
              </button>
            )
          })}
        </div>
        <footer className="calendar-legend">
          {(['rest', 'event', 'pending', 'favorable', 'mild', 'major'] as const).map((tone) => <span key={tone}><i className={`tone-${tone}`} />{toneLabel(tone)}</span>)}
        </footer>
      </section>
    </main>
  )
}

function ProgressView({ days }: { days: RehabDay[] }) {
  const completed = [...days].filter((day) => hasSession(day) && day.symptomsBefore !== null && day.symptoms24h !== null).sort((a, b) => a.date.localeCompare(b.date)).slice(-10)
  const sessions = days.filter(hasSession)
  const symptomEvents = days.filter((day) => day.entryType === 'symptom_event')
  const duration = sessions.reduce((sum, day) => sum + (day.durationMinutes ?? 0), 0)
  const deltas = completed.map(symptomDelta).filter((value): value is number => value !== null)
  const averageDelta = deltas.length ? deltas.reduce((sum, value) => sum + value, 0) / deltas.length : null
  const flares = days.filter((day) => day.response24h === 'Mild flare' || day.response24h === 'Significant flare').length
  const followUps = days.filter((day) => day.followUpComplete).length

  const points = (key: 'symptomsBefore' | 'symptoms24h') => completed.map((day, index) => {
    const x = completed.length === 1 ? 50 : 5 + (index / (completed.length - 1)) * 90
    const y = 92 - ((day[key] ?? 0) / 10) * 78
    return `${x},${y}`
  }).join(' ')

  return (
    <main className="page-shell progress-page">
      <div className="page-title-row"><div><p className="context-label">Progress</p><h1>Look for useful context, not a score.</h1><p>These summaries reflect your subjective entries and do not diagnose an injury.</p></div></div>
      <div className="metric-cards">
        <article><span>Logged sessions</span><strong>{sessions.length}</strong><small>across {new Set(sessions.map((day) => day.date.slice(0, 7))).size || 0} months</small></article>
        <article><span>Symptom events</span><strong>{symptomEvents.length}</strong><small>{symptomEvents.filter((day) => day.eventChange === 'Worse').length} recorded as worse</small></article>
        <article><span>Total duration</span><strong>{duration}</strong><small>recorded minutes</small></article>
        <article><span>Average 24h change</span><strong>{averageDelta === null ? '—' : `${averageDelta > 0 ? '+' : ''}${averageDelta.toFixed(1)}`}</strong><small>{deltas.length ? `from ${deltas.length} complete pairs` : 'needs complete follow-ups'}</small></article>
        <article><span>Recorded flares</span><strong>{followUps ? `${Math.round((flares / followUps) * 100)}%` : '—'}</strong><small>{flares} of {followUps} follow-ups</small></article>
      </div>
      <section className="chart-panel">
        <div className="block-title"><div><p className="context-label">Symptoms</p><h2>Before training vs follow-up</h2></div><div className="chart-key"><span><i className="before" />Before</span><span><i className="after" />24h later</span></div></div>
        {completed.length ? (
          <div className="symptom-chart">
            <div className="y-labels"><span>10</span><span>5</span><span>0</span></div>
            <svg viewBox="0 0 100 100" role="img" aria-label="Symptoms before training and at follow-up for entries with both values">
              <line x1="5" x2="95" y1="14" y2="14" /><line x1="5" x2="95" y1="53" y2="53" /><line x1="5" x2="95" y1="92" y2="92" />
              <polyline className="before-line" points={points('symptomsBefore')} /><polyline className="after-line" points={points('symptoms24h')} />
              {completed.map((day, index) => {
                const x = completed.length === 1 ? 50 : 5 + (index / (completed.length - 1)) * 90
                return <g key={day.id}><circle className="before-point" cx={x} cy={92 - ((day.symptomsBefore ?? 0) / 10) * 78} r="1.7"><title>{day.date}: before {day.symptomsBefore}</title></circle><circle className="after-point" cx={x} cy={92 - ((day.symptoms24h ?? 0) / 10) * 78} r="1.7"><title>{day.date}: follow-up {day.symptoms24h}</title></circle></g>
              })}
            </svg>
            <div className="x-labels"><span>{displayDate(completed[0].date, { day: 'numeric', month: 'short' })}</span>{completed.length > 1 && <span>{displayDate(completed.at(-1)!.date, { day: 'numeric', month: 'short' })}</span>}</div>
          </div>
        ) : <div className="empty-state compact"><LineChart aria-hidden="true" /><h3>No paired symptom entries yet</h3><p>Add symptoms before and at follow-up. Missing values are never treated as zero.</p></div>}
      </section>
    </main>
  )
}

function SettingsView({ days }: { days: RehabDay[] }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [message, setMessage] = useState<string | null>(null)

  function exportJson() {
    downloadFile(`rehab-tracker-backup-${todayInBerlin()}.json`, JSON.stringify(createBackup(days), null, 2), 'application/json')
    setMessage('JSON backup downloaded.')
  }

  function exportCsv() {
    downloadFile(`rehab-tracker-${todayInBerlin()}.csv`, recordsToCsv(days), 'text/csv;charset=utf-8')
    setMessage('CSV export downloaded.')
  }

  async function handleImport(file?: File) {
    if (!file) return
    try {
      const backup = parseBackup(JSON.parse(await file.text()))
      const validRecords = backup.records.filter((record) => isDateOnly(record.date))
      const count = await importDays(validRecords)
      setMessage(`${count} ${count === 1 ? 'entry' : 'entries'} restored and queued locally.`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not import this backup.')
    } finally {
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  return (
    <main className="page-shell settings-page">
      <div className="page-title-row"><div><p className="context-label">Settings</p><h1>Your data, under your control.</h1><p>Back up regularly. Browser data can be erased if you clear storage or uninstall the app.</p></div></div>
      {message && <div className="notice" role="status"><CircleCheck aria-hidden="true" />{message}<button type="button" onClick={() => setMessage(null)} aria-label="Dismiss">×</button></div>}
      <section className="settings-section"><div className="settings-intro"><FileJson aria-hidden="true" /><div><h2>Backup and restore</h2><p>JSON preserves every app field and is the recommended backup. CSV is designed for review or manual spreadsheet import.</p></div></div><div className="settings-actions"><button className="secondary-button" type="button" onClick={exportJson}><Download aria-hidden="true" /> Export JSON</button><button className="secondary-button" type="button" onClick={exportCsv}><Download aria-hidden="true" /> Export CSV</button><button className="secondary-button" type="button" onClick={() => fileRef.current?.click()}><Upload aria-hidden="true" /> Restore JSON</button><input ref={fileRef} className="visually-hidden" type="file" accept="application/json,.json" onChange={(event) => void handleImport(event.target.files?.[0])} /></div></section>
      <GoogleSyncSettings onMessage={setMessage} />
      <section className="settings-section privacy-section"><div className="settings-intro"><ShieldCheck aria-hidden="true" /><div><h2>Privacy</h2><p>No analytics, advertising, AI services or remote app database. Data stays in this browser unless you export it. Do not rely on browser storage as your only backup.</p></div></div></section>
    </main>
  )
}

function App() {
  const [view, setView] = useState<View>('home')
  const [editor, setEditor] = useState<EditorState>(null)
  const days = useLiveQuery(() => db.days.toArray(), []) ?? []
  const pending = useLiveQuery(() => db.syncOutbox.count(), []) ?? 0
  const selectedSheet = useLiveQuery(() => db.settings.get(GOOGLE_SETTING_KEYS.id), [])

  useEffect(() => {
    const resumeSync = () => scheduleAutomaticGoogleSync(250)
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') resumeSync()
    }
    window.addEventListener('online', resumeSync)
    document.addEventListener('visibilitychange', handleVisibility)
    const timer = window.setInterval(resumeSync, 5 * 60_000)
    resumeSync()
    return () => {
      window.removeEventListener('online', resumeSync)
      document.removeEventListener('visibilitychange', handleVisibility)
      window.clearInterval(timer)
    }
  }, [])

  if (editor?.overview) {
    return <DayOverview date={editor.date} days={days} openEditor={setEditor} onBack={() => setEditor(null)} />
  }

  if (editor) {
    return <DayEditor date={editor.date} recordId={editor.recordId} entryType={editor.entryType} focusFollowUp={editor.followUp} onBack={() => setEditor(editor.recordId && !editor.followUp ? { date: editor.date, overview: true } : null)} onSaved={(record) => {
      setEditor((current) => current ? { ...current, date: record.date, recordId: record.id, entryType: record.entryType } : null)
      scheduleAutomaticGoogleSync()
    }} />
  }

  return (
    <div className="app-frame">
      <AppHeader pending={pending} sheetSelected={Boolean(selectedSheet?.value)} />
      {view === 'home' && <HomeView days={days} openEditor={setEditor} />}
      {view === 'calendar' && <CalendarView days={days} openEditor={setEditor} />}
      {view === 'progress' && <ProgressView days={days} />}
      {view === 'settings' && <SettingsView days={days} />}
      <nav className="bottom-nav" aria-label="Primary navigation">
        {NAV_ITEMS.map(({ id, label, icon: Icon }) => <button key={id} type="button" className={view === id ? 'active' : ''} onClick={() => { setView(id); window.scrollTo({ top: 0, behavior: 'smooth' }) }}><Icon aria-hidden="true" /><span>{label}</span></button>)}
      </nav>
    </div>
  )
}

export default App
