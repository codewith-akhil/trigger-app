// Edge function: register-push-token
// ----------------------------------------------------------------------------
// Upserts the caller's FCM device token into `public.push_tokens` (called after
// FirebaseMessaging.getInstance().token refresh). Deactivates stale tokens for
// the same device and marks the new one active.
//
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "fcmToken": string, "deviceId": string, "platform"?: string,
//     "appVersion"?: string }
//
// Response 200: { "registered": true, "tokenId": string }
// Response 4xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  fcmToken?: string;
  deviceId?: string;
  platform?: string;
  appVersion?: string;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const fcmToken = (body.fcmToken ?? "").trim();
  const deviceId = (body.deviceId ?? "").trim();
  if (!fcmToken) return errorResponse("fcmToken is required", 422);
  if (!deviceId) return errorResponse("deviceId is required", 422);

  const supabase = createAdminClient();

  // Deactivate any other tokens on the same device first (token rotation).
  await supabase
    .from("push_tokens")
    .update({ is_active: false })
    .eq("user_id", userId)
    .eq("device_id", deviceId)
    .neq("fcm_token", fcmToken);

  const { data, error } = await supabase
    .from("push_tokens")
    .upsert(
      {
        user_id: userId,
        fcm_token: fcmToken,
        device_id: deviceId,
        platform: body.platform ?? "android",
        app_version: body.appVersion ?? null,
        is_active: true,
        last_seen_at: new Date().toISOString(),
      },
      { onConflict: "user_id,device_id" },
    )
    .select("id")
    .single();

  if (error) {
    console.error("push token upsert failed", error);
    return errorResponse("Failed to register push token", 500);
  }

  return json({ registered: true, tokenId: data?.id });
}

serve(handler, { port: 9009 });
