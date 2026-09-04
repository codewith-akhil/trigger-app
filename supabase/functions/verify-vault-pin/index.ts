// Edge function: verify-vault-pin
// ----------------------------------------------------------------------------
// Verifies a 6-digit Secret Vault PIN against the stored hash. Returns a
// short-lived unlock token (opaque) that the client can use to gate vault
// access for the session. Implements brute-force protection: after 5 failed
// attempts the PIN row is locked (client must reset via OTP).
//
// Auth: requires a valid Supabase JWT.
//
// Request body: { "pin": string }
// Response 200: { "unlocked": true, "unlockToken": string }
// Response 4xx: { "unlocked": false, "error": string, "attemptsRemaining"?: number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  pin?: string;
}

function isValidPin(v: string): boolean {
  return /^\d{6}$/.test(v);
}

async function hashPin(pin: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`${pin}:${salt}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
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
    .select("pin_hash, attempts")
    .eq("user_id", userId)
    .maybeSingle();

  if (!row) {
    return json({ unlocked: false, error: "No vault PIN has been set. Please set one first." }, 404);
  }
  if ((row.attempts ?? 0) >= 5) {
    return json({ unlocked: false, error: "Too many failed attempts. Reset your PIN via OTP." }, 429);
  }

  const [salt, storedHash] = row.pin_hash.split(":");
  const candidate = await hashPin(pin, salt);

  if (candidate !== storedHash) {
    const newAttempts = (row.attempts ?? 0) + 1;
    await supabase.from("vault_pins").update({ attempts: newAttempts }).eq("user_id", userId);
    const remaining = Math.max(5 - newAttempts, 0);
    return json(
      { unlocked: false, error: "Incorrect PIN", attemptsRemaining: remaining },
      403,
    );
  }

  // Reset attempts on success.
  await supabase.from("vault_pins").update({ attempts: 0 }).eq("user_id", userId);

  // Mint a short-lived opaque unlock token (not a JWT — just a random id; the
  // client keeps it in memory for the session and discards on lock).
  const tokenBytes = new Uint8Array(32);
  crypto.getRandomValues(tokenBytes);
  const unlockToken = Array.from(tokenBytes).map((b) => b.toString(16).padStart(2, "0")).join("");

  return json({ unlocked: true, unlockToken });
}

serve(handler, { port: 9011 });
