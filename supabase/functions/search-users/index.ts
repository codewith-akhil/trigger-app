// Edge function: search-users
// Search by username OR full name OR phone. Returns matching users (excluding
// self). Presence fields are NEVER returned here — online/last seen are only
// visible inside an accepted conversation (see user_presences RLS).
//
// Security:
//   - Requires a valid Supabase JWT.
//   - Rate-limited per IP (20 requests / 5 min) to slow enumeration.
//   - Query is validated against a strict allow-list before being used in a
//     PostgREST .or() filter — commas/parens/quotes (filter-injection chars)
//     are stripped, so interpolation is safe.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

interface Body { query?: string; }

// 20 searches per 5 minutes per IP — generous enough for normal use, tight
// enough to slow username enumeration attacks.
const SEARCH_LIMIT = { maxRequests: 20, windowSeconds: 300, name: "search_users" };

// ----------------------------------------------------------------------------
// Avatar/about visibility — resolves which of `ownerIds` allow `viewerId` to
// see their profile_photo/about, per each owner's user_settings row
// (default 'everyone' when no settings row; legacy 'contacts' ≡ 'followers').
//   'followers' → the viewer must follow the owner
//   'following' → the owner must follow the viewer
// ----------------------------------------------------------------------------
async function resolveVisibleProfileOwners(
  supabase: ReturnType<typeof createAdminClient>,
  viewerId: string,
  ownerIds: string[],
): Promise<Set<string>> {
  const visible = new Set<string>();
  const unique = Array.from(new Set(ownerIds));
  if (unique.length === 0) return visible;

  const { data: settings } = await supabase
    .from("user_settings")
    .select("user_id,profile_photo_visibility,about_visibility")
    .in("user_id", unique);

  const levelOf = new Map<string, string>();
  for (const id of unique) levelOf.set(id, "everyone");
  for (const s of settings ?? []) {
    levelOf.set(s.user_id, s.profile_photo_visibility ?? "everyone");
  }

  const followersLevel = unique.filter((id) => {
    const l = levelOf.get(id);
    return l === "followers" || l === "contacts";
  });
  const followingLevel = unique.filter((id) => levelOf.get(id) === "following");

  if (followersLevel.length > 0) {
    const { data: edges } = await supabase
      .from("follows")
      .select("following_id")
      .eq("follower_id", viewerId)
      .in("following_id", followersLevel);
    for (const e of edges ?? []) visible.add(e.following_id);
  }
  if (followingLevel.length > 0) {
    const { data: edges } = await supabase
      .from("follows")
      .select("follower_id")
      .eq("following_id", viewerId)
      .in("follower_id", followingLevel);
    for (const e of edges ?? []) visible.add(e.follower_id);
  }

  for (const id of unique) {
    if (levelOf.get(id) === "everyone") visible.add(id);
  }
  return visible;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const rl = checkRateLimit(req, ip, SEARCH_LIMIT);
  if (!rl.allowed) {
    return json(
      { error: `Too many search requests. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter },
      429,
    );
  }

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  // --- Sanitize + validate the query ----------------------------------------
  const raw = (body.query ?? "").trim().replace(/^@/, "").replace(/\s+/g, " ");
  if (raw.length < 2 || raw.length > 50) return json({ users: [] });
  // Allow username/name chars + spaces; strip every PostgREST filter-injection
  // character (, ( ) " :) entirely so .or() interpolation stays safe.
  const safe = raw.replace(/[,()":\\*]/g, "");
  if (safe.length < 2 || !/^[A-Za-z0-9 ._\-']+$/.test(safe)) return json({ users: [] });

  const supabase = createAdminClient();

  // Username or full-name match (case-insensitive). If the query looks like a
  // phone number, match phone digits too.
  const orParts = [`username.ilike.%${safe}%`, `full_name.ilike.%${safe}%`];
  const digits = raw.replace(/[^0-9]/g, "");
  if (digits.length >= 6 && digits.length <= 15) {
    orParts.push(`phone.ilike.%${digits}%`);
  }

  // Presence is deliberately NOT selected — search results must not leak
  // online/last-seen for users who are not accepted contacts.
  const { data, error } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url")
    .neq("id", userId)
    .or(orParts.join(","))
    .limit(20);

  if (error) {
    console.error("search-users failed", error);
    return json({ error: "Search failed" }, 500);
  }

  // Redact avatar_url for owners whose profile_photo_visibility the caller
  // may not see ("" — NOT null — so the client keeps its letter fallback).
  const rows = data ?? [];
  const visibleOwners = await resolveVisibleProfileOwners(
    supabase,
    userId,
    rows.map((r) => r.id),
  );
  const users = rows.map((r) =>
    visibleOwners.has(r.id) ? r : { ...r, avatar_url: "" },
  );
  return json({ users });
}
serve(handler, { port: 9032 });
