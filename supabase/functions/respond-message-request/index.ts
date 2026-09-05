// Edge function: respond-message-request
// Accept or block a message request. On accept: creates conversation for receiver +
// inserts the initial message + adds both users to contacts table.
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
  if (action !== "accept" && action !== "block") return json({ error: "action must be 'accept' or 'block'" }, 422);

  const supabase = createAdminClient();

  // Get the request
  const { data: msgReq, error: reqError } = await supabase
    .from("message_requests")
    .select("*")
    .eq("id", requestId)
    .maybeSingle();

  if (reqError || !msgReq) return json({ error: "Request not found" }, 404);
  if (msgReq.receiver_id !== userId) return json({ error: "Not authorized" }, 403);
  if (msgReq.status !== "pending") return json({ error: "Request already responded to" }, 409);

  const now = new Date().toISOString();

  if (action === "block") {
    // Block: update request status + add to blocked_contacts
    await supabase.from("message_requests").update({ status: "blocked", responded_at: now }).eq("id", requestId);
    await supabase.from("blocked_contacts").upsert({
      user_id: userId, blocked_user_id: msgReq.sender_id, blocked_identifier: msgReq.sender_name,
    }, { onConflict: "user_id,blocked_identifier" });
    // Update sender's conversation to blocked
    await supabase.from("conversations").update({ request_status: "blocked" })
      .eq("owner_id", msgReq.sender_id).eq("peer_id", userId);
    return json({ blocked: true });
  }

  // Accept: update request status + create receiver's conversation + add contacts + insert message
  await supabase.from("message_requests").update({ status: "accepted", responded_at: now }).eq("id", requestId);

  // Get receiver profile
  const { data: receiverProfile } = await supabase.from("profiles").select("full_name, avatar_url").eq("id", userId).maybeSingle();

  // Create receiver's conversation (accepted)
  const { data: newConv } = await supabase.from("conversations").insert({
    owner_id: userId, peer_id: msgReq.sender_id,
    peer_name: msgReq.sender_name, peer_avatar_url: msgReq.sender_avatar_url,
    request_status: "accepted", is_contact: true,
    last_message: msgReq.initial_message, last_message_type: "TEXT",
    last_message_at: now, unread_count: 1,
  }).select("id").single();

  // Update sender's conversation to accepted
  await supabase.from("conversations").update({ request_status: "accepted", is_contact: true })
    .eq("owner_id", msgReq.sender_id).eq("peer_id", userId);

  // Link the request to the new conversation
  await supabase.from("message_requests").update({ conversation_id: newConv?.id }).eq("id", requestId);

  // Add both to contacts table
  await supabase.from("contacts").upsert({ user_id: userId, contact_user_id: msgReq.sender_id }, { onConflict: "user_id,contact_user_id" });
  await supabase.from("contacts").upsert({ user_id: msgReq.sender_id, contact_user_id: userId }, { onConflict: "user_id,contact_user_id" });

  // Insert the initial message into the messages table
  await supabase.from("messages").insert({
    conversation_id: newConv?.id, sender_id: msgReq.sender_id,
    type: "TEXT", text: msgReq.initial_message, status: "DELIVERED",
    timestamp_millis: Date.now(), is_outgoing: false,
  });

  return json({ accepted: true, conversationId: newConv?.id });
}
serve(handler, { port: 9034 });
