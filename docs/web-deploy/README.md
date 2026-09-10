# Stream share links — https://www.triggerappltd.cyou (LIVE SETUP)

The Android app generates share links like:

    https://www.triggerappltd.cyou/stream/sch_1725889200123

and registers an **App Links** intent-filter (`android:autoVerify="true"`)
for BOTH hosts (`www.triggerappltd.cyou` — canonical, and the apex
`triggerappltd.cyou`). Tapping a shared link opens the Trigger App directly
on the Stream tab with the booking dialog for that stream (any signed-in
user can resolve the id — `ss_select` RLS policy on `scheduled_streams`).

## Hosting — ALREADY DONE, nothing to upload by hand

The website (and both required pieces) lives in the separate repo:

    https://github.com/codewith-akhil/Trigger-App-Web   (deployed on AWS Amplify)

which serves:

| URL | What | Implementation |
|---|---|---|
| `https://www.triggerappltd.cyou/.well-known/assetlinks.json` | Digital Asset Links statement (com.trigger.app + upload-key SHA-256 `ebfe33de…a4e20b`), `application/json`, no redirect | `src/app/.well-known/assetlinks.json/route.ts` + static copy in `public/.well-known/` |
| `https://www.triggerappltd.cyou/stream/<id>` | Branded browser-fallback landing page ("Open in Trigger App" + Google Play button) | `src/app/stream/[id]/page.tsx` |
| `https://triggerappltd.cyou/*` | 302 → same path on `www` (Amplify domain config) | — |

Pushing to `main` of Trigger-App-Web redeploys Amplify automatically.
Verify after a deploy:

    curl -s https://www.triggerappltd.cyou/.well-known/assetlinks.json

and re-verify app-side (after installing an APK built from ≥ this commit):

    adb shell pm verify-app-links --re-verify com.trigger.app
    adb shell dumpsys package com.trigger.app | grep -A5 "App Links"

## Key facts

- **www is canonical**: the apex 302-redirects to www. Android's verifier
  gets a redirect-free 200 on www, which is why share links and the
  manifest's primary host use `www.`.
- The fingerprint inside assetlinks.json is the SHA-256 of the upload key
  (`trigger-upload-key (2).jks`) — it matches every APK/AAB this pipeline
  signs. If the signing key ever changes, regenerate BOTH copies (app repo
  `docs/web-deploy/.well-known/assetlinks.json` and the Trigger-App-Web
  route handler + public copy) and redeploy.
- Until verification succeeds, Android shows the "Open with" chooser —
  the app still works, verification just removes the extra tap.
- A dormant GitHub Pages deployment (gh-pages branch of THIS repo, CNAME
  `triggerappltd.cyou`) also serves the same files. It is unused while the
  domain DNS points at AWS; kept purely as an emergency fallback.

## What is wired in the app (code, this repo)

- `StreamScheduleService.scheduleStream()` — share links use
  `https://www.triggerappltd.cyou/stream/<id>`.
- `StreamScheduleService.fetchStreamById(id)` — resolves one scheduled stream
  for any signed-in user (used by the deep link).
- `AndroidManifest.xml` — autoVerify App Links intent-filter for both hosts.
- `MainActivity` (`StreamDeepLink`) — captures the link on cold start AND
  warm delivery; accepts both hosts.
- Dashboard — jumps to the Stream tab; `StreamTabContent` fetches the stream
  and opens the booking dialog; unknown ids show a "Stream not found" toast.
