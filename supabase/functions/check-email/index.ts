// Edge function: check-email
// ----------------------------------------------------------------------------
// Checks if an email is already registered in auth.users. Used by:
//   - ForgotPasswordScreen: verify email exists before sending OTP
//   - SignUpScreen: verify email is NOT already taken before signup
//
// NO JWT required — this is a pre-auth check. Rate limited per IP.
// Supabase Auth already uses bcrypt for password hashing — no plaintext stored.
//
// Request body: { "email": string }
// Response 200: { "exists": boolean, "email": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const CHECK_EMAIL_LIMIT = { maxRequests: 20, windowSeconds: 300, name: "check_email" };

interface Body { email?: string; }

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  // Rate limit per IP
  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const rl = checkRateLimit(req, ip, CHECK_EMAIL_LIMIT);
  if (!rl.allowed) {
    return json({ error: `Too many requests. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return json({ error: "Invalid request body", code: ErrorCode.VALIDATION_FAILED }, 400); }

  const email = (body.email ?? "").trim().toLowerCase();
  if (!email) return json({ error: "Email is required", code: ErrorCode.VALIDATION_FAILED }, 422);
  if (!isValidEmail(email)) return json({ error: "Please enter a valid email address", code: ErrorCode.VALIDATION_FAILED }, 422);

  const supabase = createAdminClient();

  // profiles.email is the denormalized, UNIQUE index — listUsers({page:1,
  // perPage:1000}) silently missed every user beyond the first 1000 (signup
  // then failed with "Email not confirmed" / duplicate-account errors).
  const { data: profileHit } = await supabase
    .from("profiles")
    .select("id")
    .eq("email", email)
    .limit(1);
  const existsViaProfile = !!(profileHit && profileHit.length > 0);
  if (existsViaProfile) {
    return json({ exists: true, source: "profiles" });
  }

  // Fallback to auth.admin for brand-new signups before the trigger synced
  // the email into profiles.
  const { data: users, error } = await supabase.auth.admin.listUsers({
    page: 1,
    perPage: 1000,
  });

  if (error) {
    console.error("check-email: listUsers failed", error);
    return json({ error: "Failed to check email", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  // Check if any user has this email
  const exists = (users?.users ?? []).some((u: any) => u.email?.toLowerCase() === email);

  return json({ exists, email });
}

serve(handler, { port: 9047 });
