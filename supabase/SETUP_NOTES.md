# Trigger App — Backend Setup Notes

This document lists **everything you (the project owner) must do on the
Supabase, Agora, and Firebase dashboards** to make the backend live. The code
in `supabase/` is complete and ready; this checklist configures the managed
services and the server-side secrets.

> All secrets live **only** on the Supabase Edge Functions (set via
> `supabase secrets set`). Nothing sensitive is shipped to the Android client.
> The Android `.env` only contains the Supabase URL, anon key, Agora App ID,
> and a static fallback token — **never** the Agora Primary Certificate,
> service-role key, Resend key, Razorpay secret, or Firebase private key.

---

## 0. Prerequisites (already done in this commit)

- [x] Supabase CLI installed (`npm i -g supabase`, v2.116.0)
- [x] Project linked: `supabase link --project-ref uazkcainrajcgxecomly`
- [x] Existing migration `20260903` marked applied on remote
- [x] New migration `20260904_full_app_schema.sql` written (14 new tables +
      storage buckets + RLS + triggers + realtime)
- [x] 12 edge functions written (generate-agora-token, send/verify-email-otp,
      send-stream-scheduled-email, send-booking-confirmation-email,
      send-push-notification, create/verify-razorpay-order,
      delete-user-account, register-push-token, upsert/verify-vault-pin,
      sync-user-profile)
- [x] Email templates written (6-digit `{{ .Token }}`, never link-based)
- [x] `supabase/.env.example` template created

---

## 1. SUPABASE — what you must do on the dashboard

Project ref: `uazkcainrajcgxecomly` · URL: https://supabase.com/dashboard/project/uazkcainrajcgxecomly

### 1.1 Auth → Sign In / Providers → Email
- [ ] Enable **Email** provider (already on by default).
- [ ] Disable **"Confirm email"** link flow → instead enable **OTP**:
      under *Email* → *Auth Templates*, every template must use `{{ .Token }}`
      (the 6-digit code), **NOT** `{{ .ConfirmationURL }}` (the link).
      The HTML templates in `supabase/templates/*.html` are the canonical
      versions — paste their contents into the dashboard (or reference them
      from `config.toml` for local dev).
- [ ] Set **OTP length = 6** and **OTP expiry = 600 s** (10 min).
- [ ] Set **rate limits**: 1 OTP per email per 60 s (matches the edge function
      `send-email-otp` cooldown).
- [ ] Disable "Allow anonymous sign-ins" (already off in `config.toml`).

### 1.2 Auth → URL Configuration
- [ ] Set **Site URL** = `https://triggerapp.com` (or your production domain).
- [ ] Add **Redirect URLs**: only your app's deep links. (Not needed for OTP
      flow, but required if you ever enable OAuth.)

### 1.3 Auth → SMTP Settings → Custom SMTP (Resend)
This routes ALL Supabase auth emails (signup, recovery, email-change, magic
link) through Resend so they come from your domain and don't hit the
Supabase-managed 4/h rate limit.

- [ ] Go to *Auth → SMTP Settings* → enable **Custom SMTP**.
- [ ] **Host**: `smtp.resend.com` · **Port**: `465` · **Encryption**: `SSL/TLS`
- [ ] **Username**: `resend` (literal string)
- [ ] **Password**: your Resend API key (`re_...`)
- [ ] **Sender email**: `Trigger App <no-reply@triggerapp.com>` (must match a
      verified Resend domain — see §3 below)
- [ ] **Minimum interval**: `60s` (matches the resend cooldown requirement)
- [ ] Save & send a test email.

> ⚠️ With custom SMTP enabled, Supabase's built-in OTP emails are sent via
> Resend automatically. The custom `send-email-otp` edge function is an
> ALTERNATIVE path for flows where you want full control (e.g. vault-reset
> OTPs that aren't tied to an auth event). You can use either or both.

### 1.4 Storage → Buckets
The migration creates these buckets automatically (idempotent `insert ... on
conflict do nothing`). Verify they appear:

- [ ] `avatars` (public, 10 MB, images only)
- [ ] `chat_media` (private, 64 MB, images/video/audio/pdf)
- [ ] `voice_notes` (private, 16 MB, audio)
- [ ] `documents` (private, 100 MB, office + pdf + text + zip)
- [ ] `vault_media` (private, 100 MB, images/video)
- [ ] `stream_thumbnails` (public, 10 MB, images)

If any are missing, re-run the migration (see §5).

### 1.5 Database → Migrations
- [ ] Confirm `20260903_call_and_live_stream_schema` is **Applied**.
- [ ] Apply `20260904_full_app_schema` (see §5 below for the command).
- [ ] After applying, run `supabase db push` again to load `seed.sql`
      (languages + countries reference data).

### 1.6 Edge Functions → Deploy + Secrets
- [ ] Deploy all 12 functions (see §5).
- [ ] Set all server-side secrets (see §6).
- [ ] In the dashboard → *Edge Functions → Logs*, verify each function boots
      without "missing env var" errors.

### 1.7 Realtime
- [ ] *Database → Publications → supabase_realtime* → confirm these tables are
      listed: `call_sessions, live_streams, live_stream_comments,
      live_stream_reactions, call_events, profiles, conversations, messages,
      message_reactions, scheduled_streams, stream_bookings,
      wallet_transactions, user_presences` (all added by the migration).

### 1.8 Database → Connection pooling (optional)
- [ ] Add the connection pooler URL to your Android `SupabaseClient` if you see
      connection limits. Not required for the REST API the app uses today.

---

## 2. AGORA — what you must do on the console

URL: https://console.agora.io/ · Project App ID: `b17004d7060b4ee0bf6e50cb931e1bbd`

### 2.1 Project → Configuration
- [ ] Open the project → *Project Management* → your project.
- [ ] Confirm **App ID** = `b17004d7060b4ee0bf6e50cb931e1bbd` (already in env).
- [ ] Confirm **Primary Certificate** = `1695667e407d4900bd13c5333df86934`
      (already in `AGORA_PRIMARY_CERTIFICATE` server-side env — **never** put
      this in the Android `.env`).
- [ ] Ensure **"App ID + Token"** authentication mode is ON (NOT "App ID only"
      testing mode — that mode is only for quick demos; production requires
      tokens signed with the primary certificate).

### 2.2 Token server
- [ ] The edge function `generate-agora-token` already issues real AccessToken2
      tokens using `AGORA_APP_ID` + `AGORA_PRIMARY_CERTIFICATE`. Verify by
      calling it once after deployment (see §7 smoke test).

### 2.3 Billing / capacity
- [ ] Confirm your Agora plan includes enough minutes for 1-to-1 audio/video
      calls + live broadcasting. (Free tier: 10 000 min/month.)

### 2.4 Network / firewall (if Android fails to connect)
- [ ] Agora uses dynamic ports. No firewall changes needed on the server side
      (tokens are minted server-side; the SDK connects from the device).

---

## 3. RESEND — what you must do

URL: https://resend.com

### 3.1 Verify a sending domain
- [ ] *Domains → Add domain* → enter `triggerapp.com` (or your domain).
- [ ] Add the 3 DNS records Resend shows (SPF, DKIM, DMARC) at your DNS provider.
- [ ] Wait for "Verified" status.

### 3.2 Create an API key
- [ ] *API Keys → Create API Key* → name `trigger-supabase-edge` →
      permission *Sending access* → copy `re_...`.
- [ ] Paste into `supabase/.env` as `RESEND_API_KEY=re_...`.

### 3.3 (Optional) Onboarding sandbox
Until your domain is verified, Resend lets you send ONLY to your own
account email from `onboarding@resend.dev`. Use this to test OTP delivery,
then switch `FROM_EMAIL` to your verified domain.

---

## 4. FIREBASE — what you must do

URL: https://console.firebase.google.com

### 4.1 Create / open the project
- [ ] Create a new project (e.g. `trigger-app`) or use an existing one.
- [ ] Add an **Android app** with package name `com.aistudio.triggerapp.trigq`
      (matches `applicationId` in `app/build.gradle.kts`).
- [ ] Download `google-services.json` → place in `app/` (the
      `google-services` gradle plugin reads it automatically).
- [ ] Add the Firebase SDK dependencies if not already present (the project
      already declares `firebase-bom` + `firebase-ai` etc. — uncomment
      `firebase-messaging` in `app/build.gradle.kts` when you wire FCM).

### 4.2 Cloud Messaging → server key (HTTP v1)
FCM's legacy server key is deprecated. The edge function uses **HTTP v1** with
a service-account JWT. To get the service-account credentials:

- [ ] *Project settings → Service accounts → Generate new private key* →
      download the JSON file.
- [ ] Open the JSON and copy THREE values into `supabase/.env`:
  - `project_id` → `FIREBASE_PROJECT_ID`
  - `client_email` → `FIREBASE_CLIENT_EMAIL`
  - `private_key` → `FIREBASE_PRIVATE_KEY` (keep the `\n` escapes; the edge
    function converts them back to real newlines)
- [ ] **Delete the downloaded JSON** — it's now redundant and a security risk.

### 4.3 Android-side FCM token registration
- [ ] In the Android app, call `FirebaseMessaging.getInstance().token`
      on app launch + on `onNewToken`, then POST the token to the
      `register-push-token` edge function with the device id.
- [ ] The `send-push-notification` edge function looks up active tokens and
      sends via FCM HTTP v1, automatically deactivating invalid tokens.

---

## 5. Deploy commands (run from `trigger-app/` repo root)

```bash
# 1. Set the access token once (the one you pasted in chat):
export SUPABASE_ACCESS_TOKEN=sbp_544aa09d24df746f252a31871a1f0381652935da
export SUPABASE_DB_PASSWORD='Gizudio4.3@mfs'

# 2. Apply migrations to the remote database:
supabase db push

# 3. Load seed data (languages + countries):
supabase db push --include-seed   # or run seed.sql from the SQL editor

# 4. Set all server-side secrets from the filled-in .env:
supabase secrets set --env-file supabase/.env

# 5. Deploy all edge functions:
supabase functions deploy generate-agora-token --no-verify-jwt
supabase functions deploy send-email-otp
supabase functions deploy verify-email-otp
supabase functions deploy send-stream-scheduled-email
supabase functions deploy send-booking-confirmation-email
supabase functions deploy send-push-notification
supabase functions deploy create-razorpay-order
supabase functions deploy verify-razorpay-payment
supabase functions deploy delete-user-account
supabase functions deploy register-push-token
supabase functions deploy upsert-vault-pin
supabase functions deploy verify-vault-pin
supabase functions deploy sync-user-profile

# (or deploy all at once:)
# supabase functions deploy --no-verify-jwt  # deploys every function folder
```

> Note: `generate-agora-token` is the ONLY function that needs
> `--no-verify-jwt` removed — it MUST verify JWTs (it already calls
> `supabase.auth.getUser()` itself). The flag above is a safety default for
> functions that handle auth internally. **For production, drop `--no-verify-jwt`
> from every command** so the Supabase gateway enforces JWT auth before the
> function even runs.

---

## 6. Required server-side secrets (checklist)

Set ALL of these via `supabase secrets set --env-file supabase/.env`:

| Secret | Purpose | Where obtained |
|---|---|---|
| `SUPABASE_URL` | Edge functions call back into the project | Supabase dashboard |
| `SUPABASE_ANON_KEY` | User-context client (RLS-aware) | Supabase dashboard |
| `SUPABASE_SERVICE_ROLE_KEY` | Admin client (bypass RLS) | Supabase dashboard |
| `AGORA_APP_ID` | Agora project identifier | Agora console |
| `AGORA_PRIMARY_CERTIFICATE` | AccessToken2 signing key (SERVER ONLY) | Agora console |
| `RESEND_API_KEY` | SMTP relay for OTP + stream emails | resend.com |
| `FROM_EMAIL` | Verified sender address | resend.com (verified domain) |
| `REPLY_TO_EMAIL` | Reply-to for transactional mail | your domain |
| `FIREBASE_PROJECT_ID` | FCM HTTP v1 project id | Firebase console |
| `FIREBASE_CLIENT_EMAIL` | FCM service-account email | Firebase service-account JSON |
| `FIREBASE_PRIVATE_KEY` | FCM service-account private key (PEM) | Firebase service-account JSON |
| `RAZORPAY_KEY_ID` | Order creation + payment capture | razorpay.com dashboard |
| `RAZORPAY_KEY_SECRET` | HMAC signature verification (SERVER ONLY) | razorpay.com dashboard |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook signature verification | razorpay.com webhook settings |

> Razorpay + Resend placeholders are test values in `supabase/.env.example`.
> Replace with real keys before going live.

---

## 7. Smoke tests (after deploy)

Run each from a terminal with a valid user JWT in `$JWT`:

```bash
JWT="<paste a real access_token from the Android app or a test signup>"

# 1. Agora token (should return token starting with "006" + App ID)
curl -X POST "https://uazkcainrajcgxecomly.functions.supabase.co/generate-agora-token" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"channelName":"smoke_test","uid":12345,"role":"publisher"}'

# 2. OTP send (check Resend logs + otp_codes table)
curl -X POST "https://uazkcainrajcgxecomly.functions.supabase.co/send-email-otp" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","purpose":"signup"}'

# 3. Push notification (needs a registered token first)
curl -X POST "https://uazkcainrajcgxecomly.functions.supabase.co/send-push-notification" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"title":"Smoke test","body":"Hello from Trigger backend"}'

# 4. Razorpay order
curl -X POST "https://uazkcainrajcgxecomly.functions.supabase.co/create-razorpay-order" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"amount":99.00,"currency":"INR","receipt":"smoke_1"}'
```

---

## 8. Android wiring (UI changes NOT done in this commit — "don't touch UI")

The backend is complete and live, but the Android app still has a few
client-side mocks that must be rewired to call the new edge functions. These
are flagged here as the next phase of work (they touch UI/service Kotlin,
which this commit deliberately leaves untouched per the task constraint):

| Current client behaviour | Replace with |
|---|---|
| `SignUpScreen` / `ForgotPasswordScreen` generate OTP locally (`(100000..999999).random()`) | Call `send-email-otp` edge function; verify via `verify-email-otp` |
| `UserRepository.profile` in-memory only | Call `sync-user-profile` on profile edits; load from `profiles` table on login |
| `WalletService` in-memory balance/transactions | Read/write `wallet_transactions` table |
| `StreamScheduleService.scheduledStreams` in-memory | Read/write `scheduled_streams` + `stream_bookings` tables; call `send-stream-scheduled-email` |
| `StreamScheduleService.dispatch*Email` (no-op) | Call `send-stream-scheduled-email` / `send-booking-confirmation-email` |
| `SecretVaultService` plaintext PIN in SharedPreferences | Call `upsert-vault-pin` / `verify-vault-pin` (hashed server-side) |
| `UploadServiceImpl` fake progress | Call `supabaseClient.uploadFile(bucket, ...)` for real Storage uploads |
| `NotificationServiceImpl` `Log.d` only | Call `send-push-notification` for remote notifications |
| `ProfileScreen.onLogout` no-op | Call `supabaseClient.signOut()` |
| `AgoraLiveStreamService.startCommentSync` polls every 3s | Use Supabase Realtime channel on `live_stream_comments` |
| `AgoraCallService` writes `"NOW()"` literal for timestamps | Send ISO 8601 `Instant.now().toString()` (or omit and let DB defaults fire) |
| `AgoraCallService` `receiver_id = contactId` (non-uuid) | Schema now accepts TEXT — works. Optionally look up real peer UUID. |
| No incoming-call signalling | Subscribe to Realtime on `call_sessions` filtered by `receiver_id=eq.<me>` → trigger `IncomingCallNotificationHelper` |

---

## 9. Quick architecture recap

```
Android app (Kotlin/Compose)
   │  OkHttp REST  (anon key + user JWT)
   ▼
Supabase Gateway  ──►  Postgres (RLS-protected tables)
   │                       ▲
   │  JWT-gated            │ service role (bypass RLS)
   ▼                       │
Edge Functions (Deno) ─────┘
   ├── generate-agora-token      → Agora AccessToken2 (primary cert server-side)
   ├── send-email-otp            → Resend SMTP (6-digit, 60s cooldown)
   ├── verify-email-otp          → otp_codes table (hashed)
   ├── send-stream-scheduled-email → Resend
   ├── send-booking-confirmation-email → Resend
   ├── send-push-notification    → Firebase FCM HTTP v1
   ├── create-razorpay-order     → Razorpay Orders API (key secret server-side)
   ├── verify-razorpay-payment   → HMAC-SHA256 verify + wallet credit
   ├── delete-user-account       → Admin API (service role)
   ├── register-push-token       → push_tokens table
   ├── upsert-vault-pin          → vault_pins (salted hash)
   ├── verify-vault-pin          → vault_pins + brute-force lockout
   └── sync-user-profile         → profiles table
```

All secrets server-side. No hardcoded keys in the Android client. ✅
