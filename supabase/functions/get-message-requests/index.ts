// Edge function: get-message-requests
// Returns pending message requests for the caller, including the canonical
// conversation id + how many messages the sender has sent so far (the
// pre-accept thread preview), so the client can open the pending chat and
// show "N/3 messages".
// Sender avatars are redacted to "" when the sender's profile_photo_visibility
// forbids the caller (default 'everyone'; legacy 'contacts' ≡ 'followers').
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
const REQUESTS_LIMIT = { maxRequests: 60, windowSeconds: 60, name: "get_message_requests" };

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
  const rl = checkRateLimit(req, userId, REQUESTS_LIMIT);
  if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const supabase = createAdminClient();
  const { data, error } = await supabase
    .from("message_requests")
    .select("id, sender_id, sender_name, sender_username, sender_avatar_url, initial_message, conversation_id, created_at")
    .eq("receiver_id", userId)
    .eq("status", "pending")
    .order("created_at", { ascending: false })
    .limit(50);

  if (error) return json({ error: "Failed to fetch requests" }, 500);

  // Attach the pre-accept message count per request (batched, one query per
  // request row is avoided by grouping on distinct conversation ids).
  const requests = data ?? [];
  const convIds = Array.from(new Set(requests.map(r => r.conversation_id).filter(Boolean))) as string[];
  const counts: Record<string, number> = {};
  if (convIds.length > 0) {
    const { data: msgs } = await supabase
      .from("messages")
      .select("conversation_id, sender_id")
      .in("conversation_id", convIds)
      .limit(500);
    for (const m of msgs ?? []) {
      if (m.sender_id && requests.some(r => r.sender_id === m.sender_id && r.conversation_id === m.conversation_id)) {
        counts[m.conversation_id] = (counts[m.conversation_id] ?? 0) + 1;
      }
    }
  }

  // Redact sender avatars whose profile_photo_visibility the caller may not
  // see ("" — NOT null — so the client keeps its letter fallback).
  const visibleSenders = await resolveVisibleProfileOwners(
    supabase,
    userId,
    requests.map(r => r.sender_id),
  );

  return json({
    requests: requests.map(r => ({
      ...r,
      sender_avatar_url: visibleSenders.has(r.sender_id) ? r.sender_avatar_url : "",
      message_count: r.conversation_id ? (counts[r.conversation_id] ?? 0) : 0,
    })),
  });
}
serve(handler, { port: 9035 });
