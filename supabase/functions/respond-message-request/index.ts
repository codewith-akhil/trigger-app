// Edge function: respond-message-request
// Accept / decline / block an incoming message request.
//
// Accept: marks the request accepted, flips the CANONICAL conversation
// (sender-owned pending row, which already holds the pre-accept messages) to
// accepted, creates the receiver's mirror conversation row for their chat
// list, links both users as contacts, and returns the canonical conversation
// id so the receiver's client opens the SAME thread the sender writes into.
//
// Decline: request + canonical conversation are marked declined. The sender
// cannot send more; the receiver may still un-decline later by sending a
// message (handled in send-message).
//
// Block: request + canonical conversation blocked + blocked_contacts row.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { requestId?: string; action?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const requestId = body.requestId ?? "";
  const action = (body.action ?? "").toLowerCase();
  if (!requestId) return json({ error: "requestId is required" }, 422);
  if (!["accept", "decline", "block"].includes(action)) {
    return json({ error: "action must be 'accept', 'decline' or 'block'" }, 422);
  }

  const supabase = createAdminClient();

  // Get the request
  const { data: msgReq, error: reqError } = await supabase
    .from("message_requests").select("*").eq("id", requestId).maybeSingle();
  if (reqError || !msgReq) return json({ error: "Request not found" }, 404);
  if (msgReq.receiver_id !== userId) return json({ error: "Not authorized" }, 403);
  if (msgReq.status !== "pending") return json({ error: "Request already responded to" }, 409);

  const now = new Date().toISOString();

  // Resolve the canonical conversation: linked id → oldest pair row fallback.
  let canonicalId: string | null = msgReq.conversation_id ?? null;
  if (!canonicalId) {
    const { data: pair } = await supabase
      .from("conversations")
      .select("id, created_at")
      .or(`and(owner_id.eq.${msgReq.sender_id},peer_id.eq.${userId}),and(owner_id.eq.${userId},peer_id.eq.${msgReq.sender_id})`)
      .order("created_at", { ascending: true })
      .limit(1);
    canonicalId = pair && pair.length > 0 ? pair[0].id : null;
  }
  if (!canonicalId) return json({ error: "Conversation for this request not found" }, 404);

  // Canonical conversation state (needed on every path)
  const { data: canonical } = await supabase
    .from("conversations")
    .select("id, owner_id, peer_id, last_message, last_message_type, last_message_at")
    .eq("id", canonicalId)
    .maybeSingle();
  if (!canonical) return json({ error: "Conversation for this request not found" }, 404);

  if (action === "block") {
    await supabase.from("message_requests").update({ status: "blocked", responded_at: now }).eq("id", requestId);
    await supabase.from("blocked_contacts").upsert({
      user_id: userId, blocked_user_id: msgReq.sender_id, blocked_identifier: msgReq.sender_name,
    }, { onConflict: "user_id,blocked_identifier" });
    await supabase.from("conversations").update({ request_status: "blocked" }).eq("id", canonicalId);
    return json({ blocked: true });
  }

  if (action === "decline") {
    await supabase.from("message_requests").update({ status: "declined", responded_at: now }).eq("id", requestId);
    await supabase.from("conversations").update({ request_status: "declined" }).eq("id", canonicalId);
    return json({ declined: true, conversationId: canonicalId });
  }

  // ---------------- ACCEPT ----------------
  await supabase.from("message_requests").update({
    status: "accepted", responded_at: now, conversation_id: canonicalId,
  }).eq("id", requestId);

  // Canonical (sender-owned) row → accepted; receiver reads/writes it.
  await supabase.from("conversations").update({
    request_status: "accepted", is_contact: true,
  }).eq("id", canonicalId);

  // How many messages has the sender already written pre-accept? That is the
  // receiver's starting unread count.
  const { count: senderMsgs } = await supabase
    .from("messages")
    .select("id", { count: "exact", head: true })
    .eq("conversation_id", canonicalId)
    .eq("sender_id", msgReq.sender_id);
  const unread = Math.min(senderMsgs ?? 0, 9999);

  // Receiver's mirror conversation row (their chat-list metadata). Messages
  // stay on the canonical row; the client resolves the canonical id.
  const { data: existingMirror } = await supabase
    .from("conversations")
    .select("id")
    .eq("owner_id", userId).eq("peer_id", msgReq.sender_id)
    .maybeSingle();
  if (existingMirror) {
    await supabase.from("conversations").update({
      request_status: "accepted", is_contact: true,
      last_message: canonical.last_message, last_message_type: canonical.last_message_type,
      last_message_at: canonical.last_message_at,
    }).eq("id", existingMirror.id);
  } else {
    await supabase.from("conversations").insert({
      owner_id: userId, peer_id: msgReq.sender_id,
      peer_name: msgReq.sender_name, peer_avatar_url: msgReq.sender_avatar_url,
      request_status: "accepted", is_contact: true,
      last_message: canonical.last_message, last_message_type: canonical.last_message_type ?? "TEXT",
      last_message_at: canonical.last_message_at ?? now,
      unread_count: unread,
    });
  }

  // Both users become contacts.
  await supabase.from("contacts").upsert({ user_id: userId, contact_user_id: msgReq.sender_id }, { onConflict: "user_id,contact_user_id" });
  await supabase.from("contacts").upsert({ user_id: msgReq.sender_id, contact_user_id: userId }, { onConflict: "user_id,contact_user_id" });

  return json({ accepted: true, conversationId: canonicalId });
}
serve(handler, { port: 9034 });
