// Edge function: mark-conversation-read
// ----------------------------------------------------------------------------
// Marks all unread messages in a conversation as read by the caller (sets
// messages.read_at + conversations.unread_count=0 + last_read_at=now()). Used
// when the user opens a chat — replaces the in-memory `markConversationRead`
// Room-only call so read state syncs across devices + triggers remote
// read-receipt push (the sender's MessageService can subscribe to the
// messages table to update the double-tick to blue).
//
// Calls the security_definer RPC `mark_conversation_read` which:
//   - Updates messages where conversation_id = X AND sender_id <> auth.uid()
//     AND read_at IS NULL → read_at = now().
//   - Updates the conversation's last_read_at + sets unread_count = 0
//     (only for the caller's row, since conversations are owner-scoped).
//
// Auth: requires a valid Supabase JWT (the RPC uses auth.uid()).
// Rate limit: 30 requests / 60s.
//
// Request body:
//   { "conversationId": string }   // required UUID
//
// Response 200: { "marked": true, "messagesRead": number }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createUserClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const MARK_READ_LIMIT = { maxRequests: 30, windowSeconds: 60, name: "mark_conversation_read" };

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

  const rl = checkRateLimit(req, userId, MARK_READ_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  if (!isUuid(body.conversationId)) {
    return errorResponse("conversationId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
  }

  // Use the USER's JWT so the security_definer RPC's auth.uid() resolves.
  const userClient = createUserClient(authHeader);
  const { data, error } = await userClient.rpc("mark_conversation_read", {
    p_conversation_id: body.conversationId,
  });
  if (error) {
    console.error("mark_conversation_read failed", error);
    return errorResponse("Failed to mark conversation as read", 500, ErrorCode.INTERNAL_ERROR);
  }

  // The RPC returns the number of messages that were updated (integer).
  return json({ marked: true, messagesRead: data ?? 0 });
}

serve(handler, { port: 9024 });
