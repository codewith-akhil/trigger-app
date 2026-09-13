-- =============================================================================
-- 20260913_feed_social.sql — Feed social layer: likes + comments end-to-end
--
-- Tables:
--   feed_post_likes    (post_id, user_id)        — one like per user per post
--   feed_comments      (id, post_id, author_id)  — ≤500 chars, newest-first feed
--   feed_comment_likes (comment_id, user_id)     — per-comment hearts
--
-- Counters (trigger-maintained, never trusted from client):
--   feed_posts.like_count, feed_posts.comment_count
--   feed_comments.like_count
--
-- RLS:
--   Visible posts = published AND (free OR author OR unlocked)  [paid wall]
--   Comments inherit post visibility; authors may delete own comment;
--   post authors may moderate (delete) any comment on their own post.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table if not exists public.feed_post_likes (
  post_id    uuid not null references public.feed_posts(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table if not exists public.feed_comments (
  id         uuid primary key default gen_random_uuid(),
  post_id    uuid not null references public.feed_posts(id) on delete cascade,
  author_id  uuid not null references auth.users(id) on delete cascade,
  body       text not null check (char_length(btrim(body)) between 1 and 500),
  created_at timestamptz not null default now()
);
create index if not exists feed_comments_post_created_idx
  on public.feed_comments (post_id, created_at desc);

create table if not exists public.feed_comment_likes (
  comment_id uuid not null references public.feed_comments(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (comment_id, user_id)
);
create index if not exists feed_post_likes_post_idx      on public.feed_post_likes (post_id);
create index if not exists feed_comment_likes_comment_idx on public.feed_comment_likes (comment_id);

-- ---------------------------------------------------------------------------
-- Counters
-- ---------------------------------------------------------------------------
alter table public.feed_posts
  add column if not exists like_count    int not null default 0 check (like_count >= 0),
  add column if not exists comment_count int not null default 0 check (comment_count >= 0);

alter table public.feed_comments
  add column if not exists like_count int not null default 0 check (like_count >= 0);

-- idempotent backfill from existing rows (no-ops on fresh installs)
update public.feed_posts p set
  like_count    = (select count(*) from public.feed_post_likes l where l.post_id = p.id),
  comment_count = (select count(*) from public.feed_comments c where c.post_id = p.id)
where p.like_count <> (select count(*) from public.feed_post_likes l where l.post_id = p.id)
   or p.comment_count <> (select count(*) from public.feed_comments c where c.post_id = p.id);

update public.feed_comments c set
  like_count = (select count(*) from public.feed_comment_likes l where l.comment_id = c.id)
where c.like_count <> (select count(*) from public.feed_comment_likes l where l.comment_id = c.id);

-- ---------------------------------------------------------------------------
-- Counter maintenance triggers (SECURITY DEFINER — counters are server-owned)
-- ---------------------------------------------------------------------------
create or replace function public.feed_bump_post_like_count()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    update public.feed_posts set like_count = like_count + 1 where id = new.post_id;
    return new;
  elsif tg_op = 'DELETE' then
    update public.feed_posts set like_count = greatest(like_count - 1, 0) where id = old.post_id;
    return old;
  end if;
  return null;
end $$;

create or replace function public.feed_bump_comment_count()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    update public.feed_posts set comment_count = comment_count + 1 where id = new.post_id;
    return new;
  elsif tg_op = 'DELETE' then
    update public.feed_posts set comment_count = greatest(comment_count - 1, 0) where id = old.post_id;
    return old;
  end if;
  return null;
end $$;

create or replace function public.feed_bump_comment_like_count()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    update public.feed_comments set like_count = like_count + 1 where id = new.comment_id;
    return new;
  elsif tg_op = 'DELETE' then
    update public.feed_comments set like_count = greatest(like_count - 1, 0) where id = old.comment_id;
    return old;
  end if;
  return null;
end $$;

drop trigger if exists feed_post_likes_count_trg    on public.feed_post_likes;
drop trigger if exists feed_comments_count_trg      on public.feed_comments;
drop trigger if exists feed_comment_likes_count_trg on public.feed_comment_likes;

create trigger feed_post_likes_count_trg
  after insert or delete on public.feed_post_likes
  for each row execute function public.feed_bump_post_like_count();

create trigger feed_comments_count_trg
  after insert or delete on public.feed_comments
  for each row execute function public.feed_bump_comment_count();

create trigger feed_comment_likes_count_trg
  after insert or delete on public.feed_comment_likes
  for each row execute function public.feed_bump_comment_like_count();

-- ---------------------------------------------------------------------------
-- Post visibility helper (free = public; paid = author or unlocked buyer)
-- ---------------------------------------------------------------------------
create or replace function public.feed_can_view_post(p_post uuid, p_user uuid)
returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.feed_posts fp
    where fp.id = p_post
      and fp.status = 'published'
      and (
        fp.post_type = 'free'
        or fp.author_id = p_user
        or exists (
          select 1 from public.post_unlocks u
          where u.post_id = fp.id and u.buyer_id = p_user
        )
      )
  );
$$;

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.feed_post_likes    enable row level security;
alter table public.feed_comments      enable row level security;
alter table public.feed_comment_likes enable row level security;

-- feed_post_likes -----------------------------------------------------------
drop policy if exists "own post likes are readable" on public.feed_post_likes;
create policy "own post likes are readable" on public.feed_post_likes
  for select to authenticated using (auth.uid() = user_id);

drop policy if exists "like a visible post" on public.feed_post_likes;
create policy "like a visible post" on public.feed_post_likes
  for insert to authenticated
  with check (
    auth.uid() = user_id
    and public.feed_can_view_post(post_id, auth.uid())
  );

drop policy if exists "unlike own" on public.feed_post_likes;
create policy "unlike own" on public.feed_post_likes
  for delete to authenticated using (auth.uid() = user_id);

-- feed_comments -------------------------------------------------------------
drop policy if exists "comments visible with post" on public.feed_comments;
create policy "comments visible with post" on public.feed_comments
  for select to authenticated
  using (public.feed_can_view_post(post_id, auth.uid()));

drop policy if exists "comment on visible post" on public.feed_comments;
create policy "comment on visible post" on public.feed_comments
  for insert to authenticated
  with check (
    auth.uid() = author_id
    and public.feed_can_view_post(post_id, auth.uid())
  );

drop policy if exists "delete own comment or post author moderates" on public.feed_comments;
create policy "delete own comment or post author moderates" on public.feed_comments
  for delete to authenticated
  using (
    auth.uid() = author_id
    or exists (
      select 1 from public.feed_posts fp
      where fp.id = feed_comments.post_id and fp.author_id = auth.uid()
    )
  );

-- feed_comment_likes --------------------------------------------------------
drop policy if exists "own comment likes readable" on public.feed_comment_likes;
create policy "own comment likes readable" on public.feed_comment_likes
  for select to authenticated using (auth.uid() = user_id);

drop policy if exists "like a visible comment" on public.feed_comment_likes;
create policy "like a visible comment" on public.feed_comment_likes
  for insert to authenticated
  with check (
    auth.uid() = user_id
    and exists (
      select 1
      from public.feed_comments c
      where c.id = comment_id
        and public.feed_can_view_post(c.post_id, auth.uid())
    )
  );

drop policy if exists "unlike own comment" on public.feed_comment_likes;
create policy "unlike own comment" on public.feed_comment_likes
  for delete to authenticated using (auth.uid() = user_id);
