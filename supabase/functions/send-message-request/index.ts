// Edge function: send-message-request
// Sends the first message to a stranger (creates a message_request + a pending conversation).
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { receiverId?: string; receiverName?: string; message?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const senderId = await resolveUserId(req.headers.get("Authorization"));
  if (!senderId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const receiverId = body.receiverId ?? "";
  const message = (body.message ?? "").trim();
  if (!receiverId) return json({ error: "receiverId is required" }, 422);
  if (!message) return json({ error: "Message is required" }, 422);
  if (message.length > 500) return json({ error: "Message too long (max 500)" }, 422);

  const supabase = createAdminClient();

  // Get sender profile
  const { data: sender } = await supabase.from("profiles").select("full_name, username, avatar_url").eq("id", senderId).maybeSingle();
  if (!sender) return json({ error: "Sender profile not found" }, 404);

  // Check if already contacts or have an accepted conversation
  const { data: existingConv } = await supabase
    .from("conversations")
    .select("id, request_status")
    .or(`and(owner_id.eq.${senderId},peer_id.eq.${receiverId}),and(owner_id.eq.${receiverId},peer_id.eq.${senderId})`)
    .limit(1);

  if (existingConv && existingConv.length > 0) {
    const conv = existingConv[0];
    if (conv.request_status === "accepted") {
      return json({ error: "You can already chat with this user" }, 409);
    }
    if (conv.request_status === "blocked") {
      return json({ error: "This conversation is blocked" }, 403);
    }
    // pending — update the message
    await supabase.from("message_requests").upsert({
      sender_id: senderId, receiver_id: receiverId,
      sender_name: sender.full_name, sender_username: sender.username,
      sender_avatar_url: sender.avatar_url, initial_message: message,
      status: "pending",
    }, { onConflict: "sender_id,receiver_id" });
    return json({ sent: true, status: "pending" });
  }

  // Create the message request
  const { error: reqError } = await supabase.from("message_requests").upsert({
    sender_id: senderId, receiver_id: receiverId,
    sender_name: sender.full_name, sender_username: sender.username,
    sender_avatar_url: sender.avatar_url, initial_message: message,
    status: "pending",
  }, { onConflict: "sender_id,receiver_id" });

  if (reqError) return json({ error: "Failed to send request" }, 500);

  // Create a pending conversation (sender side)
  const { data: receiverProfile } = await supabase.from("profiles").select("full_name, avatar_url").eq("id", receiverId).maybeSingle();
  await supabase.from("conversations").insert({
    owner_id: senderId, peer_id: receiverId,
    peer_name: receiverProfile?.full_name ?? "Unknown",
    peer_avatar_url: receiverProfile?.avatar_url,
    request_status: "pending", is_contact: false,
    last_message: message, last_message_type: "TEXT",
    last_message_at: new Date().toISOString(),
  });

  return json({ sent: true, status: "pending" });
}
serve(handler, { port: 9033 });
