// Edge function: verify-email-otp
// ----------------------------------------------------------------------------
// Verifies a 6-digit OTP against the latest unexpired, unconsumed row in
// `public.otp_codes` for the given (identifier, purpose). Enforces attempt
// limiting and marks the OTP consumed on success.
//
// Env vars: none (uses service role to read otp_codes).
//
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "email": string, "code": string, "purpose": string }
//
// Response 200: { "verified": true }
// Response 4xx: { "verified": false, "error": string, "attemptsRemaining"?: number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

const OTP_EXPIRY_SECONDS = 600;
const OTP_MAX_ATTEMPTS = 5;

interface VerifyOtpBody {
  email?: string;
  code?: string;
  purpose?: string;
}

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}

async function verifyCode(code: string, storedCombo: string): Promise<boolean> {
  // storedCombo may be "salt:hash" (current) or a bare hash (legacy).
  const parts = storedCombo.split(":");
  let candidate: string;
  if (parts.length === 2) {
    const [salt, hash] = parts;
    const data = new TextEncoder().encode(`${code}:${salt}`);
    const digest = await crypto.subtle.digest("SHA-256", data);
    candidate = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
    return candidate === hash;
  }
  // Bare hash fallback (no salt) — not ideal but kept for safety.
  const data = new TextEncoder().encode(code);
  const digest = await crypto.subtle.digest("SHA-256", data);
  candidate = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
  return candidate === storedCombo;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: VerifyOtpBody;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const email = (body.email ?? "").trim().toLowerCase();
  const code = (body.code ?? "").trim();
  const purpose = body.purpose ?? "signup";

  if (!isValidEmail(email)) return errorResponse("Invalid email", 422);
  if (!/^\d{6}$/.test(code)) return errorResponse("Code must be 6 digits", 422);

  const supabase = createAdminClient();

  const { data: rows, error } = await supabase
    .from("otp_codes")
    .select("id, code_hash, expires_at, attempts, max_attempts, consumed_at, created_at")
    .eq("identifier", email)
    .eq("purpose", purpose)
    .order("created_at", { ascending: false })
    .limit(1);

  if (error) {
    console.error("OTP lookup failed", error);
    return errorResponse("Verification failed", 500);
  }

  const otp = rows?.[0];
  if (!otp) {
    return json({ verified: false, error: "No code was issued for this email. Please request a new one." }, 404);
  }
  if (otp.consumed_at) {
    return json({ verified: false, error: "This code has already been used. Please request a new one." }, 410);
  }
  if (new Date(otp.expires_at).getTime() < Date.now()) {
    return json({ verified: false, error: "This code has expired. Please request a new one." }, 410);
  }
  if (otp.attempts >= (otp.max_attempts ?? OTP_MAX_ATTEMPTS)) {
    return json({ verified: false, error: "Too many incorrect attempts. Please request a new code." }, 429);
  }

  const matched = await verifyCode(code, otp.code_hash);

  if (!matched) {
    const newAttempts = (otp.attempts ?? 0) + 1;
    await supabase
      .from("otp_codes")
      .update({ attempts: newAttempts })
      .eq("id", otp.id);
    const remaining = Math.max((otp.max_attempts ?? OTP_MAX_ATTEMPTS) - newAttempts, 0);
    return json(
      { verified: false, error: "Incorrect code. Please try again.", attemptsRemaining: remaining },
      400,
    );
  }

  // --- Success: mark consumed ----------------------------------------------
  await supabase
    .from("otp_codes")
    .update({ consumed_at: new Date().toISOString(), attempts: (otp.attempts ?? 0) + 1 })
    .eq("id", otp.id);

  return json({ verified: true });
}

serve(handler, { port: 9002 });
