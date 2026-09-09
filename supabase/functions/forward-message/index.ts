// Edge function: forward-message
// Forwards a message to one or more target conversations. Creates a copy of
// the original message in each target conversation with a new ID.
//
// Task 24: chat_media/voice_notes are PRIVATE buckets with participant-only
// RLS keyed on the uploader's object folder ("{uid}/{uuid}.ext"). A forwarded
// copy that still points into the ORIGINAL uploader's folder would 403 for
// the new recipient, so every media object is server-side COPIED into the
// forwarder's own folder before the message rows are inserted.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; target_conversation_ids?: string[]; }

/** Buckets whose objects must be re-homed into the forwarder's folder. */
const PRIVATE_MEDIA_BUCKETS = new Set(["chat_media", "voice_notes"]);

/** Extracts the bare object path from a stored media_url (bare path since
 *  migration 20260924; public/signed URL form on pre-migration rows). */
function extractObjectPath(stored: string, bucket: string): string | null {
  for (const marker of [
    `/storage/v1/object/public/${bucket}/`,
    `/storage/v1/object/sign/${bucket}/`,
    `/storage/v1/object/authenticated/${bucket}/`,
  ]) {
    const idx = stored.indexOf(marker);
    if (idx >= 0) return stored.substring(idx + marker.length).split("?")[0];
  }
  // Bare path form: "uid/uuid.ext" (no scheme, no leading slash).
  if (!stored.startsWith("http") && stored.includes("/") && !stored.startsWith("/")) return stored;
  return null;
}

/** Server-side storage copy via POST /storage/v1/object/copy (service role). */
async function copyStorageObject(bucket: string, sourcePath: string, destPath: string): Promise<boolean> {
  try {
    const base = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!base || !serviceKey) return false;
    const res = await fetch(`${base}/storage/v1/object/copy`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${serviceKey}`,
        "apikey": serviceKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        bucketId: bucket,
        sourceKey: sourcePath,
        destinationBucket: bucket,
        destinationKey: destPath,
      }),
    });
    return res.ok;
  } catch (_e) {
    return false;
  }
}

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

    // Task 24: re-home private-bucket media into the FORWARDER's folder so
    // the new recipient passes participant storage RLS. On copy failure the
    // original coordinates are kept (legacy behavior) rather than failing
    // the whole forward.
    let mediaUrl = orig.media_url ?? null;
    let mediaThumbnail = orig.media_thumbnail ?? null;
    const bucket = orig.media_bucket ?? null;
    if (bucket && PRIVATE_MEDIA_BUCKETS.has(bucket)) {
      const srcPath = mediaUrl ? extractObjectPath(mediaUrl, bucket) : null;
      if (srcPath) {
        const ext = srcPath.includes(".") ? srcPath.split(".").pop() : "bin";
        const destPath = `${userId}/${crypto.randomUUID()}.${ext}`;
        if (await copyStorageObject(bucket, srcPath, destPath)) {
          mediaUrl = destPath;  // bare path — same storage shape as Task 24 uploads
        }
      }
      const srcThumb = mediaThumbnail ? extractObjectPath(mediaThumbnail, bucket) : null;
      if (srcThumb && mediaUrl && mediaUrl !== orig.media_url) {
        const thumbExt = srcThumb.includes(".") ? srcThumb.split(".").pop() : "jpg";
        const destThumb = `${userId}/${crypto.randomUUID()}.${thumbExt}`;
        if (await copyStorageObject(bucket, srcThumb, destThumb)) {
          mediaThumbnail = destThumb;
        }
      }
    }

    const insertData = {
      id: newId,
      conversation_id: targetId,
      sender_id: userId,
      type: orig.type,
      text: orig.text ?? "",
      media_url: mediaUrl,
      media_thumbnail: mediaThumbnail,
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
