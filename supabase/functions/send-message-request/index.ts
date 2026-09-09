// Edge function: send-message-request
// Sends the first message to a stranger. Instagram model:
//   - Creates a message_request row + a PENDING canonical conversation owned
//     by the sender, and inserts the message into the canonical conversation.
//   - The sender may send up to 3 messages until the receiver accepts.
//   - If the pair can already chat (accepted conversation), returns 409 so
//     the client opens the chat directly.
//   - If blocked, returns 403.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

// 20 new requests / 5 min per user — request-sending is an abuse surface.
const REQ_LIMIT = { maxRequests: 20, windowSeconds: 300, name: "send_message_request" };

const MAX_REQUEST_MESSAGES = 3;

interface Body { receiverId?: string; receiverName?: string; message?: string; }

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const senderId = await resolveUserId(req.headers.get("Authorization"));
  if (!senderId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, senderId, REQ_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const receiverId = body.receiverId ?? "";
  const message = (body.message ?? "").trim();
  if (!isUuid(receiverId)) return json({ error: "receiverId is required" }, 422);
  if (receiverId === senderId) return json({ error: "You cannot message yourself" }, 422);
  if (!message) return json({ error: "Message is required" }, 422);
  if (message.length > 500) return json({ error: "Message too long (max 500)" }, 422);

  const supabase = createAdminClient();

  // Receiver must exist
  const { data: receiver } = await supabase.from("profiles").select("id").eq("id", receiverId).maybeSingle();
  if (!receiver) return json({ error: "User not found" }, 404);

  // Blocked-contacts enforcement (Task 24) — both directions.
  {
    const { data: blockedRow } = await supabase
      .from("blocked_contacts")
      .select("id")
      .or(`and(user_id.eq.${senderId},blocked_user_id.eq.${receiverId}),and(user_id.eq.${receiverId},blocked_user_id.eq.${senderId})`)
      .limit(1);
    if (blockedRow && blockedRow.length > 0) {
      return json({ error: "You can't message this user", code: "BLOCKED" }, 403);
    }
  }

  // Sender profile (request card + conversation metadata)
  const { data: sender } = await supabase
    .from("profiles").select("full_name, username, avatar_url").eq("id", senderId).maybeSingle();
  if (!sender) return json({ error: "Sender profile not found" }, 404);

  // Find the CANONICAL conversation (oldest row for the pair, either direction
  // — the same deterministic rule send-message uses).
  const { data: existingConvs } = await supabase
    .from("conversations")
    .select("id, owner_id, peer_id, request_status, created_at")
    .or(`and(owner_id.eq.${senderId},peer_id.eq.${receiverId}),and(owner_id.eq.${receiverId},peer_id.eq.${senderId})`)
    .order("created_at", { ascending: true })
    .limit(1);
  const existingConv = existingConvs && existingConvs.length > 0 ? existingConvs[0] : null;

  if (existingConv) {
    const status = existingConv.request_status;
    if (status === "accepted") {
      return json({ error: "You can already chat with this user", code: "ALREADY_CONNECTED" }, 409);
    }
    if (status === "blocked") {
      return json({ error: "This conversation is blocked", code: "CONVERSATION_BLOCKED" }, 403);
    }
    if (status === "declined") {
      return json({ error: "Your earlier message request was declined", code: "REQUEST_DECLINED" }, 403);
    }
    // pending — the sender may send up to MAX_REQUEST_MESSAGES messages.
    if (existingConv.owner_id === senderId) {
      const { count } = await supabase
        .from("messages")
        .select("id", { count: "exact", head: true })
        .eq("conversation_id", existingConv.id)
        .eq("sender_id", senderId);
      const sent = count ?? 0;
      if (sent >= MAX_REQUEST_MESSAGES) {
        return json({
          error: `You can send up to ${MAX_REQUEST_MESSAGES} messages while your request is pending`,
          code: "REQUEST_MESSAGE_LIMIT",
        }, 403);
      }
      // Insert this follow-up message ATOMICALLY — the count-then-insert
      // above (and this insert) were two separate steps; two concurrent
      // requests could both pass the count and push past the 3-message cap.
      const { data: msg, error: msgErr } = await supabase.rpc(
        "try_send_pending_message",
        {
          p_conversation_id: existingConv.id,
          p_sender_id: senderId,
          p_text: message,
          p_timestamp_millis: null,
        }
      );
      if (msgErr || !msg) {
        const code = String((msgErr as Record<string, unknown>)?.message ?? "");
        if (code.includes("REQUEST_MESSAGE_LIMIT")) {
          return json({ error: `You can send up to ${MAX_REQUEST_MESSAGES} messages while your request is pending`, code: "REQUEST_MESSAGE_LIMIT" }, 403);
        }
        return json({ error: "Failed to send message" }, 500);
      }
      await supabase.from("conversations").update({
        last_message: message.slice(0, 100),
        last_message_type: "TEXT",
        last_message_at: new Date().toISOString(),
      }).eq("id", existingConv.id);
      await supabase.from("message_requests").upsert({
        sender_id: senderId, receiver_id: receiverId,
        sender_name: sender.full_name, sender_username: sender.username,
        sender_avatar_url: sender.avatar_url, initial_message: message,
        status: "pending", conversation_id: existingConv.id,
      }, { onConflict: "sender_id,receiver_id" });
      return json({
        sent: true, status: "pending", conversationId: existingConv.id,
        messagesSent: sent + 1, messagesRemaining: MAX_REQUEST_MESSAGES - sent - 1,
      });
    }
    // The RECEIVER owns the pending row (they requested first in the other
    // direction at some point) — treat as already-connected prompt.
    return json({ error: "You can already chat with this user", code: "ALREADY_CONNECTED" }, 409);
  }

  // No conversation yet — create the canonical (sender-owned) pending row.
  // Task 24: creation goes through get_or_create_conversation (advisory lock
  // on the UNORDERED pair) so a first-request racing a first-message from the
  // other side cannot yield two pair rows. On a lost race, re-select the
  // winner and route through the same status gate.
  const { data: receiverProfile } = await supabase
    .from("profiles").select("full_name, avatar_url").eq("id", receiverId).maybeSingle();
  const { data: rpcRes, error: rpcErr } = await supabase.rpc("get_or_create_conversation", {
    p_owner: senderId,
    p_peer: receiverId,
    p_peer_name: receiverProfile?.full_name ?? body.receiverName ?? "Unknown",
  });
  if (rpcErr || !rpcRes || rpcRes.length === 0) {
    console.error("send-message-request: conversation create failed", rpcErr);
    return json({ error: "Failed to send request" }, 500);
  }
  const rpcRow = rpcRes[0] as { conversation_id: string; was_created: boolean };
  if (!rpcRow.was_created) {
    const { data: winner } = await supabase
      .from("conversations")
      .select("id, owner_id, peer_id, request_status")
      .eq("id", rpcRow.conversation_id)
      .maybeSingle();
    if (winner) {
      if (winner.request_status === "accepted") {
        return json({ error: "You can already chat with this user", code: "ALREADY_CONNECTED" }, 409);
      }
      if (winner.request_status === "blocked" || winner.request_status === "declined") {
        return json({ error: "This conversation is not open for messages", code: "CONVERSATION_BLOCKED" }, 403);
      }
      if (winner.owner_id === senderId) {
        // Pending row created by a concurrent request from us — insert this
        // message atomically and fall through to the normal pending return.
        const { data: msg, error: msgErr } = await supabase.rpc("try_send_pending_message", {
          p_conversation_id: winner.id,
          p_sender_id: senderId,
          p_text: message,
          p_timestamp_millis: null,
        });
        if (msgErr || !msg) {
          return json({ error: "Failed to send message" }, 500);
        }
        await supabase.from("conversations").update({
          last_message: message.slice(0, 100), last_message_type: "TEXT",
          last_message_at: new Date().toISOString(),
        }).eq("id", winner.id);
        await supabase.from("message_requests").upsert({
          sender_id: senderId, receiver_id: receiverId,
          sender_name: sender.full_name, sender_username: sender.username,
          sender_avatar_url: sender.avatar_url, initial_message: message,
          status: "pending", conversation_id: winner.id,
        }, { onConflict: "sender_id,receiver_id" });
        const { count: sentNow } = await supabase
          .from("messages")
          .select("id", { count: "exact", head: true })
          .eq("conversation_id", winner.id)
          .eq("sender_id", senderId);
        return json({
          sent: true, status: "pending", conversationId: winner.id,
          messagesSent: sentNow ?? 1, messagesRemaining: Math.max(0, MAX_REQUEST_MESSAGES - (sentNow ?? 1)),
        });
      }
      return json({ error: "You can already chat with this user", code: "ALREADY_CONNECTED" }, 409);
    }
  }
  const conv = { id: rpcRow.conversation_id };

  // Stamp the sender's request text + receiver avatar on the canonical row
  // (get_or_create_conversation inserts a bare pending row).
  await supabase.from("conversations").update({
    peer_avatar_url: receiverProfile?.avatar_url ?? null,
    last_message: message, last_message_type: "TEXT",
    last_message_at: new Date().toISOString(),
  }).eq("id", conv.id);

  // Register the request and link it to the canonical conversation.
  const { error: reqError } = await supabase.from("message_requests").upsert({
    sender_id: senderId, receiver_id: receiverId,
    sender_name: sender.full_name, sender_username: sender.username,
    sender_avatar_url: sender.avatar_url, initial_message: message,
    status: "pending", conversation_id: conv.id,
  }, { onConflict: "sender_id,receiver_id" });
  if (reqError) {
    console.error("send-message-request: request upsert failed", reqError);
    return json({ error: "Failed to send request" }, 500);
  }

  // Insert the first message INTO the canonical conversation so the receiver
  // reads it on accept and the 3-message cap counts real rows.
  const { error: msgErr } = await supabase.from("messages").insert({
    conversation_id: conv.id,
    sender_id: senderId,
    type: "TEXT",
    text: message,
    status: "SENT",
    timestamp_millis: Date.now(),
    is_outgoing: true,
  });
  if (msgErr) {
    console.error("send-message-request: message insert failed", msgErr);
    return json({ error: "Failed to send message" }, 500);
  }

  return json({
    sent: true, status: "pending", conversationId: conv.id,
    messagesSent: 1, messagesRemaining: MAX_REQUEST_MESSAGES - 1,
  });
}
serve(handler, { port: 9033 });
