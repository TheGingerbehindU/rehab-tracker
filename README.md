# Rehab Tracker

A private, offline-first Progressive Web App for recording rehabilitation and training sessions, immediate symptoms, and the later response. The current prototype implements Milestones 0–1 of the product specification and deliberately does **not** connect to Microsoft 365 yet.

## What works

- One local entry per Europe/Berlin calendar date
- Fast mobile daily editor with validation and automatic IndexedDB saves
- Next-day follow-up prompt and recorded follow-up time
- Monthly calendar with descriptive response markers
- Basic symptom, duration, follow-up, and flare summaries
- Versioned JSON backup/restore and CSV export with formula-injection protection
- Offline app shell, install manifest, and service worker
- Persistent sync outbox plus an isolated, currently unavailable cloud provider adapter
- Light/dark mode, 44 px touch targets, and 320 px responsive layout

No analytics, remote database, AI service, credentials, private SharePoint URL, or sample health record is included.

## Run locally

Requirements: Node.js 22 or newer and npm.

```bash
npm install
npm run dev
```

Open the local URL printed by Vite. Useful checks:

```bash
npm test
npm run lint
npm run build
npm run preview
```

The production preview is where the generated service worker is active. In browser developer tools, switch the network to offline and reload to verify the cached app shell.

## Install on Android

1. Deploy the production build to an approved HTTPS static host, or expose the local production preview through an approved HTTPS development setup.
2. Open the site in Android Chrome.
3. Use Chrome’s menu and choose **Install app** or **Add to Home screen**.
4. Open the installed app once while online, then test reopening it in airplane mode.

GitHub Pages deployment is intentionally deferred until the repository and hosting visibility are confirmed. The Vite build uses relative asset paths so it can run below a repository subpath.

## Backups and privacy

Entries are stored in IndexedDB on the current browser profile. Clearing site data or uninstalling the PWA can erase them.

- Use **Settings → Export JSON** for a complete, restorable backup.
- Use **Export CSV** for review or a manual spreadsheet workflow.
- Keep backups in a location approved for sensitive health information.
- Importing a JSON backup upserts entries by their date and queues them locally.

Cloud synchronization remains disabled until the organization-authorized feasibility check in [docs/sharepoint-feasibility.md](docs/sharepoint-feasibility.md) succeeds against a workbook copy.

## Architecture

- React + TypeScript + Vite
- Dexie/IndexedDB with schema version 1
- `RehabDay` records keyed by stable UUID with a unique date index
- `SyncOutbox` coalesced by date, so repeated local edits queue one logical remote upsert
- Pure date-only helpers; timestamps are UTC, calendar dates are never converted through local midnight
- PWA caching through `vite-plugin-pwa`
- `CloudSyncProvider` interface separates the future Graph implementation from local product behavior

More detail is in [docs/architecture.md](docs/architecture.md).

## Workbook status

The named `Rehab_Response_Tracker.xlsx` was not available when the repository was created. The column contract from the written specification is documented, but actual worksheet names, formulas, tables, styles, and weekly summary integrity have not been inspected. See [docs/workbook-analysis.md](docs/workbook-analysis.md).

## Current limitations

- SharePoint/Microsoft Graph sign-in, discovery, reads, writes, and conflict UI are not implemented.
- Progress is intentionally basic and only uses complete symptom pairs; missing follow-ups are not plotted as zero.
- One combined entry per day is supported. Separately measured multiple sessions require a data migration and workbook redesign.
- Browser storage is not a permanent backup.
- The PWA has not yet been exercised on the target Android device or deployed to HTTPS.

## Medical note

The app records subjective observations. Its labels and charts are not diagnostic advice or medically validated thresholds.

