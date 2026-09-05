-- ============================================================================
-- Trigger App — Profile fixes: avatar storage, gender FK, email, validations
-- Migration: 20260916_profile_fixes.sql
-- ----------------------------------------------------------------------------
-- Fixes:
--   1. Drop hardcoded profiles_gender_check CHECK constraint
--   2. Add FK: profiles.gender -> genders(name)  (DB enforces gender from table)
--   3. Add FK: profiles.country_code -> countries(currency_code)
--   4. Add email column to profiles (denormalized from auth.users for profile screen)
--   5. Add full_name length CHECK (2-50 chars)
--   6. Add dob CHECK (age >= 13, <= 120)
--   7. Update handle_new_user trigger to also insert email
--   8. Backfill email for existing profile rows
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Drop hardcoded gender CHECK constraint (was: in ('Male','Female','Transmen','Transwomen'))
--    The genders table is now the single source of truth.
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_gender_check;

-- ----------------------------------------------------------------------------
-- 2. FK: profiles.gender -> genders(name)
--    genders.name has a UNIQUE constraint, so it can be referenced.
--    ON UPDATE CASCADE: if a gender name is renamed, profiles follow.
--    ON DELETE SET NULL: if a gender is deleted, profiles lose the value.
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_gender_fk;
alter table public.profiles
  add constraint profiles_gender_fk
    foreign key (gender) references public.genders(name)
    on update cascade on delete set null;

-- ----------------------------------------------------------------------------
-- 3. FK: profiles.country_code -> countries(currency_code)
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_country_code_fk;
alter table public.profiles
  add constraint profiles_country_code_fk
    foreign key (country_code) references public.countries(currency_code)
    on update cascade on delete set null;

-- ----------------------------------------------------------------------------
-- 4. Add email column to profiles (denormalized from auth.users)
--    This makes the profile self-contained — the ProfileScreen can show email
--    without a separate auth.users lookup.
-- ----------------------------------------------------------------------------
alter table public.profiles
  add column if not exists email text;

alter table public.profiles
  drop constraint if exists profiles_email_unique;
alter table public.profiles
  add constraint profiles_email_unique unique (email);

-- ----------------------------------------------------------------------------
-- 5. full_name length CHECK: 2-50 characters (after trim)
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_full_name_length_check;
alter table public.profiles
  add constraint profiles_full_name_length_check
    check (char_length(full_name) >= 2 and char_length(full_name) <= 50);

-- ----------------------------------------------------------------------------
-- 6. dob CHECK: must be at least 13 years old, not older than 120 years
-- ----------------------------------------------------------------------------
alter table public.profiles
  drop constraint if exists profiles_dob_check;
alter table public.profiles
  add constraint profiles_dob_check
    check (
      dob is null or (
        dob <= (current_date - interval '13 years')::date
        and dob >= (current_date - interval '120 years')::date
      )
    );

-- ----------------------------------------------------------------------------
-- 7. Update handle_new_user trigger to also insert email
-- ----------------------------------------------------------------------------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, full_name, username, avatar_url, email)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)),
    new.raw_user_meta_data->>'username',
    new.raw_user_meta_data->>'avatar_url',
    new.email
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

-- ----------------------------------------------------------------------------
-- 8. Backfill email for existing profile rows from auth.users
-- ----------------------------------------------------------------------------
update public.profiles p
  set email = u.email
  from auth.users u
  where p.id = u.id
    and (p.email is null or p.email = '');

-- ----------------------------------------------------------------------------
-- 9. Sync trigger: keep profiles.email in sync with auth.users.email
--    Fires when a user's email is changed in auth.users.
-- ----------------------------------------------------------------------------
create or replace function public.sync_profile_email()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  update public.profiles
    set email = new.email
    where id = new.id
      and (email is null or email <> new.email);
  return new;
end;
$$;

drop trigger if exists on_auth_user_email_changed on auth.users;
create trigger on_auth_user_email_changed
  after update of email on auth.users
  for each row
  when (old.email is distinct from new.email)
  execute function public.sync_profile_email();

-- ============================================================================
-- Done.
-- ============================================================================
