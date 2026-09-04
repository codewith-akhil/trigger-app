// Edge function: send-email-otp
// ----------------------------------------------------------------------------
// Generates a 6-digit OTP server-side, stores a SHA-256 hash in `public.otp_codes`
// (10-minute expiry, 60-second resend cooldown), and emails the code via Resend.
//
// IMPORTANT: This function does NOT require a JWT — it's called by users who
// haven't signed up / logged in yet (so they have no session). Rate limiting
// is enforced per-IP to prevent abuse. Deploy with --no-verify-jwt.
//
// Env vars:
//   RESEND_API_KEY, FROM_EMAIL, REPLY_TO_EMAIL
//
// Request body:
//   { "email": string, "purpose": "signup" | "recovery" | "magic_link"
//            | "email_change" | "phone_verify" | "vault_reset" }
//
// Response 200: { "sent": true, "resendAvailableIn": 60, "expiresIn": 600 }
// Response 4xx: { "error": string, "code": string, "resendAvailableIn"?: number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
import { sendEmail, renderOtpEmail } from "../_shared/resend.ts";

const RESEND_COOLDOWN_SECONDS = 60;
const OTP_EXPIRY_SECONDS = 600;       // 10 minutes
const OTP_MAX_ATTEMPTS = 5;
const SEND_OTP_LIMIT = { maxRequests: 5, windowSeconds: 300, name: "send_email_otp" }; // 5 per 5 min per IP

interface SendOtpBody {
  email?: string;
  purpose?: string;
}

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}

function generateSixDigitCode(): string {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  // 100000..999999 inclusive
  return String(100000 + (buf[0] % 900000));
}

async function sha256Hex(value: string): Promise<string> {
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  // --- Rate limit per IP (no JWT needed — unauthenticated users call this) ---
  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const rl = checkRateLimit(req, ip, SEND_OTP_LIMIT);
  if (!rl.allowed) {
    return json(
      { error: `Too many OTP requests. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter },
      429,
    );
  }

  let body: SendOtpBody;
  try {
    body = await req.json();
  } catch {
    return json({ error: "Invalid request body", code: ErrorCode.VALIDATION_FAILED }, 400);
  }

  const email = (body.email ?? "").trim().toLowerCase();
  if (!email) return json({ error: "Email is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!isValidEmail(email)) return json({ error: "Please enter a valid email address", code: ErrorCode.VALIDATION_FAILED }, 422);

  const purpose = body.purpose ?? "signup";
  const allowedPurposes = ["signup", "recovery", "magic_link", "email_change", "phone_verify", "vault_reset"];
  if (!allowedPurposes.includes(purpose)) {
    return json({ error: "Invalid OTP purpose", code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  const supabase = createAdminClient();

  // --- Enforce 60-second resend cooldown -----------------------------------
  const { data: recent } = await supabase
    .from("otp_codes")
    .select("id, created_at, last_resent_at")
    .eq("identifier", email)
    .eq("purpose", purpose)
    .order("created_at", { ascending: false })
    .limit(1);

  const lastEntry = recent?.[0];
  const lastTouchTs = lastEntry
    ? new Date(lastEntry.last_resent_at ?? lastEntry.created_at).getTime()
    : 0;
  const elapsedSec = Math.floor((Date.now() - lastTouchTs) / 1000);
  if (lastEntry && elapsedSec < RESEND_COOLDOWN_SECONDS) {
    return json(
      {
        error: "Please wait before requesting another code",
        resendAvailableIn: RESEND_COOLDOWN_SECONDS - elapsedSec,
      },
      429,
    );
  }

  // --- Generate code + salted hash -----------------------------------------
  // code_hash is stored as "<salt>:<sha256(code:salt)>" so verify can reproduce
  // the hash without ever storing the plaintext code.
  const code = generateSixDigitCode();
  const salt = crypto.randomUUID();
  const hash = await sha256Hex(`${code}:${salt}`);
  const codeHash = `${salt}:${hash}`;
  const expiresAt = new Date(Date.now() + OTP_EXPIRY_SECONDS * 1000).toISOString();

  // Insert a new OTP row (old rows remain for audit; expired ones are ignored at verify time)
  const { error: insertError } = await supabase.from("otp_codes").insert({
    identifier: email,
    code_hash: codeHash,
    purpose,
    expires_at: expiresAt,
    max_attempts: OTP_MAX_ATTEMPTS,
    attempts: 0,
    last_resent_at: new Date().toISOString(),
  });
  if (insertError) {
    console.error("OTP insert failed", insertError);
    return errorResponse("Failed to issue OTP", 500);
  }

  // --- Send the email via Resend -------------------------------------------
  const subject: Record<string, string> = {
    signup: "Trigger App — Your confirmation code",
    recovery: "Trigger App — Your password reset code",
    magic_link: "Trigger App — Your login code",
    email_change: "Trigger App — Confirm your new email",
    phone_verify: "Trigger App — Your verification code",
    vault_reset: "Trigger App — Your vault reset code",
  };
  const sendResult = await sendEmail({
    to: email,
    subject: subject[purpose] ?? "Trigger App — Your verification code",
    html: renderOtpEmail(code, purpose),
    tags: [{ name: "purpose", value: purpose }],
  });

  if (sendResult.error) {
    console.error("Resend send failed", sendResult.error);
    // Surface the actual Resend error so the client can show a useful message
    // (e.g. "domain not verified", "test-mode restriction", "invalid recipient").
    return json(
      {
        error: "Failed to send verification email",
        code: "EMAIL_SEND_FAILED",
        detail: sendResult.error,
      },
      502,
    );
  }

  return json({
    sent: true,
    resendAvailableIn: RESEND_COOLDOWN_SECONDS,
    expiresIn: OTP_EXPIRY_SECONDS,
  });
}

serve(handler, { port: 9001 });
