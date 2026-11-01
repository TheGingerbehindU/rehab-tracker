# SharePoint feasibility gate

Status: **not started**. Cloud sync must stay disabled until every write test below succeeds against an authorized workbook copy.

## User preparation

1. Confirm you can open and edit the exact workbook in Excel Online with your own Microsoft 365 identity.
2. Identify whether that identity is a DLV tenant member, a guest in the DLV tenant, or a personal account using a shared link.
3. Ask the workbook owner to create a test copy in an approved location. Keep the production workbook untouched.
4. Obtain the test copy’s web URL privately. Do not commit it to Git or paste it into an issue.
5. Ask DLV IT whether a browser SPA may be registered and whether delegated Graph consent is permitted. Request only the minimum approved file access.
6. Obtain the non-secret tenant ID and SPA client ID only after registration is approved. Never create or embed a client secret in this PWA.
7. Register the exact HTTPS test and production redirect URIs. Localhost may be added separately for development.

## Developer probe

1. Add a minimal MSAL Browser probe behind a development-only feature flag.
2. Sign in with the user’s own account using authorization code + PKCE.
3. Call `GET /me` and record the tenant/account type without storing tokens in project files.
4. Resolve the test copy to drive and item IDs using the sharing model that actually applies.
5. List workbook worksheets and compare them with `docs/workbook-analysis.md`.
6. Read one known row from the first worksheet.
7. Create a persistent workbook session if supported for this file.
8. Write one unmistakable test value to a pre-agreed noncritical cell in the copy, preserving column I.
9. Close/commit the session, reopen Excel Online, and verify the persisted value and untouched formulas/weekly review.
10. Repeat the same logical write to prove it does not create a duplicate date row.
11. Make a remote edit, then prove a pending local edit is classified as a conflict rather than overwriting it.
12. Record the exact scopes, tenant consent behavior, endpoints, and errors here before enabling any automated sync.

## Stop conditions

Stop cloud development and keep local export workflows if app registration, consent, shared-file Graph access, workbook APIs, or organizational policy blocks the probe. Do not work around federation controls and do not ask for another person’s password.

