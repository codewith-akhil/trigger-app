// Edge function: delete-account-otp
// ----------------------------------------------------------------------------
// Handles account deletion via a 2-step flow:
//   Step 1 (action="send_otp"): Sends a 6-digit OTP to the user's email
//           confirming they want to delete their account.
//   Step 2 (action="confirm_delete"): Verifies the OTP + deletes the account
//           from auth.users (cascade deletes all profile data).
//
// Auth: requires a valid Supabase JWT (the user must be logged in to delete).
//
// Request body:
//   { "action": "send_otp" }
//   { "action": "confirm_delete", "code": "123456", "reason"?: string }
//
// Response 200:
//   send_otp → { "sent": true, "resendAvailableIn": 60 }
//   confirm_delete → { "deleted": true }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
import { sendEmail, renderOtpEmail } from "../_shared/resend.ts";

const SEND_LIMIT = { maxRequests: 3, windowSeconds: 3600, name: "delete_account_otp" };
const DELETE_LIMIT = { maxRequests: 3, windowSeconds: 3600, name: "delete_account_confirm" };
const OTP_EXPIRY = 600;
const OTP_COOLDOWN = 60;

interface Body {
  action?: string;
  code?: string;
  reason?: string;
}

function generateSixDigitCode(): string {
  // Rejection sampling — no modulo bias.
  const buf = new Uint32Array(1);
  const LIMIT = Math.floor(4294967296 / 900000) * 900000;
  do {
    crypto.getRandomValues(buf);
  } while (buf[0] >= LIMIT);
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

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body = {};
  try { body = await req.json(); } catch { /* empty body ok for send_otp */ }
  const action = (body.action ?? "send_otp").toLowerCase();

  const supabase = createAdminClient();

  // Get the user's email
  const { data: userData, error: userError } = await supabase.auth.admin.getUserById(userId);
  if (userError || !userData?.user?.email) {
    return errorResponse("Unable to resolve user", 500, ErrorCode.INTERNAL_ERROR);
  }
  const email = userData.user.email;

  if (action === "send_otp") {
    const rl = checkRateLimit(req, userId, SEND_LIMIT);
    if (!rl.allowed) {
      return json({ error: `Too many requests. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
    }

    // Cooldown check
    const { data: recent } = await supabase
      .from("otp_codes")
      .select("id, last_resent_at")
      .eq("identifier", email)
      .eq("purpose", "account_delete")
      .order("created_at", { ascending: false })
      .limit(1);

    const lastEntry = recent?.[0];
    const lastTs = lastEntry ? new Date(lastEntry.last_resent_at ?? "").getTime() : 0;
    const elapsed = Math.floor((Date.now() - lastTs) / 1000);
    if (lastEntry && elapsed < OTP_COOLDOWN) {
      return json({ error: "Please wait before requesting another code", resendAvailableIn: OTP_COOLDOWN - elapsed }, 429);
    }

    // Generate + store OTP
    const code = generateSixDigitCode();
    const salt = crypto.randomUUID();
    const hash = await sha256Hex(`${code}:${salt}`);
    const expiresAt = new Date(Date.now() + OTP_EXPIRY * 1000).toISOString();

    const { error: insertError } = await supabase.from("otp_codes").insert({
      identifier: email,
      code_hash: `${salt}:${hash}`,
      purpose: "account_delete",
      expires_at: expiresAt,
      max_attempts: 5,
      attempts: 0,
      last_resent_at: new Date().toISOString(),
    });
    if (insertError) return errorResponse("Failed to issue OTP", 500, ErrorCode.INTERNAL_ERROR);

    // Send email
    const result = await sendEmail({
      to: email,
      subject: "Trigger App — Confirm account deletion",
      html: renderOtpEmail(code, "vault_reset"), // reuse the vault_reset template (amber warning)
      tags: [{ name: "purpose", value: "account_delete" }],
    });
    if (result.error) return errorResponse("Failed to send verification email", 502, ErrorCode.CONFIG_MISSING);

    return json({ sent: true, resendAvailableIn: OTP_COOLDOWN });
  }

  if (action === "confirm_delete") {
    const rl = checkRateLimit(req, userId, DELETE_LIMIT);
    if (!rl.allowed) {
      return json({ error: `Too many attempts. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
    }

    const code = (body.code ?? "").trim();
    if (!/^\d{6}$/.test(code)) return errorResponse("Code must be 6 digits", 422, ErrorCode.VALIDATION_FAILED);

    // Verify OTP
    const { data: otpRows } = await supabase
      .from("otp_codes")
      .select("id, code_hash, expires_at, consumed_at, attempts, max_attempts")
      .eq("identifier", email)
      .eq("purpose", "account_delete")
      .order("created_at", { ascending: false })
      .limit(1);

    const otp = otpRows?.[0];
    if (!otp) return json({ deleted: false, error: "No deletion code was issued. Please request one." }, 404);
    if (otp.consumed_at) return json({ deleted: false, error: "This code has already been used." }, 410);
    if (new Date(otp.expires_at).getTime() < Date.now()) return json({ deleted: false, error: "This code has expired." }, 410);

    const [salt, storedHash] = otp.code_hash.split(":");
    const candidate = await sha256Hex(`${code}:${salt}`);
    if (candidate !== storedHash) {
      await supabase.rpc("bump_otp_attempts", { p_otp_id: otp.id });
      return json({ deleted: false, error: "Incorrect code." }, 400);
    }

    // Mark consumed
    await supabase.from("otp_codes").update({ consumed_at: new Date().toISOString() }).eq("id", otp.id);

    // Store the reason (if provided) in support_tickets for audit
    if (body.reason?.trim()) {
      await supabase.from("support_tickets").insert({
        user_id: userId,
        subject: "Account deletion",
        message: body.reason.trim().slice(0, 500),
        category: "account",
        status: "closed",
      });
    }

    // DELETE THE USER (cascade deletes profiles, messages, wallet, vault, etc.)
    const { error: deleteError } = await supabase.auth.admin.deleteUser(userId);
    if (deleteError) {
      console.error("Account deletion failed:", deleteError);
      return errorResponse("Failed to delete account", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({ deleted: true });
  }

  return errorResponse(`Unknown action: ${action}`, 422, ErrorCode.VALIDATION_FAILED);
}

serve(handler, { port: 9031 });
