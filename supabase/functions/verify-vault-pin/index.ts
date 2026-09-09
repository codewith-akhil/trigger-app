// Edge function: verify-vault-pin
// ----------------------------------------------------------------------------
// Verifies a 6-digit Secret Vault PIN against the stored hash. Returns a
// short-lived unlock token (opaque) that the client can use to gate vault
// access for the session.
//
// Brute-force protection: 3 STRIKES PER 24h WINDOW (replaces the old
// 5-lifetime-attempts lockout):
//   • attempts < 3                              → verified normally
//   • attempts >= 3 AND now - first_fail_at < 24h → 429 "locked" until
//     first_fail_at + 24h (lockedUntil ISO). Only the email-OTP reset
//     (reset-vault-pin) or the window elapsing clears it.
//   • attempts >= 3 AND window elapsed          → counter reset to 0 first,
//     then the supplied PIN is checked against a fresh set of 3 strikes.
// A CORRECT PIN always zeroes attempts + first_fail_at.
//
// Auth: requires a valid Supabase JWT.
//
// Request body: { "pin": string }
// Response 200: { "unlocked": true, "unlockToken": string }
// Response 4xx: { "unlocked": false, "error": string,
//                 "attemptsRemaining"?: number,   // strikes left today
//                 "lockedUntil"?: string }        // ISO-8601, on "locked"
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { hashVaultPin, verifyVaultPin } from "../_shared/vault_pin.ts";

const MAX_ATTEMPTS_PER_DAY = 3;
const LOCKOUT_WINDOW_MS = 24 * 60 * 60 * 1000;

interface Body {
  pin?: string;
}

function isValidPin(v: string): boolean {
  return /^\d{6}$/.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const pin = body.pin ?? "";
  if (!isValidPin(pin)) return errorResponse("PIN must be 6 digits", 422);

  const supabase = createAdminClient();

  const { data: row } = await supabase
    .from("vault_pins")
    .select("pin_hash, attempts, first_fail_at")
    .eq("user_id", userId)
    .maybeSingle();

  if (!row) {
    return json({ unlocked: false, error: "No vault PIN has been set. Please set one first." }, 404);
  }

  let attempts = row.attempts ?? 0;
  const rawFirstFail: string | null = row.first_fail_at ?? null;

  // --- 3-strikes / 24h lockout ---------------------------------------------
  if (attempts >= MAX_ATTEMPTS_PER_DAY) {
    let firstFailAt = rawFirstFail ? new Date(rawFirstFail) : null;
    if (!firstFailAt || isNaN(firstFailAt.getTime())) {
      // Legacy row (predates first_fail_at): anchor the window at now so the
      // lockedUntil below is well-defined. Strikes stay consumed — never
      // silently grant fresh guesses.
      firstFailAt = new Date();
      await supabase
        .from("vault_pins")
        .update({ first_fail_at: firstFailAt.toISOString() })
        .eq("user_id", userId);
    }
    const elapsed = Date.now() - firstFailAt.getTime();
    if (elapsed < LOCKOUT_WINDOW_MS) {
      return json({
        unlocked: false,
        error: "locked",
        attemptsRemaining: 0,
        lockedUntil: new Date(firstFailAt.getTime() + LOCKOUT_WINDOW_MS).toISOString(),
      }, 429);
    }
    // Window elapsed — give the user a fresh set of strikes before checking.
    attempts = 0;
    await supabase
      .from("vault_pins")
      .update({ attempts: 0, first_fail_at: null })
      .eq("user_id", userId);
  }

  const verdict = await verifyVaultPin(pin, row.pin_hash);

  if (!verdict.ok) {
    // Atomic increment — concurrent guesses both wrote the same +1. The RPC
    // also stamps first_fail_at on the 0 -> 1 transition (start of the 24h
    // window). See 20260922_vault_lockout.sql.
    await supabase.rpc("bump_vault_pin_attempts", { p_user_id: userId });
    const newAttempts = attempts + 1;
    const remaining = Math.max(MAX_ATTEMPTS_PER_DAY - newAttempts, 0);
    return json(
      { unlocked: false, error: "Incorrect PIN", attemptsRemaining: remaining },
      403,
    );
  }

  // Reset attempts + window on success. Transparently upgrade a legacy
  // single-iteration SHA-256 hash to PBKDF2 on the first successful verify.
  if (verdict.needsUpgrade) {
    await supabase
      .from("vault_pins")
      .update({ attempts: 0, first_fail_at: null, pin_hash: await hashVaultPin(pin) })
      .eq("user_id", userId);
  } else {
    await supabase
      .from("vault_pins")
      .update({ attempts: 0, first_fail_at: null })
      .eq("user_id", userId);
  }

  // Mint a short-lived opaque unlock token (not a JWT — just a random id; the
  // client keeps it in memory for the session and discards on lock).
  const tokenBytes = new Uint8Array(32);
  crypto.getRandomValues(tokenBytes);
  const unlockToken = Array.from(tokenBytes).map((b) => b.toString(16).padStart(2, "0")).join("");

  return json({ unlocked: true, unlockToken });
}

serve(handler, { port: 9011 });
