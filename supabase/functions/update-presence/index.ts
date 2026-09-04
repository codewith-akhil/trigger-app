// Edge function: update-presence
// ----------------------------------------------------------------------------
// Heartbeat for online/last-seen presence. The Android PresenceService is
// currently a stub (setUserTyping/setUserRecording are no-ops; setNetworkConnected
// fakes RECONNECTING→ONLINE). This function persists the caller's presence to
// the `user_presences` table so other users can see "online" / "last seen at".
//
// The Android client should call this:
//   - On app foreground → { isOnline: true }
//   - On app background / onPause → { isOnline: false }
//   - Every 30s while foregrounded → { isOnline: true } (heartbeat)
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 30 requests / 60s (heartbeats are cheap).
//
// Request body:
//   { "isOnline"?: boolean }   // default true
//
// Response 200: { "updated": true, "isOnline": boolean, "lastSeenAt": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, createUserClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const PRESENCE_LIMIT = { maxRequests: 30, windowSeconds: 60, name: "update_presence" };

interface Body {
  isOnline?: boolean;
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

  let body: Body = {};
  try { body = await req.json(); } catch { /* empty body defaults to online */ }
  const isOnline = body.isOnline !== false;

  // Use the USER's JWT (not the service role) so auth.uid() resolves correctly
  // inside the security_definer RPC. The service role has no user identity.
  const userClient = createUserClient(authHeader);
  const { error } = await userClient.rpc("upsert_presence", { p_is_online: isOnline });
  if (error) {
    console.error("update-presence failed", error);
    return errorResponse("Failed to update presence", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ updated: true, isOnline, lastSeenAt: new Date().toISOString() });
}

serve(handler, { port: 9020 });
