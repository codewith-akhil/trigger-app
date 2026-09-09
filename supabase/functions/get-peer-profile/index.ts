// Edge function: get-peer-profile
// ----------------------------------------------------------------------------
// Privacy-enforcing profile fetch for viewing ANOTHER user's profile.
//
// WHY THIS EXISTS: profiles is row-readable by authenticated users (identity
// row: name/username — needed by notifications/social surfaces), which means
// avatar_url + about must be filtered in the SERVICE layer, not the client.
// The old flow (UserProfileScreen reading `profiles` directly) leaked the
// avatar and About of users whose privacy settings restricted them.
//
// Enforcement (mirrors get-peer-presence/search-users semantics):
//   profile_photo_visibility / about_visibility ∈
//     'everyone' | 'followers' (≡ legacy 'contacts') | 'following' | 'nobody'
//   'followers' → the viewer must follow the owner
//   'following' → the owner must follow the viewer
// Hidden fields are returned as "" (photo) / "" (about) so the client keeps
// its letter-avatar / hidden-About fallbacks.
//
// Also returns follow state + counts (merges get-follow-info) so the profile
// screen needs a single call.
//
// Request: { "peerId": uuid }  (self is allowed — nothing is redacted)
// Response 200: {
//   profile: { id, username, full_name, about, avatar_url },
//   followersCount, followingCount, isFollowing, followsYou
// }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

/** Resolves whether the viewer passes the owner's visibility level. */
async function viewerSeesField(
  supabase: ReturnType<typeof createAdminClient>,
  viewerId: string,
  ownerId: string,
  visibility: string | null | undefined,
): Promise<boolean> {
  const level = visibility ?? "everyone";
  if (level === "everyone") return true;
  if (level === "nobody") return false;
  if (level === "followers" || level === "contacts") {
    // The VIEWER must follow the owner.
    const { data: edge } = await supabase
      .from("follows")
      .select("id")
      .eq("follower_id", viewerId)
      .eq("following_id", ownerId)
      .maybeSingle();
    return !!edge;
  }
  if (level === "following") {
    // The OWNER must follow the viewer.
    const { data: edge } = await supabase
      .from("follows")
      .select("id")
      .eq("follower_id", ownerId)
      .eq("following_id", viewerId)
      .maybeSingle();
    return !!edge;
  }
  return false;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: { peerId?: string };
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const peerId = body.peerId ?? "";
  if (!isUuid(peerId)) return json({ error: "peerId is required" }, 422, ErrorCode.VALIDATION_ERROR);

  const supabase = createAdminClient();

  const { data: peer, error: peerError } = await supabase
    .from("profiles")
    .select("id, full_name, username, about, avatar_url")
    .eq("id", peerId)
    .maybeSingle();
  if (peerError) {
    console.error("get-peer-profile profile lookup failed", peerError);
    return json({ error: "Lookup failed" }, 500);
  }
  if (!peer) return json({ error: "User not found" }, 404, ErrorCode.NOT_FOUND);

  const { data: settings } = await supabase
    .from("user_settings")
    .select("profile_photo_visibility,about_visibility")
    .eq("user_id", peerId)
    .maybeSingle();

  let about = peer.about ?? "";
  let avatarUrl = peer.avatar_url ?? "";

  if (userId !== peerId) {
    const [photoVisible, aboutVisible] = await Promise.all([
      viewerSeesField(supabase, userId, peerId, settings?.profile_photo_visibility),
      viewerSeesField(supabase, userId, peerId, settings?.about_visibility),
    ]);
    if (!photoVisible) avatarUrl = "";
    if (!aboutVisible) about = "";
  }

  const [mineRes, theirsRes, followersRes, followingRes] = await Promise.all([
    supabase.from("follows").select("id").eq("follower_id", userId).eq("following_id", peerId).maybeSingle(),
    supabase.from("follows").select("id").eq("follower_id", peerId).eq("following_id", userId).maybeSingle(),
    supabase.from("follows").select("id", { count: "exact", head: true }).eq("following_id", peerId),
    supabase.from("follows").select("id", { count: "exact", head: true }).eq("follower_id", peerId),
  ]);

  return json({
    profile: {
      id: peer.id,
      username: peer.username ?? "",
      full_name: peer.full_name ?? "",
      about,
      avatar_url: avatarUrl,
    },
    followersCount: followersRes.count ?? 0,
    followingCount: followingRes.count ?? 0,
    isFollowing: !!mineRes.data,
    followsYou: !!theirsRes.data,
  });
}
serve(handler, { port: 9043 });
