// Edge function: send-message
// ----------------------------------------------------------------------------
// Inserts a message into the messages table with SERVER-SIDE validation.
// The client is untrusted — all validation happens here.
//
// Also triggers a push notification to the receiver via send-chat-notification.
//
// LEGAL-RETENTION ARCHIVE (Phase 6): after a media message is accepted, the
// object is COPIED (never moved) into the private `media_vault` bucket and
// registered in `media_archive` with purge_at = now() + 14 days. This runs
// for EVERY media type INCLUDING view-once (owner's explicit compliance
// decision). The archive is invisible to end users: media_archive has RLS
// with zero policies, media_vault has no user-facing storage policies — only
// the service role can ever read it. Archiving failure NEVER fails the send
// (log + continue).
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

// --- Legal-retention archive (Phase 6) constants ---------------------------
const VAULT_BUCKET = "media_vault";               // private vault bucket
const RETENTION_DAYS = 14;                        // hard purge window
const ARCHIVE_KIND_BY_TYPE: Record<string, string> = {
  IMAGE: "image", VIDEO: "video", VOICE_NOTE: "voice", AUDIO: "audio", DOCUMENT: "file",
};
const ARCHIVE_MIME_BY_EXT: Record<string, string> = {
  jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp",
  gif: "image/gif", mp4: "video/mp4", mov: "video/quicktime", mkv: "video/x-matroska",
  m4a: "audio/mp4", aac: "audio/aac", mp3: "audio/mpeg", ogg: "audio/ogg",
  opus: "audio/opus", amr: "audio/amr", wav: "audio/wav", pdf: "application/pdf",
  txt: "text/plain", zip: "application/zip", doc: "application/msword",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xls: "application/vnd.ms-excel",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
};

// The client sends the BARE object path in media_url (private-bucket shape);
// legacy rows may carry a full public/signed URL — extract the object path.
function resolveObjectPath(mediaUrl: unknown): string | null {
  if (typeof mediaUrl !== "string" || mediaUrl.trim().length === 0) return null;
  const v = mediaUrl.trim();
  if (!v.startsWith("http")) return v.split("?")[0];
  try {
    const u = new URL(v);
    const m = u.pathname.match(/\/object\/(?:public|sign|authenticated)\/[^/]+\/(.+)$/);
    if (m) return decodeURIComponent(m[1].split("?")[0]);
  } catch { /* unparseable — treat as no path */ }
  return null;
}

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
    // Look for an existing conversation between this user and the peer in
    // EITHER direction. The previous query required peer_id = the CALLER
    // (`owner_id.eq.me OR peer_id.eq.me AND peer_id.eq.peer`) which can never
    // match a conversation the PEER initiated — creating duplicate rows for
    // the same pair.
    const { data: existing } = await supabase
      .from("conversations")
      .select("id, owner_id, peer_id, request_status")
      .or(`and(owner_id.eq.${userId},peer_id.eq.${body.peer_id}),and(owner_id.eq.${body.peer_id},peer_id.eq.${userId})`)
      .order("created_at", { ascending: true })
      .limit(1);
    if (existing && existing.length > 0) {
      conv = existing[0];
      conversationId = conv.id;
    } else {
      // Auto-create a new conversation. The FIRST message from one user to
      // another IS a message request — it must start PENDING (it previously
      // auto-created as "accepted", letting anyone message anyone while
      // bypassing the whole request/3-message gate).
      //
      // Creation goes through get_or_create_conversation (Task 24): the RPC
      // takes a pg advisory lock keyed on the UNORDERED pair, so two
      // simultaneous first-sends from opposite sides serialize and only ONE
      // pending row exists (the ordered-pair unique index alone cannot span
      // (A,B)+(B,A)). If the RPC reports the row already existed we lost the
      // race — re-select it and continue with the winner.
      const { data: rpcRes, error: createErr } = await supabase.rpc("get_or_create_conversation", {
        p_owner: userId,
        p_peer: body.peer_id,
        p_peer_name: body.peer_name ?? "Unknown",
      });
      if (createErr || !rpcRes || rpcRes.length === 0) {
        console.error("send-message: failed to auto-create conversation", createErr);
        return json({ error: "Conversation not found and could not be created. Provide a valid conversation_id or peer_id.", code: ErrorCode.NOT_FOUND }, 404);
      }
      const rpcRow = rpcRes[0] as { conversation_id: string; was_created: boolean };
      if (rpcRow.was_created) {
        conv = { id: rpcRow.conversation_id, owner_id: userId, peer_id: body.peer_id, request_status: "pending" };
      } else {
        const { data: winner } = await supabase
          .from("conversations")
          .select("id, owner_id, peer_id, request_status")
          .eq("id", rpcRow.conversation_id)
          .maybeSingle();
        if (!winner) {
          return json({ error: "Conversation not found and could not be created. Provide a valid conversation_id or peer_id.", code: ErrorCode.NOT_FOUND }, 404);
        }
        conv = winner;
      }
      conversationId = conv.id;
      // Mirror the request into message_requests (same as send-message-request
      // does) so the receiver's Requests list shows it.
      try {
        const { data: senderProfile } = await supabase
          .from("profiles")
          .select("full_name, username, avatar_url")
          .eq("id", userId)
          .maybeSingle();
        await supabase.from("message_requests").upsert({
          sender_id: userId,
          receiver_id: body.peer_id,
          sender_name: senderProfile?.full_name ?? body.peer_name ?? "Unknown",
          sender_username: senderProfile?.username ?? null,
          sender_avatar_url: senderProfile?.avatar_url ?? null,
          initial_message: (body.text ?? "").slice(0, 200),
          status: "pending",
          conversation_id: conversationId,
        }, { onConflict: "sender_id,receiver_id" });
      } catch (reqErr) {
        console.warn("send-message: message_requests mirror failed", reqErr);
      }
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

  // Blocked-contacts enforcement (Task 24): in-chat block was previously a
  // client-UI-only flag — the server never learned about it and a blocked
  // peer could keep sending. Blocking writes blocked_contacts (via
  // manage-blocked-contacts), so check it here in BOTH directions.
  {
    const other = conv.owner_id === userId ? conv.peer_id : conv.owner_id;
    if (other) {
      const { data: blockedRow } = await supabase
        .from("blocked_contacts")
        .select("id")
        .or(`and(user_id.eq.${userId},blocked_user_id.eq.${other}),and(user_id.eq.${other},blocked_user_id.eq.${userId})`)
        .limit(1);
      if (blockedRow && blockedRow.length > 0) {
        return json({ error: "This conversation is blocked", code: ErrorCode.FORBIDDEN }, 403);
      }
    }
  }

  // --- Message-request gating (Instagram model) ---------------------------
  // While the request is PENDING: the requester (conversation owner) may send
  // up to 3 messages; the receiver must ACCEPT before replying.
  // FAST-PATH gate (friendly error before the atomic insert below): while
  // the request is PENDING the requester may send up to 3 messages; the
  // receiver must ACCEPT before replying. The authoritative, race-free
  // enforcement is the try_send_pending_message RPC used for the insert when
  // pending (count-then-insert here was a TOCTOU — concurrent sends both
  // counted 2 and both inserted).
  if (conv.request_status === "pending") {
    const isRequester = conv.owner_id === userId;
    if (!isRequester) {
      return json({
        error: "Accept the message request to reply",
        code: "REQUEST_NOT_ACCEPTED",
      }, 403);
    }
    const { count: sentCount } = await supabase
      .from("messages")
      .select("id", { count: "exact", head: true })
      .eq("conversation_id", conv.id)
      .eq("sender_id", userId);
    if ((sentCount ?? 0) >= 3) {
      return json({
        error: "You can send up to 3 messages while your request is pending",
        code: "REQUEST_MESSAGE_LIMIT",
      }, 403);
    }
  }

  // DECLINED: the receiver changed their mind — sending re-opens the chat.
  // The requester stays blocked until the receiver re-opens it.
  if (conv.request_status === "declined") {
    if (conv.owner_id === userId) {
      return json({ error: "Your message request was declined", code: "REQUEST_DECLINED" }, 403);
    }
    await supabase.from("conversations").update({ request_status: "accepted" }).eq("id", conv.id);
    conv.request_status = "accepted";
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

  let msg: Record<string, unknown> | null = null;
  let insertError: Record<string, unknown> | null = null;

  if (conv.request_status === "pending") {
    // ATOMIC path — the RPC locks the conversation row, re-checks the budget
    // and inserts in one transaction (cannot be raced past 3). The FULL
    // payload is forwarded (media, reply target, location/contact/call
    // metadata, idempotency key) — the old RPC hardcoded TEXT and silently
    // dropped everything else, converting pending media sends into empty
    // text rows.
    const { data: rpcMsg, error: rpcErr } = await supabase.rpc(
      "try_send_pending_message",
      {
        p_conversation_id: conv.id,
        p_sender_id: userId,
        p_text: (body.text ?? "").toString(),
        p_timestamp_millis: body.timestamp_millis ?? null,
        p_type: body.type ?? "TEXT",
        p_media_url: body.media_url ?? null,
        p_media_thumbnail: body.media_thumbnail ?? null,
        p_media_bucket: body.media_bucket ?? null,
        p_file_name: body.file_name ?? null,
        p_file_size: body.file_size ?? null,
        p_mime_type: body.mime_type ?? null,
        p_media_duration_sec: body.media_duration_sec ?? 0,
        p_is_view_once: body.is_view_once ?? false,
        p_reply_to_id: body.reply_to_id ?? null,
        p_idempotency_key: body.idempotency_key ?? null,
        p_location_lat: body.location_lat ?? null,
        p_location_lng: body.location_lng ?? null,
        p_location_address: body.location_address ?? null,
        p_location_live_minutes: body.location_live_minutes ?? null,
        p_location_comment: body.location_comment ?? null,
        p_contact_name: body.contact_name ?? null,
        p_contact_phone: body.contact_phone ?? null,
        p_call_type: body.call_type ?? null,
        p_call_duration_sec: body.call_duration_sec ?? 0,
      }
    );
    if (rpcErr) {
      const rpcCode = String((rpcErr as Record<string, unknown>).message ?? "");
      if (rpcCode.includes("REQUEST_MESSAGE_LIMIT")) {
        return json({ error: "You can send up to 3 messages while your request is pending", code: "REQUEST_MESSAGE_LIMIT" }, 403);
      }
      if (rpcCode.includes("REQUEST_NOT_ACCEPTED")) {
        return json({ error: "Accept the message request to reply", code: "REQUEST_NOT_ACCEPTED" }, 403);
      }
      if (rpcCode.includes("CONVERSATION_BLOCKED")) {
        return json({ error: "This conversation is blocked", code: ErrorCode.FORBIDDEN }, 403);
      }
      console.error("send-message: try_send_pending_message failed", rpcErr);
      return json({ error: "Failed to send message", code: ErrorCode.INTERNAL_ERROR }, 500);
    }
    msg = rpcMsg as Record<string, unknown>;
  } else {
    const ins = await supabase
      .from("messages")
      .insert(insertData)
      .select()
      .single();
    msg = ins.data as Record<string, unknown> | null;
    insertError = ins.error as Record<string, unknown> | null;
    // Unique idempotency_key race: the pre-check above can miss a concurrent
    // duplicate — previously returned a confusing 500; return the winner.
    if (insertError && (insertError as Record<string, unknown>).code === "23505" && body.idempotency_key) {
      const { data: dup } = await supabase
        .from("messages")
        .select("*")
        .eq("idempotency_key", body.idempotency_key)
        .eq("sender_id", userId)
        .limit(1);
      if (dup && dup.length > 0) {
        return json({ sent: true, message: dup[0], idempotent: true });
      }
    }
  }

  if (insertError || !msg) {
    console.error("send-message insert failed", insertError);
    return json({ error: "Failed to send message", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  // Update conversation's last_message
  const isViewOnce = body.is_view_once === true;
  const lastMsgPreview = body.type === "TEXT" ? (body.text ?? "").slice(0, 100)
    : isViewOnce && body.type === "IMAGE" ? "📷 View-once photo"
    : isViewOnce && body.type === "VIDEO" ? "🎥 View-once video"
    : body.type === "IMAGE" ? "📷 Photo"
    : body.type === "VIDEO" ? "🎥 Video"
    : body.type === "AUDIO" || body.type === "VOICE_NOTE" ? "🎤 Voice message"
    : body.type === "DOCUMENT" ? "📄 Document"
    : body.type === "LOCATION" ? (body.location_live_minutes ? "📍 Live location" : "📍 Location")
    : body.type === "CONTACT" ? "👤 Contact"
    : body.type === "CALL_LOG" ? (body.call_type === "video" ? "📞 Video call" : "📞 Voice call")
    : "Message";

  await supabase.from("conversations").update({
    last_message: lastMsgPreview,
    last_message_type: body.type,
    last_message_at: new Date().toISOString(),
  }).eq("id", conversationId);

  // Keep the PEER's mirror conversation row (per-user chat-list metadata)
  // in sync too — messages live on the canonical row but the peer's chat
  // list reads their own mirror row.
  const receiverId = conv.owner_id === userId ? conv.peer_id : conv.owner_id;
  if (receiverId) {
    await supabase.from("conversations").update({
      last_message: lastMsgPreview,
      last_message_type: body.type,
      last_message_at: new Date().toISOString(),
    }).eq("owner_id", receiverId).eq("peer_id", userId);
  }

  // Increment unread_count for the receiver's conversation atomically.
  // The previous "direct SQL" update referenced `conv.unread_count`, which
  // was never fetched in the SELECT above (`.select("id, owner_id, peer_id, request_status")`),
  // so `(conv.unread_count ?? 0) + 1` always evaluated to 1 — clobbering the
  // receiver's true unread count instead of incrementing it. The
  // security_definer RPC `increment_unread_count` (defined in migrations
  // 20260912 + 20260913) does the increment atomically server-side:
  //   update conversations set unread_count = unread_count + 1
  //    where id = p_conversation_id and (owner_id = p_user_id or peer_id = p_user_id)
  if (receiverId) {
    await supabase.rpc("increment_unread_count", {
      p_conversation_id: conversationId,
      p_user_id: receiverId,
    });
  }

  // --- Legal-retention archive (Phase 6) ----------------------------------
  // Copy (NOT move) the media object into the private media_vault bucket and
  // register it in media_archive with a 14-day purge_at. Runs for every
  // archiveable media type INCLUDING view-once. Idempotent: on conflict
  // (message_id) do nothing. Never fails the send — any error is logged and
  // swallowed; the next send is unaffected and this row simply isn't archived
  // (the daily purge sweep only touches registered rows).
  {
    const sourceBucket = (body.media_bucket ?? "").trim();
    const sourcePath = resolveObjectPath(body.media_url);
    const archiveKind = ARCHIVE_KIND_BY_TYPE[body.type ?? ""];
    if (sourceBucket && sourcePath && archiveKind && msg.id) {
      try {
        const messageId = String(msg.id);
        const baseName = ((body.file_name ?? "").trim() || sourcePath.split("/").pop() || "media")
          .split("?")[0].replace(/[\\/:*?"<>|]/g, "_") || "media";
        const vaultPath = `${messageId}/${baseName}`;
        const ext = baseName.includes(".") ? baseName.split(".").pop()!.toLowerCase() : "";
        const mime = (body.mime_type ?? "").trim() || ARCHIVE_MIME_BY_EXT[ext] || null;
        const copyRes = await fetch(`${Deno.env.get("SUPABASE_URL")}/storage/v1/object/copy`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")}`,
          },
          body: JSON.stringify({
            bucketId: sourceBucket,
            sourceKey: sourcePath,
            destinationBucket: VAULT_BUCKET,
            destinationKey: vaultPath,
          }),
        });
        if (!copyRes.ok) {
          // Non-fatal: log + continue (send already succeeded).
          console.warn("media_archive: storage copy failed", copyRes.status,
            await copyRes.text().catch(() => ""));
        } else {
          const { error: archErr } = await supabase.from("media_archive").upsert({
            message_id: messageId,
            conversation_id: conversationId,
            sender_id: userId,
            receiver_id: receiverId ?? null,
            media_kind: archiveKind,
            source_bucket: sourceBucket,
            source_path: sourcePath,
            vault_path: vaultPath,
            byte_size: fileSize > 0 ? fileSize : null,
            mime,
            is_view_once: isViewOnce,
            purge_at: new Date(Date.now() + RETENTION_DAYS * 24 * 60 * 60 * 1000).toISOString(),
          }, { onConflict: "message_id", ignoreDuplicates: true });
          if (archErr) console.warn("media_archive: upsert failed", archErr.message);
        }
      } catch (archErr) {
        console.warn("media_archive failed (non-fatal)", archErr);
      }
    }
  }

  // Send push notification to the receiver via send-chat-notification edge function
  try {
    // messages has NO sender_name column (previous code read a non-existent
    // column -> push title was always "New message"). Resolve the display
    // name from the sender's profile row instead.
    let senderName = "New message";
    const { data: senderProfile } = await supabase
      .from("profiles")
      .select("full_name")
      .eq("id", userId)
      .maybeSingle();
    if (senderProfile?.full_name) senderName = senderProfile.full_name;
    const preview = lastMsgPreview;
    const notifPayload = {
      conversationId: conversationId,
      recipientId: receiverId,
      senderName: senderName,
      messagePreview: preview,
      messageType: body.type,
      viewOnce: isViewOnce,
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
