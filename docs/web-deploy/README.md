# Deploying https://triggerappltd.cyou (stream share links)

The Android app now generates share links like:

    https://triggerappltd.cyou/stream/sch_1725889200123

and registers an **App Links** intent-filter (`android:autoVerify="true"`)
for `https://triggerappltd.cyou/stream/*`. Tapping a shared link opens the
Trigger App directly on the Stream tab with the booking dialog for that
stream (any signed-in user can resolve the id — `ss_select` RLS policy on
`scheduled_streams`).

You own the domain — TWO files must be hosted on it for everything to work.

## 1. Assetlinks verification (makes the app open WITHOUT the chooser)

Upload this repo file:

    docs/web-deploy/.well-known/assetlinks.json

so it is served EXACTLY at:

    https://triggerappltd.cyou/.well-known/assetlinks.json

Requirements:
- HTTP 200, `Content-Type: application/json`, no redirect (https only).
- The fingerprint inside (`ebfe33de…a4e20b`) is the upload-key certificate
  (SHA-256 of `trigger-upload-key (2).jks`) — it matches every APK/AAB this
  pipeline signs. If you ever change the signing key, regenerate the file.

Verify after upload (from any machine):

    curl -s https://triggerappltd.cyou/.well-known/assetlinks.json

and re-verify app-side:

    adb shell pm verify-app-links --re-verify com.trigger.app
    adb shell dumpsys package com.trigger.app | grep -A5 "App Links"

Until verification succeeds, Android shows the "Open with" chooser — the
app still works, verification just removes the extra tap.

## 2. Landing page (browser fallback for people without the app)

Upload:

    docs/web-deploy/index.html

to the web root of `triggerappltd.cyou` (any static host: Cloudflare Pages,
Netlify, GitHub Pages behind the custom domain, cPanel, nginx …). It is a
single dependency-free page with an "Open in Trigger App" button that
re-fires the deep link.

Minimal nginx example:

    server {
      listen 443 ssl;
      server_name triggerappltd.cyou;
      root /var/www/triggerapp;
      location = /.well-known/assetlinks.json {
        default_type application/json;
      }
      location /stream/ { try_files /index.html =404; }
    }

(If you use Cloudflare, proxying is fine — assetlinks.json must still be
returned unmodified and without redirects.)

## 3. What was wired in the app (code, this repo)

- `StreamScheduleService.scheduleStream()` — share links now use the real domain.
- `StreamScheduleService.fetchStreamById(id)` — resolves one scheduled stream
  for any signed-in user (used by the deep link).
- `AndroidManifest.xml` — `autoVerify` App Links intent-filter.
- `MainActivity` — captures the link on cold start AND warm delivery
  (`StreamDeepLink` holder).
- Dashboard — jumps to the Stream tab; `StreamTabContent` fetches the stream
  and opens the booking dialog; unknown ids show a "Stream not found" toast.
