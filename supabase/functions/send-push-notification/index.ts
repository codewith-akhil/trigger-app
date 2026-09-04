// Edge function: send-push-notification
// ----------------------------------------------------------------------------
// Sends a Firebase Cloud Messaging (FCM) push notification to one or many
// users. Looks up active FCM tokens from `public.push_tokens`, sends via the
// FCM HTTP v1 API, and deactivates invalid tokens.
//
// Env vars: FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, FIREBASE_PRIVATE_KEY
// Auth: requires a valid Supabase JWT (only the user themselves or a server
// trigger should call this — there is no admin role check here; for server-
// triggered flows use the service-role key from another edge function).
//
// Request body:
//   { "userId": string,                  // recipient
//     "title": string, "body": string,
//     "data"?: Record<string,string>,
//     "imageUrl"?: string,
//     "androidChannelId"?: string,
//     "priority"?: "normal" | "high" }
//
// Response 200: { "sent": number, "deactivated": number, "errors": string[] }
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { sendFcmBatch } from "../_shared/firebase.ts";

interface Body {
  userId?: string;
  title?: string;
  body?: string;
  data?: Record<string, string>;
  imageUrl?: string;
  androidChannelId?: string;
  priority?: "normal" | "high";
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const callerId = await resolveUserId(authHeader);
  if (!callerId) return errorResponse("Unauthorized", 401);

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const targetUserId = body.userId ?? callerId;
  if (!body.title?.trim() || !body.body?.trim()) {
    return errorResponse("title and body are required", 422);
  }

  const supabase = createAdminClient();

  // --- Fetch active tokens for the recipient -------------------------------
  const { data: tokenRows, error: tokenError } = await supabase
    .from("push_tokens")
    .select("fcm_token")
    .eq("user_id", targetUserId)
    .eq("is_active", true);

  if (tokenError) {
    console.error("Token lookup failed", tokenError);
    return errorResponse("Failed to look up push tokens", 500);
  }

  const tokens = (tokenRows ?? []).map((r) => r.fcm_token).filter(Boolean);
  if (tokens.length === 0) {
    return json({ sent: 0, deactivated: 0, errors: ["No active push tokens for user"] });
  }

  // --- Broadcast via FCM ----------------------------------------------------
  const results = await sendFcmBatch(
    {
      title: body.title!,
      body: body.body!,
      data: body.data,
      imageUrl: body.imageUrl,
      androidChannelId: body.androidChannelId ?? "trigger_stream_notifications",
      priority: body.priority ?? "high",
    },
    tokens,
  );

  // --- Deactivate invalid tokens -------------------------------------------
  const invalidTokens = results.filter((r) => r.invalidToken).map((r) => r.token);
  if (invalidTokens.length > 0) {
    await supabase
      .from("push_tokens")
      .update({ is_active: false })
      .in("fcm_token", invalidTokens);
  }

  const sent = results.filter((r) => r.name).length;
  const errors = results.filter((r) => r.error && !r.invalidToken).map((r) => r.error!);

  return json({ sent, deactivated: invalidTokens.length, errors });
}

serve(handler, { port: 9005 });
