// Edge function: send-call-invite
// ----------------------------------------------------------------------------
// REAL cross-device call signaling — the missing half of the Agora call flow.
// When a caller creates a `call_sessions` row they invoke this function so the
// RECEIVER's device is woken with a DATA-ONLY high-priority FCM push
// (data-only ⇒ FirebaseMessagingService.onMessageReceived fires even when the
// app is backgrounded/killed). The device then posts the incoming-call
// notification with Accept/Decline actions + full-screen intent.
//
// Also supports `action: "cancel"` so a caller who gives up (ring timeout /
// caller decline) immediately removes the ring on the receiver's device.
//
// SECURITY: requires a Supabase JWT. The caller must be the `caller_id` on
// the call_sessions row (receiver never calls this). Authorization of the
// call itself happens here via a service-role lookup — RLS still gates any
// direct table access.
//
// Request body:
//   { "callId": string, "action"?: "invite" | "cancel" }
//
// Response 200: { "sent": number, "skipped"?: boolean, "reason"?: string }
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { sendFcmBatch } from "../_shared/firebase.ts";

interface Body {
  callId?: string;
  action?: "invite" | "cancel";
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  // --- Authenticate ----------------------------------------------------------
  const authHeader = req.headers.get("Authorization");
  const callerId = await resolveUserId(authHeader);
  if (!callerId) return errorResponse("Unauthorized", 401);

  // --- Parse + validate ------------------------------------------------------
  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }
  const callId = (body.callId ?? "").trim();
  if (!callId) return errorResponse("callId is required", 422);
  const action = body.action === "cancel" ? "cancel" : "invite";

  const admin = createAdminClient();

  // --- Load the call row (service role — RLS is not bypassed for users, the
  //     participant check below is STRICTER than RLS) -------------------------
  const { data: call, error: callError } = await admin
    .from("call_sessions")
    .select("id, caller_id, receiver_id, call_type, channel_name, status")
    .eq("id", callId)
    .maybeSingle();
  if (callError) {
    console.error("call_sessions lookup failed", callError);
    return errorResponse("Failed to load call", 500);
  }
  if (!call) return errorResponse("Call not found", 404);

  // --- Authorization: only the CALLER may ring/cancel ------------------------
  if (call.caller_id !== callerId) {
    return errorResponse("Only the caller can send or cancel the ring", 403);
  }

  // --- Resolve caller display name -------------------------------------------
  const { data: profile } = await admin
    .from("profiles")
    .select("full_name, username")
    .eq("id", callerId)
    .maybeSingle();
  const callerName =
    profile?.full_name?.trim() || profile?.username?.trim() || "Trigger user";

  // --- Fetch active FCM tokens for the receiver ------------------------------
  const { data: tokenRows, error: tokenError } = await admin
    .from("push_tokens")
    .select("fcm_token")
    .eq("user_id", call.receiver_id)
    .eq("is_active", true);
  if (tokenError) {
    console.error("token lookup failed", tokenError);
    return errorResponse("Failed to look up push tokens", 500);
  }
  const tokens = (tokenRows ?? []).map((r: { fcm_token?: string }) => r.fcm_token).filter(Boolean);
  if (tokens.length === 0) {
    return json({ sent: 0, skipped: true, reason: "no_tokens" });
  }

  // --- Send data-only push (no notification payload → onMessageReceived) -----
  const isVideo = call.call_type === "video";
  const data =
    action === "invite"
      ? {
          type: "incoming_call",
          callId: call.id,
          channelName: call.channel_name ?? "",
          callType: isVideo ? "video" : "audio",
          callerId: call.caller_id,
          callerName,
        }
      : {
          type: "call_cancelled",
          callId: call.id,
        };

  const results = await sendFcmBatch(
    {
      // Data-only: title/body are ignored by the transport.
      title: callerName,
      body: action === "invite" ? "Incoming call" : "Call cancelled",
      data,
      priority: "high",
      dataOnly: true,
    },
    tokens,
  );

  // --- Deactivate invalid tokens ---------------------------------------------
  const invalidTokens = results.filter((r) => r.invalidToken).map((r) => r.token);
  if (invalidTokens.length > 0) {
    await admin.from("push_tokens").update({ is_active: false }).in("fcm_token", invalidTokens);
  }

  const sent = results.filter((r) => r.name).length;
  const errors = results.filter((r) => r.error && !r.invalidToken).map((r) => r.error!);
  return json({ sent, skipped: false, errors });
}

serve(handler, { port: 9000 });
