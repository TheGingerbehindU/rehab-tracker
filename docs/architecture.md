# Architecture

## Product boundary

The local application is useful without an account or network. IndexedDB is the working source of truth while offline. A user-selected private Google Sheet is the shared reconciliation target, not a prerequisite for saving an entry.

## Data flow

1. The editor keeps a local draft and validates numeric boundaries.
2. A 650 ms debounce (or **Save now**) calls one transactional local save.
3. The transaction increments the record revision, marks it `pending`, and replaces the date-keyed outbox item.
4. UI screens subscribe to Dexie queries and update after the transaction commits.
5. The sync orchestrator fetches the Google Sheets row first, compares its fingerprint, then calls the provider upsert only when safe.
6. A verified write removes only the exact queued revision. An edit made during synchronization stays queued.

## Local stores — schema version 1

- `days`: `id, &date, updatedAt, syncStatus`
- `outbox`: `&date, recordId, queuedAt`
- `settings`: `&key`

The unique date index enforces the workbook’s one-row-per-date model. Changing an existing record’s date is transactional. A target date that already contains another record is rejected instead of silently merging data.

## Dates

`RehabDay.date` is a `YYYY-MM-DD` calendar value. Date-only arithmetic uses UTC constructors as a neutral arithmetic device and never converts a local midnight. “Today” is derived with `Intl.DateTimeFormat` and the explicit `Europe/Berlin` time zone. Timestamps such as `updatedAt` remain UTC ISO strings.

## Backups

JSON exports use a versioned envelope:

```json
{
  "schemaVersion": 1,
  "exportedAt": "UTC timestamp",
  "app": "Rehab Tracker",
  "records": []
}
```

CSV follows the specified workbook input columns but omits Excel’s calculated column I. Cells beginning with spreadsheet formula control characters are prefixed with an apostrophe.

## Cloud boundary

`src/sync/provider.ts` defines the provider contract. `src/sync/google.ts` implements Google Identity Services, Google Picker, the Sheets REST API, date-based row lookup, remote fingerprints, and verified writes. `src/sync/orchestrator.ts` processes the persistent outbox and preserves conflicts and failures for review.

The app requests Google’s per-file `drive.file` scope. OAuth access tokens stay in memory and are revoked on disconnect. The selected spreadsheet ID is stored locally; it is not committed or embedded in the deployment. No client secret belongs in the browser bundle.
