# Architecture

## Product boundary

The local application is useful without an account or network. IndexedDB is the working source of truth while offline. The future SharePoint workbook is a reconciliation target, not a prerequisite for saving an entry.

## Data flow

1. The editor keeps a local draft and validates numeric boundaries.
2. A 650 ms debounce (or **Save now**) calls one transactional local save.
3. The transaction increments the record revision, marks it `pending`, and replaces the date-keyed outbox item.
4. UI screens subscribe to Dexie queries and update after the transaction commits.
5. A future sync orchestrator will fetch the remote row first, compare its fingerprint, then call the provider upsert only when safe.

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

`src/sync/provider.ts` defines the provider contract and an intentionally unavailable implementation. A Graph provider must not be added until the feasibility gate passes. It must support fetch-before-write, fingerprints, idempotent date upserts, retry classification, and explicit conflicts.

No authentication secret belongs in the browser bundle. If authorized later, use MSAL Browser authorization code with PKCE and non-secret deployment configuration.

