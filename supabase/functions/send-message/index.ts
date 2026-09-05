// Edge function: send-message
// ----------------------------------------------------------------------------
// Inserts a message into the messages table with SERVER-SIDE validation.
// The client is untrusted — all validation happens here.
//
// Also triggers a push notification to the receiver via send-chat-notification.
//
// Auth: requires a valid Supabase JWT.
// Request body: all message fields (conversation_id, type, text, media_url, etc.)
// Response 200: { "sent": true, "message": {...} }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const SEND_MSG_LIMIT = { maxRequests: 60, windowSeconds: 60, name: "send_message" };

// Server-side file size limits (bytes) — authoritative, client cannot override.
const MAX_IMAGE = 50 * 1024 * 1024;   // 50 MB
const MAX_VIDEO = 250 * 1024 * 1024;  // 250 MB
const MAX_AUDIO = 55 * 1024 * 1024;   // 55 MB
const MAX_DOC = 55 * 1024 * 1024;     // 55 MB

const VALID_TYPES = ["TEXT", "IMAGE", "VIDEO", "AUDIO", "VOICE_NOTE", "DOCUMENT", "LOCATION", "CONTACT", "CALL_LOG", "SYSTEM"];

interface Body {
  conversation_id?: string;
  peer_id?: string;
  peer_name?: string;
  type?: string;
  text?: string;
  media_url?: string;
  media_thumbnail?: string;
  media_bucket?: string;
  file_name?: string;
  file_size?: number;
  mime_type?: string;
  media_duration_sec?: number;
  is_view_once?: boolean;
  reply_to_id?: string;
  timestamp_millis?: number;
  location_lat?: number;
  location_lng?: number;
  location_address?: string;
  location_live_minutes?: number;
  location_comment?: string;
  contact_name?: string;
  contact_phone?: string;
  call_type?: string;
  call_duration_sec?: number;
  idempotency_key?: string;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, SEND_MSG_LIMIT);
  if (!rl.allowed) {
    return json({ error: `Rate limited. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return json({ error: "Invalid body", code: ErrorCode.VALIDATION_FAILED }, 400); }

  // --- Server-side validation ---
  // Either conversation_id OR peer_id must be provided (peer_id auto-creates).
  if (!body.conversation_id && !body.peer_id) {
    return json({ error: "conversation_id or peer_id is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  }
  if (!body.type || !VALID_TYPES.includes(body.type)) return json({ error: "Invalid message type", code: ErrorCode.VALIDATION_FAILED }, 422);

  // Text validation
  if (body.type === "TEXT" && (!body.text || body.text.trim().length === 0)) {
    return json({ error: "Message text cannot be empty", code: ErrorCode.VALIDATION_FAILED }, 422);
  }
  if (body.text && body.text.length > 10000) {
    return json({ error: "Message too long (max 10000 chars)", code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  // Live-location metadata validation (optional, LOCATION messages only)
  if (body.location_live_minutes != null) {
    if (!Number.isInteger(body.location_live_minutes) || body.location_live_minutes < 1 || body.location_live_minutes > 1440) {
      return json({ error: "location_live_minutes must be an integer between 1 and 1440", code: ErrorCode.VALIDATION_FAILED }, 422);
    }
  }
  if (body.location_comment != null && typeof body.location_comment === "string" && body.location_comment.length > 500) {
    return json({ error: "location_comment too long (max 500 chars)", code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  // File size validation (server-side authoritative)
  const fileSize = body.file_size ?? 0;
  if (fileSize > 0) {
    const type = body.type;
    let max = 0;
    if (type === "IMAGE") max = MAX_IMAGE;
    else if (type === "VIDEO") max = MAX_VIDEO;
    else if (type === "AUDIO" || type === "VOICE_NOTE") max = MAX_AUDIO;
    else if (type === "DOCUMENT") max = MAX_DOC;
    if (max > 0 && fileSize > max) {
      return json({ error: `File too large. Max ${max / 1024 / 1024}MB for ${type}`, code: ErrorCode.VALIDATION_FAILED }, 422);
    }
  }

  // Resolve the conversation: try by conversation_id first, then by (owner, peer),
  // then auto-create if peer_id is provided.
  const supabase = createAdminClient();
  let conv: { id: string; owner_id: string; peer_id: string | null; request_status: string | null } | null = null;
  let conversationId = body.conversation_id ?? "";

  if (conversationId) {
    const { data: found } = await supabase
      .from("conversations")
      .select("id, owner_id, peer_id, request_status")
      .eq("id", conversationId)
      .maybeSingle();
    if (found) conv = found;
  }

  // If not found by conversation_id, try find-or-create by peer_id
  if (!conv && body.peer_id) {
    // Look for an existing conversation between this user and the peer
    const { data: existing } = await supabase
      .from("conversations")
      .select("id, owner_id, peer_id, request_status")
      .or(`owner_id.eq.${userId},peer_id.eq.${userId}`)
      .eq("peer_id", body.peer_id)
      .limit(1);
    if (existing && existing.length > 0) {
      conv = existing[0];
      conversationId = conv.id;
    } else {
      // Auto-create a new conversation
      const { data: newConv, error: createErr } = await supabase
        .from("conversations")
        .insert({
          owner_id: userId,
          peer_id: body.peer_id,
          peer_name: body.peer_name ?? "Unknown",
          request_status: "accepted",
          is_group: false,
        })
        .select("id, owner_id, peer_id, request_status")
        .single();
      if (createErr || !newConv) {
        console.error("send-message: failed to auto-create conversation", createErr);
        return json({ error: "Conversation not found and could not be created. Provide a valid conversation_id or peer_id.", code: ErrorCode.NOT_FOUND }, 404);
      }
      conv = newConv;
      conversationId = newConv.id;
    }
  }

  if (!conv) {
    return json({ error: "Conversation not found. Provide a valid conversation_id or peer_id.", code: ErrorCode.NOT_FOUND }, 404);
  }
  if (conv.owner_id !== userId && conv.peer_id !== userId) {
    return json({ error: "Not a participant in this conversation", code: ErrorCode.FORBIDDEN }, 403);
  }
  if (conv.request_status === "blocked") {
    return json({ error: "This conversation is blocked", code: ErrorCode.FORBIDDEN }, 403);
  }

  // --- Idempotency check: if this idempotency_key was already used, return the existing message ---
  if (body.idempotency_key) {
    const { data: existing } = await supabase
      .from("messages")
      .select("*")
      .eq("idempotency_key", body.idempotency_key)
      .eq("sender_id", userId)
      .limit(1);
    if (existing && existing.length > 0) {
      // Duplicate request — return the existing message instead of inserting a new one
      return json({ sent: true, message: existing[0], idempotent: true });
    }
  }

  // --- Insert the message ---
  const insertData: Record<string, unknown> = {
    conversation_id: conversationId,
    sender_id: userId,
    type: body.type,
    text: (body.text ?? "").slice(0, 10000),
    media_url: body.media_url ?? null,
    media_thumbnail: body.media_thumbnail ?? null,
    media_bucket: body.media_bucket ?? null,
    file_name: body.file_name ?? null,
    file_size: fileSize,
    mime_type: body.mime_type ?? null,
    media_duration_sec: body.media_duration_sec ?? 0,
    is_view_once: body.is_view_once ?? false,
    reply_to_id: body.reply_to_id ?? null,
    status: "SENT",
    timestamp_millis: body.timestamp_millis ?? Date.now(),
    is_outgoing: true,
    location_lat: body.location_lat ?? null,
    location_lng: body.location_lng ?? null,
    location_address: body.location_address ?? null,
    location_live_minutes: body.location_live_minutes ?? null,
    location_comment: body.location_comment != null ? String(body.location_comment).slice(0, 500) : null,
    contact_name: body.contact_name ?? null,
    contact_phone: body.contact_phone ?? null,
    call_type: body.call_type ?? null,
    call_duration_sec: body.call_duration_sec ?? 0,
    idempotency_key: body.idempotency_key ?? null,
  };

  const { data: msg, error: insertError } = await supabase
    .from("messages")
    .insert(insertData)
    .select()
    .single();

  if (insertError) {
    console.error("send-message insert failed", insertError);
    return json({ error: "Failed to send message", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  // Update conversation's last_message
  const lastMsgPreview = body.type === "TEXT" ? (body.text ?? "").slice(0, 100)
    : body.type === "IMAGE" ? "📷 Photo"
    : body.type === "VIDEO" ? "🎥 Video"
    : body.type === "AUDIO" || body.type === "VOICE_NOTE" ? "🎤 Voice message"
    : body.type === "DOCUMENT" ? "📄 Document"
    : body.type === "LOCATION" ? (body.location_live_minutes ? "📍 Live location" : "📍 Location")
    : body.type === "CONTACT" ? "👤 Contact"
    : "Message";

  await supabase.from("conversations").update({
    last_message: lastMsgPreview,
    last_message_type: body.type,
    last_message_at: new Date().toISOString(),
  }).eq("id", conversationId);

  // Increment unread_count for the receiver's conversation atomically.
  // The previous "direct SQL" update referenced `conv.unread_count`, which
  // was never fetched in the SELECT above (`.select("id, owner_id, peer_id, request_status")`),
  // so `(conv.unread_count ?? 0) + 1` always evaluated to 1 — clobbering the
  // receiver's true unread count instead of incrementing it. The
  // security_definer RPC `increment_unread_count` (defined in migrations
  // 20260912 + 20260913) does the increment atomically server-side:
  //   update conversations set unread_count = unread_count + 1
  //    where id = p_conversation_id and (owner_id = p_user_id or peer_id = p_user_id)
  const receiverId = conv.owner_id === userId ? conv.peer_id : conv.owner_id;
  if (receiverId) {
    await supabase.rpc("increment_unread_count", {
      p_conversation_id: conversationId,
      p_user_id: receiverId,
    });
  }

  // Send push notification to the receiver via send-chat-notification edge function
  try {
    const senderName = msg.sender_name || "New message";
    const preview = lastMsgPreview;
    const notifPayload = {
      conversationId: conversationId,
      recipientId: receiverId,
      senderName: senderName,
      messagePreview: preview,
      messageType: body.type,
    };
    // Call send-chat-notification edge function (internal HTTP call)
    const token = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const baseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    await fetch(`${baseUrl}/functions/v1/send-chat-notification`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
        "apikey": anonKey,
      },
      body: JSON.stringify(notifPayload),
    });
  } catch (pushErr) {
    // Push failure is non-fatal — the message was still sent + Realtime will deliver it
    console.warn("send-chat-notification failed:", pushErr);
  }

  return json({ sent: true, message: msg });
}

serve(handler, { port: 9037 });
