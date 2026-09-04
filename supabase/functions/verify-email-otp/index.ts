// Edge function: verify-email-otp
// ----------------------------------------------------------------------------
// Verifies a 6-digit OTP against the latest unexpired, unconsumed row in
// `public.otp_codes` for the given (identifier, purpose). Enforces attempt
// limiting and marks the OTP consumed on success.
//
// IMPORTANT: This function does NOT require a JWT — it's called by users who
// haven't confirmed their email yet (so they have no session). Rate limiting
// is enforced per-IP to prevent brute-force. Deploy with --no-verify-jwt.
//
// Env vars: none (uses service role to read/update otp_codes).
//
// Request body:
//   { "email": string, "code": string, "purpose": string }
//
// Response 200: { "verified": true }
// Response 4xx: { "verified": false, "error": string, "attemptsRemaining"?: number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const OTP_MAX_ATTEMPTS = 5;
const VERIFY_LIMIT = { maxRequests: 20, windowSeconds: 300, name: "verify_email_otp" }; // 20 per 5 min per IP

interface VerifyOtpBody {
  email?: string;
  code?: string;
  purpose?: string;
}

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}

async function verifyCode(code: string, storedCombo: string): Promise<boolean> {
  const parts = storedCombo.split(":");
  if (parts.length === 2) {
    const [salt, hash] = parts;
    const data = new TextEncoder().encode(`${code}:${salt}`);
    const digest = await crypto.subtle.digest("SHA-256", data);
    const candidate = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
    return candidate === hash;
  }
  // Bare hash fallback (no salt).
  const data = new TextEncoder().encode(code);
  const digest = await crypto.subtle.digest("SHA-256", data);
  const candidate = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
  return candidate === storedCombo;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  // --- Rate limit per IP (no JWT needed — unauthenticated users call this) ---
  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const rl = checkRateLimit(req, ip, VERIFY_LIMIT);
  if (!rl.allowed) {
    return json(
      { verified: false, error: `Too many verification attempts. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter },
      429,
    );
  }

  let body: VerifyOtpBody;
  try {
    body = await req.json();
  } catch {
    return json({ verified: false, error: "Invalid request body", code: ErrorCode.VALIDATION_FAILED }, 400);
  }

  const email = (body.email ?? "").trim().toLowerCase();
  const code = (body.code ?? "").trim();
  const purpose = body.purpose ?? "signup";

  // --- Input validation ---
  if (!email) return json({ verified: false, error: "Email is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!isValidEmail(email)) return json({ verified: false, error: "Please enter a valid email address", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!code) return json({ verified: false, error: "Verification code is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!/^\d{6}$/.test(code)) return json({ verified: false, error: "Code must be exactly 6 digits", code: ErrorCode.VALIDATION_FAILED }, 422);
  const validPurposes = ["signup", "recovery", "magic_link", "email_change", "phone_verify", "vault_reset"];
  if (!validPurposes.includes(purpose)) {
    return json({ verified: false, error: "Invalid verification purpose", code: ErrorCode.VALIDATION_FAILED }, 422);
  }

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
    return json({ verified: false, error: "Verification failed. Please try again.", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  const otp = rows?.[0];
  if (!otp) {
    return json({ verified: false, error: "No verification code was found for this email. Please request a new code.", code: ErrorCode.NOT_FOUND }, 404);
  }
  if (otp.consumed_at) {
    return json({ verified: false, error: "This code has already been used. Please request a new one.", code: ErrorCode.CONSUMED }, 410);
  }
  if (new Date(otp.expires_at).getTime() < Date.now()) {
    return json({ verified: false, error: "This code has expired. Please request a new one.", code: ErrorCode.EXPIRED }, 410);
  }
  if (otp.attempts >= (otp.max_attempts ?? OTP_MAX_ATTEMPTS)) {
    return json({ verified: false, error: "Too many incorrect attempts. Please request a new code.", code: ErrorCode.LOCKED_OUT }, 429);
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
      { verified: false, error: remaining > 0 ? `Incorrect code. ${remaining} attempt${remaining === 1 ? "" : "s"} remaining.` : "Too many incorrect attempts. Please request a new code.", code: ErrorCode.VALIDATION_FAILED, attemptsRemaining: remaining },
      400,
    );
  }

  // --- Success: mark consumed + confirm email in auth.users ----------------
  await supabase
    .from("otp_codes")
    .update({ consumed_at: new Date().toISOString(), attempts: (otp.attempts ?? 0) + 1 })
    .eq("id", otp.id);

  // CRITICAL: If this is a signup OTP, confirm the user's email in auth.users.
  // Without this, email_confirmed_at stays NULL → login fails with "Email not confirmed".
  if (purpose === "signup") {
    // Look up the user by email + confirm their email.
    const { data: users } = await supabase.auth.admin.listUsers({
      page: 1,
      perPage: 1000,
    });
    const user = users?.users?.find((u: any) => u.email?.toLowerCase() === email);
    if (user) {
      const { error: confirmError } = await supabase.auth.admin.updateUserById(user.id, {
        email_confirm: true,
      });
      if (confirmError) {
        console.error("Failed to confirm email:", confirmError);
      }
    }
  }

  return json({ verified: true });
}

serve(handler, { port: 9002 });
