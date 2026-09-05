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
  contact_name?: string;
  contact_phone?: string;
  call_type?: string;
  call_duration_sec?: number;
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
  if (!body.conversation_id) return json({ error: "conversation_id is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!body.type || !VALID_TYPES.includes(body.type)) return json({ error: "Invalid message type", code: ErrorCode.VALIDATION_FAILED }, 422);

  // Text validation
  if (body.type === "TEXT" && (!body.text || body.text.trim().length === 0)) {
    return json({ error: "Message text cannot be empty", code: ErrorCode.VALIDATION_FAILED }, 422);
  }
  if (body.text && body.text.length > 10000) {
    return json({ error: "Message too long (max 10000 chars)", code: ErrorCode.VALIDATION_FAILED }, 422);
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

  // Verify the caller is a participant in the conversation
  const supabase = createAdminClient();
  const { data: conv } = await supabase
    .from("conversations")
    .select("id, owner_id, peer_id, request_status")
    .eq("id", body.conversation_id)
    .maybeSingle();

  if (!conv) return json({ error: "Conversation not found", code: ErrorCode.NOT_FOUND }, 404);
  if (conv.owner_id !== userId && conv.peer_id !== userId) {
    return json({ error: "Not a participant in this conversation", code: ErrorCode.FORBIDDEN }, 403);
  }
  if (conv.request_status === "blocked") {
    return json({ error: "This conversation is blocked", code: ErrorCode.FORBIDDEN }, 403);
  }

  // --- Insert the message ---
  const insertData: Record<string, unknown> = {
    conversation_id: body.conversation_id,
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
    contact_name: body.contact_name ?? null,
    contact_phone: body.contact_phone ?? null,
    call_type: body.call_type ?? null,
    call_duration_sec: body.call_duration_sec ?? 0,
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
    : body.type === "LOCATION" ? "📍 Location"
    : body.type === "CONTACT" ? "👤 Contact"
    : "Message";

  await supabase.from("conversations").update({
    last_message: lastMsgPreview,
    last_message_type: body.type,
    last_message_at: new Date().toISOString(),
  }).eq("id", body.conversation_id);

  // Increment unread_count for the receiver's conversation
  const receiverId = conv.owner_id === userId ? conv.peer_id : conv.owner_id;
  if (receiverId) {
    await supabase.rpc("increment_unread_count", {
      p_conversation_id: body.conversation_id,
      p_user_id: receiverId,
    }).then(() => {}).catch(() => {}); // non-fatal if RPC doesn't exist
  }

  return json({ sent: true, message: msg });
}

serve(handler, { port: 9037 });
