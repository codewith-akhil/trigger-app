// Edge function: create-razorpay-order
// ----------------------------------------------------------------------------
// Creates a Razorpay Order (server-side) for paid stream bookings and wallet
// top-ups. Returns the order id + amount + currency to the client, which then
// opens the Razorpay checkout sheet.
//
// Razorpay keys are stored ONLY on the server (env vars). The client never
// sees the key secret. The client only needs the key_id (publishable) to open
// the checkout — and even that is returned by this function.
//
// Env vars:
//   RAZORPAY_KEY_ID        — rzp_test_... (or rzp_live_...)
//   RAZORPAY_KEY_SECRET    — server-only secret
//   RAZORPAY_WEBHOOK_SECRET — (used by verify, declared here for completeness)
//
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "amount": number,           // in MAJOR currency units (e.g. 99.00 INR)
//     "currency": string,         // "INR" | "USD" | "EUR" | "GBP"
//     "receipt": string,          // client-generated receipt id (e.g. "sch_<ts>")
//     "notes"?: Record<string,string>,
//     "purpose"?: string,         // "stream_booking" | "wallet_topup"
//     "streamId"?: string }
//
// Response 200: { "orderId": string, "amount": number, "currency": string,
//                 "keyId": string, "receipt": string }
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { resolveUserId } from "../_shared/supabase.ts";

interface Body {
  amount?: number;
  currency?: string;
  receipt?: string;
  notes?: Record<string, string>;
  purpose?: string;
  streamId?: string;
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

  const keyId = Deno.env.get("RAZORPAY_KEY_ID");
  const keySecret = Deno.env.get("RAZORPAY_KEY_SECRET");
  if (!keyId || !keySecret) {
    return errorResponse("Razorpay keys are not configured on the server", 500);
  }

  const amount = Number(body.amount);
  if (!Number.isFinite(amount) || amount <= 0) {
    return errorResponse("amount must be a positive number", 422);
  }
  const currency = (body.currency ?? "INR").toUpperCase();
  if (!["INR", "USD", "EUR", "GBP"].includes(currency)) {
    return errorResponse("Unsupported currency", 422);
  }
  if (!body.receipt?.trim()) return errorResponse("receipt is required", 422);

  // Razorpay expects amount in the smallest currency unit (paise for INR,
  // cents for USD/EUR/GBP). Multiply by 100.
  const amountMinor = Math.round(amount * 100);

  const notes: Record<string, string> = {
    user_id: userId,
    purpose: body.purpose ?? "stream_booking",
    ...(body.streamId ? { stream_id: body.streamId } : {}),
    ...(body.notes ?? {}),
  };

  const auth = btoa(`${keyId}:${keySecret}`);
  const res = await fetch("https://api.razorpay.com/v1/orders", {
    method: "POST",
    headers: {
      Authorization: `Basic ${auth}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      amount: amountMinor,
      currency,
      receipt: body.receipt,
      notes,
      payment_capture: 1,
    }),
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    console.error("Razorpay order create failed", data);
    return errorResponse(data?.error?.description ?? `Razorpay HTTP ${res.status}`, 502);
  }

  return json({
    orderId: data.id,
    amount: data.amount,
    currency: data.currency,
    keyId,
    receipt: data.receipt,
  });
}

serve(handler, { port: 9006 });
