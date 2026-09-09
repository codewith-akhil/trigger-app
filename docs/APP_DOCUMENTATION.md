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
