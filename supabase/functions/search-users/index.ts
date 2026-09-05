// Edge function: search-users
// Search by username. Returns matching users (excluding self).
//
// Security:
//   - Requires a valid Supabase JWT.
//   - Rate-limited per IP (20 requests / 5 min) to slow enumeration.
//   - Query is validated against a strict allow-list (alphanumeric + ._-,
//     max 50 chars) — defense-in-depth against PostgREST filter injection.
//   - Uses .ilike("username", ...) rather than .or(...) with interpolated
//     input — the previous .or() filter was an injection vector.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

interface Body { query?: string; }

// 20 searches per 5 minutes per IP — generous enough for normal use, tight
// enough to slow username enumeration attacks.
const SEARCH_LIMIT = { maxRequests: 20, windowSeconds: 300, name: "search_users" };

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  // --- Rate limit per IP (search is a sensitive enumeration surface) ---
  const ip = (req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip") ?? "anonymous")
    .split(",")[0].trim();
  const rl = checkRateLimit(req, ip, SEARCH_LIMIT);
  if (!rl.allowed) {
    return json(
      { error: `Too many search requests. Try again in ${rl.retryAfter}s.`, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter },
      429,
    );
  }

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  // --- Sanitize + validate the query ----------------------------------------
  // Allow alphanumeric + a few safe chars used in usernames. Max 50 chars.
  // Stripping leading "@" handles the "@username" search style. Any query
  // that doesn't match the allow-list returns an empty result set rather
  // than an error — the client treats both the same.
  const raw = (body.query ?? "").trim().replace(/^@/, "");
  if (raw.length < 2 || raw.length > 50) return json({ users: [] });
  if (!/^[A-Za-z0-9._\-]+$/.test(raw)) return json({ users: [] });
  const query = raw.toLowerCase();

  const supabase = createAdminClient();
  // Search by username only (ilike). The previous .or(...) builder
  // interpolated the unsanitized query directly into a PostgREST filter
  // string, allowing an attacker to inject additional filters
  // (e.g. ",email.eq.admin@x,") — .ilike() on a single column is safe.
  const { data, error } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url, phone")
    .neq("id", userId)
    .ilike("username", `%${query}%`)
    .limit(20);

  if (error) return json({ error: "Search failed" }, 500);
  return json({ users: data ?? [] });
}
serve(handler, { port: 9032 });
