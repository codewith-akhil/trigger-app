// Edge function: update-payout-details
// ----------------------------------------------------------------------------
// Persists the caller's preferred payout method from the WalletScreen
// EditPayoutDetailsDialog. The Android app keeps this in-memory only.
//
// The `payout_details` table has a CHECK constraint requiring:
//   (primary_method='UPI'   AND upi_id IS NOT NULL) OR
//   (primary_method='PayPal' AND paypal_email IS NOT NULL) OR
//   (primary_method='Bank')
// So this function validates the payload matches the constraint + does
// format validation (UPI: name@vpa pattern, PayPal: email pattern).
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 5 requests / hour.
//
// Request body:
//   {
//     "primaryMethod": "Bank"|"UPI"|"PayPal",   // required
//     "upiId"?: string,                          // required if primaryMethod=UPI
//     "paypalEmail"?: string                     // required if primaryMethod=PayPal
//   }
//
// Response 200: { "updated": true, "primaryMethod": string }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const PAYOUT_LIMIT = { maxRequests: 5, windowSeconds: 3600, name: "update_payout_details" };

interface Body {
  primaryMethod?: string;
  upiId?: string;
  paypalEmail?: string;
}

const METHODS = ["Bank", "UPI", "PayPal"];

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, PAYOUT_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const primaryMethod = (body.primaryMethod ?? "").trim();
  if (!METHODS.includes(primaryMethod)) {
    return errorResponse(`primaryMethod must be one of: ${METHODS.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
  }

  let upiId: string | null = null;
  let paypalEmail: string | null = null;

  if (primaryMethod === "UPI") {
    upiId = (body.upiId ?? "").trim().toLowerCase();
    // UPI VPA format: name@bank (e.g. john@okhdfcbank). Allow 3-50 chars total.
    if (!/^[a-z0-9.\-_]{2,30}@[a-z]{2,20}$/i.test(upiId)) {
      return errorResponse("upiId must be a valid UPI VPA (e.g. name@okhdfcbank)", 422, ErrorCode.VALIDATION_FAILED);
    }
  }
  if (primaryMethod === "PayPal") {
    paypalEmail = (body.paypalEmail ?? "").trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(paypalEmail)) {
      return errorResponse("paypalEmail must be a valid email address", 422, ErrorCode.VALIDATION_FAILED);
    }
  }

  const supabase = createAdminClient();
  const { error } = await supabase
    .from("payout_details")
    .upsert(
      {
        user_id: userId,
        primary_method: primaryMethod,
        upi_id: upiId,
        paypal_email: paypalEmail,
      },
      { onConflict: "user_id" },
    );

  if (error) {
    console.error("update-payout-details failed", error);
    return errorResponse("Failed to save payout details", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ updated: true, primaryMethod });
}

serve(handler, { port: 9027 });
