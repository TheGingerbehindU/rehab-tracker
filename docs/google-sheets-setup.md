# Google Sheets setup

The app uses Google Identity Services, Google Picker, and the `drive.file` scope. The user explicitly selects one spreadsheet; the app does not request access to the rest of Google Drive. Access tokens stay in browser memory. The selected spreadsheet ID and last successful sync time are stored in IndexedDB.

Do not commit a spreadsheet ID, account email, OAuth client ID, API key, access token, or health record to this repository. Browser OAuth client IDs and restricted browser API keys are public deployment configuration, but keep their real values in local environment files and GitHub repository variables.

## Google Cloud configuration

1. Create or choose a Google Cloud project.
2. Enable **Google Sheets API**, **Google Drive API**, and **Google Picker API**.
3. Configure the OAuth consent screen. For a private prototype, keep it in testing and add the athlete’s Google account as a test user.
4. Create an OAuth client with application type **Web application**.
5. Add `http://localhost:5173` as an authorized JavaScript origin.
6. After GitHub Pages is enabled, add its exact HTTPS origin as another authorized JavaScript origin.
7. Create a browser API key. Restrict it to the localhost and production origins and to the Google Picker API.
8. Copy the numeric Google Cloud project number. Google Picker calls this the app ID.

Create `.env.local` from `.env.example`:

```dotenv
VITE_GOOGLE_CLIENT_ID=...
VITE_GOOGLE_API_KEY=...
VITE_GOOGLE_APP_ID=...
VITE_GOOGLE_LOGIN_HINT=...
```

`.env.local` is ignored by Git. Restart Vite after changing it.

## First connection

1. Open **Settings → Connect Google**.
2. Sign in with the spreadsheet owner account.
3. Select **Rehab Response Tracker** in Google Picker.
4. The app verifies that the spreadsheet contains a `Daily Rehab Log` tab.
5. Tap **Sync now**. The app reads remote state before each write and updates columns B–H and J–P. Column I remains formula-owned. Columns Q–S store session time, stable session ID, and follow-up due time so several sessions can share one date.

The first session on a date reuses that date’s prepared row. Additional same-day sessions are appended as separate rows, with the calculated column-I formula added to each appended row.

The spreadsheet should be shared directly with the coach as **Viewer**, not “Anyone with the link.” The coach uses Google Sheets or the browser and does not need to connect to the PWA.

## GitHub Pages variables

Add these repository variables under **Settings → Secrets and variables → Actions → Variables**:

- `VITE_GOOGLE_CLIENT_ID`
- `VITE_GOOGLE_API_KEY`
- `VITE_GOOGLE_APP_ID`
- `VITE_GOOGLE_LOGIN_HINT` (optional)

The Pages workflow builds the same static PWA with those browser configuration values. The spreadsheet itself is chosen inside the app and is never embedded in the deployment.

## Safety behavior

- Offline edits remain in IndexedDB and the outbox.
- Expired authorization asks the user to connect again; it does not discard queued work.
- A remote edit to the same session marks the local record as a conflict instead of overwriting it.
- Additional sessions and dates outside the prepared range append verified rows without overwriting an existing session.
- JSON export remains the independent recovery path if browser storage is cleared.
