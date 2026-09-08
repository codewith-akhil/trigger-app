// Edge function: set-auto-delete
// ----------------------------------------------------------------------------
// Shared "Auto delete" (disappearing messages) setting for a conversation.
//
// Either participant may change it or turn it off. The setting lives on the
// SINGLE shared conversations row (not per-user), so both sides see the same
// duration. `disappearing_updated_at` is stamped on every change: the DB
// cleanup cron only deletes messages created AFTER that instant, so enabling
// auto delete never wipes existing history ("delete from the activated time,
// dont auto delete older messages").
//
// Auth: requires a valid Supabase JWT; caller must be the conversation's
// owner or peer.
// Rate limit: 20 requests / 60s.
//
// Request body: { "conversationId": string, "duration": "OFF"|"24H"|"7D"|"30D" }
// Response 200: { "ok": true, "duration": string, "activatedAt": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const AUTO_DELETE_LIMIT = { maxRequests: 20, windowSeconds: 60, name: "set_auto_delete" };

const VALID_DURATIONS = ["OFF", "24H", "7D", "30D"];

interface Body {
  conversationId?: string;
  duration?: string;
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

  const rl = checkRateLimit(req, userId, AUTO_DELETE_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  if (!isUuid(body.conversationId)) {
    return errorResponse("conversationId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
  }
  const duration = (body.duration ?? "").toUpperCase();
  if (!VALID_DURATIONS.includes(duration)) {
    return errorResponse("duration must be one of OFF|24H|7D|30D", 422, ErrorCode.VALIDATION_FAILED);
  }

  const admin = createAdminClient();

  // Participant check on the SHARED row (owner or peer).
  const { data: conv, error: convErr } = await admin
    .from("conversations")
    .select("id")
    .eq("id", body.conversationId)
    .or(`owner_id.eq.${userId},peer_id.eq.${userId}`)
    .limit(1);
  if (convErr) {
    console.error("set-auto-delete participant check failed", convErr);
    return errorResponse("Failed to resolve conversation", 500, ErrorCode.INTERNAL_ERROR);
  }
  if (!conv || conv.length === 0) {
    return errorResponse("Not a participant in this conversation", 403, ErrorCode.FORBIDDEN);
  }

  const activatedAt = new Date().toISOString();
  const { error: updErr } = await admin
    .from("conversations")
    .update({
      disappearing_duration: duration,
      disappearing_updated_at: activatedAt,
    })
    .eq("id", body.conversationId);
  if (updErr) {
    console.error("set-auto-delete update failed", updErr);
    return errorResponse("Failed to update auto delete", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ ok: true, duration, activatedAt });
}

serve(handler, { port: 9037 });
