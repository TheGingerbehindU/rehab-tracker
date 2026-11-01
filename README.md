# Rehab Tracker

A private, offline-first Progressive Web App for recording rehabilitation and training sessions, immediate symptoms, and the later response. The prototype works fully offline and includes an optional per-file Google Sheets synchronization path.

## What works

- Several independently measured sessions per Europe/Berlin calendar date
- Fast mobile daily editor with validation and automatic IndexedDB saves
- Per-session 24-hour follow-up prompts, recorded check-in time, and calendar reminders
- Monthly calendar with descriptive response markers
- Basic symptom, duration, follow-up, and flare summaries
- Versioned JSON backup/restore and CSV export with formula-injection protection
- Offline app shell, install manifest, and service worker
- Persistent sync outbox with fetch-before-write Google Sheets synchronization
- Per-file Google Drive Picker access; no broad Drive permission
- Remote write verification and conflict detection
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

The repository includes a GitHub Pages workflow. The Vite build uses relative asset paths so it can run below a repository subpath. Configure the repository variables documented in [docs/google-sheets-setup.md](docs/google-sheets-setup.md) before expecting Google sign-in to work in the deployed app.

## Backups and privacy

Entries are stored in IndexedDB on the current browser profile. Clearing site data or uninstalling the PWA can erase them.

- Use **Settings → Export JSON** for a complete, restorable backup.
- Use **Export CSV** for review or a manual spreadsheet workflow.
- Keep backups in a location approved for sensitive health information.
- Importing a JSON backup upserts sessions by their stable ID and queues them locally.

Google synchronization is optional. Without Google OAuth deployment configuration, the local app and all backup/export functions continue to work normally. Setup is documented in [docs/google-sheets-setup.md](docs/google-sheets-setup.md).

## Architecture

- React + TypeScript + Vite
- Dexie/IndexedDB with schema version 2 and an automatic migration from the original daily model
- Session records keyed by stable UUID with non-unique date, session time, and individual follow-up due time
- `SyncOutbox` coalesced by session ID, so repeated local edits queue one logical remote upsert
- Pure date-only helpers; timestamps are UTC, calendar dates are never converted through local midnight
- PWA caching through `vite-plugin-pwa`
- `CloudSyncProvider` interface separates Google Sheets from local product behavior
- Google tokens stay in memory; the selected sheet ID stays in IndexedDB

More detail is in [docs/architecture.md](docs/architecture.md).

## Workbook template

The repository includes the verified empty [Rehab_Response_Tracker.xlsx](Rehab_Response_Tracker.xlsx) integration template. It contains the exact A–P input contract, 120 dates from 2026-11-01 through 2027-02-28, protected-by-convention formulas in column I, validation lists, a usage guide, and formula-driven weekly summaries. See [docs/workbook-analysis.md](docs/workbook-analysis.md).

## Current limitations

- Google Cloud OAuth/Picker credentials must be configured by the deployment owner.
- Conflicts are detected and preserved, but the side-by-side conflict-resolution screen is not implemented yet.
- Progress is intentionally basic and only uses complete symptom pairs; missing follow-ups are not plotted as zero.
- Calendar reminders are used for reliable phone alerts; a closed static PWA cannot schedule its own background notification reliably.
- Browser storage is not a permanent backup.
- The PWA has not yet been exercised on the target Android device or deployed to HTTPS.

## Medical note

The app records subjective observations. Its labels and charts are not diagnostic advice or medically validated thresholds.
