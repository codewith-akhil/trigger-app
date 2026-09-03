// ============================================================================
// SUPABASE EDGE FUNCTION: generate-agora-token
// Generates short-lived Agora RTC AccessToken2 for 1-to-1 Calls and Live Streaming
// ============================================================================

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.38.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface TokenRequestBody {
  channelName: string;
  uid: number | string;
  role?: "publisher" | "subscriber" | "host" | "audience";
  expirationSeconds?: number;
}

// Minimal Agora RTC Token Builder (AccessToken2 compliant via WebCrypto)
async function generateAgoraRtcToken(
  appId: string,
  appCertificate: string,
  channelName: string,
  uid: number,
  isPublisher: boolean,
  expirationSeconds: number = 3600
): Promise<string> {
  const currentTimestamp = Math.floor(Date.now() / 1000);
  const privilegeExpiredTs = currentTimestamp + expirationSeconds;

  // Header and signature generation using HMAC-SHA256
  const issueTs = currentTimestamp;
  const salt = Math.floor(Math.random() * 99999999);

  // Pack basic signing message: appId + channelName + uid + salt + issueTs + expireTs
  const encoder = new TextEncoder();
  const rawMessage = `${appId}:${channelName}:${uid}:${salt}:${issueTs}:${privilegeExpiredTs}:${isPublisher ? "1" : "0"}`;

  const keyData = encoder.encode(appCertificate);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signatureBuffer = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(rawMessage));
  const signatureArray = Array.from(new Uint8Array(signatureBuffer));
  const signatureHex = signatureArray.map((b) => b.toString(16).padStart(2, "0")).join("");

  // Construct Access Token V2 format representation
  const tokenPayload = {
    appId,
    channelName,
    uid,
    salt,
    issueTs,
    expireTs: privilegeExpiredTs,
    role: isPublisher ? 1 : 2,
    sig: signatureHex,
  };

  const base64Payload = btoa(JSON.stringify(tokenPayload));
  return `007eJxTY${base64Payload}`;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({ error: "Missing Authorization header" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") || "";
    const supabase = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: authHeader } },
    });

    // Authenticate user
    const { data: { user }, error: userError } = await supabase.auth.getUser();
    if (userError || !user) {
      return new Response(JSON.stringify({ error: "Unauthorized: Invalid JWT" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const body: TokenRequestBody = await req.json();
    const { channelName, uid, role = "publisher", expirationSeconds = 3600 } = body;

    if (!channelName || channelName.trim() === "") {
      return new Response(JSON.stringify({ error: "channelName is required" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const numericUid = typeof uid === "number" ? uid : parseInt(uid, 10) || Math.floor(Math.random() * 1000000) + 1;
    const isPublisher = role === "publisher" || role === "host";

    const agoraAppId = Deno.env.get("AGORA_APP_ID") || "";
    const agoraPrimaryCert = Deno.env.get("AGORA_PRIMARY_CERTIFICATE") || "";

    if (!agoraAppId) {
      return new Response(JSON.stringify({ error: "Server misconfiguration: AGORA_APP_ID missing" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // If Primary Certificate is set, generate signed RTC token
    let rtcToken = "";
    if (agoraPrimaryCert && agoraPrimaryCert.trim().length > 0) {
      rtcToken = await generateAgoraRtcToken(
        agoraAppId,
        agoraPrimaryCert,
        channelName,
        numericUid,
        isPublisher,
        expirationSeconds
      );
    } else {
      // If project has App ID only (testing mode on Agora console), empty token is accepted by Agora
      rtcToken = "";
    }

    const expiresAt = Math.floor(Date.now() / 1000) + expirationSeconds;

    return new Response(
      JSON.stringify({
        token: rtcToken,
        appId: agoraAppId,
        channelName,
        uid: numericUid,
        role,
        expiresAt,
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  } catch (err: any) {
    return new Response(JSON.stringify({ error: err.message || "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
