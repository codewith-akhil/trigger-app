// Edge function: wallet-withdraw
// ----------------------------------------------------------------------------
// Processes a wallet withdrawal request from the WalletScreen WithdrawDialog.
// The Android app currently simulates this in-memory (decrements balance,
// generates a fake refId "WTHD-XXXXXX", prepends a "Processing" transaction).
// This function persists a real debit row to `wallet_transactions` with a
// server-generated reference id + validates the amount against the caller's
// actual available_balance (from the wallet_balance view).
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 5 requests / hour (WITHDRAW_LIMIT — tight, money movement).
//
// Request body:
//   {
//     "amount": number,             // required, > 0, <= available_balance
//     "destination": string,        // required, one of: "Bank"|"UPI"|"PayPal"
//     "description"?: string        // optional, max 200 chars
//   }
//
// Response 200: { "withdrawn": true, "transactionId": string, "referenceId": string, "newBalance": number }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const WITHDRAW_LIMIT = { maxRequests: 5, windowSeconds: 3600, name: "wallet_withdraw" };

interface Body {
  amount?: number;
  destination?: string;
  description?: string;
}

const DESTINATIONS = ["Bank", "UPI", "PayPal"];

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, WITHDRAW_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const amount = Number(body.amount);
  if (!Number.isFinite(amount) || amount <= 0) {
    return errorResponse("Amount must be greater than 0", 422, ErrorCode.VALIDATION_FAILED);
  }
  if (amount > 1_000_000) {
    return errorResponse("Amount too large (max 1,000,000)", 422, ErrorCode.VALIDATION_FAILED);
  }
  const destination = (body.destination ?? "").trim();
  if (!DESTINATIONS.includes(destination)) {
    return errorResponse(`destination must be one of: ${DESTINATIONS.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
  }
  const description = (body.description ?? `Withdrawal to ${destination}`).slice(0, 200);

  const supabase = createAdminClient();

  // --- Atomic withdrawal via RPC (prevents TOCTOU race on balance check) ---
  // The process_withdrawal RPC locks the user's wallet_balance row, checks
  // the balance, and inserts the debit transaction in a single transaction.
  const referenceId = `WTHD-${Date.now().toString(36).toUpperCase()}-${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
  const { data: withdrawResult, error: withdrawError } = await supabase
    .rpc("process_withdrawal", {
      p_user_id: userId,
      p_amount: amount,
      p_description: description,
      p_reference_id: referenceId,
    });

  if (withdrawError) {
    console.error("wallet-withdraw: RPC failed", withdrawError);
    return errorResponse("Failed to process withdrawal", 500, ErrorCode.INTERNAL_ERROR);
  }

  // The RPC returns { success: boolean, new_balance: number, error?: string }
  if (!withdrawResult?.success) {
    return json(
      { error: withdrawResult?.error ?? "Insufficient balance", code: ErrorCode.VALIDATION_FAILED },
      422,
    );
  }

  const tx = { id: withdrawResult.transaction_id, reference_id: referenceId };
  const newBalance = withdrawResult.new_balance;

  // Send a push notification to the user confirming the withdrawal (best-effort).
  try {
    const { data: tokens } = await supabase
      .from("push_tokens")
      .select("fcm_token")
      .eq("user_id", userId)
      .eq("is_active", true);
    if (tokens && tokens.length > 0) {
      const { sendFcmBatch } = await import("../_shared/firebase.ts");
      await sendFcmBatch(
        {
          title: "Withdrawal initiated",
          body: `${amount.toFixed(2)} INR → ${destination}. Ref: ${referenceId}`,
          data: { type: "wallet_withdrawal", reference_id: referenceId, amount: String(amount) },
          androidChannelId: "trigger_wallet",
          priority: "normal",
        },
        tokens.map((t) => t.fcm_token),
      );
    }
  } catch (pushErr) {
    // Non-fatal — the withdrawal was recorded.
    console.warn("wallet-withdraw: push failed", pushErr);
  }

  return json({
    withdrawn: true,
    transactionId: tx.id,
    referenceId: tx.reference_id,
    newBalance: newBalance,
  });
}

serve(handler, { port: 9025 });
