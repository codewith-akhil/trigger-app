// Edge function: get-contacts
// Returns the caller's contacts (users who have accepted message requests) + their profiles.
// Avatar URLs are redacted to "" when the contact's profile_photo_visibility
// forbids the caller (default 'everyone'; legacy 'contacts' ≡ 'followers').
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

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

  const supabase = createAdminClient();

  // Get contact user IDs
  const { data: contacts, error: cError } = await supabase
    .from("contacts")
    .select("contact_user_id")
    .eq("user_id", userId);

  if (cError) return json({ error: "Failed to fetch contacts" }, 500);

  if (!contacts || contacts.length === 0) return json({ contacts: [] });

  // Fetch profiles for those contacts
  const contactIds = contacts.map(c => c.contact_user_id);
  const { data: profiles, error: pError } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url, is_online, last_seen_at")
    .in("id", contactIds);

  if (pError) return json({ error: "Failed to fetch contact profiles" }, 500);

  // Redact avatar_url for contacts whose profile_photo_visibility the caller
  // may not see ("" — NOT null — so the client keeps its letter fallback).
  const rows = profiles ?? [];
  const visibleOwners = await resolveVisibleProfileOwners(
    supabase,
    userId,
    rows.map(r => r.id),
  );
  const safeRows = rows.map(r =>
    visibleOwners.has(r.id) ? r : { ...r, avatar_url: "" },
  );
  return json({ contacts: safeRows });
}
serve(handler, { port: 9036 });
