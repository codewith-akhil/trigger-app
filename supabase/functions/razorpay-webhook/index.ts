// Edge function: razorpay-webhook
// ----------------------------------------------------------------------------
// Receives Razorpay webhook events for async payment state changes
// (payment.captured, payment.failed, payment.refunded). This is the
// authoritative server-side record of payment — the client-side
// verify-razorpay-payment is a fast-path UX, but webhooks handle cases where
// the user closes the app before verification, or the payment is delayed.
//
// Idempotency: each webhook payload includes a razorpay_payment_id; we look
// up any existing wallet_transactions row with that reference_id and skip
// re-processing if already captured.
//
// Signature verification: Razorpay signs the raw body with
// HMAC-SHA256(RAZORPAY_WEBHOOK_SECRET, rawBody) and sends the hex digest in
// the X-Razorpay-Signature header. We recompute and compare.
//
// Auth: NONE (Razorpay calls this directly). Signature verification is the
// gate. Deploy with --no-verify-jwt.
//
// Env vars: RAZORPAY_WEBHOOK_SECRET (server-only)
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { corsHeaders, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

interface RazorpayEvent {
  entity?: string;
  account_id?: string;
  event?: string;
  contains?: string[];
  payload?: {
    payment?: {
      entity?: {
        id?: string;
        entity?: string;
        amount?: number;
        currency?: string;
        status?: string; // "captured" | "failed" | "refunded"
        order_id?: string;
        method?: string;
        amount_refunded?: number;
        amount_captured?: number;
        notes?: Record<string, string>;
        email?: string;
        contact?: string;
      };
    };
    order?: {
      entity?: {
        id?: string;
        status?: string;
        amount?: number;
        notes?: Record<string, string>;
      };
    };
  };
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
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function handler(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const webhookSecret = Deno.env.get("RAZORPAY_WEBHOOK_SECRET");
  if (!webhookSecret) {
    console.error("RAZORPAY_WEBHOOK_SECRET not configured");
    return errorResponse("Webhook secret not configured", 500);
  }

  // Read the RAW body — Razorpay signs the raw bytes, not parsed JSON.
  const rawBody = await req.text();
  const signature = req.headers.get("X-Razorpay-Signature") ?? "";

  // --- Verify signature ----------------------------------------------------
  const expectedSig = await hmacSha256Hex(webhookSecret, rawBody);
  if (!signature || expectedSig !== signature.toLowerCase()) {
    console.warn("razorpay-webhook: signature mismatch");
    return json({ error: "Invalid signature" }, 401);
  }

  // --- Parse + dispatch ----------------------------------------------------
  let event: RazorpayEvent;
  try {
    event = JSON.parse(rawBody);
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  const eventType = event.event ?? "";
  const payment = event.payload?.payment?.entity;
  if (!payment?.id) {
    return json({ ok: true, skipped: "no payment entity" });
  }

  const supabase = createAdminClient();
  const referenceId = `RZP-${payment.id}`;

  // --- Idempotency check ---------------------------------------------------
  const { data: existing } = await supabase
    .from("wallet_transactions")
    .select("id, status, reference_id")
    .eq("reference_id", referenceId)
    .limit(1);
  if (existing && existing.length > 0 && existing[0].status === "completed" && eventType === "payment.captured") {
    // Already processed — ack the webhook so Razorpay stops retrying.
    return json({ ok: true, idempotent: true, referenceId });
  }

  if (eventType === "payment.captured" || eventType === "payment.paid") {
    // Extract context from the order notes (set by create-razorpay-order).
    const notes = payment.notes ?? {};
    const userId = notes.user_id;
    const purpose = notes.purpose ?? "stream_booking";
    const streamId = notes.stream_id;
    const amount = (payment.amount ?? 0) / 100; // paise → major
    const currency = (payment.currency ?? "INR");

    if (!userId) {
      console.error("razorpay-webhook: missing user_id in payment notes", payment.id);
      return json({ ok: true, skipped: "missing user_id in notes" });
    }

    if (purpose === "stream_booking" && streamId) {
      // Insert the booking + credit the host.
      const { error: bookingError } = await supabase.from("stream_bookings").upsert(
        {
          stream_id: streamId,
          user_id: userId,
          user_name: "",
          user_email: payment.email ?? "",
          payment_method: payment.method ?? "cards",
          payment_reference: referenceId,
          amount_paid: amount,
          currency: currency === "INR" ? "INR (₹)" : currency,
        },
        { onConflict: "stream_id,user_id" },
      );
      if (bookingError) console.error("booking upsert failed", bookingError);

      // Credit the host's wallet.
      const { data: stream } = await supabase
        .from("scheduled_streams")
        .select("host_id, title")
        .eq("id", streamId)
        .single();
      if (stream?.host_id) {
        await supabase.from("wallet_transactions").upsert(
          {
            user_id: stream.host_id,
            type: "credit",
            amount,
            currency: currency === "INR" ? "INR (₹)" : currency,
            description: `Ticket sale: ${stream.title ?? "stream"}`,
            reference_id: referenceId,
            status: "completed",
            related_stream_id: streamId,
          },
          { onConflict: "reference_id" },
        );
      }
    } else if (purpose === "wallet_topup") {
      await supabase.from("wallet_transactions").upsert(
        {
          user_id: userId,
          type: "credit",
          amount,
          currency: currency === "INR" ? "INR (₹)" : currency,
          description: "Wallet top-up",
          reference_id: referenceId,
          status: "completed",
        },
        { onConflict: "reference_id" },
      );
    }
    return json({ ok: true, captured: true, referenceId });
  }

  if (eventType === "payment.failed") {
    // Mark any pending transaction as failed.
    await supabase
      .from("wallet_transactions")
      .update({ status: "failed" })
      .eq("reference_id", referenceId);
    return json({ ok: true, failed: true, referenceId });
  }

  if (eventType === "payment.refunded") {
    await supabase
      .from("wallet_transactions")
      .update({ status: "failed", description: supabase.raw("description || ' [REFUNDED]'") })
      .eq("reference_id", referenceId);
    return json({ ok: true, refunded: true, referenceId });
  }

  // Unhandled event type — ack so Razorpay doesn't retry.
  return json({ ok: true, unhandled: eventType });
}

serve(handler, { port: 9014 });
