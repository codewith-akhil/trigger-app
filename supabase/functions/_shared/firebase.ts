// Shared Firebase Cloud Messaging (FCM) HTTP v1 sender.
// Sends push notifications without the heavy firebase-admin SDK by minting a
// Google OAuth2 access token from the service-account JSON and calling the
// FCM HTTP v1 endpoint directly.
//
// Env vars (set via `supabase secrets set`):
//   FIREBASE_PROJECT_ID
//   FIREBASE_CLIENT_EMAIL
//   FIREBASE_PRIVATE_KEY      (PEM string with \n escapes)
//   FIREBASE_MESSAGE_SENDER_ID  (optional, informational)
//
// Import pattern:
//   import { sendFcm } from "../_shared/firebase.ts";
//   const result = await sendFcm({ token, title, body, data });

interface FcmPayload {
  token: string;
  title: string;
  body: string;
  data?: Record<string, string>;
  imageUrl?: string;
  androidChannelId?: string;
  priority?: "normal" | "high";
}

interface FcmResult {
  name?: string;
  error?: string;
  invalidToken?: boolean;
}

function pemToDer(pem: string): JsonWebKey {
  // Decode PKCS#8 PEM RSA private key → JsonWebKey for WebCrypto import.
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = b64ToBytes(b64);
  return pkcs8ToJwk(der);
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

// Minimal PKCS#8 → JWK parser for RSA keys (handles the common FCM service-account shape).
function pkcs8ToJwk(der: Uint8Array): JsonWebKey {
  const view = new DataView(der.buffer, der.byteOffset, der.byteLength);
  let i = 0;
  const readLen = (): number => {
    const b = view.getUint8(i++);
    if (b & 0x80) {
      const n = b & 0x7f;
      let len = 0;
      for (let k = 0; k < n; k++) len = (len << 8) | view.getUint8(i++);
      return len;
    }
    return b;
  };
  const enter = () => { i++; return readLen(); };
  const skip = () => { const l = readLen(); i += l; };
  const readInt = (): Uint8Array => {
    i++; // tag 0x02
    const l = readLen();
    // drop leading zero (sign byte)
    let off = i;
    let len = l;
    if (der[off] === 0 && len > 1) { off++; len--; }
    i += l;
    return der.subarray(off, off + len);
  };
  const toB64Url = (u8: Uint8Array): string => {
    let s = "";
    for (const b of u8) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  };

  // SEQUENCE
  enter();
  // version
  skip();
  // AlgorithmIdentifier SEQUENCE
  skip();
  // PrivateKey OCTET STRING → SEQUENCE (RSAPrivateKey)
  enter(); // octet string header
  enter(); // RSA private key sequence
  readInt(); // version
  const n = readInt();
  const e = readInt();
  const d = readInt();
  // p, q, dp, dq, qinv — present but we only need n,e,d for signing
  return {
    kty: "RSA",
    n: toB64Url(n),
    e: toB64Url(e),
    d: toB64Url(d),
    ext: true,
  };
}

async function mintAccessToken(clientEmail: string, privateKeyPem: string, scope: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const jwk = pemToDer(privateKeyPem);
  const key = await crypto.subtle.importKey(
    "pkcs8",
    // Re-encode JWK→DER is complex; instead import via jwk form using "jwk" format.
    // WebCrypto can import JWK directly for RSASSA-PKCS1-v1_5.
    { ...jwk, alg: "RS256" } as JsonWebKey,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: clientEmail,
    scope,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const enc = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const unsigned = `${enc(header)}.${enc(claims)}`;
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const sigB64 = btoa(String.fromCharCode(...new Uint8Array(sig)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const jwt = `${unsigned}.${sigB64}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`Failed to mint FCM access token: ${data.error_description ?? data.error}`);
  return data.access_token;
}

export async function sendFcm(payload: FcmPayload): Promise<FcmResult> {
  const projectId = Deno.env.get("FIREBASE_PROJECT_ID");
  const clientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL");
  let privateKey = Deno.env.get("FIREBASE_PRIVATE_KEY") ?? "";
  if (!projectId || !clientEmail || !privateKey) {
    return { error: "Firebase service-account env vars are not configured" };
  }
  // Allow the PEM to be passed with literal \n escapes (common when stored as a single-line env var).
  privateKey = privateKey.replace(/\\n/g, "\n");
  if (!privateKey.includes("BEGIN PRIVATE KEY")) {
    return { error: "FIREBASE_PRIVATE_KEY is not a valid PEM" };
  }

  let accessToken: string;
  try {
    accessToken = await mintAccessToken(
      clientEmail,
      privateKey,
      "https://www.googleapis.com/auth/firebase.messaging",
    );
  } catch (e) {
    return { error: e instanceof Error ? e.message : "Failed to mint access token" };
  }

  const message: Record<string, unknown> = {
    message: {
      token: payload.token,
      android: {
        priority: payload.priority ?? "high",
        notification: {
          channel_id: payload.androidChannelId ?? "trigger_stream_notifications",
          default_sound: true,
          default_vibrate_timings: true,
          notification_count: 1,
        },
      },
      notification: {
        title: payload.title,
        body: payload.body,
        ...(payload.imageUrl ? { image: payload.imageUrl } : {}),
      },
      ...(payload.data ? { data: payload.data } : {}),
    },
  };

  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(message),
    },
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const errDetail = data?.error?.details?.[0];
    if (
      (typeof data?.error?.message === "string" && /registration-token|not valid|UNREGISTERED/i.test(data.error.message)) ||
      (errDetail && /UNREGISTERED/i.test(errDetail.reason ?? ""))
    ) {
      return { error: "Invalid or unregistered FCM token", invalidToken: true };
    }
    return { error: data?.error?.message ?? `FCM HTTP ${res.status}` };
  }
  return { name: data?.name };
}

/**
 * Broadcast a push to many tokens (parallel). Returns per-token results so the
 * caller can deactivate invalid tokens.
 */
export async function sendFcmBatch(payload: Omit<FcmPayload, "token">, tokens: string[]): Promise<(FcmResult & { token: string })[]> {
  return Promise.all(tokens.map(async (token) => ({ token, ...(await sendFcm({ ...payload, token })) })));
}
