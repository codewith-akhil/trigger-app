# Trigger App — Complete Product & Backend Documentation

> **Living document** — updated on **every** task. Last updated: 2026-09-07.
> HEAD at last update: message-request + follows + call-history + presence-gating release.
>
> Purpose: one place that explains the whole app — every UI screen, the design
> language, navigation, every backend integration (completed vs pending),
> config, edge functions, API keys, Agora, auth, Resend, maps — so any
> engineer (or agent) can understand what was done and what is next.

---

## 1. Product overview

Trigger is a WhatsApp-style messenger with Instagram-style social onboarding,
built as a **native Android** app:

| Layer | Technology |
|---|---|
| Language | Kotlin, TypeScript (edge functions) |
| UI | Jetpack Compose + Material 3 (WhatsApp-flavoured light theme) |
| Local store | Room (SQLite) — v7 schema, offline cache |
| Backend | Supabase — Postgres + RLS, Auth, Storage, Realtime, Edge Functions |
| Calls & streams | Agora RTC 4.4.1 (voice/video 1:1, live streams) |
| Push | Firebase Cloud Messaging |
| Email | Resend (OTP / transactional) |
| Map | osmdroid (location sharing) |
| Payments | Razorpay (wallet) |

**Package:** `com.trigger.app` (namespace `com.example` — see TODO in
`app/build.gradle.kts`). minSdk 24 · targetSdk 36 · compileSdk 36.1.

---

## 2. Design system

### 2.1 Colour theme (all values from `ui/theme/Color.kt`)

Base greens (brand):
- `GeometricGreenPrimary #00A884` — primary actions, FAB, accents
- `GeometricGreenDark / TriggerHeaderGreen #008069` — headers, top bars
- `TriggerChatTeal #075E54`, `TriggerChatDarkTeal #005D4B` — chat header surfaces
- `TriggerUnreadGreen #25D366`, `TriggerCheckmarkBlue #53BDEB` — badges & read ticks

Surfaces & text:
- Background/surface `#FFFFFF`, `GeometricSurfaceVariant #F8F9FA`,
  `GeometricSurfaceContainer #F0F4F8`
- Text primary `#111B21`, secondary `#5F6368`/`#667781`, muted `#8696A0`
- Chat wallpaper `TriggerChatBg #E5DDD5`, bubbles: incoming `#FFFFFF`,
  outgoing `#E7FFDB`
- Search field `#F0F2F5`, active filter chip `#D8FDD2` (text `#0B614E`)
- Dark accents (cards only, no dark theme yet): `TriggerDarkBg #0B141B`,
  `TriggerDarkCard #111B21`

### 2.2 Theme & typography
- `Theme.kt` — single `lightColorScheme` (primary `#00A884`, secondary `#008069`,
  primaryContainer `#E8F5E9`). **No dark scheme, no dynamic color yet.**
- `Type.kt` — M3 defaults with `bodyLarge` 16sp/24lh.
- Shapes: 16–24dp rounded (search bars 24dp, chips 18–20dp, cards 12–16dp).
- Some screens declare private colour vals duplicating the palette
  (e.g. `NewMessageScreen`, `SelectContactScreen`) — cleanup item (§10).

---

## 3. Navigation map (`ui/navigation/TriggerAppNavHost.kt`)

Start destination: persisted Supabase session → `dashboard`, else `landing`.
Chat args travel via `rememberSaveable` holders (`activeChatConversationId`,
`activeChatPeerId`, …), not route args. Transitions: slide+fade 280 ms.

| Route | Screen | Notes |
|---|---|---|
| `landing` | LandingScreen | + notification permission dialog |
| `language_selection` | LanguageSelectionScreen | |
| `email_auth` | EmailAuthScreen | login; connects Realtime on success |
| `sign_up` | SignUpScreen | |
| `forgot_password` → `email_otp` → `reset_password` | OTP flow | purpose SIGN_UP / FORGOT_PASSWORD |
| `dashboard` | WhatsAppDashboardScreen | tabs CHATS/UPDATES/STREAM/CALLS/PROFILE |
| `new_message` | NewMessageScreen | **FAB entry point** — requests, contacts, following, search |
| `select_contact` | SelectContactScreen | legacy route (self-chat) — kept, not primary |
| `chat` | ChatScreen | canonical conversation uuid + peer uuid |
| `profile`, `delete_account` | Profile / DeleteAccount | |
| `settings` (+ account/privacy/chats/notifications/storage/help) | Settings | |
| `schedule_stream`, `stream_history` | Streams | |
| `wallet`, `secret_vault` | Wallet / Vault | |

---

## 4. UI screens inventory (`ui/screens/`, unless noted)

**Auth & onboarding:** LandingScreen · LanguageSelectionScreen ·
EmailAuthScreen · SignUpScreen · ForgotPasswordScreen ·
EmailOtpVerificationScreen · ResetPasswordScreen

**Core chat:** WhatsAppDashboardScreen (tabs: Chats / Updates / Stream /
Calls / Profile; `CallsTabContent` = real call history, `CallLogItem`) ·
NewMessageScreen (message requests + contacts + following + search) ·
SelectContactScreen · ChatScreen (+ ChatMainTopBar, selection top bar,
network banners) · ChatComponents.kt (bubbles incl. CALL_LOG, view-once,
location, contact) · ChatMediaViewer (ExoPlayer) · ChatMediaPreviewDialog ·
ChatContactInfoSheet · ChatCallingOverlay · ChatEmojiPicker · ChatGifPicker

**Profile & settings:** ProfileScreen · SettingsScreen ·
Account/Privacy/Chats/Notifications/Storage/HelpSettingsScreen ·
DeleteAccountScreen

**Streams:** ScheduleStreamScreen · StreamHistoryScreen ·
LiveStreamPlayerScreen · StreamBookingDialog

**Other:** WalletScreen · SecretVaultScreen · TriggerHomeScreen ·
SendLocationScreen (osmdroid map)

---

## 5. Backend — Supabase project `uazkcainrajcgxecomly`

### 5.1 Database tables (public schema)

`profiles` (username **citext** unique, full_name, avatar_url, phone,
is_online, last_seen_at, two-step PIN, gender/dob/country, email) ·
`conversations` (per-user rows: owner_id + peer_id + peer_name/avatar,
last_message*, unread_count, last_read_at, **request_status**
pending|accepted|blocked|declined, is_contact) · `messages` (type, text,
media_url/bucket/thumbnail, file_name/size/mime, view-once, reply, status,
timestamp_millis, location*, contact*, **call_type + call_duration_sec**,
reactions, starred/pinned/edited/deleted, **seq**, idempotency_key) ·
`message_requests` (sender/receiver, initial_message, status
pending|accepted|blocked|declined, conversation_id) · `contacts` ·
`blocked_contacts` · **`follows`** (follower_id, following_id, unique pair,
no-self check) · `user_presences` (is_online, last_seen_at, typing_until,
recording_until) · `call_sessions` (caller/receiver **uuid**, call_type,
channel_name unique, status, answered_at, ended_at, ended_reason,
**duration_seconds**) · `call_events` · `live_location_shares` ·
`live_streams` (+comments/reactions) · `scheduled_streams`/`stream_bookings` ·
`push_tokens` · `user_settings` · `wallet_transactions`/`bank_details`/
`payout_details` · `vault_pins`/`vault_media` · `support_tickets` ·
`otp_codes` · `chat_backups` · `username_history` · `genders`/`countries`/
`languages` catalogs · `shared_links` view.

### 5.2 Row Level Security — security model

- **messages** — participant of the conversation (owner OR peer) may read;
  sender-only update; participant delete-for-me.
- **conversations** — read: owner OR peer (so a receiver can preview a
  pending request thread); write: owner.
- **user_presences** — read gated: **only yourself, or users with an
  ACCEPTED conversation with you** (`up_select_self_or_accepted_peer`).
  This enforces "online/last seen visible only after the request is
  accepted" at the database layer (REST **and** Realtime).
- **follows** — read: any authenticated user; insert/delete: only own
  follower rows (no self-follow, DB CHECK).
- **call_sessions / call_events** — caller OR receiver only (uuid FKs to
  auth.users).
- **chat_media** bucket — public read (permanent URLs), authenticated write.
- Unique index `conversations(owner_id, peer_id)` prevents duplicate mirror
  rows; unique `(follower_id, following_id)` prevents double-follows.

### 5.3 Edge functions (51 deployed, `supabase/functions/`)

**Chat core:** `send-message` (get-or-create canonical conversation, 3-message
request cap, REQUEST_NOT_ACCEPTED / REQUEST_DECLINED gates, mirror-row sync,
push) · `send-chat-notification` (FCM, skip-if-online) · `edit-message`
(15-min window) · `delete-message` · `forward-message` · `pin-message` ·
`toggle-star-message` · `toggle-reaction` · `search-messages` ·
`sync-messages` / `sync-conversations` (cross-device) · `mark-conversation-read` ·
`mark-view-once-opened` · `upload-chat-media` (raw binary, server-validated
sizes: image 50 MB / video 250 MB / audio+doc 55 MB) · `backup-messages`

**Social & discovery:** `search-users` (username OR full name OR phone,
injection-safe, rate-limited 20/5 min/IP, **no presence fields returned**) ·
`send-message-request` (creates canonical pending conversation + real first
message, 3-message cap) · `respond-message-request` (accept → canonical
conversation unlocked + receiver mirror row + contacts; **decline**; block) ·
`get-message-requests` (with conversation_id + message_count) ·
`get-contacts` · **`toggle-follow-user`** (follow/unfollow + counts) ·
**`get-follow-info`** (isFollowing/followsYou/counts) ·
**`get-follow-list`** (followers/following + profiles) ·
`check-username-availability` · `report-user` · `manage-blocked-contacts`

**Presence & calls:** `update-presence` (heartbeat / typing / recording via
`upsert_presence` RPC) · `generate-agora-token` (AccessToken2, 1 h)

**Profile & auth:** `get-my-profile` · `sync-user-profile` ·
`update-user-settings` · `check-email` · `send-email-otp` · `verify-email-otp`
· `reset-password` · `delete-account-otp` · `delete-user-account`

**Push & email:** `register-push-token` · `send-push-notification` ·
`send-stream-scheduled-email` · `send-booking-confirmation-email` (Resend)

**Streams / wallet / vault:** `cron-auto-start-streams` (pg_cron) ·
`create-razorpay-order` · `verify-razorpay-payment` · `razorpay-webhook` ·
`wallet-withdraw` · `update-bank-details` · `update-payout-details` ·
`update-fx-rates` · `upsert-vault-pin` · `verify-vault-pin` · `reset-vault-pin`

JWT config: the 9 self-validating functions run with `verify_jwt=false`
(they resolve + validate JWTs internally); everything else uses platform
JWT validation.

### 5.4 Realtime
Raw phoenix websocket (`realtime/v1/websocket`), postgres_changes on
`messages` (filtered `conversation_id=eq.<convId>`), `conversations`,
`user_presences`, `live_location_shares`. Auto-reconnect + missed-message
resync. Presence RLS also gates Realtime deliveries.

---

## 6. Config, keys & integrations

| Key | Where used | Notes |
|---|---|---|
| `SUPABASE_URL` / `SUPABASE_ANON_KEY` | `.env` → Secrets Gradle → `BuildConfig` | client key only; all privileged work is in edge functions (service role never ships) |
| `AGORA_APP_ID` | `.env` → Agora engine | |
| `AGORA_PRIMARY_CERTIFICATE` | edge function env only | token signing, never in app |
| `RESEND_API_KEY` | edge function env only | OTP + stream/booking emails |
| `FCM_SERVER_KEY` | edge function env only | push |
| `RAZORPAY_*` | edge function env only | wallet; secrets ignored by plugin |
| Google services | `app/google-services.json` | FCM |
| Upload keystore | env at build time (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) | **never committed**; alias `upload` |

**Auth email — custom SMTP (⚠️ LOCKED — owner-configured, verified working,
DO NOT MODIFY):** Supabase Auth → custom SMTP via Resend.
`smtp.resend.com:587` · username `resend` · sender
`team@beauzead.shop` (name "Trigger App") · min interval 1 s / user.
Passwords are encrypted at rest by Supabase. Auth OTP/reset emails flow
through this path — no code or dashboard change is permitted here without
the owner's explicit say-so. (Note: the `no-reply@triggerapp.com` strings in
`_shared/resend.ts` are only an in-code fallback for edge-function emails,
NOT the auth sender — do not confuse the two.)

osmdroid (static + live location) needs no key. Maps render offline tiles.

**Auth flow:** email + password; signup OTP via `email_otp` (Resend);
session persisted in prefs; transparent refresh-token grant 120 s before
expiry; account-switch wipes per-user state (Room, presence, vault).

**Calls flow:** ChatScreen top bar → `startCall(peerId…)` → `call_sessions`
row → `generate-agora-token` → join channel → incoming via 4 s polling of
`call_sessions` (status calling/ringing) → duration timer while CONNECTED →
at end `duration_seconds` written + caller persists a single `CALL_LOG`
message (both participants see it in-thread; Calls tab reads `call_sessions`).

---

## 7. Feature checklist — message requests & social (this pass)

| Requirement | Status |
|---|---|
| Username search (username + name + phone) on New Message page | ✅ server (`search-users`) + UI |
| Instagram-style follow/unfollow (table, RLS, functions, UI) | ✅ `follows` + toggle-follow-user + get-follow-info/list + Follow buttons |
| Following surfaced on New Message page | ✅ Following section |
| First message to a stranger = message request | ✅ canonical pending conversation + real message row |
| Max 3 messages before acceptance | ✅ server cap (`REQUEST_MESSAGE_LIMIT`) + client budget banner + dialog counter |
| Receiver must accept before replying | ✅ server gate (`REQUEST_NOT_ACCEPTED`) + locked composer + Accept/Decline banner |
| Decline action | ✅ `respond-message-request action=declined`; receiver may re-open by messaging later |
| Online/last seen visible ONLY after acceptance | ✅ DB-level RLS on `user_presences` (REST + Realtime) |
| WhatsApp-style "online / last seen today at 3:45 PM / …" | ✅ `LastSeenFormatter` (today / yesterday / weekday / date + time, device TZ) |
| New Message page reachable | ✅ dashboard FAB (+ Updates tab) opens `new_message` |
| Same thread for both users | ✅ canonical (oldest row) resolution in send-message, respond-accept, client resolve + heal on open |
| RLS hardened for all of the above | ✅ follows policies, presence gating, request-status CHECK, unique pair index |

## 8. Feature checklist — calls & data persistence

| Requirement | Status |
|---|---|
| Audio/video call history with duration stored in DB | ✅ `call_sessions.duration_seconds` + `messages.call_type`/`call_duration_sec` (caller writes one CALL_LOG row) |
| Calls tab shows real history (both directions, missed, duration) | ✅ `CallsTabContent` reads `call_sessions` + profiles |
| All chats & messages persisted to DB | ✅ `send-message` inserts every message (text/media/location/contact/call-log) with idempotency; Room mirrors |
| Media & files persisted | ✅ `chat_media` bucket + `media_url`/`media_bucket`/`media_path` columns; permanent public URLs |
| Call history integrity | ✅ `call_sessions.receiver_id` now uuid + FK; both-side RLS |

## 9. Backend integration status — completed vs pending

**Completed:** auth (email/OTP/reset/delete) · profiles (username, avatar,
two-step PIN) · chat (text, media, docs, voice, view-once, reply, edit,
delete, star, pin, reactions, search, sync, disappearing, live location) ·
presence + typing/recording · message requests + 3-message cap + presence
gating · follows · contacts/blocks · calls (token, ring, accept/decline,
duration, history) · streams (schedule, booking emails, live) · wallet
(Razorpay) · vault · push (FCM) · email (Resend).

**Pending / known gaps (next up):**
1. **Dark theme** — single light scheme today; dark palette constants exist.
2. **Follower list screen** with pagination (function supports limit/offset).
3. **Group chats** — conversations.is_group flag exists, no group flows yet.
4. **Privacy setting "Last seen & online"** — stored but not enforced in the
   presence RLS (only request-acceptance gate is).
5. **Incoming call push** — call ring is polled (4 s); FCM data-message
   would cut latency + battery cost.
6. **Username `citext` search** is ilike-based; consider trigram index for
   scale.
7. **Namespace rename** `com.example` → `com.trigger.app` (TODO in build file).
8. **Per-screen colour dupes** → consolidate to `ui/theme` palette.

## 10. Version history (documentation updates)

- **2026-09-07 (release distribution moved to Supabase Storage):** The sandbox
  gateway kept aborting large downloads (`/api/download` streamed ~151 MB
  through the preview proxy and died mid-transfer every time), so the signed
  release artifacts are now hosted on **Supabase Storage, bucket `releases`**:
  `builds/<commit>/` holds the immutable objects, `latest/manifest.json` is
  the single atomic pointer (versionName/versionCode, commit, sizes,
  per-part + full-file sha256, mirror URLs). Supabase **free plan caps a
  single upload at 50 MB** (verified empirically: simple POST, TUS resumable
  and even existing bucket-level limits are all gated by the global 50 MB
  cap; Management API returns HTTP 402 to raise it on the free plan), so the
  APK (151,078,593 B → 3 parts) and AAB (75,522,785 B → 2 parts) are stored
  as sha256-verified parts and reassembled **in the browser** by the download
  page (per-part checksum verification with retry, progress UI, blob
  assembly; GitHub release `v1.0-test` stays a one-click mirror, and
  `/api/download` now 302-redirects to it instead of streaming through the
  gateway). Future rebuilds: `scripts/upload-release.sh` enforces the
  **delete-old → upload-new** policy (content-addressed build folder, atomic
  manifest switch, HEAD + full reassembly sha256 verification, stale-object
  purge after the manifest-cache window, `--purge-only` resume mode) and runs
  automatically at the end of `trigger-recover.sh` (whose repo `.env` backup
  now lives durably at `secrets/repo-env.txt`). Verified end-to-end:
  reassembled sha256 APK `3aa7c979…` / AAB `dce1e9e5…` byte-identical to the
  signed build; browser download completed and checksum-verified on desktop
  and mobile layouts.
- **2026-09-07 (deep full-stack audit — 68 verified findings fixed):**
  Four-parallel-agent audit (UI / data / edge functions / DB+RLS) with every
  finding hand-verified before fixing. **BLOCKERS:** media uploads ran
  synchronous OkHttp on the MAIN dispatcher (NetworkOnMainThreadException —
  media sending was dead 100% of the time) → streaming IO body + fresh-token
  + non-ASCII filename fix; duplicate LazyColumn key crashed the emoji picker;
  `process_withdrawal` (live-only drift, no caller check) hard-definer-hardened
  + locked to service_role; `wallet_balance`/`stream_history`/
  `streams_due_to_start` views now `security_invoker` (anon could read wallet
  balances); Room schema identity crash for migrated devices (messages.seq
  index declared in entity). **SECURITY:** message-request gate + 3-message
  budget now enforced in RLS `msg_participant_insert` AND atomically via
  `try_send_pending_message` RPC (TOCTOU race closed); send-message auto-create
  no longer opens `accepted` threads (starts `pending` + mirrors
  message_requests); sync-conversations/sync-messages pushes are ownership/
  sender-scoped (conversation hijack + peer-message rewrite closed);
  profiles PII (email/phone/dob/gender/two_step_pin_hash) revoked from direct
  REST reads (column-level grants); Razorpay verify credits the PAYER (was the
  caller) with error-surfacing upsert; refund now inserts the compensating
  debit; timing-safe signature compares everywhere; `listUsers({perPage:1000})`
  lookups replaced with profiles.email; FCM tokens/read-receipt/cron RPCs
  revoked from users; email senders locked to the caller's own address with
  rate limits; incoming-call heads-up notification wired (backgrounded users
  missed calls); cron `trigger-auto-start-streams` fixed (was 401 on every run
  — NULL app.cron_secret; now 200). **DATA/CORRECTNESS:** Room v8
  (`conversations.lastActivityMillis`) — chat list finally sorts by recency;
  realtime re-filters per open chat (returning to an older chat lost live
  messages); per-conversation sync watermarks (chat B's history was never
  fetched); reactions now propagate (DB trigger denormalizes
  message_reactions → messages.reactions + client parses); false "edited" from
  receipt updates fixed; logout via dashboard performs full cleanup; real
  date separators + open-at-newest; media viewer quick reactions/reply bar
  wired to the VM; report dialog has structured categories; Profile Links
  editor reachable; auth double-tap guards; launchSingleTop on chat nav;
  atomic OTP attempt counters; upload MIME whitelist + extension sanitizing +
  per-user rate limit; get-my-profile now hydrates `settings` (4 settings
  screens rendered dead defaults); REAL biometric app lock
  (androidx.biometric, foreground gate); Agora uid collisions (hashCode%1e6)
  replaced with uuid-tail uids; conversation watermark/editedAt/typo-level
  micros (28 total) fixed. Compile verified (compileReleaseKotlin green);
  30 edge functions redeployed; live-DB changes executed + verified (grants,
  policies, views, cron 200s). Known-dead-code cleanup (orphaned HOME/
  SELECT_CONTACT routes, fake CallServiceImpl) documented for a follow-up
  refactor PR — unreachable, harmless.
- **2026-09-07 (UI/UX overhaul & User Profile release):** Complete WhatsApp-style
  chat bubble overhaul with asymmetric shapes, inline single-line timestamps,
  translucent corner pill badges for media, pinch-to-zoom full-screen media
  viewer with ExoPlayer and interactive reply bar, chronological message sorting
  fix (`timestampMillis ASC, seq ASC`), decluttered user search results, and new
  dedicated `UserProfileScreen` with follower metrics and safety block/report actions.
  Full documentation available in [`docs/UI_CHANGES_DOCUMENTATION.md`](UI_CHANGES_DOCUMENTATION.md).
- **2026-09-07 (build recovery):** Sandbox was reset → toolchain + artifacts
  wiped; full rebuild from `dc176de` reproduced identical signed binaries
  (APK 150,451,914 B / AAB 74,610,631 B; upload-key cert SHA-1 `5b:7f:4b:cd:
  c2:09:66:80:4a:58:2f:85:8a:32:1a:2d:78:74:d8:9d`). Artifacts now stored
  durably and mirrored to the GitHub release **`v1.0-test`** (sha256-verified:
  APK `53b366e3…`, AAB `07e19a6e…`) so download options can never go blank
  again. Download page + `/api/download` now read from the persistent
  artifacts dir; recovery automated via `scripts/trigger-recover.sh`
  (fast path = re-download from release, `--rebuild` = full toolchain build).
- **2026-09-07 (this pass):** New Message page end-to-end audit; username/name/
  phone search; follows (Instagram model); message requests with 3-message cap,
  accept/decline, locked receiver composer; presence RLS gating + WhatsApp-style
  last seen; real call history tab; CALL_LOG persistence + duration to DB;
  canonical conversation heal; dashboard FAB → New Message; fake call FAB
  removed; migration `20260920_social_follows_and_requests.sql`; 8 edge
  functions deployed (send-message, send-message-request,
  respond-message-request, get-message-requests, search-users,
  toggle-follow-user, get-follow-info, get-follow-list).
- **Earlier:** 42-finding chat audit remediation (b187c4f); 4 deferred items —
  UUID unification, URL re-sign, ExoPlayer video, real live location (ce3a680);
  10 function redeploys + `20260918_chat_fixes.sql`.
