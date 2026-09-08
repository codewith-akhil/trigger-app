// Edge function: mark-messages-delivered
// ----------------------------------------------------------------------------
// DELIVERED receipt: the RECEIVER calls this after an incoming message lands
// (realtime INSERT or history pull). The security_definer RPC
// `mark_messages_delivered` flips status SENT→DELIVERED (+ delivered_at) for
// the caller's incoming messages in the conversation only — the sender's
// device then receives the realtime UPDATE and renders the double grey tick.
//
// Auth: requires a valid Supabase JWT (the RPC uses auth.uid()).
// Rate limit: 120 requests / 60s (fires per incoming batch, must be cheap).
//
// Request body: { "conversationId": string }
// Response 200: { "marked": true, "messagesDelivered": number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createUserClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const MARK_DELIVERED_LIMIT = { maxRequests: 120, windowSeconds: 60, name: "mark_messages_delivered" };

interface Body {
  conversationId?: string;
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

  const rl = checkRateLimit(req, userId, MARK_DELIVERED_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  if (!isUuid(body.conversationId)) {
    return errorResponse("conversationId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
  }

  const userClient = createUserClient(authHeader);
  const { data, error } = await userClient.rpc("mark_messages_delivered", {
    p_conversation_id: body.conversationId,
  });
  if (error) {
    console.error("mark_messages_delivered failed", error);
    return errorResponse("Failed to mark messages delivered", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ marked: true, messagesDelivered: data ?? 0 });
}

serve(handler, { port: 9035 });
