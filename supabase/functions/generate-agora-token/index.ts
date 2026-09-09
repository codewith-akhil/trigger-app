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
import { createAdminClient, createUserClient } from "../_shared/supabase.ts";

interface TokenRequestBody {
  channelName?: string;
  uid?: number | string;
  role?: "publisher" | "subscriber" | "host" | "audience";
  expirationSeconds?: number;
  /** When present, the token is for a 1:1 call — the caller must be a
   *  participant of that call_sessions row and the channel is derived from
   *  the row (client-supplied channelName is ignored for calls). */
  callId?: string;
}

function sanitizeChannelName(raw: string): string {
  // Match AgoraConfig.sanitizeChannelName on the client EXACTLY: keep
  // [A-Za-z0-9_-], replace other chars with "_", cap 64. The previous version
  // stripped "_" and "-" while the client kept them — so the token was signed
  // for a DIFFERENT channel than the one joined (ERR_INVALID_TOKEN on every
  // token-authenticated call/stream).
  const sanitized = (raw ?? "").replace(/[^a-zA-Z0-9_-]/g, "_").substring(0, 64);
  return sanitized.length > 0
    ? sanitized
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

  // --- Channel ownership / participant authorization -------------------------
  // Previously ANY authenticated user could mint a PUBLISHER token for ANY
  // channel name (call squatting/spoofing). Now:
  //  • calls  — callId required-ish: participant check against call_sessions,
  //             channel derived from the row;
  //  • streams — channelName must be a live_streams channel; PUBLISHER only
  //             for the stream owner, everyone else gets SUBSCRIBER;
  //  • anything else — 403 (no tokens for unknown channels).
  let effectiveChannel = channelName;
  let effectiveRoleEnum: number;
  const admin = createAdminClient();

  if (body.callId) {
    const callId = String(body.callId).trim();
    const { data: call, error: callError } = await admin
      .from("call_sessions")
      .select("id, caller_id, receiver_id, channel_name")
      .eq("id", callId)
      .maybeSingle();
    if (callError) {
      console.error("call_sessions lookup failed", callError);
      return errorResponse("Failed to verify call", 500);
    }
    if (!call) return errorResponse("Call not found", 404);
    if (call.caller_id !== userData.user.id && call.receiver_id !== userData.user.id) {
      return errorResponse("You are not a participant of this call", 403);
    }
    effectiveChannel = sanitizeChannelName(call.channel_name ?? "");
    effectiveRoleEnum = resolveRole(body.role);
  } else {
    // Stream path: resolve the channel against live_streams.
    const { data: stream, error: streamError } = await admin
      .from("live_streams")
      .select("id, host_id, channel_name, status")
      .eq("channel_name", body.channelName.trim())
      .maybeSingle();
    if (streamError) {
      console.error("live_streams lookup failed", streamError);
      return errorResponse("Failed to verify stream", 500);
    }
    if (!stream) {
      return errorResponse("Unknown channel — tokens are only issued for real calls or streams", 403);
    }
    const isHost = stream.host_id === userData.user.id;
    const wantsPublisher = resolveRole(body.role) === RtcRole.PUBLISHER;
    if (wantsPublisher && !isHost) {
      // Viewers may NEVER publish into someone else's stream.
      effectiveRoleEnum = RtcRole.SUBSCRIBER;
    } else {
      effectiveRoleEnum = resolveRole(body.role);
    }
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

  // Strict role + expiry validation — an unknown role silently became
  // PUBLISHER and a string expirationSeconds produced NaN (NaN expiry =>
  // token instantly invalid).
  if (body.role !== undefined && !["publisher", "host", "subscriber", "audience"].includes(String(body.role))) {
    return errorResponse("role must be publisher|host|subscriber|audience", 422);
  }
  if (body.expirationSeconds !== undefined && typeof body.expirationSeconds !== "number") {
    return errorResponse("expirationSeconds must be a number", 422);
  }
  const roleEnum = effectiveRoleEnum;
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
    // APP-ID-ONLY MODE: the Agora project was created without a primary
    // certificate (or it was never provisioned as a secret). Previously this
    // returned 500 → the client silently fell back to an empty token and the
    // call hung with no diagnosable error. Now we explicitly return the
    // app-id-only contract so the client joins with an empty token, which is
    // exactly what a certificate-disabled ("test mode") Agora project expects.
    console.warn("AGORA_PRIMARY_CERTIFICATE not set — serving app-id-only mode");
    return json({
      token: "",
      appId,
      channelName: effectiveChannel,
      uid,
      role: body.role ?? "publisher",
      expiresAt: Math.floor(Date.now() / 1000) + expirationSeconds,
      mode: "appid_only",
    });
  }

  // --- Build the real AccessToken2 -----------------------------------------
  let token: string;
  try {
    const currentTs = Math.floor(Date.now() / 1000);
    const privilegeExpiredTs = currentTs + expirationSeconds;
    token = RtcTokenBuilder.buildTokenWithUid(
      appId,
      appCertificate,
      effectiveChannel,
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
    channelName: effectiveChannel,
    uid,
    role: body.role ?? "publisher",
    expiresAt: Math.floor(Date.now() / 1000) + expirationSeconds,
  });
}

serve(handler, { port: 9000 });
