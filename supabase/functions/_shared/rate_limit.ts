// Shared per-user + per-IP rate limiter for edge functions.
// Uses the `otp_codes` table as a backing store is NOT suitable, so we keep
// an in-memory windowed counter map. For multi-instance deployments this
// should be backed by Upstash Redis or a Postgres-backed counter, but for a
// single Supabase Edge Function instance the in-memory map is sufficient
// and zero-dependency.
//
// Usage:
//   import { checkRateLimit, RATE_LIMITS } from "../_shared/rate_limit.ts";
//   const rl = checkRateLimit(req, userId, RATE_LIMITS.PROFILE_SYNC);
//   if (!rl.allowed) return errorResponse(rl.message, 429, ErrorCode.RATE_LIMITED, { retryAfter: rl.retryAfter });
//
// The limiter returns 429 with a Retry-After hint once the budget is exhausted
// inside the rolling window.

export interface RateLimitConfig {
  /** Maximum number of requests allowed within the window. */
  maxRequests: number;
  /** Window duration in seconds. */
  windowSeconds: number;
  /** Human-readable identifier for the limit (used in error messages). */
  name: string;
}

export const RATE_LIMITS = {
  // High-frequency, low-risk operations — generous budgets.
  PROFILE_SYNC: { maxRequests: 30, windowSeconds: 60, name: "profile_sync" },
  REGISTER_PUSH_TOKEN: { maxRequests: 20, windowSeconds: 60, name: "register_push_token" },
  GENERATE_AGORA_TOKEN: { maxRequests: 60, windowSeconds: 60, name: "generate_agora_token" },
  SEND_CHAT_NOTIFICATION: { maxRequests: 60, windowSeconds: 60, name: "send_chat_notification" },
  // Medium-frequency operations.
  UPDATE_SETTINGS: { maxRequests: 10, windowSeconds: 60, name: "update_settings" },
  CREATE_SUPPORT_TICKET: { maxRequests: 5, windowSeconds: 3600, name: "create_support_ticket" },
  // Sensitive operations — tight budgets (in addition to existing OTP cooldown /
  // vault lockout protections).
  SEND_EMAIL_OTP: { maxRequests: 3, windowSeconds: 3600, name: "send_email_otp" },
  RESET_VAULT_PIN: { maxRequests: 3, windowSeconds: 3600, name: "reset_vault_pin" },
  DELETE_USER_ACCOUNT: { maxRequests: 3, windowSeconds: 3600, name: "delete_user_account" },
} as const;

interface Bucket {
  count: number;
  windowStart: number; // epoch ms
}

// Map<key, Bucket> — key = `${limitName}:${userIdOrIp}`.
const buckets = new Map<string, Bucket>();

// Periodically prune expired buckets to avoid unbounded memory growth.
// Runs on every check (cheap because Map iteration is O(n) but we cap n).
let lastPrune = Date.now();
function pruneExpired(now: number) {
  if (now - lastPrune < 60_000) return; // at most once per minute
  lastPrune = now;
  for (const [k, b] of buckets) {
    if (now - b.windowStart > 3_600_000) buckets.delete(k); // drop buckets idle >1h
  }
}

export interface RateLimitResult {
  allowed: boolean;
  remaining: number;
  retryAfter: number; // seconds until the window resets; 0 when allowed
  message: string;
}

/**
 * Check the rate limit for a given identifier (user id or IP). Returns a
 * RateLimitResult — if `allowed` is false, respond with 429 + retryAfter.
 */
export function checkRateLimit(
  req: Request,
  identifier: string,
  config: RateLimitConfig,
): RateLimitResult {
  // Fall back to IP when no identifier (the caller should pass userId; for
  // unauthenticated functions like razorpay-webhook we use the IP).
  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const key = `${config.name}:${identifier || ip}`;
  const now = Date.now();
  pruneExpired(now);

  const bucket = buckets.get(key);
  const windowMs = config.windowSeconds * 1000;

  if (!bucket || now - bucket.windowStart > windowMs) {
    // Start a fresh window.
    buckets.set(key, { count: 1, windowStart: now });
    return {
      allowed: true,
      remaining: config.maxRequests - 1,
      retryAfter: 0,
      message: "",
    };
  }

  if (bucket.count >= config.maxRequests) {
    const retryAfter = Math.ceil((bucket.windowStart + windowMs - now) / 1000);
    return {
      allowed: false,
      remaining: 0,
      retryAfter,
      message: `Rate limit exceeded for ${config.name}. Try again in ${retryAfter}s.`,
    };
  }

  bucket.count += 1;
  return {
    allowed: true,
    remaining: config.maxRequests - bucket.count,
    retryAfter: 0,
    message: "",
  };
}

/** Extract the caller's identifier (userId when authenticated, IP otherwise). */
export function getRateLimitIdentifier(userId: string | null, req: Request): string {
  if (userId) return userId;
  return (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
}
