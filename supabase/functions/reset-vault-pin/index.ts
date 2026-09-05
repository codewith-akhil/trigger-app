// Edge function: reset-vault-pin
// ----------------------------------------------------------------------------
// Resets a locked-out or forgotten vault PIN using a 6-digit OTP sent to the
// user's email (purpose = "vault_reset"). This is the recovery path referenced
// by verify-vault-pin's "Too many failed attempts. Reset your PIN via OTP."
// error message — closes the lockout-recovery gap surfaced during QA.
//
// Two-step flow:
//   Step 1 (no body, or {"action":"send_otp"}) → emails a 6-digit OTP via the
//          existing send-email-otp machinery (60s cooldown enforced there).
//   Step 2 ({"action":"reset","otp":"123456","newPin":"654321"}) → verifies
//          the OTP via verify-email-otp, then clears the lockout + sets the
//          new hashed PIN (without requiring the old PIN).
//
// Auth: requires a valid Supabase JWT.
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { sendEmail, renderOtpEmail } from "../_shared/resend.ts";

const OTP_PURPOSE = "vault_reset";
const OTP_EXPIRY_SECONDS = 600;
const OTP_MAX_ATTEMPTS = 5;
const OTP_COOLDOWN_SECONDS = 60;

interface Body {
  action?: string; // "send_otp" (default) | "reset"
  otp?: string;
  newPin?: string;
  // NOTE: `email` is intentionally NOT accepted from the client body —
  // the OTP is always sent to the authenticated user's own email
  // (resolved via the JWT). Accepting a client-supplied email would let
  // an attacker spam arbitrary inboxes.
}

function isValidPin(v: string): boolean {
  return /^\d{6}$/.test(v);
}

function generateSixDigitCode(): string {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  return String(100000 + (buf[0] % 900000));
}

async function sha256Hex(value: string): Promise<string> {
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function verifyOtpCombo(supabase: any, email: string, code: string): Promise<{ ok: boolean; error?: string }> {
  const { data: rows, error } = await supabase
    .from("otp_codes")
    .select("id, code_hash, expires_at, attempts, max_attempts, consumed_at")
    .eq("identifier", email)
    .eq("purpose", OTP_PURPOSE)
    .order("created_at", { ascending: false })
    .limit(1);
  if (error) return { ok: false, error: "Verification failed" };
  const otp = rows?.[0];
  if (!otp) return { ok: false, error: "No reset code was issued. Please request one first." };
  if (otp.consumed_at) return { ok: false, error: "This code has already been used. Please request a new one." };
  if (new Date(otp.expires_at).getTime() < Date.now()) return { ok: false, error: "This code has expired. Please request a new one." };
  if (otp.attempts >= (otp.max_attempts ?? OTP_MAX_ATTEMPTS)) return { ok: false, error: "Too many incorrect attempts. Please request a new code." };

  const [salt, storedHash] = otp.code_hash.split(":");
  const candidate = await sha256Hex(`${code}:${salt}`);
  if (candidate !== storedHash) {
    await supabase.from("otp_codes").update({ attempts: (otp.attempts ?? 0) + 1 }).eq("id", otp.id);
    return { ok: false, error: "Incorrect code. Please try again." };
  }
  // Mark consumed.
  await supabase.from("otp_codes").update({ consumed_at: new Date().toISOString(), attempts: (otp.attempts ?? 0) + 1 }).eq("id", otp.id);
  return { ok: true };
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: Body = {};
  try { body = await req.json(); } catch { /* allow empty body for step 1 */ }
  const action = (body.action ?? "send_otp").toLowerCase();

  // Look up the user's email from auth.users via the admin client.
  // The email is ALWAYS resolved from the JWT — never from the request
  // body — so an attacker can't spam OTPs at arbitrary inboxes.
  const supabase = createAdminClient();
  const { data: userData, error: userError } = await supabase.auth.admin.getUserById(userId);
  if (userError || !userData?.user?.email) {
    return errorResponse("Unable to resolve user email", 500);
  }
  const email = userData.user.email.trim().toLowerCase();

  if (action === "send_otp") {
    // --- Cooldown check ------------------------------------------------------
    const { data: recent } = await supabase
      .from("otp_codes")
      .select("id, created_at, last_resent_at")
      .eq("identifier", email)
      .eq("purpose", OTP_PURPOSE)
      .order("created_at", { ascending: false })
      .limit(1);
    const lastEntry = recent?.[0];
    const lastTouchTs = lastEntry ? new Date(lastEntry.last_resent_at ?? lastEntry.created_at).getTime() : 0;
    const elapsedSec = Math.floor((Date.now() - lastTouchTs) / 1000);
    if (lastEntry && elapsedSec < OTP_COOLDOWN_SECONDS) {
      return json({ error: "Please wait before requesting another code", resendAvailableIn: OTP_COOLDOWN_SECONDS - elapsedSec }, 429);
    }

    // --- Generate + store OTP ----------------------------------------------
    const code = generateSixDigitCode();
    const salt = crypto.randomUUID();
    const hash = await sha256Hex(`${code}:${salt}`);
    const expiresAt = new Date(Date.now() + OTP_EXPIRY_SECONDS * 1000).toISOString();
    const { error: insertError } = await supabase.from("otp_codes").insert({
      identifier: email, code_hash: `${salt}:${hash}`, purpose: OTP_PURPOSE,
      expires_at: expiresAt, max_attempts: OTP_MAX_ATTEMPTS, attempts: 0,
      last_resent_at: new Date().toISOString(),
    });
    if (insertError) {
      console.error("vault-reset OTP insert failed", insertError);
      return errorResponse("Failed to issue reset code", 500);
    }

    // --- Email via Resend ----------------------------------------------------
    const result = await sendEmail({
      to: email,
      subject: "Trigger App — Your vault reset code",
      html: renderOtpEmail(code, OTP_PURPOSE),
      tags: [{ name: "purpose", value: OTP_PURPOSE }],
    });
    if (result.error) {
      console.error("vault-reset email send failed", result.error);
      return errorResponse("Failed to send reset email. Please try again.", 502);
    }
    return json({ sent: true, resendAvailableIn: OTP_COOLDOWN_SECONDS, expiresIn: OTP_EXPIRY_SECONDS });
  }

  if (action === "reset") {
    const otp = (body.otp ?? "").trim();
    const newPin = body.newPin ?? "";
    if (!/^\d{6}$/.test(otp)) return errorResponse("OTP must be 6 digits", 422);
    if (!isValidPin(newPin)) return errorResponse("New PIN must be 6 digits", 422);

    // --- Verify OTP ---------------------------------------------------------
    const verify = await verifyOtpCombo(supabase, email, otp);
    if (!verify.ok) return json({ reset: false, error: verify.error }, 400);

    // --- Reset PIN + clear lockout ------------------------------------------
    const salt = crypto.randomUUID();
    const hash = await sha256Hex(`${newPin}:${salt}`);
    const pinHash = `${salt}:${hash}`;
    const { error: pinError } = await supabase
      .from("vault_pins")
      .upsert({ user_id: userId, pin_hash: pinHash, attempts: 0 }, { onConflict: "user_id" });
    if (pinError) {
      console.error("vault pin reset failed", pinError);
      return errorResponse("Failed to reset vault PIN", 500);
    }
    return json({ reset: true });
  }

  return errorResponse(`Unknown action: ${action}. Use "send_otp" or "reset".`, 422);
}

serve(handler, { port: 9013 });
