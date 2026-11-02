import { useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { Cloud, ExternalLink, LogIn, RefreshCw, Unplug } from 'lucide-react'
import { db } from '../db'
import { googleClientConfig } from '../sync/google'
import { disconnectGoogleDrive, googleDriveSession, GOOGLE_SETTING_KEYS, syncSelectedGoogleSheet } from '../sync/googleRuntime'

type Props = {
  onMessage(message: string): void
}

export function GoogleSyncSettings({ onMessage }: Props) {
  const config = googleClientConfig()
  const [busy, setBusy] = useState(false)
  const [connected, setConnected] = useState(false)
  const settings = useLiveQuery(async () => {
    const [id, name, url, lastSync] = await Promise.all([
      db.settings.get(GOOGLE_SETTING_KEYS.id),
      db.settings.get(GOOGLE_SETTING_KEYS.name),
      db.settings.get(GOOGLE_SETTING_KEYS.url),
      db.settings.get(GOOGLE_SETTING_KEYS.lastSync),
    ])
    return {
      id: typeof id?.value === 'string' ? id.value : '',
      name: typeof name?.value === 'string' ? name.value : '',
      url: typeof url?.value === 'string' ? url.value : '',
      lastSync: typeof lastSync?.value === 'string' ? lastSync.value : '',
    }
  }, [])

  async function authorize() {
    const session = googleDriveSession()
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
        { key: GOOGLE_SETTING_KEYS.id, value: selected.id },
        { key: GOOGLE_SETTING_KEYS.name, value: selected.name },
        { key: GOOGLE_SETTING_KEYS.url, value: selected.url },
      ])
      const result = await syncSelectedGoogleSheet()
      onMessage(`${probe.message} ${result?.message ?? 'New edits will sync automatically while this app session remains connected.'}`)
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
      const result = await syncSelectedGoogleSheet({ authorize: true })
      setConnected(Boolean(googleDriveSession()?.connected))
      onMessage(result?.message ?? 'No Google Sheet is selected yet.')
    } catch (error) {
      onMessage(`${error instanceof Error ? error.message : 'Google Sheets sync failed.'} Your entries remain saved locally.`)
    } finally {
      setBusy(false)
    }
  }

  function disconnect() {
    disconnectGoogleDrive()
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
              ? <>Selected: <strong>{settings.name || 'Rehab Response Tracker'}</strong>. Last successful sync: {lastSyncLabel}. New saves sync automatically while Google remains connected.</>
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
