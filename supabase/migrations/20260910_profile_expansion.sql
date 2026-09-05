-- ============================================================================
-- Trigger App — Profile expansion: gender, DOB, country, username history
-- Migration: 20260910_profile_expansion.sql
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- 1. Add gender, dob, country_code columns to profiles
-- ----------------------------------------------------------------------------
alter table public.profiles
  add column if not exists gender text,
  add column if not exists dob date,
  add column if not exists country_code char(3);  -- ISO 4217 3-letter currency code

-- Gender CHECK constraint
alter table public.profiles
  drop constraint if exists profiles_gender_check;
alter table public.profiles
  add constraint profiles_gender_check
    check (gender is null or gender in ('Male','Female','Transmen','Transwomen'));

-- ----------------------------------------------------------------------------
-- 2. Gender table (seed values)
-- ----------------------------------------------------------------------------
create table if not exists public.genders (
  id        serial primary key,
  name      text not null unique,
  sort_order int not null default 0
);
alter table public.genders enable row level security;
drop policy if exists "genders_public_read" on public.genders;
create policy "genders_public_read" on public.genders
  for select to anon, authenticated using (true);

insert into public.genders (name, sort_order) values
  ('Male', 1),
  ('Female', 2),
  ('Transmen', 3),
  ('Transwomen', 4)
on conflict (name) do nothing;

-- ----------------------------------------------------------------------------
-- 3. Rebuild countries table with full FX rate data
--    (drops the old 48-country seed and replaces with 160+ countries)
-- ----------------------------------------------------------------------------
drop table if exists public.countries cascade;
create table public.countries (
  id              serial primary key,
  currency_code   char(3) not null unique,    -- ISO 4217 (USD, INR, EUR)
  country_name    text not null,
  iso2            char(2),                     -- ISO 3166-1 alpha-2
  currency_symbol text,                        -- $, ₹, €, £
  fx_rate         numeric(20,6) not null default 1.0,  -- vs USD
  fx_updated_at   timestamptz,
  created_at      timestamptz not null default now()
);
alter table public.countries enable row level security;
drop policy if exists "countries_public_read" on public.countries;
create policy "countries_public_read" on public.countries
  for select to anon, authenticated using (true);

create index if not exists countries_currency_idx on public.countries (currency_code);
create index if not exists countries_iso2_idx on public.countries (iso2);

-- ----------------------------------------------------------------------------
-- 4. Username history table (for 1-hour cooldown on old usernames)
--    When a user changes their username, the old username is recorded here
--    with a released_at timestamp. Another user can't claim it until
--    released_at + 1 hour.
-- ----------------------------------------------------------------------------
create table if not exists public.username_history (
  id            serial primary key,
  user_id       uuid not null references auth.users(id) on delete cascade,
  old_username  text not null,
  new_username  text not null,
  changed_at    timestamptz not null default now(),
  released_at   timestamptz not null default now()  -- when the old username becomes available to others
);
create index if not exists username_history_old_idx on public.username_history (old_username, released_at);
alter table public.username_history enable row level security;
drop policy if exists "username_history_owner_select" on public.username_history;
create policy "username_history_owner_select" on public.username_history
  for select to authenticated using (user_id = auth.uid());

-- ----------------------------------------------------------------------------
-- 5. Update the profiles username constraint (5-25 chars)
--    The username column is citext (case-insensitive). Add a CHECK for length.
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_username_length_check;
alter table public.profiles
  add constraint profiles_username_length_check
    check (username is null or (char_length(username::text) >= 5 and char_length(username::text) <= 25));

-- ============================================================================
-- Done. Country seeding happens in a separate seed file (160+ rows).
-- ============================================================================
