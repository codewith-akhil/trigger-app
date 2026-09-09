// Edge function: get-peer-presence
// ----------------------------------------------------------------------------
// Privacy-aware presence lookup for the chat header subtitle.
//
// Returns the peer's online state + last-seen ONLY when the peer allows it:
//   - viewer must share an ACCEPTED conversation with the peer (same gate as
//     the user_presences RLS), otherwise visible=false;
//   - peer's user_settings.last_seen = 'nobody'            → visible=false;
//   - 'followers' (or legacy 'contacts') → visible only when the VIEWER
//     follows the PEER (viewer ∈ peer's followers);
//   - 'following' → visible only when the PEER follows the VIEWER
//     (viewer ∈ the peers the peer follows);
//   - 'everyone' → visible.
// When visible=false the client renders a BLANK subtitle (never "offline").
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 60 requests / 60s.
//
// Request body: { "peerId": string }
// Response 200: { "online": boolean, "lastSeenAt": string|null, "visible": boolean }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const PRESENCE_LIMIT = { maxRequests: 60, windowSeconds: 60, name: "get_peer_presence" };

interface Body {
  peerId?: string;
}

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, PRESENCE_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const peerId = body.peerId;
  if (!isUuid(peerId)) {
    return errorResponse("peerId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (peerId === userId) {
    return json({ online: false, lastSeenAt: null, visible: false });
  }

  const admin = createAdminClient();

  // 1. Gate: accepted conversation between viewer and peer.
  const { data: conv, error: convErr } = await admin
    .from("conversations")
    .select("id")
    .or(`and(owner_id.eq.${userId},peer_id.eq.${peerId}),and(owner_id.eq.${peerId},peer_id.eq.${userId})`)
    .eq("request_status", "accepted")
    .limit(1);
  if (convErr) {
    console.error("get-peer-presence conversation check failed", convErr);
    return errorResponse("Failed to resolve conversation", 500, ErrorCode.INTERNAL_ERROR);
  }
  if (!conv || conv.length === 0) {
    // No accepted chat → presence hidden (mirrors user_presences RLS).
    return json({ online: false, lastSeenAt: null, visible: false });
  }

  // 2. Peer's privacy setting (default 'everyone' when unset).
  //    'contacts' is a legacy token — the social graph replaced the contacts
  //    table as the relationship source, so it is treated as 'followers'.
  const { data: settings } = await admin
    .from("user_settings")
    .select("last_seen")
    .eq("user_id", peerId)
    .limit(1);
  const lastSeenSetting = settings?.[0]?.last_seen ?? "everyone";

  let visible = true;
  if (lastSeenSetting === "nobody") {
    visible = false;
  } else if (lastSeenSetting === "followers" || lastSeenSetting === "contacts") {
    // Audience = the peer's followers → the viewer must follow the peer.
    const { data: follow, error: followErr } = await admin
      .from("follows")
      .select("id")
      .eq("follower_id", userId)
      .eq("following_id", peerId)
      .maybeSingle();
    if (followErr) {
      console.error("get-peer-presence followers check failed", followErr);
      return errorResponse("Failed to resolve follow state", 500, ErrorCode.INTERNAL_ERROR);
    }
    visible = !!follow;
  } else if (lastSeenSetting === "following") {
    // Audience = users the peer follows → the peer must follow the viewer.
    const { data: follow, error: followErr } = await admin
      .from("follows")
      .select("id")
      .eq("follower_id", peerId)
      .eq("following_id", userId)
      .maybeSingle();
    if (followErr) {
      console.error("get-peer-presence following check failed", followErr);
      return errorResponse("Failed to resolve follow state", 500, ErrorCode.INTERNAL_ERROR);
    }
    visible = !!follow;
  }
  if (!visible) {
    return json({ online: false, lastSeenAt: null, visible: false });
  }

  // 3. Actual presence row.
  const { data: presence, error: pErr } = await admin
    .from("user_presences")
    .select("is_online,last_seen_at")
    .eq("user_id", peerId)
    .limit(1);
  if (pErr) {
    console.error("get-peer-presence presence fetch failed", pErr);
    return errorResponse("Failed to fetch presence", 500, ErrorCode.INTERNAL_ERROR);
  }

  const row = presence?.[0];
  return json({
    online: row?.is_online === true,
    lastSeenAt: row?.last_seen_at ?? null,
    visible: true,
  });
}

serve(handler, { port: 9036 });
