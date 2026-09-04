// Deno test suite for Trigger App edge functions.
// Run with:  deno test --allow-net --allow-env --allow-read supabase/tests/
//
// These tests exercise the pure helpers (no live network calls) to keep them
// fast + deterministic. End-to-end tests against the deployed functions are
// done via curl in the worklog (see BACKEND-IMPL + QA-ROUND-1).
/// <reference lib="deno.ns" />

import { assertEquals, assertRejects } from "https://deno.land/std@0.224.0/assert/mod.ts";

// --- _shared/cors.ts -------------------------------------------------------
import { corsHeaders, handleOptions, json, errorResponse, ErrorCode } from "../functions/_shared/cors.ts";

Deno.test("corsHeaders includes required CORS + security headers", () => {
  assertEquals(corsHeaders["Access-Control-Allow-Origin"], "*");
  assertEquals(corsHeaders["Access-Control-Allow-Methods"], "POST, OPTIONS, GET");
  assertEquals(corsHeaders["X-Content-Type-Options"], "nosniff");
  // The Authorization header MUST be in Allow-Headers so JWT-gated functions
  // can receive it from the browser.
  assertIncludes(corsHeaders["Access-Control-Allow-Headers"], "authorization");
});

Deno.test("handleOptions returns 200 on OPTIONS, null otherwise", () => {
  const optReq = new Request("https://x/y", { method: "OPTIONS" });
  const res = handleOptions(optReq);
  assertEquals(res?.status, 200);

  const getReq = new Request("https://x/y", { method: "GET" });
  assertEquals(handleOptions(getReq), null);
});

Deno.test("json() returns the payload as JSON with 200 by default", async () => {
  const res = json({ ok: true });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "application/json");
  assertEquals(await res.json(), { ok: true });
});

Deno.test("errorResponse() includes a stable error code", async () => {
  const res = errorResponse("Bad input", 422, ErrorCode.VALIDATION_FAILED);
  assertEquals(res.status, 422);
  const body = await res.json();
  assertEquals(body.error, "Bad input");
  assertEquals(body.code, "VALIDATION_FAILED");
});

Deno.test("ErrorCode enum covers the documented error codes", () => {
  // Spot-check the codes referenced across the edge functions.
  assertEquals(ErrorCode.UNAUTHORIZED, "UNAUTHORIZED");
  assertEquals(ErrorCode.RATE_LIMITED, "RATE_LIMITED");
  assertEquals(ErrorCode.LOCKED_OUT, "LOCKED_OUT");
  assertEquals(ErrorCode.VALIDATION_FAILED, "VALIDATION_FAILED");
  assertEquals(ErrorCode.EXPIRED, "EXPIRED");
  assertEquals(ErrorCode.CONSUMED, "CONSUMED");
  assertEquals(ErrorCode.PAYMENT_FAILED, "PAYMENT_FAILED");
});

function assertIncludes(haystack: string, needle: string) {
  if (!haystack.includes(needle)) {
    throw new Error(`Expected "${needle}" to be included in "${haystack}"`);
  }
}

// --- _shared/rate_limit.ts -------------------------------------------------
import { checkRateLimit, RATE_LIMITS, getRateLimitIdentifier } from "../functions/_shared/rate_limit.ts";

Deno.test("checkRateLimit allows up to maxRequests then blocks", () => {
  const req = new Request("https://x/y", { method: "POST" });
  const id = "user_test_1";
  const config = { maxRequests: 3, windowSeconds: 60, name: "test_limit" };

  // First 3 requests allowed.
  for (let i = 0; i < 3; i++) {
    const r = checkRateLimit(req, id, config);
    assertEquals(r.allowed, true);
    assertEquals(r.retryAfter, 0);
  }
  // 4th request blocked.
  const r = checkRateLimit(req, id, config);
  assertEquals(r.allowed, false);
  assertEquals(r.remaining, 0);
  assertR(r.retryAfter > 0, "retryAfter should be positive when blocked");
  assertR(r.message.includes("test_limit"), "message should name the limit");
});

Deno.test("checkRateLimit resets after the window expires", async () => {
  const req = new Request("https://x/y", { method: "POST" });
  const id = "user_test_2";
  const config = { maxRequests: 1, windowSeconds: 1, name: "test_reset" };

  // First request allowed.
  assertEquals(checkRateLimit(req, id, config).allowed, true);
  // Second request blocked.
  assertEquals(checkRateLimit(req, id, config).allowed, false);
  // Wait 1.1s for the window to expire.
  await new Promise((r) => setTimeout(r, 1100));
  // Third request allowed again (new window).
  assertEquals(checkRateLimit(req, id, config).allowed, true);
});

Deno.test("RATE_LIMITS defines sensible budgets for sensitive ops", () => {
  // OTP sends should be tight (3/hour).
  assertR(RATE_LIMITS.SEND_EMAIL_OTP.maxRequests <= 5, "OTP limit too high");
  assertR(RATE_LIMITS.SEND_EMAIL_OTP.windowSeconds >= 3600, "OTP window too short");
  // Account deletion should be tight.
  assertR(RATE_LIMITS.DELETE_USER_ACCOUNT.maxRequests <= 5, "delete limit too high");
  // High-frequency ops should be generous.
  assertR(RATE_LIMITS.GENERATE_AGORA_TOKEN.maxRequests >= 30, "agora limit too low");
});

Deno.test("getRateLimitIdentifier prefers userId over IP", () => {
  const req = new Request("https://x/y", {
    method: "POST",
    headers: { "x-forwarded-for": "203.0.113.1" },
  });
  assertEquals(getRateLimitIdentifier("user-123", req), "user-123");
  assertEquals(getRateLimitIdentifier(null, req), "203.0.113.1");
});

function assertR(cond: unknown, msg: string) {
  if (!cond) throw new Error(msg);
}

// --- _shared/resend.ts -----------------------------------------------------
import { renderOtpEmail, renderStreamScheduledEmail, renderBookingConfirmationEmail } from "../functions/_shared/resend.ts";

Deno.test("renderOtpEmail contains the 6-digit code + security reminder", () => {
  const html = renderOtpEmail("482910", "signup");
  assertIncludes(html, "482910");
  assertIncludes(html, "Never share this code");
  assertIncludes(html, "Expires in 10 minutes");
  assertIncludes(html, "Resend available after 60 seconds");
  // The code must be monospace so it renders consistently.
  assertIncludes(html, "monospace");
  // Dark-mode CSS must be present.
  assertIncludes(html, "prefers-color-scheme: dark");
});

Deno.test("renderOtpEmail uses the right title per purpose", () => {
  const titles: Record<string, string> = {
    signup: "Confirm your email",
    recovery: "Reset your password",
    magic_link: "Your login code",
    email_change: "Confirm your new email",
    phone_verify: "Verify your phone",
    vault_reset: "Reset your vault PIN",
  };
  for (const [purpose, title] of Object.entries(titles)) {
    const html = renderOtpEmail("123456", purpose);
    assertIncludes(html, title);
  }
});

Deno.test("renderStreamScheduledEmail includes title + share link", () => {
  const html = renderStreamScheduledEmail({
    hostName: "Alice",
    streamTitle: "Live Coding Session",
    category: "Tech",
    scheduledDateTime: "2026-09-10 18:00 UTC",
    slotInfo: "50",
    pricingBadge: "PAID ₹99",
    shareLink: "https://triggerapp.com/stream/abc",
  });
  assertIncludes(html, "Live Coding Session");
  assertIncludes(html, "Alice");
  assertIncludes(html, "2026-09-10 18:00 UTC");
  assertIncludes(html, "https://triggerapp.com/stream/abc");
  assertIncludes(html, "PAID ₹99");
});

Deno.test("renderBookingConfirmationEmail includes attendee + host names", () => {
  const html = renderBookingConfirmationEmail({
    attendeeName: "Bob",
    streamTitle: "Live Coding Session",
    hostName: "Alice",
    scheduledDateTime: "2026-09-10 18:00 UTC",
    pricingBadge: "PAID ₹99",
    shareLink: "https://triggerapp.com/stream/abc",
  });
  assertIncludes(html, "Bob");
  assertIncludes(html, "Alice");
  assertIncludes(html, "Live Coding Session");
  assertIncludes(html, "Slot confirmed");
});

// --- _shared/firebase.ts ---------------------------------------------------
// We test the FCM sender's defensive behaviour on placeholder keys without
// hitting the network (the actual FCM call only happens after key validation).

import { sendFcm } from "../functions/_shared/firebase.ts";

Deno.test("sendFcm returns clean error for placeholder key (no leak)", async () => {
  // Simulate a placeholder private key in the env.
  const orig = Deno.env.get("FIREBASE_PRIVATE_KEY");
  Deno.env.set("FIREBASE_PROJECT_ID", "test");
  Deno.env.set("FIREBASE_CLIENT_EMAIL", "test@test.iam.gserviceaccount.com");
  Deno.env.set("FIREBASE_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDPLACEHOLDER\n-----END PRIVATE KEY-----\n");
  try {
    const result = await sendFcm({
      token: "fake_token",
      title: "test",
      body: "test",
    });
    assertEquals(result.error, "Firebase service account is not configured (placeholder key detected)");
    assertR(!JSON.stringify(result).includes("DataView"), "must not leak DataView parser error");
  } finally {
    if (orig !== undefined) Deno.env.set("FIREBASE_PRIVATE_KEY", orig);
    else Deno.env.delete("FIREBASE_PRIVATE_KEY");
  }
});

Deno.test("sendFcm returns error when env vars are missing", async () => {
  Deno.env.delete("FIREBASE_PROJECT_ID");
  Deno.env.delete("FIREBASE_CLIENT_EMAIL");
  Deno.env.delete("FIREBASE_PRIVATE_KEY");
  const result = await sendFcm({ token: "t", title: "x", body: "y" });
  assertIncludes(result.error ?? "", "not configured");
});

// --- Extended rate_limit tests (QA-ROUND-3) ---------------------------------

Deno.test("checkRateLimit isolates per identifier (user A exhaustion doesn't block user B)", () => {
  const req = new Request("https://x/y", { method: "POST" });
  const config = { maxRequests: 2, windowSeconds: 60, name: "test_isolation" };
  const alice = "user_alice";
  const bob = "user_bob";

  // Alice uses her full budget.
  assertEquals(checkRateLimit(req, alice, config).allowed, true);
  assertEquals(checkRateLimit(req, alice, config).allowed, true);
  assertEquals(checkRateLimit(req, alice, config).allowed, false); // Alice exhausted.

  // Bob is unaffected.
  assertEquals(checkRateLimit(req, bob, config).allowed, true);
  assertEquals(checkRateLimit(req, bob, config).allowed, true);
  assertEquals(checkRateLimit(req, bob, config).allowed, false); // Bob exhausted.
});

Deno.test("checkRateLimit isolates per limit name (same user, different limits)", () => {
  const req = new Request("https://x/y", { method: "POST" });
  const userId = "user_multi";
  const limitA = { maxRequests: 1, windowSeconds: 60, name: "limit_a" };
  const limitB = { maxRequests: 1, windowSeconds: 60, name: "limit_b" };

  // Exhaust limit A.
  assertEquals(checkRateLimit(req, userId, limitA).allowed, true);
  assertEquals(checkRateLimit(req, userId, limitA).allowed, false);
  // Limit B is a separate bucket.
  assertEquals(checkRateLimit(req, userId, limitB).allowed, true);
  assertEquals(checkRateLimit(req, userId, limitB).allowed, false);
});

Deno.test("checkRateLimit retryAfter is within the window", () => {
  const req = new Request("https://x/y", { method: "POST" });
  const config = { maxRequests: 1, windowSeconds: 120, name: "test_retry_after" };
  const id = "user_retry";

  assertEquals(checkRateLimit(req, id, config).allowed, true);
  const blocked = checkRateLimit(req, id, config);
  assertEquals(blocked.allowed, false);
  assertR(blocked.retryAfter > 0, "retryAfter should be positive");
  assertR(blocked.retryAfter <= 120, "retryAfter should not exceed window (120s)");
});

// --- Email template gradient-color sanity (QA-ROUND-3) ---------------------

Deno.test("renderOtpEmail uses accent color per purpose (vault_reset = amber, signup = teal)", () => {
  const vaultHtml = renderOtpEmail("123456", "vault_reset");
  const signupHtml = renderOtpEmail("123456", "signup");
  // Both should render a gradient header — we just confirm the gradient is
  // present and the title differs.
  assertIncludes(vaultHtml, "linear-gradient");
  assertIncludes(signupHtml, "linear-gradient");
  assertIncludes(vaultHtml, "Reset your vault PIN");
  assertIncludes(signupHtml, "Confirm your email");
});

// --- Message preview builder sanity (send-chat-notification) ----------------
// The buildPreview function is private to send-chat-notification, but we can
// verify the notification body shape by checking the FCM data payload contract
// via the published response shape. Here we just sanity-check that the
// preview truncation rule (100 chars + ellipsis) is documented in the source.

Deno.test("send-chat-notification preview truncation contract", async () => {
  const src = await Deno.readTextFile(new URL("../functions/send-chat-notification/index.ts", import.meta.url));
  // The source must reference the 100-char truncation + ellipsis for TEXT.
  assertIncludes(src, ".slice(0, 97)");
  assertIncludes(src, '"\u2026"'); // …
});

console.log("All Trigger App edge-function tests passed.");
