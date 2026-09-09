// Edge function: upsert-vault-pin
// ----------------------------------------------------------------------------
// Sets or resets the caller's 6-digit Secret Vault PIN. The PIN is NEVER stored
// in plaintext — it is salted + SHA-256 hashed server-side before being written
// to `public.vault_pins`. Replaces the insecure plaintext SharedPreferences
// storage in the current Android SecretVaultService.
//
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "pin": string,                 // 6 digits
//     "oldPin"?: string }            // required when resetting an existing PIN
//
// Response 200: { "set": true }
// Response 4xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
import { hashVaultPin, verifyVaultPin } from "../_shared/vault_pin.ts";

interface Body {
  pin?: string;
  oldPin?: string;
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
  // Rate limit EVERY vault-PIN write — the old-PIN confirmation is a
  // 6-digit guess surface with no per-day lockout (lockout only protects
  // verify-vault-pin); without this an attacker could brute-force at
  // network speed.
  const rl = checkRateLimit(req, userId, { maxRequests: 10, windowSeconds: 60, name: "upsert_vault_pin" });
  if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

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

  // If a PIN already exists, require the old PIN to reset.
  const { data: existing } = await supabase
    .from("vault_pins")
    .select("pin_hash")
    .eq("user_id", userId)
    .maybeSingle();

  if (existing) {
    if (!body.oldPin || !isValidPin(body.oldPin)) {
      return errorResponse("Current PIN is required to reset the vault PIN", 422);
    }
    const verdict = await verifyVaultPin(body.oldPin, existing.pin_hash);
    if (!verdict.ok) {
      return errorResponse("Current PIN is incorrect", 403);
    }
  }

  const pinHash = await hashVaultPin(pin);

  const { error } = await supabase
    .from("vault_pins")
    .upsert({ user_id: userId, pin_hash: pinHash }, { onConflict: "user_id" });

  if (error) {
    console.error("vault pin upsert failed", error);
    return errorResponse("Failed to set vault PIN", 500);
  }

  // Also flip the two_step_enabled flag on the profile.
  await supabase.from("profiles").update({ two_step_enabled: true }).eq("id", userId);

  return json({ set: true });
}

serve(handler, { port: 9010 });
