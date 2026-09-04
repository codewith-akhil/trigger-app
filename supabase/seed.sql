-- ============================================================================
-- Trigger App — Reference seed data (languages + countries)
-- Run with: supabase db push  (seed.sql runs after migrations on first link)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- languages — mirrors the static list in model/Language.kt (15 languages)
-- ----------------------------------------------------------------------------
create table if not exists public.languages (
  code        varchar(5) primary key,
  title       text not null,
  subtitle    text,
  created_at  timestamptz not null default now()
);

insert into public.languages (code, title, subtitle) values
  ('en', 'English',  'English'),
  ('hi', 'हिन्दी',    'Hindi'),
  ('mr', 'मराठी',    'Marathi'),
  ('gu', 'ગુજરાતી',  'Gujarati'),
  ('ta', 'தமிழ்',    'Tamil'),
  ('bn', 'বাংলা',    'Bengali'),
  ('te', 'తెలుగు',  'Telugu'),
  ('kn', 'ಕನ್ನಡ',    'Kannada'),
  ('ml', 'മലയാളം',  'Malayalam'),
  ('pa', 'ਪੰਜਾਬੀ',   'Punjabi'),
  ('ur', 'اردو',     'Urdu'),
  ('es', 'Español',  'Spanish'),
  ('fr', 'Français', 'French'),
  ('de', 'Deutsch',  'German'),
  ('pt', 'Português','Portuguese')
on conflict (code) do nothing;

alter table public.languages enable row level security;
drop policy if exists "languages_public_read" on public.languages;
create policy "languages_public_read" on public.languages
  for select to anon, authenticated using (true);

-- ----------------------------------------------------------------------------
-- countries — mirrors model/Country.kt (dial codes for PhoneAuthScreen /
-- CountrySelectionScreen)
-- ----------------------------------------------------------------------------
create table if not exists public.countries (
  iso         char(2) primary key,
  name        text not null,
  dial_code   varchar(8) not null,
  flag_emoji  text,
  created_at  timestamptz not null default now()
);

insert into public.countries (iso, name, dial_code, flag_emoji) values
  ('IN','India','+91','🇮🇳'),
  ('US','United States','+1','🇺🇸'),
  ('GB','United Kingdom','+44','🇬🇧'),
  ('CA','Canada','+1','🇨🇦'),
  ('AU','Australia','+61','🇦🇺'),
  ('AE','United Arab Emirates','+971','🇦🇪'),
  ('SA','Saudi Arabia','+966','🇸🇦'),
  ('SG','Singapore','+65','🇸🇬'),
  ('MY','Malaysia','+60','🇲🇾'),
  ('PH','Philippines','+63','🇵🇭'),
  ('ID','Indonesia','+62','🇮🇩'),
  ('PK','Pakistan','+92','🇵🇰'),
  ('BD','Bangladesh','+880','🇧🇩'),
  ('LK','Sri Lanka','+94','🇱🇰'),
  ('NP','Nepal','+977','🇳🇵'),
  ('DE','Germany','+49','🇩🇪'),
  ('FR','France','+33','🇫🇷'),
  ('ES','Spain','+34','🇪🇸'),
  ('IT','Italy','+39','🇮🇹'),
  ('PT','Portugal','+351','🇵🇹'),
  ('NL','Netherlands','+31','🇳🇱'),
  ('BE','Belgium','+32','🇧🇪'),
  ('CH','Switzerland','+41','🇨🇭'),
  ('SE','Sweden','+46','🇸🇪'),
  ('NO','Norway','+47','🇳🇴'),
  ('DK','Denmark','+45','🇩🇰'),
  ('FI','Finland','+358','🇫🇮'),
  ('IE','Ireland','+353','🇮🇪'),
  ('ZA','South Africa','+27','🇿🇦'),
  ('NG','Nigeria','+234','🇳🇬'),
  ('KE','Kenya','+254','🇰🇪'),
  ('EG','Egypt','+20','🇪🇬'),
  ('BR','Brazil','+55','🇧🇷'),
  ('MX','Mexico','+52','🇲🇽'),
  ('AR','Argentina','+54','🇦🇷'),
  ('CL','Chile','+56','🇨🇱'),
  ('CO','Colombia','+57','🇨🇴'),
  ('PE','Peru','+51','🇵🇪'),
  ('NZ','New Zealand','+64','🇳🇿'),
  ('JP','Japan','+81','🇯🇵'),
  ('KR','South Korea','+82','🇰🇷'),
  ('CN','China','+86','🇨🇳'),
  ('HK','Hong Kong','+852','🇭🇰'),
  ('TH','Thailand','+66','🇹🇭'),
  ('VN','Vietnam','+84','🇻🇳'),
  ('RU','Russia','+7','🇷🇺'),
  ('TR','Turkey','+90','🇹🇷'),
  ('IL','Israel','+972','🇮🇱')
on conflict (iso) do nothing;

alter table public.countries enable row level security;
drop policy if exists "countries_public_read" on public.countries;
create policy "countries_public_read" on public.countries
  for select to anon, authenticated using (true);
