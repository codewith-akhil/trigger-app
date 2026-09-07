// Edge function: send-chat-notification
// ----------------------------------------------------------------------------
// Sends a push notification to the recipient of a chat message when they are
// offline (not currently in the chat). Called by the Android MessageService
// right after inserting a message row, OR by a Supabase Realtime trigger on
// the `messages` table.
//
// The function:
//   1. Resolves the recipient's user_id from the conversation peer_id.
//   2. Checks the recipient's `user_presences.is_online` flag — skip if online
//      AND `skipIfOnline` is true (default).
//   3. Looks up active FCM tokens for the recipient.
//   4. Sends the push via FCM with the message preview + conversation metadata.
//   5. Deactivates any invalid tokens returned by FCM.
//
// Auth: requires a valid Supabase JWT (the SENDER calls this).
// Rate limit: 60 requests / 60s per user (RATE_LIMITS.SEND_CHAT_NOTIFICATION).
//
// Request body:
//   {
//     "conversationId": string,    // required — the conversation UUID
//     "recipientId"?: string,      // optional override; resolved from conversation if absent
//     "senderName": string,        // required — display name for the notification title
//     "messagePreview": string,   // required — truncated body (e.g. "📷 Photo", "🎤 Voice message")
//     "messageType"?: string,      // TEXT|IMAGE|VIDEO|AUDIO|VOICE_NOTE|DOCUMENT|LOCATION|CONTACT|CALL_LOG
//     "messageId"?: string,        // optional, for de-dup
//     "skipIfOnline"?: boolean     // default true — skip if recipient is online
//   }
//
// Response 200: { "sent": number, "skipped": boolean, "deactivated": number, "errors": string[] }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit, RATE_LIMITS } from "../_shared/rate_limit.ts";
import { sendFcmBatch } from "../_shared/firebase.ts";

interface Body {
  conversationId?: string;
  recipientId?: string;
  senderName?: string;
  messagePreview?: string;
  messageType?: string;
  messageId?: string;
  skipIfOnline?: boolean;
}

// Truncate the preview for the FCM body (max ~100 chars for notification display).
function buildPreview(type: string | undefined, preview: string): string {
  const p = (preview ?? "").trim();
  switch (type) {
    case "IMAGE": return "📷 Photo";
    case "VIDEO": return "🎥 Video";
    case "AUDIO":
    case "VOICE_NOTE": return "🎤 Voice message";
    case "DOCUMENT": return "📄 Document";
    case "LOCATION": return "📍 Location";
    case "CONTACT": return "👤 Contact";
    case "CALL_LOG": return p || "📞 Call";
    default: return p.length > 100 ? p.slice(0, 97) + "…" : p;
  }
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const apiKey = req.headers.get("apikey") ?? "";

  // INTERNAL CALLS: send-message invokes this function with the service-role
  // key as Bearer. resolveUserId() -> auth.getUser() REJECTS that JWT (it has
  // no `sub`), so every server-originated push silently died with 401. Accept
  // the service-role key as a trusted internal caller; user JWTs still go
  // through resolveUserId.
  let senderId: string | null = null;
  let isInternal = false;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const bearer = (authHeader ?? "").replace(/^Bearer\s+/i, "").trim();
  if (serviceKey && (bearer === serviceKey || apiKey === serviceKey)) {
    isInternal = true;
  } else {
    senderId = await resolveUserId(authHeader);
  }
  if (!isInternal && !senderId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, senderId ?? "internal:send-message", RATE_LIMITS.SEND_CHAT_NOTIFICATION);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED);
  }

  if (!body.conversationId?.trim()) {
    return errorResponse("conversationId is required", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (!body.senderName?.trim()) {
    return errorResponse("senderName is required", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (!body.messagePreview?.trim() && !body.messageType) {
    return errorResponse("messagePreview (or messageType) is required", 422, ErrorCode.VALIDATION_FAILED);
  }

  const supabase = createAdminClient();

  // --- Resolve the recipient (always from the conversation — never trust
  // client-supplied recipientId to prevent cross-user notification spam) ---
  let recipientId: string | undefined;
  {
    // Look up the conversation, find the peer (the participant that isn't the sender).
    const { data: conv, error: convError } = await supabase
      .from("conversations")
      .select("owner_id, peer_id, peer_name")
      .eq("id", body.conversationId)
      .maybeSingle();
    if (convError) {
      console.error("conversation lookup failed", convError);
      return errorResponse("Failed to resolve conversation", 500, ErrorCode.INTERNAL_ERROR);
    }
    if (!conv) return errorResponse("Conversation not found", 404, ErrorCode.NOT_FOUND);
    if (isInternal) {
      // Server-originated (send-message) calls pass the recipient explicitly —
      // senderId is null here, and the old `owner===null ? peer : owner`
      // arithmetic resolved to the SENDER's own id for owner-initiated
      // conversations, so the push targeted the sender and got skipped as
      // "online".
      recipientId = (body as Record<string, unknown>).recipientId as string ?? null;
      if (!recipientId) {
        recipientId = conv.owner_id === (body as Record<string, unknown>).senderId ? conv.peer_id : conv.owner_id;
      }
    } else {
      // User-JWT callers must be a participant — previously a third party
      // resolved to the owner and could probe arbitrary conversations.
      if (senderId !== conv.owner_id && senderId !== conv.peer_id) {
        return errorResponse("Not a participant in this conversation", 403, ErrorCode.FORBIDDEN);
      }
      recipientId = conv.owner_id === senderId ? conv.peer_id : conv.owner_id;
    }
    if (!recipientId) {
      // Conversation has no real auth-user peer (e.g. a simulated contact) — nothing to push.
      return json({ sent: 0, skipped: true, reason: "no_recipient", deactivated: 0, errors: [] });
    }
  }

  if (recipientId === senderId) {
    // Don't notify yourself.
    return json({ sent: 0, skipped: true, reason: "self_message", deactivated: 0, errors: [] });
  }

  // --- Check online presence (skip if recipient is online + skipIfOnline) --
  const skipIfOnline = body.skipIfOnline !== false;
  if (skipIfOnline) {
    const { data: presence } = await supabase
      .from("user_presences")
      .select("is_online, last_seen_at")
      .eq("user_id", recipientId)
      .maybeSingle();
    if (presence?.is_online) {
      return json({ sent: 0, skipped: true, reason: "recipient_online", deactivated: 0, errors: [] });
    }
  }

  // --- Fetch active FCM tokens for the recipient ---------------------------
  const { data: tokenRows, error: tokenError } = await supabase
    .from("push_tokens")
    .select("fcm_token")
    .eq("user_id", recipientId)
    .eq("is_active", true);
  if (tokenError) {
    console.error("token lookup failed", tokenError);
    return errorResponse("Failed to look up push tokens", 500, ErrorCode.INTERNAL_ERROR);
  }
  const tokens = (tokenRows ?? []).map((r) => r.fcm_token).filter(Boolean);
  if (tokens.length === 0) {
    return json({ sent: 0, skipped: true, reason: "no_tokens", deactivated: 0, errors: [] });
  }

  // --- Send via FCM --------------------------------------------------------
  const preview = buildPreview(body.messageType, body.messagePreview ?? "");
  const results = await sendFcmBatch(
    {
      title: body.senderName,
      body: preview,
      data: {
        type: "chat_message",
        conversationId: body.conversationId,
        senderName: body.senderName,
        messageType: body.messageType ?? "TEXT",
        ...(body.messageId ? { messageId: body.messageId } : {}),
      },
      androidChannelId: "trigger_chat_messages",
      priority: "high",
    },
    tokens,
  );

  // --- Deactivate invalid tokens ------------------------------------------
  const invalidTokens = results.filter((r) => r.invalidToken).map((r) => r.token);
  if (invalidTokens.length > 0) {
    await supabase
      .from("push_tokens")
      .update({ is_active: false })
      .in("fcm_token", invalidTokens);
  }

  const sent = results.filter((r) => r.name).length;
  const errors = results.filter((r) => r.error && !r.invalidToken).map((r) => r.error!);

  return json({ sent, skipped: false, deactivated: invalidTokens.length, errors });
}

serve(handler, { port: 9018 });
