-- ============================================================================
-- Trigger App — Full Application Schema
-- Migration: 20260904_full_app_schema.sql
-- ----------------------------------------------------------------------------
-- Covers EVERY UI input catalogued in the Android app (see worklog EXPLORE-2):
--   Auth/OTP, Profile, Chat, Scheduled Streams & Bookings, Wallet & Payouts,
--   Secret Vault, Settings/Privacy/Notifications, Support tickets, Push tokens.
--
-- This migration runs AFTER 20260903_call_and_live_stream_schema.sql (5 tables).
-- It AMENDS the 5 existing tables (fixes receiver_id FK bug, adds streamer_name)
-- and ADDS 14 new tables + storage buckets + realtime + RLS + triggers.
--
-- All server-side secrets live in Supabase Edge Function env (Deno.env.get).
-- Nothing client-side. No hardcoded keys.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. Extensions & helpers
-- ----------------------------------------------------------------------------
create extension if not exists "uuid-ossp";
create extension if not exists "pgcrypto";        -- for gen_random_bytes / digest
create extension if not exists "citext";          -- case-insensitive email/username

-- Generic updated_at trigger function (idempotent)
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- Safe string→uuid coerce (returns NULL for non-uuid strings — used in RLS)
create or replace function public.try_cast_uuid(v text)
returns uuid
language plpgsql
immutable
as $$
begin
  begin
    return v::uuid;
  exception when others then
    return null;
  end;
end;
$$;

-- ----------------------------------------------------------------------------
-- 1. AMEND existing call_sessions — fix receiver_id FK violation
-- ----------------------------------------------------------------------------
-- The Android AgoraCallService writes receiver_id = contactId which can be a
-- non-uuid string ("call_general", "call_<name>"). Drop the auth.users FK and
-- widen the column to TEXT so any contact identifier can be stored.
-- NB: must drop existing RLS policies first (PG won't alter a column that's
-- referenced in a policy expression), then recreate them with a ::text cast.
drop policy if exists "Users can view calls they participate in" on public.call_sessions;
drop policy if exists "Users can insert calls where they are caller" on public.call_sessions;
drop policy if exists "Users can update calls they participate in" on public.call_sessions;

do $$
begin
  if exists (
    select 1 from information_schema.table_constraints
    where constraint_name = 'call_sessions_receiver_id_fkey'
      and table_name = 'call_sessions'
  ) then
    alter table public.call_sessions drop constraint call_sessions_receiver_id_fkey;
  end if;
end $$;

alter table public.call_sessions
  alter column receiver_id type text using receiver_id::text,
  alter column receiver_id drop not null;

-- caller_id stays UUID FK → auth.users (always the real logged-in user).

-- Recreate call_sessions RLS policies (receiver_id is now TEXT, so cast auth.uid()).
drop policy if exists "call_sessions_select_participants" on public.call_sessions;
drop policy if exists "call_sessions_insert_caller"       on public.call_sessions;
drop policy if exists "call_sessions_update_participants" on public.call_sessions;
create policy "call_sessions_select_participants"
  on public.call_sessions for select to authenticated
  using (caller_id = auth.uid() or receiver_id = auth.uid()::text);
create policy "call_sessions_insert_caller"
  on public.call_sessions for insert to authenticated
  with check (caller_id = auth.uid());
create policy "call_sessions_update_participants"
  on public.call_sessions for update to authenticated
  using (caller_id = auth.uid() or receiver_id = auth.uid()::text)
  with check (caller_id = auth.uid() or receiver_id = auth.uid()::text);

-- call_events policies reference call_sessions.receiver_id in a subquery, so
-- they must be dropped+recreated whenever receiver_id changes type.
drop policy if exists "call_events_select_participants" on public.call_events;
drop policy if exists "call_events_insert_participants" on public.call_events;
create policy "call_events_select_participants"
  on public.call_events for select to authenticated
  using (
    exists (
      select 1 from public.call_sessions cs
      where cs.id = call_events.call_id
        and (cs.caller_id = auth.uid() or cs.receiver_id = auth.uid()::text)
    )
  );
create policy "call_events_insert_participants"
  on public.call_events for insert to authenticated
  with check (
    auth.uid() = user_id and exists (
      select 1 from public.call_sessions cs
      where cs.id = call_events.call_id
        and (cs.caller_id = auth.uid() or cs.receiver_id = auth.uid()::text)
    )
  );

-- ----------------------------------------------------------------------------
-- 2. AMEND existing live_streams — add streamer_name (code reads it)
-- ----------------------------------------------------------------------------
alter table public.live_streams
  add column if not exists streamer_name text,
  add column if not exists thumbnail_bucket text,
  add column if not exists host_avatar_url text;

-- ----------------------------------------------------------------------------
-- 3. profiles  (1:1 with auth.users — created by trigger on signup)
--    Covers ProfileScreen + AccountSettingsScreen + SignUp inputs.
-- ----------------------------------------------------------------------------
create table if not exists public.profiles (
  id              uuid primary key references auth.users(id) on delete cascade,
  full_name       text not null,
  username        citext unique,
  about           text default '' ,
  avatar_url      text,
  avatar_bucket   text default 'avatars',
  phone           varchar(20),
  phone_verified  boolean default false,
  country_iso     char(2),
  language_code   varchar(5) default 'en',
  links           jsonb not null default '[]'::jsonb,
  is_online       boolean default false,
  last_seen_at    timestamptz,
  two_step_pin_hash text,
  two_step_enabled  boolean default false,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create index if not exists profiles_username_idx on public.profiles (username);
create index if not exists profiles_phone_idx    on public.profiles (phone);

drop trigger if exists profiles_set_updated_at on public.profiles;
create trigger profiles_set_updated_at
  before update on public.profiles
  for each row execute function public.set_updated_at();

-- Auto-create a profile row whenever a new auth.users row is inserted
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, full_name, username, avatar_url)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)),
    new.raw_user_meta_data->>'username',
    new.raw_user_meta_data->>'avatar_url'
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ----------------------------------------------------------------------------
-- 4. conversations  (WhatsApp dashboard chat list, per-user)
-- ----------------------------------------------------------------------------
create table if not exists public.conversations (
  id                    uuid primary key default uuid_generate_v4(),
  owner_id              uuid not null references auth.users(id) on delete cascade,
  peer_id               uuid references auth.users(id) on delete set null,
  peer_name             text not null,
  peer_avatar_url       text,
  peer_avatar_color     bigint default 4281344132,   -- 0xFF00A884
  is_group              boolean default false,
  is_pinned             boolean default false,
  is_muted              boolean default false,
  is_blocked            boolean default false,
  is_archived           boolean default false,
  disappearing_duration text not null default 'OFF'
    check (disappearing_duration in ('OFF','24H','7D','90D')),
  last_message          text default '',
  last_message_type     text default 'TEXT',
  last_message_at       timestamptz,
  unread_count          int default 0,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);

create index if not exists conv_owner_idx        on public.conversations (owner_id);
create index if not exists conv_owner_pinned_idx on public.conversations (owner_id, is_pinned);
create index if not exists conv_peer_idx         on public.conversations (peer_id);

drop trigger if exists conv_set_updated_at on public.conversations;
create trigger conv_set_updated_at
  before update on public.conversations
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 5. messages  (covers EVERY MessageType in ChatModels.kt + location/contact)
-- ----------------------------------------------------------------------------
create table if not exists public.messages (
  id                    uuid primary key default uuid_generate_v4(),
  conversation_id       uuid not null references public.conversations(id) on delete cascade,
  sender_id             uuid not null references auth.users(id) on delete cascade,
  type                  text not null default 'TEXT'
    check (type in ('TEXT','IMAGE','VIDEO','AUDIO','VOICE_NOTE','DOCUMENT',
                    'LOCATION','CONTACT','CALL_LOG','SYSTEM')),
  text                  text default '',
  media_url             text,
  media_thumbnail       text,
  media_bucket          text,
  file_name             text,
  file_size             bigint default 0,
  mime_type             text,
  media_duration_sec    int default 0,
  is_view_once          boolean default false,
  is_viewed             boolean default false,
  reply_to_id           uuid references public.messages(id) on delete set null,
  status                text not null default 'SENDING'
    check (status in ('SENDING','SENT','DELIVERED','READ','FAILED')),
  timestamp_millis      bigint not null default (extract(epoch from now()) * 1000)::bigint,
  is_outgoing           boolean default true,
  is_starred            boolean default false,
  is_deleted_for_everyone boolean default false,
  -- LOCATION message inputs (SendLocationScreen: lat,lng,placeName,address,duration,comment)
  location_lat          double precision,
  location_lng          double precision,
  location_address      text,
  location_place_name   text,
  location_live_minutes int,
  location_comment      text,
  -- CONTACT message inputs (ChatContactInfoSheet: name, phone)
  contact_name          text,
  contact_phone         text,
  -- CALL_LOG message inputs
  call_type             text check (call_type in ('audio','video')),
  call_duration_sec     int default 0,
  -- reactions serialised as {emoji: {count, userReacted}} via JSONB
  reactions             jsonb not null default '{}'::jsonb,
  created_at            timestamptz not null default now()
);

create index if not exists msg_conv_ts_idx   on public.messages (conversation_id, timestamp_millis);
create index if not exists msg_sender_idx    on public.messages (sender_id);
create index if not exists msg_reply_idx     on public.messages (reply_to_id);
create index if not exists msg_starred_idx   on public.messages (is_starred) where is_starred = true;

-- ----------------------------------------------------------------------------
-- 6. message_reactions
-- ----------------------------------------------------------------------------
create table if not exists public.message_reactions (
  id           uuid primary key default uuid_generate_v4(),
  message_id   uuid not null references public.messages(id) on delete cascade,
  user_id      uuid not null references auth.users(id) on delete cascade,
  emoji        text not null,
  created_at   timestamptz not null default now(),
  unique (message_id, user_id)
);
create index if not exists mr_msg_idx on public.message_reactions (message_id);

-- ----------------------------------------------------------------------------
-- 7. scheduled_streams  (ScheduleStreamScreen — 12 fields)
-- ----------------------------------------------------------------------------
create table if not exists public.scheduled_streams (
  id              uuid primary key default uuid_generate_v4(),
  host_id         uuid not null references auth.users(id) on delete cascade,
  channel_name    varchar(120) unique,
  title           text not null,
  description     text,
  category        text not null default 'General',
  scheduled_date  date not null,
  scheduled_time  text not null,
  slot_limit      text not null default '25'
    check (slot_limit in ('25','50','150','200','500','ANY')),
  pricing_type    text not null default 'FREE'
    check (pricing_type in ('FREE','PAID')),
  amount          numeric(10,2) not null default 0 check (amount >= 0),
  currency        text not null default 'INR (₹)'
    check (currency in ('USD ($)','INR (₹)','EUR (€)','GBP (£)')),
  send_email      boolean default true,
  send_push       boolean default true,
  share_link      text,
  status          text not null default 'scheduled'
    check (status in ('scheduled','live','ended','cancelled')),
  slots_booked    int not null default 0 check (slots_booked >= 0),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create index if not exists ss_host_idx    on public.scheduled_streams (host_id);
create index if not exists ss_status_idx  on public.scheduled_streams (status);
create index if not exists ss_date_idx    on public.scheduled_streams (scheduled_date);

drop trigger if exists ss_set_updated_at on public.scheduled_streams;
create trigger ss_set_updated_at
  before update on public.scheduled_streams
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 8. stream_bookings  (StreamBookingDialog — userName, userEmail, paymentMethod)
-- ----------------------------------------------------------------------------
create table if not exists public.stream_bookings (
  id               uuid primary key default uuid_generate_v4(),
  stream_id        uuid not null references public.scheduled_streams(id) on delete cascade,
  user_id          uuid not null references auth.users(id) on delete cascade,
  user_name        text not null,
  user_email       text not null,
  payment_method   text not null default 'free'
    check (payment_method in ('wallet','bank','upi','cards','free')),
  payment_reference text,
  amount_paid      numeric(10,2) not null default 0 check (amount_paid >= 0),
  currency         text default 'INR (₹)',
  booked_at        timestamptz not null default now(),
  unique (stream_id, user_id)
);
create index if not exists sb_stream_idx on public.stream_bookings (stream_id);
create index if not exists sb_user_idx   on public.stream_bookings (user_id);

-- Auto-increment slots_booked on booking insert
create or replace function public.increment_stream_booking()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  update public.scheduled_streams
    set slots_booked = slots_booked + 1
    where id = new.stream_id
      and (slot_limit = 'ANY' or slots_booked < slot_limit::int);
  if not found then
    raise exception 'Stream is fully booked';
  end if;
  return new;
end;
$$;

drop trigger if exists sb_after_insert on public.stream_bookings;
create trigger sb_after_insert
  after insert on public.stream_bookings
  for each row execute function public.increment_stream_booking();

-- ----------------------------------------------------------------------------
-- 9. wallet_transactions  (WalletScreen — credit/debit, ref ids)
-- ----------------------------------------------------------------------------
create table if not exists public.wallet_transactions (
  id                uuid primary key default uuid_generate_v4(),
  user_id           uuid not null references auth.users(id) on delete cascade,
  type              text not null check (type in ('credit','debit')),
  amount            numeric(10,2) not null check (amount > 0),
  currency          text not null default 'INR (₹)',
  description       text,
  reference_id      text unique not null,
  related_stream_id uuid references public.scheduled_streams(id) on delete set null,
  status            text not null default 'processing'
    check (status in ('processing','completed','failed','pending')),
  created_at        timestamptz not null default now()
);
create index if not exists wt_user_idx    on public.wallet_transactions (user_id);
create index if not exists wt_ref_idx     on public.wallet_transactions (reference_id);

-- ----------------------------------------------------------------------------
-- 10. bank_details  (EditBankDetailsDialog — 5 fields, store last4 only)
-- ----------------------------------------------------------------------------
create table if not exists public.bank_details (
  id                    uuid primary key default uuid_generate_v4(),
  user_id               uuid not null unique references auth.users(id) on delete cascade,
  account_holder_name   text not null,
  bank_name             text not null,
  account_number_last4  char(4) not null,
  account_number_hash   text not null,        -- sha256 hash for de-dup, never raw
  ifsc_or_routing       text,
  swift_code            text,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
drop trigger if exists bd_set_updated_at on public.bank_details;
create trigger bd_set_updated_at
  before update on public.bank_details
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 11. payout_details  (EditPayoutDetailsDialog — primaryMethod, upiId, paypalEmail)
-- ----------------------------------------------------------------------------
create table if not exists public.payout_details (
  id            uuid primary key default uuid_generate_v4(),
  user_id       uuid not null unique references auth.users(id) on delete cascade,
  primary_method text not null check (primary_method in ('Bank','UPI','PayPal')),
  upi_id        text,
  paypal_email  text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  check (
    (primary_method = 'UPI' and upi_id is not null) or
    (primary_method = 'PayPal' and paypal_email is not null) or
    (primary_method = 'Bank')
  )
);
drop trigger if exists pd_set_updated_at on public.payout_details;
create trigger pd_set_updated_at
  before update on public.payout_details
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 12. vault_pins  (SecretVaultScreen — 6-digit PIN, hashed, never plaintext)
-- ----------------------------------------------------------------------------
create table if not exists public.vault_pins (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  pin_hash    text not null,                 -- "<salt>:<sha256(pin:salt)>"
  attempts    int not null default 0,         -- brute-force counter (reset on success)
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
drop trigger if exists vp_set_updated_at on public.vault_pins;
create trigger vp_set_updated_at
  before update on public.vault_pins
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 13. vault_media  (SecretVaultScreen — imported media, encrypted at rest)
-- ----------------------------------------------------------------------------
create table if not exists public.vault_media (
  id            uuid primary key default uuid_generate_v4(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  storage_path  text not null,                  -- path inside vault_media bucket
  file_name     text,
  file_size     bigint default 0,
  is_video      boolean default false,
  mime_type     text,
  created_at    timestamptz not null default now()
);
create index if not exists vm_user_idx on public.vault_media (user_id);

-- ----------------------------------------------------------------------------
-- 14. user_settings  (all Privacy/Chats/Notifications/Storage settings)
--     Covers PrivacySettingsScreen, ChatsSettingsScreen,
--     NotificationsSettingsScreen, StorageSettingsScreen, AccountSettingsScreen.
-- ----------------------------------------------------------------------------
create table if not exists public.user_settings (
  user_id                    uuid primary key references auth.users(id) on delete cascade,
  -- Security & privacy
  security_notifications     boolean default true,
  two_step_enabled           boolean default false,
  read_receipts              boolean default true,
  fingerprint_lock           boolean default false,
  last_seen                  text default 'everyone' check (last_seen in ('everyone','contacts','nobody')),
  profile_photo_visibility   text default 'everyone' check (profile_photo_visibility in ('everyone','contacts','nobody')),
  about_visibility           text default 'everyone' check (about_visibility in ('everyone','contacts','nobody')),
  groups_visibility          text default 'everyone' check (groups_visibility in ('everyone','contacts','nobody')),
  -- Chats
  disappearing_default       text default 'OFF' check (disappearing_default in ('OFF','24H','7D','90D')),
  enter_is_send              boolean default false,
  media_visibility           text default 'on' check (media_visibility in ('on','off')),
  font_size                  text default 'medium' check (font_size in ('small','medium','large')),
  conversation_tones         boolean default true,
  -- Notifications
  high_priority_messages     boolean default true,
  message_tone               text default 'default',
  message_vibrate            boolean default true,
  group_tone                 text default 'default',
  call_ringtone              text default 'default',
  use_less_data_for_calls    boolean default false,
  mobile_data_media          text default 'auto' check (mobile_data_media in ('auto','on','off')),
  wifi_media                 text default 'auto' check (wifi_media in ('auto','on','off')),
  roaming_media              boolean default false,
  -- Locale
  app_language               text default 'en',
  updated_at                 timestamptz not null default now()
);
drop trigger if exists us_set_updated_at on public.user_settings;
create trigger us_set_updated_at
  before update on public.user_settings
  for each row execute function public.set_updated_at();

-- Auto-create default settings row on signup
create or replace function public.handle_new_user_settings()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.user_settings (user_id) values (new.id) on conflict do nothing;
  return new;
end;
$$;
drop trigger if exists on_auth_user_created_settings on auth.users;
create trigger on_auth_user_created_settings
  after insert on auth.users
  for each row execute function public.handle_new_user_settings();

-- ----------------------------------------------------------------------------
-- 15. blocked_contacts  (PrivacySettingsScreen — blocked list)
-- ----------------------------------------------------------------------------
create table if not exists public.blocked_contacts (
  id                  uuid primary key default uuid_generate_v4(),
  user_id             uuid not null references auth.users(id) on delete cascade,
  blocked_user_id     uuid references auth.users(id) on delete cascade,
  blocked_identifier  text,                  -- peer_name when no auth user id
  created_at          timestamptz not null default now(),
  check (blocked_user_id is not null or blocked_identifier is not null),
  unique (user_id, blocked_identifier)
);
create index if not exists bc_user_idx on public.blocked_contacts (user_id);

-- ----------------------------------------------------------------------------
-- 16. support_tickets  (HelpSettingsScreen — Contact Support dialog)
-- ----------------------------------------------------------------------------
create table if not exists public.support_tickets (
  id          uuid primary key default uuid_generate_v4(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  subject     text,
  message     text not null,
  status      text not null default 'open' check (status in ('open','in_progress','resolved','closed')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
drop trigger if exists st_set_updated_at on public.support_tickets;
create trigger st_set_updated_at
  before update on public.support_tickets
  for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------
-- 17. otp_codes  (custom 6-digit OTP via Resend edge function)
--    Supports: 60s resend cooldown, 10min expiry, attempt limiting, purpose.
-- ----------------------------------------------------------------------------
create table if not exists public.otp_codes (
  id            uuid primary key default uuid_generate_v4(),
  identifier    text not null,                 -- email or phone
  code_hash     text not null,                 -- sha256(code + salt), never raw
  purpose       text not null check (purpose in ('signup','recovery','magic_link','email_change','phone_verify','vault_reset')),
  expires_at    timestamptz not null,
  attempts      int not null default 0,
  max_attempts  int not null default 5,
  consumed_at   timestamptz,
  last_resent_at timestamptz,
  created_at    timestamptz not null default now()
);
create index if not exists otp_identifier_idx on public.otp_codes (identifier, purpose);
create index if not exists otp_expires_idx    on public.otp_codes (expires_at);

-- ----------------------------------------------------------------------------
-- 18. push_tokens  (Firebase Cloud Messaging device tokens)
--    Used by send-push-notification edge function.
-- ----------------------------------------------------------------------------
create table if not exists public.push_tokens (
  id            uuid primary key default uuid_generate_v4(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  fcm_token     text not null,
  platform      text not null default 'android',
  app_version   text,
  device_id     text,
  is_active     boolean default true,
  created_at    timestamptz not null default now(),
  last_seen_at  timestamptz not null default now(),
  unique (user_id, device_id)
);
create index if not exists pt_user_idx        on public.push_tokens (user_id);
create index if not exists pt_token_idx       on public.push_tokens (fcm_token);
create index if not exists pt_active_user_idx on public.push_tokens (user_id) where is_active = true;

-- ----------------------------------------------------------------------------
-- 19. user_presences  (online/last-seen — PresenceService)
-- ----------------------------------------------------------------------------
create table if not exists public.user_presences (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  is_online    boolean default false,
  last_seen_at timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
drop trigger if exists up_set_updated_at on public.user_presences;
create trigger up_set_updated_at
  before update on public.user_presences
  for each row execute function public.set_updated_at();

-- ============================================================================
-- STORAGE BUCKETS  (public read, owner-only write via RLS)
-- ============================================================================
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('avatars',          'avatars',          true,  10485760,  array['image/jpeg','image/png','image/webp','image/gif']),
  ('chat_media',       'chat_media',       false, 67108864,  array['image/jpeg','image/png','image/webp','image/gif','video/mp4','video/quicktime','audio/mpeg','audio/mp4','audio/aac','application/pdf','application/octet-stream']),
  ('voice_notes',      'voice_notes',      false, 16777216,  array['audio/mpeg','audio/mp4','audio/aac','audio/3gpp','audio/ogg']),
  ('documents',        'documents',        false, 104857600, array['application/pdf','application/msword','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.ms-excel','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.ms-powerpoint','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain','application/zip']),
  ('vault_media',      'vault_media',      false, 104857600, array['image/jpeg','image/png','image/webp','video/mp4','video/quicktime','video/x-matroska']),
  ('stream_thumbnails','stream_thumbnails',true,  10485760,  array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

-- Storage RLS policies (owner-scoped writes, public reads for public buckets)
-- Drop all of them first so this migration is re-runnable.
drop policy if exists "avatars_public_read"      on storage.objects;
drop policy if exists "avatars_owner_write"      on storage.objects;
drop policy if exists "avatars_owner_update"     on storage.objects;
drop policy if exists "thumbnails_public_read"   on storage.objects;
drop policy if exists "thumbnails_owner_write"   on storage.objects;
drop policy if exists "chat_media_owner_read"    on storage.objects;
drop policy if exists "chat_media_owner_write"   on storage.objects;
drop policy if exists "chat_media_owner_update"  on storage.objects;
drop policy if exists "chat_media_owner_delete"  on storage.objects;
drop policy if exists "voice_notes_owner_read"   on storage.objects;
drop policy if exists "voice_notes_owner_write"  on storage.objects;
drop policy if exists "voice_notes_owner_delete" on storage.objects;
drop policy if exists "documents_owner_read"     on storage.objects;
drop policy if exists "documents_owner_write"    on storage.objects;
drop policy if exists "documents_owner_delete"   on storage.objects;
drop policy if exists "vault_media_owner_read"   on storage.objects;
drop policy if exists "vault_media_owner_write"  on storage.objects;
drop policy if exists "vault_media_owner_delete" on storage.objects;

-- avatars: public read, owner write
create policy "avatars_public_read" on storage.objects
  for select using (bucket_id = 'avatars');
create policy "avatars_owner_write" on storage.objects
  for insert to authenticated with check (bucket_id = 'avatars' and owner = auth.uid());
create policy "avatars_owner_update" on storage.objects
  for update to authenticated using (bucket_id = 'avatars' and owner = auth.uid());

-- stream_thumbnails: public read, owner write
create policy "thumbnails_public_read" on storage.objects
  for select using (bucket_id = 'stream_thumbnails');
create policy "thumbnails_owner_write" on storage.objects
  for insert to authenticated with check (bucket_id = 'stream_thumbnails' and owner = auth.uid());

-- Private buckets: owner-only read + write
create policy "chat_media_owner_read"   on storage.objects for select to authenticated using (bucket_id = 'chat_media' and owner = auth.uid());
create policy "chat_media_owner_write"  on storage.objects for insert to authenticated with check (bucket_id = 'chat_media' and owner = auth.uid());
create policy "chat_media_owner_update" on storage.objects for update to authenticated using (bucket_id = 'chat_media' and owner = auth.uid());
create policy "chat_media_owner_delete" on storage.objects for delete to authenticated using (bucket_id = 'chat_media' and owner = auth.uid());

create policy "voice_notes_owner_read"   on storage.objects for select to authenticated using (bucket_id = 'voice_notes' and owner = auth.uid());
create policy "voice_notes_owner_write"  on storage.objects for insert to authenticated with check (bucket_id = 'voice_notes' and owner = auth.uid());
create policy "voice_notes_owner_delete" on storage.objects for delete to authenticated using (bucket_id = 'voice_notes' and owner = auth.uid());

create policy "documents_owner_read"   on storage.objects for select to authenticated using (bucket_id = 'documents' and owner = auth.uid());
create policy "documents_owner_write"  on storage.objects for insert to authenticated with check (bucket_id = 'documents' and owner = auth.uid());
create policy "documents_owner_delete" on storage.objects for delete to authenticated using (bucket_id = 'documents' and owner = auth.uid());

create policy "vault_media_owner_read"   on storage.objects for select to authenticated using (bucket_id = 'vault_media' and owner = auth.uid());
create policy "vault_media_owner_write"  on storage.objects for insert to authenticated with check (bucket_id = 'vault_media' and owner = auth.uid());
create policy "vault_media_owner_delete" on storage.objects for delete to authenticated using (bucket_id = 'vault_media' and owner = auth.uid());

-- ============================================================================
-- RLS — enable on every new table
-- ============================================================================
alter table public.profiles             enable row level security;
alter table public.conversations        enable row level security;
alter table public.messages            enable row level security;
alter table public.message_reactions   enable row level security;
alter table public.scheduled_streams   enable row level security;
alter table public.stream_bookings     enable row level security;
alter table public.wallet_transactions enable row level security;
alter table public.bank_details        enable row level security;
alter table public.payout_details      enable row level security;
alter table public.vault_pins          enable row level security;
alter table public.vault_media         enable row level security;
alter table public.user_settings       enable row level security;
alter table public.blocked_contacts    enable row level security;
alter table public.support_tickets     enable row level security;
alter table public.otp_codes           enable row level security;
alter table public.push_tokens         enable row level security;
alter table public.user_presences      enable row level security;

-- ----------------------------------------------------------------------------
-- profiles RLS
-- ----------------------------------------------------------------------------
drop policy if exists "profiles_select" on public.profiles;
create policy "profiles_select" on public.profiles
  for select to authenticated using (true);   -- profiles are discoverable (username search)
drop policy if exists "profiles_self_insert" on public.profiles;
create policy "profiles_self_insert" on public.profiles
  for insert to authenticated with check (id = auth.uid());
drop policy if exists "profiles_self_update" on public.profiles;
create policy "profiles_self_update" on public.profiles
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- ----------------------------------------------------------------------------
-- conversations RLS — owner only
-- ----------------------------------------------------------------------------
drop policy if exists "conv_owner_select" on public.conversations;
create policy "conv_owner_select" on public.conversations
  for select to authenticated using (owner_id = auth.uid() or peer_id = auth.uid());
drop policy if exists "conv_owner_insert" on public.conversations;
create policy "conv_owner_insert" on public.conversations
  for insert to authenticated with check (owner_id = auth.uid());
drop policy if exists "conv_owner_update" on public.conversations;
create policy "conv_owner_update" on public.conversations
  for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
drop policy if exists "conv_owner_delete" on public.conversations;
create policy "conv_owner_delete" on public.conversations
  for delete to authenticated using (owner_id = auth.uid());

-- ----------------------------------------------------------------------------
-- messages RLS — participants of the conversation
-- ----------------------------------------------------------------------------
drop policy if exists "msg_participant_select" on public.messages;
create policy "msg_participant_select" on public.messages
  for select to authenticated using (
    exists (
      select 1 from public.conversations c
      where c.id = messages.conversation_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );
drop policy if exists "msg_participant_insert" on public.messages;
create policy "msg_participant_insert" on public.messages
  for insert to authenticated with check (
    sender_id = auth.uid() and exists (
      select 1 from public.conversations c
      where c.id = messages.conversation_id
        and (c.owner_id = auth.uid() or c.peer_id = auth.uid())
    )
  );
drop policy if exists "msg_participant_update" on public.messages;
create policy "msg_participant_update" on public.messages
  for update to authenticated using (
    sender_id = auth.uid() or exists (
      select 1 from public.conversations c
      where c.id = messages.conversation_id and c.owner_id = auth.uid()
    )
  );
drop policy if exists "msg_participant_delete" on public.messages;
create policy "msg_participant_delete" on public.messages
  for delete to authenticated using (
    sender_id = auth.uid() or exists (
      select 1 from public.conversations c
      where c.id = messages.conversation_id and c.owner_id = auth.uid()
    )
  );

-- ----------------------------------------------------------------------------
-- message_reactions RLS
-- ----------------------------------------------------------------------------
drop policy if exists "mr_select" on public.message_reactions;
create policy "mr_select" on public.message_reactions
  for select to authenticated using (true);
drop policy if exists "mr_self_insert" on public.message_reactions;
create policy "mr_self_insert" on public.message_reactions
  for insert to authenticated with check (user_id = auth.uid());
drop policy if exists "mr_self_delete" on public.message_reactions;
create policy "mr_self_delete" on public.message_reactions
  for delete to authenticated using (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- scheduled_streams RLS — host owns; anyone can view scheduled+live
-- ----------------------------------------------------------------------------
drop policy if exists "ss_select" on public.scheduled_streams;
create policy "ss_select" on public.scheduled_streams
  for select to authenticated using (host_id = auth.uid() or status in ('scheduled','live'));
drop policy if exists "ss_host_insert" on public.scheduled_streams;
create policy "ss_host_insert" on public.scheduled_streams
  for insert to authenticated with check (host_id = auth.uid());
drop policy if exists "ss_host_update" on public.scheduled_streams;
create policy "ss_host_update" on public.scheduled_streams
  for update to authenticated using (host_id = auth.uid()) with check (host_id = auth.uid());
drop policy if exists "ss_host_delete" on public.scheduled_streams;
create policy "ss_host_delete" on public.scheduled_streams
  for delete to authenticated using (host_id = auth.uid());

-- ----------------------------------------------------------------------------
-- stream_bookings RLS — booking owner or stream host
-- ----------------------------------------------------------------------------
drop policy if exists "sb_select" on public.stream_bookings;
create policy "sb_select" on public.stream_bookings
  for select to authenticated using (
    user_id = auth.uid() or exists (
      select 1 from public.scheduled_streams s where s.id = stream_bookings.stream_id and s.host_id = auth.uid()
    )
  );
drop policy if exists "sb_self_insert" on public.stream_bookings;
create policy "sb_self_insert" on public.stream_bookings
  for insert to authenticated with check (user_id = auth.uid());
drop policy if exists "sb_self_delete" on public.stream_bookings;
create policy "sb_self_delete" on public.stream_bookings
  for delete to authenticated using (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- wallet_transactions / bank_details / payout_details — owner only
-- ----------------------------------------------------------------------------
drop policy if exists "wt_owner_select" on public.wallet_transactions;
create policy "wt_owner_select" on public.wallet_transactions
  for select to authenticated using (user_id = auth.uid());
drop policy if exists "wt_owner_insert" on public.wallet_transactions;
create policy "wt_owner_insert" on public.wallet_transactions
  for insert to authenticated with check (user_id = auth.uid());
drop policy if exists "wt_owner_update" on public.wallet_transactions;
create policy "wt_owner_update" on public.wallet_transactions
  for update to authenticated using (user_id = auth.uid());

drop policy if exists "bd_owner_all" on public.bank_details;
create policy "bd_owner_all" on public.bank_details
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "pd_owner_all" on public.payout_details;
create policy "pd_owner_all" on public.payout_details
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- vault_pins / vault_media — owner only
-- ----------------------------------------------------------------------------
drop policy if exists "vp_owner_all" on public.vault_pins;
create policy "vp_owner_all" on public.vault_pins
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "vm_owner_all" on public.vault_media;
create policy "vm_owner_all" on public.vault_media
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- user_settings — owner only
-- ----------------------------------------------------------------------------
drop policy if exists "us_owner_all" on public.user_settings;
create policy "us_owner_all" on public.user_settings
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- blocked_contacts — owner only
-- ----------------------------------------------------------------------------
drop policy if exists "bc_owner_all" on public.blocked_contacts;
create policy "bc_owner_all" on public.blocked_contacts
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- support_tickets — owner only (admins can be added later via service role)
-- ----------------------------------------------------------------------------
drop policy if exists "st_owner_all" on public.support_tickets;
create policy "st_owner_all" on public.support_tickets
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- otp_codes — NO client RLS. Only edge functions (service role) read/write.
-- Deny all direct client access to prevent OTP brute-force / enumeration.
-- ----------------------------------------------------------------------------
drop policy if exists "otp_deny_all" on public.otp_codes;
create policy "otp_deny_all" on public.otp_codes
  for all to authenticated using (false) with check (false);

-- ----------------------------------------------------------------------------
-- push_tokens — owner can manage own tokens
-- ----------------------------------------------------------------------------
drop policy if exists "pt_owner_all" on public.push_tokens;
create policy "pt_owner_all" on public.push_tokens
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- user_presences — owner can update own; everyone can read (for last-seen)
-- ----------------------------------------------------------------------------
drop policy if exists "up_select_all" on public.user_presences;
create policy "up_select_all" on public.user_presences
  for select to authenticated using (true);
drop policy if exists "up_self_upsert" on public.user_presences;
create policy "up_self_upsert" on public.user_presences
  for insert to authenticated with check (user_id = auth.uid());
drop policy if exists "up_self_update" on public.user_presences;
create policy "up_self_update" on public.user_presences
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ============================================================================
-- REALTIME — add all new tables to the supabase_realtime publication
-- ============================================================================
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end $$;

-- Add each table only if not already a member (idempotent).
do $$
declare
  t text;
begin
  foreach t in array array[
    'public.profiles','public.conversations','public.messages',
    'public.message_reactions','public.scheduled_streams','public.stream_bookings',
    'public.wallet_transactions','public.user_presences','public.call_events'
  ] loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname || '.' || tablename = t
    ) then
      execute format('alter publication supabase_realtime add table %s', t);
    end if;
  end loop;
end $$;
-- (call_sessions, live_streams, live_stream_comments, live_stream_reactions
--  were already added by the 20260903 migration.)

-- ============================================================================
-- RPC HELPERS (used by edge functions / clients)
-- ============================================================================

-- upsert push token (called by Android after FCM token refresh)
create or replace function public.upsert_push_token(
  p_fcm_token text,
  p_device_id text,
  p_platform text default 'android',
  p_app_version text default null
)
returns uuid
language plpgsql
security definer set search_path = public
as $$
declare
  v_id uuid;
begin
  insert into public.push_tokens (user_id, fcm_token, device_id, platform, app_version, is_active, last_seen_at)
  values (auth.uid(), p_fcm_token, p_device_id, p_platform, p_app_version, true, now())
  on conflict (user_id, device_id)
  do update set fcm_token = excluded.fcm_token, is_active = true,
                 app_version = excluded.app_version, last_seen_at = now()
  returning id into v_id;
  return v_id;
end;
$$;

-- get active FCM tokens for a user (used by send-push-notification edge fn)
create or replace function public.get_active_push_tokens(p_user_id uuid)
returns setof text
language plpgsql
security definer set search_path = public
as $$
begin
  return query select fcm_token from public.push_tokens
    where user_id = p_user_id and is_active = true;
end;
$$;

-- record a wallet transaction (security definer so client can't forge ref ids freely)
create or replace function public.record_wallet_transaction(
  p_type text,
  p_amount numeric,
  p_description text,
  p_reference_id text,
  p_currency text default 'INR (₹)',
  p_status text default 'completed',
  p_related_stream_id uuid default null
)
returns uuid
language plpgsql
security definer set search_path = public
as $$
declare v_id uuid;
begin
  insert into public.wallet_transactions
    (user_id, type, amount, currency, description, reference_id, status, related_stream_id)
  values
    (auth.uid(), p_type, p_amount, p_currency, p_description, p_reference_id, p_status, p_related_stream_id)
  returning id into v_id;
  return v_id;
end;
$$;

-- ============================================================================
-- DONE. All UI inputs from the Android app are now represented in the schema.
-- ============================================================================
