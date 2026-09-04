// Shared error + response helpers with structured error codes.
// All edge functions return consistent JSON envelopes:
//   success: { ...payload, ok: true }
//   error:   { error: "<message>", code: "<ERROR_CODE>", details?: {...} }
//
// Error codes follow a STABLE convention so the Android client can map them
// to localized UI strings without parsing prose.

export const corsHeaders: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-trigger-source",
  "Access-Control-Allow-Methods": "POST, OPTIONS, GET",
  "Access-Control-Max-Age": "86400",
  "X-Content-Type-Options": "nosniff",
};

export function handleOptions(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  return null;
}

export interface ErrorBody {
  error: string;
  code?: string;
  details?: Record<string, unknown>;
  [key: string]: unknown;
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/**
 * Structured error response. Always includes a human-readable `error` message
 * and a stable `code` for client-side dispatch. Status defaults to 400.
 */
export function errorResponse(message: string, status = 400, code?: string, details?: Record<string, unknown>): Response {
  const body: ErrorBody = { error: message };
  if (code) body.code = code;
  if (details) body.details = details;
  return json(body, status);
}

// Common error codes (use these instead of ad-hoc strings).
export const ErrorCode = {
  UNAUTHORIZED: "UNAUTHORIZED",
  FORBIDDEN: "FORBIDDEN",
  NOT_FOUND: "NOT_FOUND",
  VALIDATION_FAILED: "VALIDATION_FAILED",
  CONFLICT: "CONFLICT",
  RATE_LIMITED: "RATE_LIMITED",
  EXPIRED: "EXPIRED",
  CONSUMED: "CONSUMED",
  LOCKED_OUT: "LOCKED_OUT",
  PAYMENT_FAILED: "PAYMENT_FAILED",
  CONFIG_MISSING: "CONFIG_MISSING",
  INTERNAL_ERROR: "INTERNAL_ERROR",
  METHOD_NOT_ALLOWED: "METHOD_NOT_ALLOWED",
} as const;
