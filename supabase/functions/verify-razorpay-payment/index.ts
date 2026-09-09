// Edge function: verify-razorpay-payment
// ----------------------------------------------------------------------------
// Verifies the HMAC-SHA256 signature returned by the Razorpay checkout on the
// client. If valid, marks the related stream_booking / wallet_transaction as
// paid by recording a wallet credit (for hosts) or a booking confirmation
// (for attendees).
//
// Env vars:
//   RAZORPAY_KEY_SECRET  — used as the HMAC key (server-only)
//
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "razorpayOrderId": string, "razorpayPaymentId": string,
//     "razorpaySignature": string, "purpose"?: string,
//     "streamId"?: string, "amount"?: number, "currency"?: string }
//
// Response 200: { "verified": true, "referenceId": string }
// Response 4xx: { "verified": false, "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  razorpayOrderId?: string;
  razorpayPaymentId?: string;
  razorpaySignature?: string;
  purpose?: string;
  streamId?: string;
  amount?: number;
  currency?: string;
}

/** Length-safe, early-exit-free string compare (signature verification). */
function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

async function hmacSha256Hex(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
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

  const keySecret = Deno.env.get("RAZORPAY_KEY_SECRET");
  if (!keySecret) return errorResponse("Razorpay key secret is not configured", 500);

  if (!body.razorpayOrderId || !body.razorpayPaymentId || !body.razorpaySignature) {
    return errorResponse("razorpayOrderId, razorpayPaymentId, razorpaySignature are required", 422);
  }

  // --- Verify signature ----------------------------------------------------
  const expected = await hmacSha256Hex(
    keySecret,
    `${body.razorpayOrderId}|${body.razorpayPaymentId}`,
  );
  if (!timingSafeEqualHex(expected, body.razorpaySignature.toLowerCase())) {
    return json({ verified: false, error: "Payment signature verification failed" }, 400);
  }

  // --- Fetch the authoritative payment details from Razorpay (don't trust
  // client-supplied amount/streamId — only the signature is verified above).
  const keyId = Deno.env.get("RAZORPAY_KEY_ID");
  if (!keyId || !keySecret) return errorResponse("Razorpay keys not configured", 500);
  const auth = btoa(`${keyId}:${keySecret}`);
  const payRes = await fetch(`https://api.razorpay.com/v1/payments/${body.razorpayPaymentId}`, {
    headers: { Authorization: `Basic ${auth}` },
  });
  const payment = await payRes.json().catch(() => ({}));
  if (!payRes.ok || !payment.id) {
    return errorResponse("Failed to fetch payment details from Razorpay", 502);
  }
  const amount = (payment.amount ?? 0) / 100; // paise → major
  const currency = (payment.currency ?? "INR");
  const notes = payment.notes ?? {};
  const purpose = notes.purpose ?? "stream_booking";
  const streamId = notes.stream_id ?? null;
  const payerUserId = notes.user_id ?? null;

  if (!payerUserId) {
    return json({ verified: true, skipped: "missing user_id in payment notes" });
  }

  // --- Persist the verified payment ----------------------------------------
  const supabase = createAdminClient();
  const referenceId = `RZP-${body.razorpayPaymentId}`;

  if (purpose === "stream_booking" && streamId) {
    // Same payer rule as wallet_topup — a non-payer holding a valid
    // signature could otherwise book a stream on someone else's payment.
    if (userId !== payerUserId) {
      return errorResponse("Only the payer can verify this payment", 403, ErrorCode.FORBIDDEN);
    }
    // Insert a stream_booking row + a wallet credit to the host.
    const { error: bookingError } = await supabase.from("stream_bookings").upsert(
      {
        stream_id: streamId,
        user_id: userId,
        user_name: "",  // filled by client-triggered profile lookup if desired
        user_email: "",
        payment_method: "cards",
        payment_reference: referenceId,
        amount_paid: amount,
        currency: currency === "INR" ? "INR (₹)" : currency,
      },
      { onConflict: "stream_id,user_id" },
    );
    if (bookingError) {
      console.error("booking insert failed", bookingError);
    }

    // Credit the host's wallet. createAdminClient uses the service role so the
    // insert bypasses RLS (the caller is the attendee, not the host).
    const { data: stream } = await supabase
      .from("scheduled_streams")
      .select("host_id, title")
      .eq("id", streamId)
      .single();
    if (stream?.host_id) {
      await supabase.from("wallet_transactions").insert({
        user_id: stream.host_id,
        type: "credit",
        amount: amount,
        currency: currency === "INR" ? "INR (₹)" : currency,
        description: `Ticket sale: ${stream.title ?? "stream"}`,
        reference_id: referenceId,
        status: "completed",
        related_stream_id: streamId,
      });
    }
  } else if (purpose === "wallet_topup") {
    // Credit the PAYER (notes.user_id), not the caller — anyone could
    // otherwise submit someone's signature and have the money land in THEIR
    // wallet. Only the payer themself may verify a top-up.
    if (userId !== payerUserId) {
      return errorResponse("Only the payer can verify this payment", 403, ErrorCode.FORBIDDEN);
    }
    // Upsert on the UNIQUE reference_id + surface failures — a replayed or
    // failed insert previously still returned { verified: true }.
    const { error: creditError } = await supabase
      .from("wallet_transactions")
      .upsert({
        user_id: payerUserId,
        type: "credit",
        amount: amount,
        currency: currency === "INR" ? "INR (₹)" : currency,
        description: "Wallet top-up",
        reference_id: referenceId,
        status: "completed",
      }, { onConflict: "reference_id" });
    if (creditError) {
      console.error("wallet credit failed", creditError);
      return errorResponse("Payment verified but crediting failed — contact support", 500, ErrorCode.INTERNAL_ERROR);
    }
  }

  return json({ verified: true, referenceId });
}

serve(handler, { port: 9007 });
