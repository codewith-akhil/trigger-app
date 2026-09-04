// Edge function: generate-agora-token
// ----------------------------------------------------------------------------
// Generates a REAL Agora AccessToken2 (RTC) for 1-to-1 calls and live streams.
//
// Replaces the previous FAKE implementation that produced a non-standard
// "007eJxTY<base64-json>" string which Agora servers reject when a primary
// certificate is enabled. This version uses the official `agora-access-token`
// npm package (AccessToken2 builder) loaded via esm.sh.
//
// SECURITY: The Agora Primary Certificate is read ONLY from the server-side
// env var `AGORA_PRIMARY_CERTIFICATE`. It is NEVER shipped to the client.
//
// Env vars (set via `supabase secrets set`):
//   AGORA_APP_ID              — Agora project App ID
//   AGORA_PRIMARY_CERTIFICATE — Agora primary certificate (server-only secret)
//
// Auth: requires a valid Supabase JWT in the Authorization header.
//
// Request body:
//   { "channelName": string, "uid": number | string,
//     "role": "publisher" | "subscriber" | "host" | "audience",
//     "expirationSeconds": number  // default 3600
//   }
//
// Response 200:
//   { "token": string, "appId": string, "channelName": string,
//     "uid": number, "role": string, "expiresAt": number }
//
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { RtcTokenBuilder, RtcRole } from "https://esm.sh/agora-access-token@2.0.4";
import { corsHeaders, handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createUserClient } from "../_shared/supabase.ts";

interface TokenRequestBody {
  channelName?: string;
  uid?: number | string;
  role?: "publisher" | "subscriber" | "host" | "audience";
  expirationSeconds?: number;
}

function sanitizeChannelName(raw: string): string {
  // Match AgoraConfig.sanitizeChannelName on the client: alphanumeric, 1-64 chars.
  const sanitized = (raw ?? "").replace(/[^A-Za-z0-9]/g, "");
  return sanitized.length > 0
    ? sanitized.substring(0, 64)
    : `channel_${Date.now()}`;
}

function resolveRole(role: string | undefined): number {
  // AccessToken2 uses a single Role enum. Publisher/Host → PUBLISHER (1),
  // Subscriber/Audience → SUBSCRIBER (2).
  switch (role) {
    case "publisher":
    case "host":
      return RtcRole.PUBLISHER;
    case "subscriber":
    case "audience":
      return RtcRole.SUBSCRIBER;
    default:
      return RtcRole.PUBLISHER;
  }
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  // --- Authenticate the caller ---------------------------------------------
  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return errorResponse("Missing Authorization header", 401);
  const userClient = createUserClient(authHeader);
  const { data: userData, error: authError } = await userClient.auth.getUser();
  if (authError || !userData?.user) {
    return errorResponse("Unauthorized", 401);
  }

  // --- Parse + validate body -----------------------------------------------
  let body: TokenRequestBody;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const channelName = sanitizeChannelName(body.channelName ?? "");
  if (!body.channelName || !body.channelName.trim()) {
    return errorResponse("channelName is required", 400);
  }

  let uid: number;
  if (typeof body.uid === "number") {
    uid = Math.floor(body.uid);
  } else if (typeof body.uid === "string" && body.uid.trim() !== "") {
    const parsed = parseInt(body.uid, 10);
    uid = Number.isFinite(parsed) && parsed > 0 ? parsed : Math.floor(Math.random() * 1_000_000) + 1;
  } else {
    uid = Math.floor(Math.random() * 1_000_000) + 1;
  }
  if (uid <= 0 || uid > 4294967295) {
    return errorResponse("uid must be between 1 and 4294967295", 400);
  }

  const roleEnum = resolveRole(body.role);
  const expirationSeconds = Math.min(
    Math.max(body.expirationSeconds ?? 3600, 60),
    86400, // cap at 24h
  );

  // --- Load server-only secrets --------------------------------------------
  const appId = Deno.env.get("AGORA_APP_ID");
  const appCertificate = Deno.env.get("AGORA_PRIMARY_CERTIFICATE");
  if (!appId) {
    return errorResponse("AGORA_APP_ID is not configured on the server", 500);
  }
  if (!appCertificate) {
    return errorResponse("AGORA_PRIMARY_CERTIFICATE is not configured on the server", 500);
  }

  // --- Build the real AccessToken2 -----------------------------------------
  let token: string;
  try {
    const currentTs = Math.floor(Date.now() / 1000);
    const privilegeExpiredTs = currentTs + expirationSeconds;
    token = RtcTokenBuilder.buildTokenWithUid(
      appId,
      appCertificate,
      channelName,
      uid,
      roleEnum,
      privilegeExpiredTs,
    );
  } catch (e) {
    console.error("Token generation failed", e);
    return errorResponse("Failed to generate Agora token", 500);
  }

  return json({
    token,
    appId,
    channelName,
    uid,
    role: body.role ?? "publisher",
    expiresAt: Math.floor(Date.now() / 1000) + expirationSeconds,
  });
}

serve(handler, { port: 9000 });
