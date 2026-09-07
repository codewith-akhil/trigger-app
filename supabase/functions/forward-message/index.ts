// Edge function: forward-message
// Forwards a message to one or more target conversations. Creates a copy of
// the original message in each target conversation with a new ID.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; target_conversation_ids?: string[]; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? "";
  const targetIds = body.target_conversation_ids ?? [];
  if (!messageId) return json({ error: "message_id required" }, 422);
  if (targetIds.length === 0) return json({ error: "target_conversation_ids required" }, 422);
  if (targetIds.length > 20) return json({ error: "Max 20 targets per forward" }, 422);

  const supabase = createAdminClient();

  // Get the original message
  const { data: orig, error: fetchErr } = await supabase.from("messages").select("*").eq("id", messageId).maybeSingle();
  if (fetchErr || !orig) return json({ error: "Message not found" }, 404);

  // Verify sender has access to the source conversation
  const { data: srcConv } = await supabase.from("conversations").select("owner_id, peer_id").eq("id", orig.conversation_id).maybeSingle();
  if (!srcConv || (srcConv.owner_id !== userId && srcConv.peer_id !== userId)) {
    return json({ error: "Not authorized to forward this message" }, 403);
  }

  // Verify caller is a participant in ALL target conversations
  for (const targetId of targetIds) {
    const { data: tgtConv } = await supabase.from("conversations").select("owner_id, peer_id, request_status").eq("id", targetId).maybeSingle();
    if (!tgtConv) return json({ error: `Target conversation ${targetId} not found` }, 404);
    if (tgtConv.owner_id !== userId && tgtConv.peer_id !== userId) {
      return json({ error: `Not authorized for conversation ${targetId}` }, 403);
    }
    if (tgtConv.request_status === "blocked") {
      return json({ error: `Conversation ${targetId} is blocked` }, 403);
    }
    // The message-request gate applied to send-message must also apply to
    // forwards — pending/declined threads previously accepted forwarded
    // messages from either side (bypassing the 3-message cap entirely).
    if (tgtConv.request_status === "declined") {
      return json({ error: `Conversation ${targetId}: request was declined` }, 403);
    }
    if (tgtConv.request_status === "pending") {
      if (tgtConv.owner_id !== userId) {
        return json({ error: `Accept the message request in conversation ${targetId} first` }, 403);
      }
      const { count: sentCount } = await supabase
        .from("messages")
        .select("id", { count: "exact", head: true })
        .eq("conversation_id", targetId)
        .eq("sender_id", userId);
      if ((sentCount ?? 0) >= 3) {
        return json({ error: `You can send up to 3 messages while your request in conversation ${targetId} is pending` }, 403);
      }
    }
  }

  // Insert forwarded copies
  const forwarded = [];
  for (const targetId of targetIds) {
    const newId = crypto.randomUUID();
    const insertData = {
      id: newId,
      conversation_id: targetId,
      sender_id: userId,
      type: orig.type,
      text: orig.text ?? "",
      media_url: orig.media_url ?? null,
      media_thumbnail: orig.media_thumbnail ?? null,
      media_bucket: orig.media_bucket ?? null,
      file_name: orig.file_name ?? null,
      file_size: orig.file_size ?? 0,
      mime_type: orig.mime_type ?? null,
      media_duration_sec: orig.media_duration_sec ?? 0,
      is_view_once: false, // forwarded messages are never view-once
      reply_to_id: null,
      status: "SENT",
      timestamp_millis: Date.now(),
      is_outgoing: true,
      location_lat: orig.location_lat ?? null,
      location_lng: orig.location_lng ?? null,
      location_address: orig.location_address ?? null,
      contact_name: orig.contact_name ?? null,
      contact_phone: orig.contact_phone ?? null,
      idempotency_key: crypto.randomUUID(),
    };
    const { data: inserted, error: insErr } = await supabase.from("messages").insert(insertData).select().single();
    if (!insErr && inserted) {
      forwarded.push(inserted);
      // Update conversation last_message
      const preview = orig.type === "TEXT" ? (orig.text ?? "").slice(0, 100)
        : orig.type === "IMAGE" ? "📷 Photo" : orig.type === "VIDEO" ? "🎥 Video"
        : orig.type === "AUDIO" || orig.type === "VOICE_NOTE" ? "🎤 Voice message"
        : orig.type === "DOCUMENT" ? "📄 Document"
        : orig.type === "LOCATION" ? "📍 Location"
        : orig.type === "CONTACT" ? "👤 Contact"
        : orig.type === "CALL_LOG" ? (orig.call_type === "video" ? "📞 Video call" : "📞 Voice call")
        : "Message";
      await supabase.from("conversations").update({
        last_message: preview, last_message_type: orig.type,
        last_message_at: new Date().toISOString(),
      }).eq("id", targetId);
      // Forwarded messages never incremented the receiver's unread badge.
      const receiverId2 = (await supabase.from("conversations").select("owner_id, peer_id").eq("id", targetId).maybeSingle()).data;
      if (receiverId2) {
        const other = receiverId2.owner_id === userId ? receiverId2.peer_id : receiverId2.owner_id;
        if (other) {
          await supabase.rpc("increment_unread_count", { p_conversation_id: targetId, p_user_id: other });
        }
      }
    }
  }

  return json({ forwarded: true, count: forwarded.length, messages: forwarded });
}
serve(handler, { port: 9044 });
