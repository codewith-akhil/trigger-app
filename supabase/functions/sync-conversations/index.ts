// Edge function: sync-conversations
// ----------------------------------------------------------------------------
// Cross-device conversation-list sync (companion to sync-messages). The
// Android app currently stores conversations in a local Room DB only; this
// function provides push (upsert a batch of conversations) + pull (fetch the
// caller's conversations newer than sinceTs) so the chat list syncs across
// devices + survives reinstall.
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 20 requests / 60s (SYNC_CONVERSATIONS_LIMIT).
//
// Request body for "push":
//   { "action": "push",
//     "conversations": [
//       { "id": string (uuid), "peerId"?: string (uuid),
//         "peerName": string, "peerAvatarUrl"?: string,
//         "peerAvatarColor"?: number, "isGroup"?: boolean,
//         "isPinned"?: boolean, "isMuted"?: boolean, "isBlocked"?: boolean,
//         "disappearingDuration"?: "OFF"|"24H"|"7D"|"90D",
//         "lastMessage"?: string, "lastMessageType"?: string,
//         "lastMessageAt"?: string (ISO), "unreadCount"?: number }
//     ] }
//
// Request body for "pull":
//   { "action": "pull", "sinceTs"?: number }   // epoch millis; omit for full
//
// Response 200:
//   push → { "synced": number, "skipped": number }
//   pull → { "conversations": [...], "untilTs": number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const SYNC_CONVERSATIONS_LIMIT = { maxRequests: 20, windowSeconds: 60, name: "sync_conversations" };

interface PushConversation {
  id: string;
  peerId?: string;
  peerName: string;
  peerAvatarUrl?: string;
  peerAvatarColor?: number;
  isGroup?: boolean;
  isPinned?: boolean;
  isMuted?: boolean;
  isBlocked?: boolean;
  disappearingDuration?: string;
  lastMessage?: string;
  lastMessageType?: string;
  lastMessageAt?: string;
  unreadCount?: number;
}

interface Body {
  action?: string;
  conversations?: PushConversation[];
  sinceTs?: number;
}

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

const DISAPPEARING = ["OFF", "24H", "7D", "90D"];

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, SYNC_CONVERSATIONS_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const action = (body.action ?? "").toLowerCase();
  const supabase = createAdminClient();

  if (action === "push") {
    if (!Array.isArray(body.conversations)) {
      return errorResponse("conversations array is required for push", 422, ErrorCode.VALIDATION_FAILED);
    }
    if (body.conversations.length > 100) {
      return errorResponse("Cannot push more than 100 conversations at once", 422, ErrorCode.VALIDATION_FAILED);
    }

    const rows: Record<string, unknown>[] = [];
    let skipped = 0;
    for (const c of body.conversations) {
      if (!isUuid(c.id)) { skipped++; continue; }
      if (!c.peerName || c.peerName.length > 200) { skipped++; continue; }
      if (c.disappearingDuration && !DISAPPEARING.includes(c.disappearingDuration)) { skipped++; continue; }
      rows.push({
        id: c.id,
        owner_id: userId,
        peer_id: c.peerId && isUuid(c.peerId) ? c.peerId : null,
        peer_name: c.peerName.slice(0, 200),
        peer_avatar_url: c.peerAvatarUrl ?? null,
        peer_avatar_color: typeof c.peerAvatarColor === "number" ? c.peerAvatarColor : 4281344132,
        is_group: c.isGroup ?? false,
        is_pinned: c.isPinned ?? false,
        is_muted: c.isMuted ?? false,
        is_blocked: c.isBlocked ?? false,
        disappearing_duration: c.disappearingDuration ?? "OFF",
        last_message: (c.lastMessage ?? "").slice(0, 500),
        last_message_type: (c.lastMessageType ?? "TEXT").slice(0, 30),
        last_message_at: c.lastMessageAt ?? null,
        unread_count: Math.min(Math.max(c.unreadCount ?? 0, 0), 99999),
      });
    }

    if (rows.length === 0) return json({ synced: 0, skipped });

    const { error: upsertError } = await supabase
      .from("conversations")
      .upsert(rows, { onConflict: "id" });

    if (upsertError) {
      console.error("sync-conversations push failed", upsertError);
      return errorResponse("Failed to sync conversations", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({ synced: rows.length, skipped });
  }

  if (action === "pull") {
    // Return conversations where the caller is owner or peer, updated since sinceTs.
    // We use the admin client (RLS would also work with the user client, but
    // admin lets us filter by updated_at > sinceTs directly).
    let query = supabase
      .from("conversations")
      .select("*")
      .or(`owner_id.eq.${userId},peer_id.eq.${userId}`)
      .order("updated_at", { ascending: false })
      .limit(500);

    if (typeof body.sinceTs === "number" && body.sinceTs > 0) {
      const since = new Date(body.sinceTs).toISOString();
      query = query.gt("updated_at", since);
    }

    const { data, error: fetchError } = await query;
    if (fetchError) {
      console.error("sync-conversations pull failed", fetchError);
      return errorResponse("Failed to fetch conversations", 500, ErrorCode.INTERNAL_ERROR);
    }
    return json({ conversations: data ?? [], untilTs: Date.now() });
  }

  return errorResponse(`Unknown action: ${action}. Use "push" or "pull".`, 422, ErrorCode.VALIDATION_FAILED);
}

serve(handler, { port: 9023 });
