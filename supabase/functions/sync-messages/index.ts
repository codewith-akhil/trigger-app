// Edge function: sync-messages
// ----------------------------------------------------------------------------
// Cross-device chat sync. The Android MessageService currently stores all
// messages in a local Room DB only — switching devices or reinstalling the
// app loses the entire chat history. This function provides two operations:
//
//   action="push"  — the client sends a batch of messages (originating from
//                    Room) to be upserted into the cloud `messages` table.
//                    Used after the client sends/edits a message.
//   action="pull"  — the client requests messages newer than `sinceTs`
//                    (epoch millis) for a conversation (or all conversations).
//                    When sinceTs <= 0 (fresh install / cleared cache) the
//                    function returns the NEWEST `initialLimit` (default 50)
//                    rows instead — Task 25: the old ascending+limit(500)
//                    returned the OLDEST 500 rows of a >500-message thread
//                    and stamped the watermark done, so the newest messages
//                    of long threads never reached the device. Older history
//                    is loaded lazily via action="history".
//   action="history" — Task 25 backward pagination page: up to `limit`
//                    (default 50, max 200) messages STRICTLY older than the
//                    composite cursor (beforeTs, beforeSeq, beforeId),
//                    newest first. RLS (participant select) applies, same
//                    as pull.
//
// Auth: requires a valid Supabase JWT. All writes are scoped to the caller
// (sender_id = auth.uid()) via RLS; reads are scoped to conversations where
// the caller is owner or peer.
// Rate limit: 30 requests / 60s (RATE_LIMITS.SYNC_MESSAGES — generous since
// pull may be called frequently).
//
// Request body for "push":
//   { "action": "push",
//     "messages": [
//       { "id": string (uuid), "conversationId": string (uuid),
//         "type": "TEXT"|"IMAGE"|..., "text": string,
//         "mediaUrl"?: string, "mediaThumbnail"?: string,
//         "mediaBucket"?: string, "fileName"?: string, "fileSize"?: number,
//         "mimeType"?: string, "mediaDurationSec"?: number,
//         "isViewOnce"?: boolean, "replyToId"?: string,
//         "status": "SENDING"|"SENT"|"DELIVERED"|"READ"|"FAILED",
//         "timestampMillis": number, "isOutgoing": boolean,
//         "locationLat"?: number, "locationLng"?: number,
//         "locationAddress"?: string, "contactName"?: string, "contactPhone"?: string,
//         "callType"?: string, "callDurationSec"?: number }
//     ] }
//
// Request body for "pull":
//   { "action": "pull",
//     "conversationId"?: string (uuid),   // omit for all conversations
//     "sinceTs"?: number,                 // epoch millis; <=0 = initial (newest page)
//     "initialLimit"?: number }           // newest-page size, default 50
//
// Request body for "history" (Task 25 backward pagination):
//   { "action": "history",
//     "conversationId": string (uuid),
//     "beforeTs": number,                 // epoch millis (required, > 0)
//     "beforeSeq": number,                // conversation seq tiebreak
//     "beforeId": string (uuid),          // final deterministic tiebreak
//     "limit"?: number }                  // default 50, max 200
//
// Response 200:
//   push → { "synced": number, "skipped": number }
//   pull → { "messages": [...], "untilTs": number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, createUserClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit, RATE_LIMITS } from "../_shared/rate_limit.ts";

// Extend RATE_LIMITS at runtime (the const is frozen, so we add a new key).
const SYNC_MESSAGES_LIMIT = { maxRequests: 30, windowSeconds: 60, name: "sync_messages" };

interface PushMessage {
  id: string;
  conversationId: string;
  type?: string;
  text?: string;
  mediaUrl?: string;
  mediaThumbnail?: string;
  mediaBucket?: string;
  fileName?: string;
  fileSize?: number;
  mimeType?: string;
  mediaDurationSec?: number;
  isViewOnce?: boolean;
  replyToId?: string;
  status?: string;
  timestampMillis?: number;
  isOutgoing?: boolean;
  locationLat?: number;
  locationLng?: number;
  locationAddress?: string;
  locationPlaceName?: string;
  contactName?: string;
  contactPhone?: string;
  callType?: string;
  callDurationSec?: number;
}

interface Body {
  action?: string;
  messages?: PushMessage[];
  conversationId?: string;
  sinceTs?: number;
  initialLimit?: number;
  beforeTs?: number;
  beforeSeq?: number;
  beforeId?: string;
  limit?: number;
}

const VALID_TYPES = ["TEXT", "IMAGE", "VIDEO", "AUDIO", "VOICE_NOTE", "DOCUMENT", "LOCATION", "CONTACT", "CALL_LOG", "SYSTEM"];
const VALID_STATUS = ["SENDING", "SENT", "DELIVERED", "READ", "FAILED"];

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

  const rl = checkRateLimit(req, userId, SYNC_MESSAGES_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const action = (body.action ?? "").toLowerCase();
  // Use the USER's JWT (not service role) so RLS enforces:
  //   - push: sender_id must = auth.uid() (can't write to others' conversations)
  //   - pull: only conversations where caller is owner or peer
  const userClient = createUserClient(authHeader);

  if (action === "push") {
    if (!Array.isArray(body.messages)) {
      return errorResponse("messages array is required for push", 422, ErrorCode.VALIDATION_FAILED);
    }
    if (body.messages.length > 100) {
      return errorResponse("Cannot push more than 100 messages at once", 422, ErrorCode.VALIDATION_FAILED);
    }

    // Validate + transform each message.
    const rows: Record<string, unknown>[] = [];
    let skipped = 0;
    for (const m of body.messages) {
      if (!isUuid(m.id) || !isUuid(m.conversationId)) { skipped++; continue; }
      if (m.type && !VALID_TYPES.includes(m.type)) { skipped++; continue; }
      if (m.status && !VALID_STATUS.includes(m.status)) { skipped++; continue; }
      rows.push({
        id: m.id,
        conversation_id: m.conversationId,
        sender_id: userId,
        type: m.type ?? "TEXT",
        text: (m.text ?? "").slice(0, 10000),
        media_url: m.mediaUrl ?? null,
        media_thumbnail: m.mediaThumbnail ?? null,
        media_bucket: m.mediaBucket ?? null,
        file_name: m.fileName ?? null,
        file_size: m.fileSize ?? 0,
        mime_type: m.mimeType ?? null,
        media_duration_sec: m.mediaDurationSec ?? 0,
        is_view_once: m.isViewOnce ?? false,
        reply_to_id: m.replyToId ?? null,
        status: m.status ?? "SENT",
        timestamp_millis: m.timestampMillis ?? Date.now(),
        is_outgoing: m.isOutgoing ?? true,
        location_lat: m.locationLat ?? null,
        location_lng: m.locationLng ?? null,
        location_address: m.locationAddress ?? null,
        location_place_name: m.locationPlaceName ?? null,
        contact_name: m.contactName ?? null,
        contact_phone: m.contactPhone ?? null,
        call_type: m.callType ?? null,
        call_duration_sec: m.callDurationSec ?? 0,
      });
    }

    if (rows.length === 0) {
      return json({ synced: 0, skipped });
    }

    // SENDER-ONLY pushes: the messages UPDATE policy used to allow the
    // conversation OWNER to update ANY message in the thread — pushing a
    // local mirror could rewrite the PEER's messages (text/media/status) and
    // bypass the 15-minute edit window. Now a pushed row updates only rows
    // the caller itself sent; foreign ids are counted as skipped.
    // (Bug fix while in-file: this existence pre-check referenced an
    // undefined `supabase` client — a ReferenceError that broke every push.
    // The ADMIN client is used read-only here so foreign existing rows are
    // correctly SKIPPED; RLS on the user client below stays the final gate.)
    const adminClient = createAdminClient();
    const rowIds = rows.map((r) => r.id as string);
    const { data: existingMsgs } = await adminClient
      .from("messages")
      .select("id, sender_id")
      .in("id", rowIds);
    const ownExisting = new Set(
      (existingMsgs ?? [])
        .filter((m: { sender_id: string }) => m.sender_id === userId)
        .map((m) => m.id)
    );
    const pushRows: Record<string, unknown>[] = [];
    for (const r of rows) {
      if (!existingMsgs?.some((m: { id: string }) => m.id === r.id) || ownExisting.has(r.id as string)) {
        pushRows.push(r);
      } else {
        skipped++;
      }
    }

    if (pushRows.length === 0) {
      return json({ synced: 0, skipped });
    }

    const { error: upsertError } = await userClient
      .from("messages")
      .upsert(pushRows, { onConflict: "id" });

    if (upsertError) {
      console.error("sync-messages push failed", upsertError);
      return errorResponse("Failed to sync messages", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({ synced: rows.length, skipped });
  }

  if (action === "pull") {
    const sinceTs = typeof body.sinceTs === "number" ? body.sinceTs : 0;
    const initialLimit = Math.min(Math.max(Number(body.initialLimit ?? 50), 1), 200);

    let query = userClient
      .from("messages")
      .select("*");

    if (body.conversationId) {
      if (!isUuid(body.conversationId)) {
        return errorResponse("conversationId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
      }
      query = query.eq("conversation_id", body.conversationId);
    }

    if (sinceTs <= 0) {
      // Task 25 — INITIAL sync: fetch the NEWEST page (WhatsApp-style chat
      // open). The old `gt(0).order(asc).limit(500)` returned the OLDEST 500
      // rows of a long thread and marked the watermark done, so the newest
      // messages never loaded on fresh installs. Older history is fetched
      // page-by-page via action="history" when the user scrolls up.
      query = query
        .order("timestamp_millis", { ascending: false })
        .order("seq", { ascending: false })
        .order("id", { ascending: false })
        .limit(initialLimit);
    } else {
      // Incremental catch-up: everything the device missed while offline.
      query = query
        .gt("timestamp_millis", sinceTs)
        .order("timestamp_millis", { ascending: true })
        .order("seq", { ascending: true })
        .limit(500);
    }

    const { data, error: fetchError } = await query;
    if (fetchError) {
      console.error("sync-messages pull failed", fetchError);
      return errorResponse("Failed to fetch messages", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({
      messages: data ?? [],
      untilTs: Date.now(),
    });
  }

  if (action === "history") {
    // Task 25 — backward pagination page for scroll-to-top history loading.
    if (!isUuid(body.conversationId)) {
      return errorResponse("conversationId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
    }
    const limit = Math.min(Math.max(Number(body.limit ?? 50), 1), 200);
    const beforeTs = Number(body.beforeTs ?? 0);
    const beforeSeq = Number(body.beforeSeq ?? 0);
    const beforeId = typeof body.beforeId === "string" ? body.beforeId : "";
    if (!(beforeTs > 0) || !isUuid(beforeId)) {
      return errorResponse("beforeTs (>0) and beforeId (uuid) are required", 422, ErrorCode.VALIDATION_FAILED);
    }

    // Composite cursor identical to the client's Room queries:
    // (timestamp_millis, seq, id) strictly less than the anchor — rows that
    // share a timestamp or seq can never be skipped or duplicated across
    // page boundaries.
    const orFilter = [
      `timestamp_millis.lt.${beforeTs}`,
      `and(timestamp_millis.eq.${beforeTs},seq.lt.${beforeSeq})`,
      `and(timestamp_millis.eq.${beforeTs},seq.eq.${beforeSeq},id.lt.${beforeId})`,
    ].join(",");

    const { data, error: historyError } = await userClient
      .from("messages")
      .select("*")
      .eq("conversation_id", body.conversationId)
      .or(orFilter)
      .order("timestamp_millis", { ascending: false })
      .order("seq", { ascending: false })
      .order("id", { ascending: false })
      .limit(limit);

    if (historyError) {
      console.error("sync-messages history failed", historyError);
      return errorResponse("Failed to fetch history", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({
      messages: data ?? [],
      untilTs: Date.now(),
    });
  }

  return errorResponse(`Unknown action: ${action}. Use "push", "pull" or "history".`, 422, ErrorCode.VALIDATION_FAILED);
}

serve(handler, { port: 9019 });
