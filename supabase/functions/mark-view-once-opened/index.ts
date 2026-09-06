// Edge function: mark-view-once-opened
// Marks a view-once message as opened by the RECEIVER. Syncs the is_viewed
// flag so the SENDER sees "Opened" via Realtime, and so the state survives
// reinstall / other devices. Previously this state was client-only.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? "";
  if (!messageId) return json({ error: "message_id required" }, 422);

  const supabase = createAdminClient();

  // Fetch the message + its conversation participants.
  const { data: msg, error: msgErr } = await supabase
    .from("messages")
    .select("id, sender_id, is_view_once, is_viewed, conversations!inner(owner_id, peer_id)")
    .eq("id", messageId)
    .maybeSingle();
  if (msgErr || !msg) return json({ error: "Message not found" }, 404);

  const convOwner = (msg as any).conversations?.owner_id;
  const convPeer = (msg as any).conversations?.peer_id;
  // Only a conversation participant may open it, and NOT the sender.
  if (convOwner !== userId && convPeer !== userId) {
    return errorResponse("Forbidden", 403, ErrorCode.FORBIDDEN);
  }
  if (msg.sender_id === userId) {
    return json({ error: "Sender cannot mark own view-once message as opened" }, 403);
  }
  if (!msg.is_view_once) {
    return json({ error: "Message is not view-once" }, 422);
  }
  if (msg.is_viewed) {
    // Idempotent — already opened
    return json({ opened: true, already: true });
  }

  const { error: updateError } = await supabase
    .from("messages")
    .update({ is_viewed: true })
    .eq("id", messageId);
  if (updateError) return json({ error: "Failed to mark opened" }, 500);

  return json({ opened: true });
}
serve(handler, { port: 9050 });
