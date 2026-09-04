// Edge function: update-bank-details
// ----------------------------------------------------------------------------
// Persists the caller's bank account details from the WalletScreen
// EditBankDetailsDialog. The Android app currently keeps this in-memory only
// (WalletService.bankDetails StateFlow) — lost on app restart.
//
// SECURITY: the full account number is NEVER stored. We store:
//   - account_number_last4 (CHAR(4)) — for display "•••• •••• •••• 1234"
//   - account_number_hash   (SHA-256 hex) — for de-dup / audit
//   - account_holder_name, bank_name, ifsc_or_routing, swift_code (plaintext
//     — these are not secret; they appear on cheques).
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 5 requests / hour (sensitive financial data).
//
// Request body:
//   {
//     "accountHolderName": string,   // required, 1-100 chars
//     "bankName": string,           // required, 1-100 chars
//     "accountNumber": string,      // required, 6-20 digits
//     "ifscOrRouting"?: string,     // optional, max 20 chars
//     "swiftCode"?: string          // optional, max 11 chars (SWIFT/BIC)
//   }
//
// Response 200: { "updated": true, "last4": "1234" }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const BANK_LIMIT = { maxRequests: 5, windowSeconds: 3600, name: "update_bank_details" };

interface Body {
  accountHolderName?: string;
  bankName?: string;
  accountNumber?: string;
  ifscOrRouting?: string;
  swiftCode?: string;
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

  const rl = checkRateLimit(req, userId, BANK_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const accountHolderName = (body.accountHolderName ?? "").trim();
  const bankName = (body.bankName ?? "").trim();
  const accountNumber = (body.accountNumber ?? "").replace(/\s+/g, "");

  if (!accountHolderName || accountHolderName.length > 100) {
    return errorResponse("accountHolderName is required (max 100 chars)", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (!bankName || bankName.length > 100) {
    return errorResponse("bankName is required (max 100 chars)", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (!/^\d{6,20}$/.test(accountNumber)) {
    return errorResponse("accountNumber must be 6-20 digits", 422, ErrorCode.VALIDATION_FAILED);
  }
  const ifscOrRouting = (body.ifscOrRouting ?? "").trim().toUpperCase().slice(0, 20);
  const swiftCode = (body.swiftCode ?? "").trim().toUpperCase().slice(0, 11);

  const last4 = accountNumber.slice(-4);
  const accountHash = await sha256Hex(`bank:${userId}:${accountNumber}`);

  const supabase = createAdminClient();
  const { error } = await supabase
    .from("bank_details")
    .upsert(
      {
        user_id: userId,
        account_holder_name: accountHolderName,
        bank_name: bankName,
        account_number_last4: last4,
        account_number_hash: accountHash,
        ifsc_or_routing: ifscOrRouting || null,
        swift_code: swiftCode || null,
      },
      { onConflict: "user_id" },
    );

  if (error) {
    console.error("update-bank-details failed", error);
    return errorResponse("Failed to save bank details", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ updated: true, last4 });
}

serve(handler, { port: 9026 });
