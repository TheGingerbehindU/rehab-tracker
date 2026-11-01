import type { RehabDay } from '../types'
import type { CloudSyncProvider, RemoteRecord, SyncResult } from './provider'
import { followUpTimestamp, sessionTimestamp } from '../lib/dates'

const DRIVE_FILE_SCOPE = 'https://www.googleapis.com/auth/drive.file'
const SHEET_NAME = 'Daily Rehab Log'
const GOOGLE_API_ROOT = 'https://sheets.googleapis.com/v4/spreadsheets'

type GoogleTokenResponse = {
  access_token?: string
  error?: string
  error_description?: string
}

type PickerDocument = {
  id: string
  name?: string
  url?: string
}

export type PickedSpreadsheet = {
  id: string
  name: string
  url: string
}

export type GoogleClientConfig = {
  clientId: string
  apiKey: string
  appId: string
}

type GoogleWindow = Window & {
  google?: {
    accounts: {
      oauth2: {
        initTokenClient(config: {
          client_id: string
          scope: string
          hint?: string
          callback: (response: GoogleTokenResponse) => void
          error_callback?: (error: { type?: string }) => void
        }): {
          callback: (response: GoogleTokenResponse) => void
          requestAccessToken(options?: { prompt?: string }): void
        }
        revoke(token: string, callback?: () => void): void
      }
    }
    picker: {
      Action: { PICKED: string; CANCEL: string }
      Response: { ACTION: string; DOCUMENTS: string }
      ViewId: { SPREADSHEETS: string }
      DocsView: new (viewId: string) => {
        setOwnedByMe(value: boolean): unknown
      }
      PickerBuilder: new () => {
        addView(view: unknown): unknown
        setOAuthToken(token: string): unknown
        setDeveloperKey(key: string): unknown
        setAppId(appId: string): unknown
        setCallback(callback: (data: Record<string, unknown>) => void): unknown
        build(): { setVisible(value: boolean): void }
      }
    }
  }
  gapi?: {
    load(name: string, options: { callback: () => void; onerror: () => void }): void
  }
}

function loadScript(source: string, id: string): Promise<void> {
  const existing = document.getElementById(id) as HTMLScriptElement | null
  if (existing?.dataset.loaded === 'true') return Promise.resolve()

  return new Promise((resolve, reject) => {
    const script = existing ?? document.createElement('script')
    script.id = id
    script.src = source
    script.async = true
    script.defer = true
    script.addEventListener('load', () => {
      script.dataset.loaded = 'true'
      resolve()
    }, { once: true })
    script.addEventListener('error', () => reject(new Error('Could not load Google sign-in. Check your connection and try again.')), { once: true })
    if (!existing) document.head.append(script)
  })
}

async function loadGoogleLibraries(): Promise<GoogleWindow> {
  await Promise.all([
    loadScript('https://accounts.google.com/gsi/client', 'google-identity-services'),
    loadScript('https://apis.google.com/js/api.js', 'google-api-loader'),
  ])
  const googleWindow = window as GoogleWindow
  if (!googleWindow.gapi || !googleWindow.google?.accounts) {
    throw new Error('Google sign-in did not finish loading. Try again while online.')
  }
  await new Promise<void>((resolve, reject) => {
    googleWindow.gapi!.load('picker', { callback: resolve, onerror: () => reject(new Error('Google Drive Picker did not load.')) })
  })
  return googleWindow
}

export function googleClientConfig(): GoogleClientConfig | null {
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID?.trim()
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY?.trim()
  const appId = import.meta.env.VITE_GOOGLE_APP_ID?.trim()
  return clientId && apiKey && appId ? { clientId, apiKey, appId } : null
}

export class GoogleDriveSession {
  private accessToken: string | null = null
  private readonly config: GoogleClientConfig

  constructor(config: GoogleClientConfig) {
    this.config = config
  }

  get connected() {
    return Boolean(this.accessToken)
  }

  async authorize(loginHint?: string): Promise<string> {
    const googleWindow = await loadGoogleLibraries()
    const token = await new Promise<string>((resolve, reject) => {
      const client = googleWindow.google!.accounts.oauth2.initTokenClient({
        client_id: this.config.clientId,
        scope: DRIVE_FILE_SCOPE,
        hint: loginHint,
        callback: (response) => {
          if (response.access_token) resolve(response.access_token)
          else reject(new Error(response.error_description || response.error || 'Google sign-in was not completed.'))
        },
        error_callback: () => reject(new Error('Google sign-in was cancelled or blocked.')),
      })
      client.requestAccessToken({ prompt: this.accessToken ? '' : 'consent' })
    })
    this.accessToken = token
    return token
  }

  async pickSpreadsheet(): Promise<PickedSpreadsheet> {
    if (!this.accessToken) throw new Error('Connect your Google account first.')
    const googleWindow = await loadGoogleLibraries()
    const picker = googleWindow.google!.picker

    return new Promise((resolve, reject) => {
      const view = new picker.DocsView(picker.ViewId.SPREADSHEETS)
      view.setOwnedByMe(true)
      const builder = new picker.PickerBuilder()
      builder.addView(view)
      builder.setOAuthToken(this.accessToken!)
      builder.setDeveloperKey(this.config.apiKey)
      builder.setAppId(this.config.appId)
      builder.setCallback((data) => {
        const action = data[picker.Response.ACTION]
        if (action === picker.Action.CANCEL) reject(new Error('No spreadsheet was selected.'))
        if (action !== picker.Action.PICKED) return
        const documents = data[picker.Response.DOCUMENTS] as PickerDocument[] | undefined
        const selected = documents?.[0]
        if (!selected?.id) return reject(new Error('Google Drive did not return a spreadsheet.'))
        resolve({
          id: selected.id,
          name: selected.name || 'Selected Google Sheet',
          url: selected.url || `https://docs.google.com/spreadsheets/d/${selected.id}/edit`,
        })
      })
      builder.build().setVisible(true)
    })
  }

  provider(spreadsheetId: string) {
    if (!this.accessToken) throw new Error('Connect your Google account first.')
    return new GoogleSheetsProvider(spreadsheetId, this.accessToken)
  }

  disconnect() {
    const token = this.accessToken
    this.accessToken = null
    if (token) (window as GoogleWindow).google?.accounts.oauth2.revoke(token)
  }
}

function sheetRange(range: string) {
  return encodeURIComponent(`'${SHEET_NAME}'!${range}`)
}

function normalizeDate(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    const date = new Date(Date.UTC(1899, 11, 30) + Math.round(value) * 86_400_000)
    return date.toISOString().slice(0, 10)
  }
  if (typeof value !== 'string') return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  const match = value.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/)
  if (!match) return null
  return `${match[3]}-${match[1].padStart(2, '0')}-${match[2].padStart(2, '0')}`
}

function numberOrNull(value: unknown): number | null {
  if (value === '' || value === null || value === undefined) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

export function recordFromSheetRow(date: string, row: unknown[]): RehabDay {
  const sessionAt = text(row[16]) || sessionTimestamp(date, '12:00')
  const entryType = text(row[19]) === 'symptom_event' ? 'symptom_event' : 'session'
  return {
    id: text(row[17]) || `remote-${date}`,
    date,
    sessionAt,
    followUpDueAt: text(row[18]) || followUpTimestamp(sessionAt),
    entryType,
    eventContext: ['Sitting', 'Standing', 'Walking', 'Driving / travel', 'Resting', 'After training', 'Other'].includes(text(row[20]))
      ? text(row[20]) as RehabDay['eventContext']
      : null,
    eventChange: ['Worse', 'Better', 'No clear change'].includes(text(row[21]))
      ? text(row[21]) as RehabDay['eventChange']
      : null,
    sessionType: ['Strength', 'Running', 'Rehab', 'Recovery / rest', 'Other'].includes(text(row[1]))
      ? text(row[1]) as RehabDay['sessionType']
      : 'Other',
    sessionContent: text(row[2]),
    durationMinutes: numberOrNull(row[3]),
    rpe: numberOrNull(row[4]),
    symptomsBefore: numberOrNull(row[5]),
    symptomsAfter: numberOrNull(row[6]),
    symptoms24h: numberOrNull(row[7]),
    response24h: ['Better', 'Same', 'Mild flare', 'Significant flare'].includes(text(row[9]))
      ? text(row[9]) as RehabDay['response24h']
      : null,
    symptomDetails: text(row[10]),
    functionMarker: text(row[11]),
    sleepHours: numberOrNull(row[12]),
    nextDayPlan: text(row[13]),
    notes: text(row[14]),
    followUpComplete: text(row[15]).toLowerCase() === 'yes',
    followUpRecordedAt: null,
    updatedAt: new Date().toISOString(),
    revision: 0,
    syncStatus: 'synced',
  }
}

export function sheetValues(record: RehabDay) {
  const blank = (value: string | number | null) => value ?? ''
  return {
    first: [
      record.sessionType,
      record.sessionContent,
      blank(record.durationMinutes),
      blank(record.rpe),
      blank(record.symptomsBefore),
      blank(record.symptomsAfter),
      blank(record.symptoms24h),
    ],
    second: [
      record.response24h ?? '',
      record.symptomDetails,
      record.functionMarker,
      blank(record.sleepHours),
      record.nextDayPlan,
      record.notes,
      record.followUpComplete ? 'Yes' : 'No',
    ],
    metadata: [record.sessionAt, record.id, record.followUpDueAt, record.entryType, record.eventContext ?? '', record.eventChange ?? ''],
  }
}

function fingerprintRecord(record: RehabDay) {
  const values = sheetValues(record)
  const payload = JSON.stringify({ date: record.date, first: values.first, second: values.second, metadata: values.metadata })
  let hash = 2166136261
  for (let index = 0; index < payload.length; index += 1) {
    hash ^= payload.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

export function hasRemoteContent(record: RehabDay) {
  const values = sheetValues(record)
  return [...values.first.slice(1), ...values.second.slice(0, -1)].some((value) => value !== '' && value !== null)
    || record.followUpComplete
}

export class GoogleSheetsProvider implements CloudSyncProvider {
  readonly name = 'Google Sheets'
  private readonly spreadsheetId: string
  private readonly accessToken: string
  private readonly request: typeof fetch
  private metadataReady = false

  constructor(
    spreadsheetId: string,
    accessToken: string,
    request: typeof fetch = fetch,
  ) {
    this.spreadsheetId = spreadsheetId
    this.accessToken = accessToken
    this.request = request
  }

  private async api(path: string, init?: RequestInit) {
    const response = await this.request(`${GOOGLE_API_ROOT}/${this.spreadsheetId}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.accessToken}`,
        'Content-Type': 'application/json',
        ...init?.headers,
      },
    })
    if (!response.ok) {
      const detail = await response.json().catch(() => null) as { error?: { message?: string } } | null
      const message = detail?.error?.message || `Google Sheets request failed (${response.status}).`
      const error = new Error(message) as Error & { status?: number }
      error.status = response.status
      throw error
    }
    return response.json()
  }

  async probe() {
    try {
      const data = await this.api('?fields=properties.title,sheets.properties.title') as {
        properties?: { title?: string }
        sheets?: Array<{ properties?: { title?: string } }>
      }
      const hasLog = data.sheets?.some((sheet) => sheet.properties?.title === SHEET_NAME)
      return {
        available: Boolean(hasLog),
        message: hasLog
          ? `Connected to ${data.properties?.title || 'the selected spreadsheet'}.`
          : `The selected spreadsheet does not contain a “${SHEET_NAME}” tab.`,
      }
    } catch (error) {
      return { available: false, message: error instanceof Error ? error.message : 'Could not reach Google Sheets.' }
    }
  }

  private async readRows(): Promise<unknown[][]> {
    const data = await this.api(`/values/${sheetRange('A2:V1000')}?majorDimension=ROWS&valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER`) as { values?: unknown[][] }
    return data.values ?? []
  }

  private locate(record: RehabDay, rows: unknown[][]): { rowNumber: number; row: unknown[] } | null {
    const exactIndex = rows.findIndex((row) => text(row[17]) === record.id)
    if (exactIndex >= 0) return { rowNumber: exactIndex + 2, row: rows[exactIndex] }
    const legacyIndex = rows.findIndex((row) => normalizeDate(row[0]) === record.date && !text(row[17]))
    return legacyIndex < 0 ? null : { rowNumber: legacyIndex + 2, row: rows[legacyIndex] }
  }

  private async ensureMetadataHeaders() {
    if (this.metadataReady) return
    const data = await this.api(`/values/${sheetRange('Q1:V1')}?majorDimension=ROWS`) as { values?: unknown[][] }
    const headers = data.values?.[0]?.map(text) ?? []
    if (headers.join('|') !== 'Session time|Session ID|24h follow-up due|Entry type|Event context|Symptom change') {
      await this.api(`/values/${sheetRange('Q1:V1')}?valueInputOption=RAW`, {
        method: 'PUT',
        body: JSON.stringify({ majorDimension: 'ROWS', values: [['Session time', 'Session ID', '24h follow-up due', 'Entry type', 'Event context', 'Symptom change']] }),
      })
    }
    this.metadataReady = true
  }

  async fetch(record: RehabDay): Promise<RemoteRecord | null> {
    const located = this.locate(record, await this.readRows())
    if (!located) return null
    const remote = recordFromSheetRow(record.date, located.row)
    return { record: remote, fingerprint: fingerprintRecord(remote) }
  }

  async upsert(record: RehabDay, expectedFingerprint?: string): Promise<SyncResult> {
    try {
      await this.ensureMetadataHeaders()
      const rows = await this.readRows()
      const located = this.locate(record, rows)

      if (located) {
        const current = recordFromSheetRow(record.date, located.row)
        const currentFingerprint = fingerprintRecord(current)
        if (expectedFingerprint && currentFingerprint !== expectedFingerprint) {
          return { status: 'conflict', remote: { record: current, fingerprint: currentFingerprint } }
        }
      }

      const values = sheetValues(record)
      const rowNumber = located?.rowNumber ?? rows.length + 2
      const data = [
        { range: `'${SHEET_NAME}'!A${rowNumber}`, majorDimension: 'ROWS', values: [[record.date]] },
        { range: `'${SHEET_NAME}'!B${rowNumber}:H${rowNumber}`, majorDimension: 'ROWS', values: [values.first] },
        { range: `'${SHEET_NAME}'!J${rowNumber}:P${rowNumber}`, majorDimension: 'ROWS', values: [values.second] },
        { range: `'${SHEET_NAME}'!Q${rowNumber}:V${rowNumber}`, majorDimension: 'ROWS', values: [values.metadata] },
      ]
      if (!located) {
        data.push(
          { range: `'${SHEET_NAME}'!I${rowNumber}`, majorDimension: 'ROWS', values: [[`=IF(OR(F${rowNumber}="",H${rowNumber}=""),"",H${rowNumber}-F${rowNumber})`]] },
        )
      }
      await this.api('/values:batchUpdate', {
        method: 'POST',
        body: JSON.stringify({
          valueInputOption: 'USER_ENTERED',
          data,
        }),
      })

      const verified = await this.fetch(record)
      if (!verified || fingerprintRecord(record) !== verified.fingerprint) {
        return { status: 'error', message: 'Google Sheets did not return the saved values. The entry remains queued.', retryable: true }
      }
      return { status: 'synced', fingerprint: verified.fingerprint }
    } catch (error) {
      const status = (error as Error & { status?: number }).status
      return {
        status: 'error',
        message: error instanceof Error ? error.message : 'Google Sheets sync failed.',
        retryable: status === 429 || Boolean(status && status >= 500),
      }
    }
  }
}
