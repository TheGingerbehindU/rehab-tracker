import { useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { Cloud, ExternalLink, LogIn, RefreshCw, Unplug } from 'lucide-react'
import { db } from '../db'
import { googleClientConfig, GoogleDriveSession } from '../sync/google'
import { syncPending } from '../sync/orchestrator'

const SETTING_KEYS = {
  id: 'google.spreadsheetId',
  name: 'google.spreadsheetName',
  url: 'google.spreadsheetUrl',
  lastSync: 'google.lastSuccessfulSync',
} as const

type Props = {
  onMessage(message: string): void
}

export function GoogleSyncSettings({ onMessage }: Props) {
  const config = googleClientConfig()
  const sessionRef = useRef<GoogleDriveSession | null>(config ? new GoogleDriveSession(config) : null)
  const [busy, setBusy] = useState(false)
  const [connected, setConnected] = useState(false)
  const settings = useLiveQuery(async () => {
    const [id, name, url, lastSync] = await Promise.all([
      db.settings.get(SETTING_KEYS.id),
      db.settings.get(SETTING_KEYS.name),
      db.settings.get(SETTING_KEYS.url),
      db.settings.get(SETTING_KEYS.lastSync),
    ])
    return {
      id: typeof id?.value === 'string' ? id.value : '',
      name: typeof name?.value === 'string' ? name.value : '',
      url: typeof url?.value === 'string' ? url.value : '',
      lastSync: typeof lastSync?.value === 'string' ? lastSync.value : '',
    }
  }, [])

  async function authorize() {
    const session = sessionRef.current
    if (!session) throw new Error('Google connection needs the deployment configuration described in the setup guide.')
    await session.authorize(import.meta.env.VITE_GOOGLE_LOGIN_HINT?.trim())
    setConnected(true)
    return session
  }

  async function chooseSheet() {
    setBusy(true)
    try {
      const session = await authorize()
      const selected = await session.pickSpreadsheet()
      const probe = await session.provider(selected.id).probe()
      if (!probe.available) throw new Error(probe.message)
      await db.settings.bulkPut([
        { key: SETTING_KEYS.id, value: selected.id },
        { key: SETTING_KEYS.name, value: selected.name },
        { key: SETTING_KEYS.url, value: selected.url },
      ])
      onMessage(`${probe.message} New local edits can now be synchronized.`)
    } catch (error) {
      onMessage(error instanceof Error ? error.message : 'Could not connect Google Sheets.')
    } finally {
      setBusy(false)
    }
  }

  async function runSync() {
    if (!settings?.id) return chooseSheet()
    if (!navigator.onLine) return onMessage('You are offline. Your entries remain queued on this device.')
    setBusy(true)
    try {
      const session = connected && sessionRef.current?.connected ? sessionRef.current : await authorize()
      const provider = session.provider(settings.id)
      const probe = await provider.probe()
      if (!probe.available) throw new Error(probe.message)
      const result = await syncPending(db, provider)
      if (result.synced > 0 && result.errors === 0 && result.conflicts === 0) {
        await db.settings.put({ key: SETTING_KEYS.lastSync, value: new Date().toISOString() })
      }
      onMessage(result.message)
    } catch (error) {
      onMessage(`${error instanceof Error ? error.message : 'Google Sheets sync failed.'} Your entries remain saved locally.`)
    } finally {
      setBusy(false)
    }
  }

  function disconnect() {
    sessionRef.current?.disconnect()
    setConnected(false)
    onMessage('Google access disconnected. The selected sheet is still remembered on this device.')
  }

  const lastSyncLabel = settings?.lastSync
    ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Berlin' }).format(new Date(settings.lastSync))
    : 'Not synchronized yet'

  return (
    <section className="settings-section cloud-section">
      <div className="settings-intro">
        <Cloud aria-hidden="true" />
        <div>
          <h2>Google Sheets sync</h2>
          <p>
            {settings?.id
              ? <>Selected: <strong>{settings.name || 'Rehab Response Tracker'}</strong>. Last successful sync: {lastSyncLabel}.</>
              : 'Connect your Google account and select the private Rehab Response Tracker sheet. Only that selected file is available to the app.'}
          </p>
          {!config && <p className="setup-warning">Deployment configuration is still required before Google sign-in can open.</p>}
        </div>
      </div>
      <div className="settings-actions cloud-actions">
        {settings?.url && <a className="secondary-button" href={settings.url} target="_blank" rel="noreferrer"><ExternalLink aria-hidden="true" /> Open sheet</a>}
        <button className="secondary-button" type="button" onClick={() => void chooseSheet()} disabled={busy || !config}>
          <LogIn aria-hidden="true" /> {settings?.id ? 'Choose another sheet' : 'Connect Google'}
        </button>
        {settings?.id && <button className="primary-button" type="button" onClick={() => void runSync()} disabled={busy || !config}><RefreshCw className={busy ? 'spin' : ''} aria-hidden="true" /> {busy ? 'Syncing…' : 'Sync now'}</button>}
        {connected && <button className="text-button disconnect-button" type="button" onClick={disconnect}><Unplug aria-hidden="true" /> Disconnect</button>}
      </div>
    </section>
  )
}
