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

### 5.3 Edge functions (59 deployed, `supabase/functions/`)

**Chat core:** `send-message` (get-or-create canonical conversation, 3-message
request cap, REQUEST_NOT_ACCEPTED / REQUEST_DECLINED gates, mirror-row sync,
push) · `send-chat-notification` (FCM, skip-if-online) · `edit-message`
(15-min window) · `delete-message` (delete-for-everyone also purges the chat_media /
voice_notes storage objects, not just the tombstone) · `forward-message`
(request-gate + 3-message budget enforced race-free via the
`try_forward_budget` advisory-lock RPC) · `pin-message` ·
`toggle-star-message` · `toggle-reaction` · `search-messages` ·
`sync-messages` / `sync-conversations` (cross-device; sync-messages actions:
`push` (sender-only upsert) · `pull` — sinceTs>0 forward catch-up, sinceTs=0
**newest 50** initial page (Task 25 fresh-install fix) · `history` — backward
composite-cursor (ts, seq, id) page of ≤200 for scroll-to-top pagination) · `mark-conversation-read` ·
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
`upsert_presence` RPC) · `get-peer-presence` (online / last-seen behind the
4-level privacy gate) · `generate-agora-token` (AccessToken2, 1 h,
participant-authorized)

**Profile & auth:** `get-my-profile` · `get-peer-profile` (server-gated
photo/about visibility vs follows) · `sync-user-profile` ·
`update-user-settings` · `check-email` · `send-email-otp` · `verify-email-otp`
· `reset-password` · `delete-account-otp` · `delete-user-account`

**Push & email:** `register-push-token` · `send-push-notification` ·
`send-stream-scheduled-email` · `send-booking-confirmation-email` (Resend)

**Cron & housekeeping:** `cleanup-disappearing-messages` (every-15-min pg_cron
http call, x-cron-secret gated — deletes expired auto-delete messages AND
purges their chat_media/voice_notes storage objects, plus expired
live_location_shares) · `cron-auto-start-streams` (pg_cron)

**Streams / wallet / vault:** `create-razorpay-order` ·
`verify-razorpay-payment` (payer-only stream_booking verification) ·
`razorpay-webhook` ·
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

- **2026-09-11 (versionCode 9 — instant chat-open + real profile photos,
  10 files, commit `874ffa1`):** Two user-reported bugs from the v8 build.
  (1) **Blank chat for 2–4s on open** — root cause: `MessageWindowController`
  init refused to anchor the window (`_initialized=false` → empty render)
  while the empty-cache newest-page pull ran a full `sync-messages` round
  trip (2–4s on mobile data) — every conversation's FIRST open after an
  install/update hit this (Room only caches what this device has already
  opened). Fix: the window now anchors instantly from the local read even
  when empty (Room flow live from frame one), raises `isInitialSyncing`
  during the pull, and ChatScreen renders a visible "Loading messages…"
  spinner state instead of a dead blank; scroll-to-top no longer races the
  init pull. Warm-cache opens were already instant and remain unchanged.
  (2) **Dashboard showed letter avatars although photos exist** — three
  stacked causes: `ChatListItem` never rendered `peerAvatarUrl` at all
  (only the legacy `avatarRes` drawable); the sync pull only profile-
  resolved avatars for MIRRORED conversation rows (canonical rows — the
  ones the initiator's device keeps — never got a lookup, and the server's
  `peer_avatar_url` column is circularly null because it is only populated
  by pushing back a value that came from this same pull); and the chat
  header's working avatar (fetched live from `profiles`) was never written
  back to Room. Fix: `ChatItem`/`DomainConversation` carry `avatarUrl`,
  the chat list renders AsyncImage(url) → drawable → initial, the pull
  profile-resolves avatar/name for ALL rows, and ChatViewModel persists
  the header avatar into `conversations.peerAvatarUrl` so the list updates
  without waiting for the next sync. Build provenance: same upload key
  (SHA-1 5b7f4bcd…), aapt2 versionCode='9' versionName='1.0', APK
  151,638,141 B sha256 b480f573…, AAB 76,363,628 B sha256 0e880018…,
  source commit 874ffa1.

- **2026-09-10 (versionCode 8 — dashboard Requests tab + notifications fixes,
  4 files):** (1) **Dashboard "Requests" tab** — new filter chip next to
  All/Unread/Groups; shows ALL pending message requests (incoming AND
  outgoing — the canonical sender-owned row already synced to both sides via
  `sync-conversations` pull; no backend change needed). Tapping a request
  opens the chat, which renders Accept/Decline (receiver) or the waiting /
  declined state (requester). Pending requests never mix into the other
  tabs; the tab has a dedicated empty state and never falls back to the
  legacy seed chats; the bottom-bar Chats badge now uses a tab-independent
  unread count so it stays truthful on the Requests tab. (2) **Notification
  rows now navigate to the actor's profile** — the NavHost wiring
  (`USER_PROFILE`) already existed; the row simply never had a click handler.
  The "Message" button on request-accepted rows still opens the chat
  directly. (3) **Bell badge reset actually works** — root cause: the
  screen marked `user_notifications` read via `upsertRecord`, which is an
  INSERT under the hood, and that table deliberately has NO insert RLS
  policy (only service-role edge functions insert), so every mark-read
  silently failed and the badge re-derived as 1 forever. New
  `SupabaseClient.patchRecord` primitive (REST PATCH = UPDATE, the exact
  permission the table grants) + one atomic
  `PATCH user_notifications?user_id=eq.me&is_read=eq.false {"is_read":true}`.
  (4) **Follow back / Following buttons removed** from notification rows per
  product decision — row tap opens the profile instead. Build provenance:
  signed with the same upload key (SHA-1 5b7f4bcd…), aapt2
  versionCode='8' versionName='1.0', APK 151,638,141 B sha256 35d50d13…,
  AAB 76,363,429 B sha256 db7ef60b…, source commit ebd2c17. Also hardened:
  gradle.properties now persists the sandbox-safe build memory settings
  (-Xmx1536m / workers.max=2 / kotlin.daemon 768m) that previously existed
  only as session flags.

- **2026-09-10 (versionCode 7 — PERFECT view-once + screenshot/screen-record
  prevention, 7 files):** Closed every leak vector of the view-once feature
  the audit remediation had introduced. **Storage contract unchanged: media
  stays in Supabase Storage and the DB forever** — view-once is a pure
  policy flag (`is_view_once` / `is_viewed`), nothing is ever deleted.
  **Capture prevention, 3 layers** (ChatScreen viewer host): `FLAG_SECURE`
  while a view-once viewer is on screen (system screenshots refused outright;
  MediaProjection recorders, HDMI/virtual displays, assistant overlays render
  black) + `setRecentsScreenshotEnabled(false)` (API 33+ — the Overview task
  thumbnail can never contain the media) + `Activity.ScreenCaptureCallback`
  (API 34+ — force-closes the viewer with a toast if a capture still slips
  through an OEM path). The media is composition-gated behind
  `secureApplied`: the first frame that can possibly show it is already
  FLAG_SECURE (kills the one-frame leak of the old post-composition
  flag-add). **Once-only enforcement:** the viewer is a locked surface for
  view-once — star/share/delete/reactions/reply all hidden (share previously
  exported the raw bytes through the FileProvider!); the sender can never
  open their own view-once media (bubble-click + VM guards; the server
  rejects the receipt for senders); forwarding is blocked at BOTH the
  ViewModel selection filter and the `MessageServiceImpl.forwardMessage`
  service backstop; view-once is excluded from the starred grid (DAO);
  Coil memory+disk caches are bypassed for view-once loads (image AND video
  poster) so decrypted media never outlives the session on disk; an
  offline-open receipt self-heals on the next sync-messages pull so the
  sender still sees "Opened"; the bubble pill says "Video • View once" for
  videos. Edge functions (committed, deploy pending a fresh
  SUPABASE_ACCESS_TOKEN): view-once-aware FCM preview ("📷 View-once photo")
  and chat-list `last_message`. Residual physics (unchanged, cannot be
  prevented by ANY app): photographing the screen with another device and
  root-level frame capture — same exposure as WhatsApp.

- **2026-09-10 (versionCode 6 — FINAL chat fix: WhatsApp-equivalent cache
  population, 4 files):** Killed the "chat opens blank, messages pop in 2-3s
  later / nothing offline" defect class permanently. Root cause: the chat
  rendered Room directly (correct) but NOTHING guaranteed Room was populated
  — catch-up raced the conversation pull and found it empty on fresh
  installs, the realtime pull-signal was suppressed on the process's FIRST
  socket connect, and an empty window dead-ended scroll-to-top. **Fixes:**
  (1) `MessageWindowController.init` — empty local cache now deterministically
  triggers a one-shot newest-page backfill (`DataSource.initialPageFromServer`
  → sync-messages initial branch, sinceTs=0) before the anchor completes;
  `_initialized` gates scroll until it resolves. (2) `loadOlderMessages` —
  empty-window dead end (`oldestLoaded == null → return`) replaced with the
  same retryable backfill; empty results never poison `hasMoreOlder`.
  (3) `SupabaseClient.onOpen` — pull signal now emits on EVERY connect
  including the first (watermark 0 = initial newest-page pull). (4)
  `DashboardViewModel` — message catch-up now runs strictly AFTER the
  sync-conversations pull lands (chained in its `finally`) instead of racing
  it at t=0; the connectivity-regain collector `.drop(1)`s the synthetic
  initial StateFlow emission (same race + duplicate rounds toward the
  30-req/min sync limit); outbox flush moved ahead of the pull. Net behavior:
  warm chats open instantly from Room; never-cached chats fill on open with
  one bounded pull; already-cached chats are pre-populated in the background
  before first tap; offline renders cache as before. `DataSource` gained
  `initialPageFromServer` with a default no-op (existing JVM fakes unaffected).

- **2026-09-10 (versionCode 5 — release rebuild: payments + deep links shipped,
  commit `fbba725` + `1011e28`):** First published binaries containing the
  end-to-end Razorpay checkout and the App Links deep-link work — every
  earlier artifact predated them. **App:** `RazorpayPaymentService`
  (create-razorpay-order / verify-razorpay-payment via edge functions; key
  secret stays server-side) + `ui/payment/RazorpayCheckout.kt` invisible
  bridge activity (official Checkout sheet, `PaymentResultWithDataListener`)
  + R8 keep rules + translucent theme; StreamBookingDialog PAID flow
  ("Pay ₹X & Reserve" → order → sheet → server verify → booking side
  effects) and WalletScreen "Add Money" top-up (INR presets, min ₹1) both
  wired through the same order→checkout→verify→refresh pipeline. Deep links:
  `https://(www.)?triggerappltd.cyou/stream/{id}` autoVerify App Links,
  canonical share host www. **Release:** rebuilt from source after a full
  sandbox reset (JDK21 + Gradle 9.3.1 + SDK 36.1 restored);
  `:app:compileReleaseKotlin` green, `assembleRelease bundleRelease` signed
  with the upload key (SHA-256 `ebfe33de…`, cert CN=Trigger App). versionCode
  bumped 4→5 because versionCode 4 (44ffdc3, WhatsApp-parity) had already
  been distributed via the GitHub mirror — same-versionCode rebuilds would
  not upgrade on those installs. **Publishing:** durable artifacts +
  local 16 MiB-part manifest regenerated (APK 151,638,137 B sha256
  `75f887ef…` · 10 parts; AAB 76,360,888 B sha256 `9d0fe2d2…` · 5 parts);
  GitHub release v1.0-test assets swapped to the same build (one-click
  mirror); download page serves the new build via /api/download-part.
  Razorpay still in TEST mode (`rzp_test_…`) — test cards / `success@razorpay`
  UPI only until live keys are swapped in the edge-function secrets.

- **2026-09-09 (post-Task-26 security audit hardening wave — migration
  `20260928_final_hardening.sql` + `cleanup-disappearing-messages`; NO
  AAB/APK rebuild):** Follow-up audit fixes (F-numbered in the migration
  header). **Backend:** `otp_codes` purpose extended with `account_delete`
  (F1 — delete-account-otp inserts were failing 23514); BEFORE-UPDATE trigger
  locks `request_status`/`peer_id`/`disappearing_*`/`owner_id` to the service
  role (F2 — owners could self-accept pending requests via direct PostgREST
  and skip the 3-message budget); messages/conversations INSERT refuse
  blocked pairs at RLS level and `try_send_pending_message` re-created with
  the same guard (F3); `is_online`/`last_seen_at` removed from the
  authenticated column grant — presence only via `get-peer-presence` (F4);
  the auto-delete plpgsql sweep was replaced by the cron-driven
  `cleanup-disappearing-messages` edge function which ALSO purges the
  storage objects and expired `live_location_shares` (F5 — the old cron
  deleted DB rows and left playable media in the buckets forever);
  `process_withdrawal` explicit service_role EXECUTE (F7); storage policies
  for documents/vault_media/backups/stream_thumbnails accept both owner
  columns + folder-scoped chat_media DELETE policy (F8); `follows` SELECT
  scoped to your own social edges (F9 — graph dump closed);
  `live_stream_comments` author-only DELETE + no spoofed usernames on INSERT
  (F10); `try_forward_budget` advisory-lock RPC makes the forward
  request-gate/budget race-free (F11). `delete-message` (delete-for-everyone)
  now purges the actual media objects; `delete-account-otp` enforces a
  server-side attempt cap; `verify-razorpay-payment` refuses non-payer
  stream_booking verifications. **App (call hardening):** callee-side
  RECORD_AUDIO/CAMERA permission gate on accept (fresh callees previously
  joined with a dead mic / SecurityException on Android 14+); speaker parity
  (voice=earpiece, video=loudspeaker — audio calls used to play on the
  loudspeaker while the UI claimed earpiece); CONNECTING-timeout ends an
  abandoned join honestly as MISSED (was sitting forever); ring re-hydration
  only re-rings for `calling`/`ringing` rows (no re-ring after cancel);
  `OngoingCallService` FGS start/stop is media-state-gated and
  exception-guarded (ForegroundServiceStartNotAllowedException crash on
  Android 12+); tapping the ongoing-call notification returns to the call;
  busy-second-call persists MISSED via an IO coroutine (suspend call was
  off-context — compile fix) and never replaces the live session.
  **Verified:** `:app:compileDebugKotlin` green, 13/13 JVM unit tests,
  72/72 live E2E (`final_e2e.py` incl. new cron-gate + auto-delete checks),
  plus a dedicated live probe proving delete-for-everyone purges the storage
  object (upload → delete → object gone, row tombstoned). 59 edge functions
  deployed; live DB restored to baseline after all E2E/probe runs.

- **2026-09-09 (FINAL E2E fix wave — real two-device calling, privacy
  enforcement, hardening; NO AAB/APK rebuild):** End-to-end audit against the
  full requirement list; 39 changed files, 2 migrations, 16 edge functions
  deployed. Highlights:
  - **Calls (Agora):** new `send-call-invite` edge function wakes the receiver
    with a **data-only FCM push** (works when the app is backgrounded/killed);
    the heads-up notification's Accept/Decline actions are now LIVE —
    `MainActivity` (singleTop + showWhenLocked/turnScreenOn +
    USE_FULL_SCREEN_INTENT) routes `ACTION_ACCEPT_CALL`/`ACTION_DECLINE_CALL`
    to `AgoraCallService.handleNotificationAccept/Decline`, which re-hydrate
    the session from `call_sessions` after process death. The call overlay
    moved to the NAV ROOT (answerable from any screen). The caller now sees
    **Call declined** immediately (outgoing status poll) instead of ringing
    45 s into a false MISSED; callee writes `ringing`, both sides write
    `started_at` (Calls-tab ordering fixed); CONNECTED remains strictly
    Agora-driven; `RECONNECTING` state + remote-drop grace window added;
    `OngoingCallService` (microphone|camera FGS) keeps backgrounded calls
    alive on Android 12+/14+. `generate-agora-token` now **authorizes**: call
    tokens require `callId` participant match (channel derived from the row);
    stream tokens require a real `live_streams` channel and viewers can never
    mint PUBLISHER tokens. Dead/simulated call code removed (timer-driven
    `CallServiceImpl`, fake channel display, fake "End-to-end encrypted"
    badge, no-op "Create call link").
  - **Privacy:** new `get-peer-profile` function enforces the peer's
    `profile_photo_visibility`/`about_visibility` server-side (UserProfileScreen
    no longer reads `profiles` directly — the old path leaked avatar/About);
    presence privacy verified server-side (hidden = blank).
  - **Pending message-request sends** now carry the FULL payload (media,
    reply, idempotency) through the atomic `try_send_pending_message` RPC
    (was: hardcoded TEXT, media dropped, retries could duplicate).
  - **Vault:** PIN hashes upgraded from single-iteration salted SHA-256 to
    **PBKDF2-SHA256 (120k iterations)** with transparent legacy upgrade on
    successful verify; upsert/reset/verify rate-limited; live-verified
    3-strikes/24h lockout + hashed OTP reset mechanics.
  - **GIF picker** is now a real GIPHY integration (trending + search);
    GIFs download to cache and send through the media pipeline as animated
    `image/gif` (compression skipped). The button hides when no API key is
    configured — no fake grids.
  - **Client UX:** reactions switch instead of stacking (one per user,
    client + DB); DELETE/CANCEL + UPDATE/CANCEL dialog labels and the
    auto-delete copy match the spec exactly; chat list "yesterday" lowercase;
    three-dot menu: View contact (→ profile) / Auto delete / Contact info;
    Account settings toggles hydrate from the real `settings` payload;
    fake "Media visibility" gallery toggle removed; dead code removed
    (AppLockScreen, TriggerHomeScreen+HOME route, links plumbing).
  - **Ops:** committed cron secret rotated into a deny-all `app_secrets`
    table (value lives only on the server; fx-rates cron jobs fixed — they
    were sending a NULL secret and 401ing forever); `upload-chat-media`
    returns short-lived signed URLs; `sync-messages` reports accurate push
    counts; rate limits added to respond-message-request / toggle-reaction /
    delete-message / search-messages / get-message-requests / sync-user-profile
    / vault writes; blocked-status check by auth UUID; avatars delete policy
    restored to dual-column; redundant messages UPDATE policy dropped.
  - **Verification:** `:app:compileDebugKotlin` + KSP/Room green;
    13/13 JVM unit tests; new 71-check live E2E (messaging matrix incl.
    pending-budget + full-payload + block/unblock, reactions switch/remove,
    sender-only delete-for-everyone, private-bucket participant isolation for
    chat_media/voice_notes/vault_media with User C, vault lockout + OTP,
    call signaling authorization + token authorization, follow/accept
    notifications, privacy=nobody enforcement, 1000-message pagination with
    zero dupes/gaps, auto-delete mechanics, cron housekeeping) — 71/71 PASS;
    Task-25 pagination suite re-run 16/16 PASS; FCM probe: OAuth mint + FCM
    HTTP v1 reached (fake token correctly rejected + deactivated).

- **2026-09-09 (Task 25 — chat message pagination: WhatsApp-style bounded
  window; NO AAB/APK rebuild per user instruction — artifacts remain
  versionCode 4):** Initial chat open now renders **only the latest 50
  messages** from Room (local-first: a cached chat opens instantly, never
  waiting on Supabase); scrolling near the top loads the previous 50 —
  **Room cache first, server only when the cache is exhausted** — until no
  older messages remain. Verified on the live deployment with a synthetic
  **1000-message thread** (16/16 live checks) + 10 JVM unit tests. Client
  changes:
  - `MessageWindowController` (new, pure Kotlin, JVM-tested): bounded window
    state machine with inclusive `top`/`bottom` cursors on the canonical
    total order **(timestampMillis, seq, id)** — Room observe/query + server
    history + reply-jump all share one cursor semantics, so pages can never
    skip or duplicate rows that share a millisecond or a seq. In-flight +
    exhausted guards (`isLoadingOlder`/`hasMoreOlder`); server short-read =
    history-end detection (no Content-Range needed). Post-jump the bottom
    stays pinned ~1 page past the target (`loadNewerMessages` grows it
    toward the live edge; any send calls `releaseBottom`).
  - `MessageCursor` (new, `model/`): composite comparable cursor.
  - Room: `getLatestMessages`, `getMessagesBeforeCursor`,
    `getMessagesAfterCursor`, `observeMessageWindow` DAO queries;
    migration **10→11** adds composite index
    `index_messages_conv_ts_seq(conversationId, timestampMillis, seq)`
    (entity annotation + migration kept name-identical for Room's schema
    validation).
  - `ChatRepositoryImpl`: cursor/window query wrappers (withSignedMedia
    applied on all of them).
  - `MessageService(+Impl)`: `fetchHistoryPage` (sync-messages action=history;
    rows REPLACE-upserted into Room → re-fetches are impossible),
    `fetchMessageById` (participant-RLS single-row read for reply deep-jump),
    and `ensureRealtimeSubscription` split out of `observeMessages` (the
    windowed chat no longer collects the full Room flow).
  - `ChatViewModel`: full-list `_liveMessages`/`_extraMessages` merge and the
    old cursorless `loadMoreMessages` removed; window controller wired in;
    `pendingJumpTo` + `jumpToMessage`; every send entry point re-attaches the
    live edge.
  - `ChatScreen`: load-older trigger near the top **captures the first
    visible message id + pixel offset and re-anchors via
    `LazyListState.requestScrollToItem` before the prepended page commits**
    (scroll position preserved, no jump); stable `pagination_top` slot
    (spinner → "Beginning of conversation" caption); reply-quote tap on an
    off-window message deep-fetches its page then scrolls (bounded 5 s);
    auto-scroll on new messages is now gated to when the user is already at
    the bottom (WhatsApp behavior — reading history is never interrupted);
    all scroll math unified on `topItemCount`.
  - **Server:** `sync-messages` redeployed with (a) action=**history**
    (backward composite-cursor page, participant RLS), (b) pull with
    `sinceTs=0` returns the **newest 50** instead of the oldest 500 — the
    old behavior permanently hid the newest messages of >500-message threads
    on fresh installs, (c) bug fix: the push path's existence pre-check
    referenced an undefined `supabase` client (ReferenceError broke every
    push); now uses the admin client read-only, RLS stays the final gate.
  - **Verified:** `:app:compileDebugKotlin` green; 10/10 pagination JVM
    tests (initial 50 of 1000, incremental growth, exhaustion, dedupe,
    cold-cache server fallback, jump bounding, live-edge re-attach,
    ordering); live E2E `scripts/task25_pagination_e2e.py` **16/16 PASS** on
    a real 1000-message conversation (newest-50 initial pull; 19×50 history
    pages + exhaustion probe; 50 same-millisecond tie groups fully
    retrieved; non-participant sees zero rows; by-id fetch participant-gated;
    forward catch-up unchanged; full cleanup, DB back to 44 messages).
    Not verifiable in this sandbox: on-device UI scroll feel (emulator-less);
    the re-anchor mechanism is the standard Compose `requestScrollToItem`
    pattern and covered by review.

- **2026-09-09 (Task 24 — 5 priority fixes: private media, canonical
  conversation hardening, DB security audit, local-first messaging;
  NO AAB/APK rebuild per user instruction — artifacts remain versionCode 4):**
  2 recon agents + main-agent implementation, verified with a 47-check live
  E2E suite (2 real user accounts + outsider account) and direct RLS probes.
  Migration `20260924_private_media_and_hardening.sql` applied live; 4 edge
  functions redeployed. Full work log in `worklog.md` Task 24.
  - **Private media (§1–2 of the user brief):** `chat_media` + `voice_notes`
    flipped back to **private** (undoing 20260918/20260922); the
    any-authenticated read policies are gone; new participant-only SELECT
    policies authorize an object when the viewer owns it or shares a
    pending/accepted conversation with the uploader's folder uid.
    `messages.media_url`/`media_thumbnail` migrated from permanent
    `/object/public/` URLs to **bare object paths** (`{uid}/{uuid}.ext`) +
    `media_bucket`; all 4 legacy rows rewritten (0 URLs left). The client now
    mints **1-hour signed URLs** on demand (MediaUrlResolver: memo cache with
    5-min safety margin, stale entries retained as offline fallback), syncs
    `media_bucket`/object path into Room (`mapSupabaseToDomain`), and re-signs
    with force-refresh on player retry (voice + video). Uploads persist bare
    paths (no public URL is ever stored or exposed). REAL BUG FIXED:
    signed-URL fetch must be `{base}/storage/v1{signedURL}` — the previous
    `{base}{signedURL}` form 404'd for every private-bucket render including
    the vault.
  - **Canonical/mirror conversation model (§3):** verified end-to-end — one
    canonical (oldest) row per pair holds the thread, mirror rows are chat-list
    metadata; accept creates/updates the receiver mirror; client heals mirror
    ids onto the canonical id. Fixes: new `get_or_create_conversation` RPC
    (advisory lock on the UNORDERED pair) closes the simultaneous-first-send
    duplicate race in send-message + send-message-request; the client
    resolve-or-create fallback no longer inserts `request_status='accepted'`
    (request bypass) — pending unless self-chat; mirror unread is reset on
    accept-update and the dashboard merges unread from the canonical row only
    (phantom-badge resurrection fixed); `pending` threads are hidden from the
    chat list (requests surface via Message Requests); in-chat block now
    writes `blocked_contacts` server-side and send-message/send-message-request
    enforce it in both directions.
  - **DB security audit (§4):** `try_send_pending_message` now verifies the
    sender is a participant (defense in depth; still atomic via
    pg_advisory_xact_lock + FOR UPDATE, still service-role only);
    `message_reactions` INSERT/DELETE RLS tightened to conversation
    participants (was: any authenticated user could react to any message id);
    `messages` DELETE tightened to sender-only (was: conversation owner could
    hard-delete the peer's rows); vault media re-confirmed owner-only
    (table `vm_owner_all` + storage policies). BONUS ROOT-CAUSE FIX: the
    `sync_message_reactions` trigger nulled the NOT NULL `messages.reactions`
    column when a message's LAST reaction was removed (jsonb_object_agg over
    an empty set → NULL → 23502), silently breaking every same-emoji-removes
    toggle — now coalesced to `'{}'` and verified live (add → remove →
    re-add → switch → remove all pass, jsonb syncs).
  - **Local-first (§5):** `ConnectivityObserver` (process-wide StateFlow) +
    `MessageService.retryPendingOutbox()` — stuck SENDING/FAILED outgoing
    messages (older than 2 min) retry automatically when connectivity returns
    and on dashboard start; idempotency keys keep retries duplicate-free
    (verified: same key twice → one row); retryFailedMessage now distinguishes
    local files from already-uploaded bare paths (no bogus re-upload);
    Coil memory/disk cache keys are the stable bucket-qualified path so
    previously-viewed media load from cache across sessions and offline;
    voice notes download into an app-private cache on first play (offline
    replay, no re-stream); share intent materializes private media through the
    existing FileProvider instead of sharing a useless URL string. Confirmed:
    received media is never auto-saved to the gallery (0 MediaStore writes);
    chats open from Room without blocking on Supabase.
  - **Verification:** `compileReleaseKotlin` green on the full tree; migration
    + 4 functions (send-message, send-message-request, respond-message-request,
    forward-message — the last now server-side COPIES forwarded media into the
    forwarder's folder so the new recipient passes participant RLS) deployed
    and probed; E2E suite 47/47 (request→accept→both-send→reactions→image/
    voice→signed-URL access→outsider denial→block/unblock→idempotency→pair
    dedupe); test fixtures deleted.

- **2026-09-09 (code-only wave 2 — media/voice/upload UX, location map,
  dashboard + notifications, followers, privacy overhaul, vault hardening;
  NO AAB/APK rebuild per user instruction — artifacts remain versionCode 4):**
  3 recon agents + 4 implementation clusters, all hand-verified;
  `compileReleaseKotlin` green; 3 live migrations + 11 edge functions deployed
  (all 201 ACTIVE, verify_jwt 401-smoke passed).
  **Media speed + upload UX:** uploads no longer relay through the
  `upload-chat-media` edge function (which buffered whole files in Deno RAM —
  every byte crossed the network twice); the client now PUTs directly to
  Storage (`{uid}/{uuid}.ext`, folder-scoped RLS policies), streams via a
  CountingSink with real per-bubble progress (percent + MB/s + ETA, 120 ms
  throttle), 300 s write/read timeouts, images downscaled to 1600 px JPEG q82
  (`util/MediaCompressor`, GIF/small-file bypass, HEIC→JPEG normalization),
  videos get a real poster frame (max 720 px) uploaded alongside so BOTH
  parties' bubbles render a frame (previously Coil failed decoding the video
  URL → blank bubbles), cancel now flips the message to FAILED with a
  tap-to-retry affordance (was stuck SENDING forever). WhatsApp-style
  in-bubble overlays: X + green progress ring on images, "14% (17s left)"
  strip on videos, linear row on audio/docs.
  **Own video + voice end-to-end:** the media viewer accepted only `http(s)`
  URLs — the sender's own fresh upload (`content://`) never played; now any
  URI plays via ExoPlayer with inline error + retry. Voice notes were dead for
  BOTH parties (`voice_notes` was a PRIVATE bucket while messages stored the
  public URL → 403): bucket flipped public (migration `20260922_media_voice.sql`,
  same model as chat_media) so stored URLs just work, sender can play their own
  recording while it uploads, real MediaPlayer playback with error surfaces,
  waveform tap-to-seek, `RECORD_AUDIO` runtime flow verified (already granted
  in manifest + requested in-chat). No gallery auto-save anywhere (verified by
  grep — the requirement already holds; media streams from app-private cache).
  **Attach sheet:** third row "Vault" — compact PIN gate (6-digit keypad,
  auto-verifies at 6 digits, wrong-PIN inline error) then a 3-column vault
  media grid; PIN is verified against the SERVER every single time.
  **Location screen:** tiles 404'd because the CARTO base URLs lacked the
  `dark_all/` style path (curl-proven) → real street tiles now render;
  marker reconciliation debounced 400 ms (was a repaint storm per GPS fix);
  chat top bar is no longer composed underneath the overlay (the "blinking
  broken header"). Zero new map dependencies, still osmdroid + free CARTO
  tiles (attribution kept).
  **Dashboard:** camera icon + its mock preview dialog removed; real
  notification bell with unread badge (`user_notifications` count) in its
  place; "Restart Onboarding Flow" menu item removed (incl. the dead
  TriggerHomeScreen duplicate) while logout keeps the full cleanup chain;
  chat-list timestamps now "13:23" / "Yesterday" / "dd/MM/yyyy" via new
  `util/ChatTimeFormatter` (24-h, locale-pinned digits), rendered from the
  epoch stamp with ISO fallback.
  **Chat-list dedupe/completeness (Room v10):** `conversations.requestStatus`
  + `peerAvatarUrl` columns; the sync pull now groups rows by peer pair
  (mirror rows from accept-message-request created one row per user with
  different UUIDs — same person twice), keeps the canonical oldest row,
  merges max(unread)/max(activity), deletes losers + legacy non-UUID rows,
  skips declined/blocked, and resolves the real peer name/avatar for mirrored
  rows (server `peer_name` is owner-perspective there). Incoming realtime
  INSERTs now also ensure the conversation row + refresh the preview so new
  chats appear instantly instead of waiting for the next pull.
  **Notifications (real, DB-backed):** new `user_notifications` table (type
  `follow` | `message_request_accepted`, owner-only RLS select/update/delete,
  no client INSERT, in the realtime publication); `toggle-follow-user` writes
  a row + FCM push on NEW follows; `respond-message-request` writes one to the
  requester on accept; new `NotificationsScreen` (Instagram-referenced rows:
  avatar, "**user** started following you.", relative time, Follow back /
  Message action buttons, mark-read on open clearing the badge) reachable
  from the bell. `get-message-requests` counts bug verified already fixed.
  **Followers:** NewMessage page now lists FOLLOWERS (paged `get-follow-list`,
  cap 300) instead of contacts, with "Followers N · Following M" caption from
  `get-follow-info` and green "Follow back" buttons; message-request gating
  and search untouched.
  **Privacy page:** "Last seen and online" → "Activity"; Activity, Profile
  photo and About all offer Everyone / Followers only / Following / Nobody
  (CHECK constraints widened live; legacy `contacts` hydrates as
  "Followers only"); Read receipts, Default message timer and
  Fingerprint/App-lock rows REMOVED (incl. the dead MainActivity lock gate);
  dialogs compacted (no Done button, 340 dp cap); "Blocked Users" opens a full
  page (search box, avatar+name rows, Unblock with confirmation dialog) backed
  by `manage-blocked-contacts` with enriched display names.
  **Presence/photo/about enforcement (server-side):** `get-peer-presence`
  honors `followers`/`following` levels (viewer↔peer `follows` lookups;
  `contacts` ≡ `followers`; `nobody` blank as before); `search-users`,
  `get-contacts`, `get-follow-list`, `get-message-requests` blank out
  `avatar_url`/`about` when the target's visibility level forbids the viewer
  (batched user_settings + follows lookups; absent settings = everyone).
  **Secret Vault (server-authoritative):** PIN state now lives on the server
  (`hasServerPin` probe drives create-vs-unlock, fixing the reinstall loop
  where a fresh install tried to "create" over an existing PIN); `verifyPin`
  is server-verified with the local-hash fallback removed (offline → explicit
  "can't verify", never a bypass); lockout is 3 WRONG PINs PER 24 h (new
  `vault_pins.first_fail_at`, migration `20260922_vault_lockout.sql` +
  atomic bump RPC hardening) with attempts-left shown inline and a locked
  view offering **email OTP unlock** (6-digit code to the registered email via
  the existing Resend integration + new PIN set, `reset-vault-pin` now also
  zeroes `first_fail_at`); vault media is CLOUD-backed — upload to the private
  owner-only `vault_media` bucket + row insert with a WhatsApp-style
  "Uploading…" overlay, signed-URL display for cloud-only items (local cache
  kept for offline), long-press/preview delete with confirm removes
  file+row+cache; owner-only enforced by RLS (`vm_owner_all` verified live).
  **Profile:** the Links feature (row + editor + validator) removed; avatar
  sheet gained a red "Remove profile picture" row (routes into the existing
  delete-photo confirm → storage delete + server sync).
  **External services (all already in use, nothing new to buy):** CARTO
  basemap tiles (free, attribution kept), Agora (calls; APP_ID+CERT confirmed
  present server-side), Firebase FCM (free; follow/request pushes),
  Resend (vault OTP + transactional email; RESEND_API_KEY confirmed present),
  Supabase Storage/Postgres/Edge Functions (current free plan). Costliest-free
  option achieved for media: direct-to-Storage upload + client compression —
  no CDN/transform service required.
  **Security verification:** live checks — voice_notes/chat_media public-read
  + owner-folder write policies; user_settings CHECKs widened; user_notifications
  RLS (no INSERT grant to clients; service-role only); realtime publication
  includes user_notifications; vault_pins.first_fail_at added;
  all 11 redeployed functions 401 without JWT; vault_media `vm_owner_all`
  owner-only policy intact; SMTP creds untouched.

- **2026-09-08 (versionCode 4 — WhatsApp-parity pass: receipts, auto delete v2,
  privacy presence, dialogs, profile viewer, calls):** User-reported fixes,
  all verified against source (3 recon agents + hand-verification; compile
  green; released as versionCode 4, commit `44ffdc3`).
  **Avatars/profile:** other users' photos rendered blank because Android
  `optString` returns the literal `"null"` for explicit JSON nulls → new
  `util/JsonUtils.optStringOrNull` applied at all 6 call-sites; chat top bar
  now loads the peer's real avatar from `profiles`; tapping the peer name or
  avatar opens the FULL UserProfileScreen (followers/about/username) via a new
  `onOpenProfile` route param (was: contact-info sheet only); new
  `ProfilePhotoViewer` — black background, pinch-zoom, back button only for
  other users; own profile viewer adds **pen (change photo)** and **delete
  photo** (new `SupabaseClient.removeFile` + `sync-user-profile` clearing).
  **Messaging:** composer text + reply mark now clear INSTANTLY on send with a
  single-flight `isSending` guard on every entry point (double-send fixed;
  perceived latency now WhatsApp-like thanks to the optimistic Room insert);
  **sent ✓ / delivered ✓✓ / read ✓✓ (blue)** — new `messages.delivered_at`,
  RPC `mark_messages_delivered`, edge fns `mark-messages-delivered`
  (debounced 600 ms per conversation) + read RPC now sets `status='READ'`,
  sync-pull heals missed tick states. **Long-press:** Forward/Star/Pin
  removed; reply banner + quoted bubbles are tappable and scroll to the
  original message; the reply mark clears the moment a reply/edit is sent;
  reaction "+" opens a real 140-emoji bottom-sheet picker (was a 🔥 hack;
  DB already guarantees one reaction per user — unique(message,user) +
  switch semantics). **Dialogs:** compact delete dialog ("DELETE MESSAGE" /
  "Are you sure you want to delete these messages?" / checkbox "Also delete
  for \<name\>" / red Delete + green Cancel) — checkbox drives
  delete-for-everyone vs delete-for-me; block/unblock compacted everywhere;
  report redesigned to the WhatsApp card (2-line text + "Block \<name\>"
  checkbox + Cancel/Report, no icon) in both profile and chat-info surfaces.
  **Three-dot menu:** "View contact"→"View profile"; Search and Mute
  notifications removed. **Auto delete (was Disappearing messages):** compact
  dialog (heading "Auto delete messages", privacy explainer, 24 hours / 7 days
  / 30 days / **Off** — default Off, 90-day option replaced), Update+Cancel
  bottom-right; setting is now SHARED (single conversations row, either
  participant can change) via new `set-auto-delete` fn;
  `disappearing_updated_at` stamps activation — a new pg_cron sweep
  (`cleanup-disappearing-messages`, every 15 min) deletes only messages
  created AFTER activation; in-chat system notice (WhatsApp-style, our
  colors) shows the state inline with a green **Change** button reopening the
  dialog; no fake SYSTEM chat message is inserted anymore (legacy ones render
  as centered pills). **Online/last seen:** new `get-peer-presence` fn
  enforces the peer's `user_settings.last_seen` (nobody → blank, contacts →
  contact check) + accepted-chat gate; header shows "online" or
  "Last seen 03:02" (24 h, device zone) or blank; initial fetch on chat open,
  realtime events gated when hidden. **Calls:** engine/token errors during
  join now surface as a visible FAILED state (were silently logged → 45 s
  hang), `call_sessions` no longer inserts a placeholder-uuid caller (FK
  violation dropped the ring), and `generate-agora-token` serves an explicit
  app-id-only contract instead of 500 when the certificate secret is absent
  (certificate IS provisioned server-side; tokens issue normally).
  **Backend deployed:** migration `20260909_auto_delete_and_receipts.sql`
  executed live (columns/RPCs/grants verified + cron job); 5 functions
  deployed (3 new). **Bubble sizing** compacted toward WhatsApp metrics
  (10/4.5 dp padding, 19.5sp line height, 290 dp max width). Note: the
  Supabase access token `sbp_fce2…` was found revoked; the older `sbp_be52…`
  token remains valid and was used.
- **2026-09-08 (versionCode 3 rebuild — AI Studio chat fixes):** Re-cloned at
  `da392c3` ("fix typebox position above keyboard and add auto-scroll to
  latest messages", `ChatScreen.kt`): composer now pinned above the keyboard
  via `windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))`
  (replaces the `BoxWithConstraints` window-resize heuristic), auto-scroll to
  the newest message whenever the last message id changes (sent or received),
  scroll-to-bottom after send, search/reply scroll targets offset for the
  disappearing-messages system header, per-conversation initial-open-at-newest
  preserved. Pre-build compile sanity checked (wildcard
  `androidx.compose.foundation.layout.*` import covers the new APIs; braces
  balanced; no dangling references to removed `maxHeightWithoutIme`/
  `windowPhysicallyResized`). `versionCode` 2 → 3 (`253f28a`). Sandbox reset
  wiped the toolchain again (5th) → reinstalled JDK 21.0.12 / Gradle 9.3.1 /
  build-tools 36.0.0 + platforms;android-36.1, `.env` + `local.properties`
  restored from durable backups. Signed rebuild (upload key SHA-1
  `5b7f4bcd…`): APK 151,078,593 B `sha256 3f9c96db…`, AAB 75,521,044 B
  `sha256 3fafbce0…`. Artifacts installed to the durable local dir, local
  part-manifest regenerated (10 APK + 5 AAB parts, sourceCommit `253f28a`),
  GitHub release `v1.0-test` assets replaced and re-verified through the
  authenticated API (byte-identical digests). **Discovery:** the repo is
  PRIVATE, so public `github.com/…/releases/download/…` URLs 404 for
  unauthenticated visitors — the old "Direct mirror" links were dead;
  the download page now shows a "(private repo — sign-in required)" note
  instead of a link, and `/api/download` streams the local file directly
  (no GitHub redirect). Both artifacts re-verified end-to-end: every part
  sha256-exact, full-file endpoint byte-identical, browser downloads of APK
  and AAB completed "Saved & checksum-verified" with zero console errors.
- **2026-09-07 (release hosting reverted to local-only; Supabase Storage
  releases bucket deleted):** Per product decision, the APK/AAB are **no
  longer stored in Supabase Storage** — the `releases` bucket (5 sha256-verified
  parts + `latest/manifest.json`) was fully purged and the bucket deleted;
  all 7 app-data buckets (avatars, chat_media, vault_media, stream_thumbnails,
  backups, voice_notes, documents) are untouched. Artifacts remain in the
  durable local artifacts dir **and** the GitHub release `v1.0-test` mirror.
  The root-cause of the original download failures (single ~151 MB stream
  dying inside the preview gateway at 15–30 s) is now solved **locally**: the
  page serves each artifact as 16 MiB parts from `/api/download-part`
  (byte-range slices of the local files, Content-Length + error handling);
  the client verifies every part's size + SHA-256 against
  `artifacts/manifest.json` (10 parts APK / 5 parts AAB) with per-part retry,
  then assembles and saves the blob. `scripts/make-release-manifest.ts`
  regenerates the manifest (per-part hashes, versionCode/versionName parsed
  from `app/build.gradle.kts`, source commit = last code-affecting commit) —
  it replaces the removed `upload-release.sh` as step 3 of
  `trigger-recover.sh`, so **future rebuilds need no Supabase interaction at
  all**: rebuild → regenerate manifest → page serves the new build instantly.
  `/api/download` remains a 302 redirect to the GitHub mirror for non-browser
  clients. Verified: all 15 parts HTTP 200 with exact sizes and matching
  hashes (assembled sha256 APK `3aa7c979…` / AAB `dce1e9e5…` identical to the
  signed build); browser downloads of both artifacts completed
  "Saved & checksum-verified" with zero console errors; footer sticky-bottom
  confirmed on mobile (390×844).
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
