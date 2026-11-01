# Architecture

## Product boundary

The local application is useful without an account or network. IndexedDB is the working source of truth while offline. A user-selected private Google Sheet is the shared reconciliation target, not a prerequisite for saving an entry.

## Data flow

1. The editor keeps a local draft and validates numeric boundaries.
2. A 650 ms debounce (or **Save now**) calls one transactional local save.
3. The transaction increments the record revision, marks it `pending`, and replaces the session-ID-keyed outbox item.
4. UI screens subscribe to Dexie queries and update after the transaction commits.
5. The sync orchestrator fetches the Google Sheets row first, compares its fingerprint, then calls the provider upsert only when safe.
6. A verified write removes only the exact queued revision. An edit made during synchronization stays queued.

## Local stores — schema version 2

- `days`: `id, date, sessionAt, followUpDueAt, updatedAt, syncStatus`
- `outbox`: `&recordId, date, queuedAt`
- `settings`: `&key`

The date index is intentionally non-unique: each training or rehab session keeps its own before/after values and its own 24-hour response. The version-2 migration gives existing daily records a noon session time and a corresponding due time without discarding data.

## Dates

`RehabDay.date` is a `YYYY-MM-DD` calendar value and is displayed explicitly as `DD.MM.YYYY`. `sessionAt` and `followUpDueAt` are UTC ISO timestamps. “Today” and displayed times use the explicit `Europe/Berlin` time zone.

## Backups

JSON exports use a versioned envelope:

```json
{
  "schemaVersion": 2,
  "exportedAt": "UTC timestamp",
  "app": "Rehab Tracker",
  "records": []
}
```

CSV follows the specified workbook input columns but omits Excel’s calculated column I. Cells beginning with spreadsheet formula control characters are prefixed with an apostrophe.

## Cloud boundary

`src/sync/provider.ts` defines the provider contract. `src/sync/google.ts` implements Google Identity Services, Google Picker, the Sheets REST API, stable session-ID lookup, remote fingerprints, and verified writes. Existing blank date rows are reused for the first session; additional same-day sessions append rows with formula column I preserved. `src/sync/orchestrator.ts` processes the persistent outbox and preserves conflicts and failures for review.

The app requests Google’s per-file `drive.file` scope. OAuth access tokens stay in memory and are revoked on disconnect. The selected spreadsheet ID is stored locally; it is not committed or embedded in the deployment. No client secret belongs in the browser bundle.
