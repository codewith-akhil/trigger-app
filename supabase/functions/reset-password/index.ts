// Edge function: reset-password
// ----------------------------------------------------------------------------
// Resets the user's password after they verify a recovery OTP. This replaces
// the client-side updatePassword() call which required a session JWT (causing
// "Invalid claim: missing sub claim" when the user had no session after OTP
// recovery verification).
//
// Flow:
//   1. User requests password reset → send-email-otp (purpose=recovery)
//   2. User enters the 6-digit code → verify-email-otp (marks OTP consumed)
//   3. User enters new password → THIS function (verifies OTP again + sets password)
//
// This function does NOT require a JWT — it uses the service role to:
//   - Re-verify the recovery OTP (must be consumed + not expired)
//   - Look up the user by email
//   - Update their password via auth.admin.updateUserById
//
// Request body:
//   { "email": string, "code": string, "newPassword": string }
//
// Response 200: { "reset": true }
// Response 4xx: { "reset": false, "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const RESET_LIMIT = { maxRequests: 5, windowSeconds: 300, name: "reset_password" };

interface Body {
  email?: string;
  code?: string;
  newPassword?: string;
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
  return false;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous").split(",")[0].trim();
  const rl = checkRateLimit(req, ip, RESET_LIMIT);
  if (!rl.allowed) {
    return json({ reset: false, error: `Too many reset attempts. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return json({ reset: false, error: "Invalid request body", code: ErrorCode.VALIDATION_FAILED }, 400); }

  const email = (body.email ?? "").trim().toLowerCase();
  const code = (body.code ?? "").trim();
  const newPassword = body.newPassword ?? "";

  // --- Input validation ---
  if (!email) return json({ reset: false, error: "Email is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!isValidEmail(email)) return json({ reset: false, error: "Please enter a valid email address", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!/^\d{6}$/.test(code)) return json({ reset: false, error: "Verification code must be 6 digits", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!newPassword) return json({ reset: false, error: "New password is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (newPassword.length < 6) return json({ reset: false, error: "Password must be at least 6 characters", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (newPassword.length > 72) return json({ reset: false, error: "Password must be 72 characters or fewer", code: ErrorCode.VALIDATION_FAILED }, 422);

  const supabase = createAdminClient();

  // --- Re-verify the recovery OTP (must be consumed + not expired) ---
  const { data: otpRows } = await supabase
    .from("otp_codes")
    .select("id, code_hash, expires_at, consumed_at, attempts, max_attempts")
    .eq("identifier", email)
    .eq("purpose", "recovery")
    .order("created_at", { ascending: false })
    .limit(1);

  const otp = otpRows?.[0];
  if (!otp) {
    return json({ reset: false, error: "No password reset was initiated. Please request a new code.", code: ErrorCode.NOT_FOUND }, 404);
  }
  if (!otp.consumed_at) {
    return json({ reset: false, error: "Please verify the 6-digit code first before setting a new password.", code: ErrorCode.VALIDATION_FAILED }, 422);
  }
  if (new Date(otp.expires_at).getTime() < Date.now()) {
    return json({ reset: false, error: "Your reset session has expired. Please request a new code.", code: ErrorCode.EXPIRED }, 410);
  }

  // Verify the code matches (double-check — the verify-email-otp function already
  // consumed it, but we re-verify to ensure the caller actually knows the code).
  const matched = await verifyCode(code, otp.code_hash);
  if (!matched) {
    return json({ reset: false, error: "Incorrect verification code. Please try again.", code: ErrorCode.VALIDATION_FAILED }, 400);
  }

  // --- Look up the user by email + update their password ---
  // profiles.email lookup first (UNIQUE + not capped at 1000 rows like the
  // listUsers page-1 scan, which silently missed users #1001+ → their reset
  // returned "No account found" forever).
  const { data: profileRow } = await supabase
    .from("profiles")
    .select("id")
    .eq("email", email)
    .limit(1);
  let user: { id: string } | null = profileRow && profileRow.length > 0
    ? { id: profileRow[0].id }
    : null;
  if (!user) {
    const { data: users } = await supabase.auth.admin.listUsers({ page: 1, perPage: 1000 });
    user = users?.users?.find((u: any) => u.email?.toLowerCase() === email) ?? null;
  }
  if (!user) {
    return json({ reset: false, error: "No account found with this email address.", code: ErrorCode.NOT_FOUND }, 404);
  }

  const { error: updateError } = await supabase.auth.admin.updateUserById(user.id, {
    password: newPassword,
    email_confirm: true, // also confirm the email in case it wasn't
  });

  if (updateError) {
    console.error("Password update failed:", updateError);
    return json({ reset: false, error: "Failed to update password. Please try again.", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  return json({ reset: true });
}

serve(handler, { port: 9029 });
