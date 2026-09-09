// Edge function: manage-blocked-contacts
// ----------------------------------------------------------------------------
// Manages the user's blocked-contacts list (PrivacySettingsScreen → Blocked
// contacts row). The Android app currently has a no-op Blocked contacts row;
// this function provides block / unblock / list operations backed by the
// `blocked_contacts` table.
//
// Actions:
//   action="block"    — block a contact (by user_id if known, else by identifier)
//   action="unblock"  — unblock a contact
//   action="list"     — list all blocked contacts for the caller
//   action="check"    — check if a specific identifier is blocked
//
// Auth: requires a valid Supabase JWT.
// Rate limit: block/unblock=20/60s, list/check=30/60s.
//
// Request body for block/unblock:
//   { "action": "block"|"unblock",
//     "blockedIdentifier": string,        // peer_name or phone — required
//     "blockedUserId"?: string }          // UUID if the contact is a real user
//
// Request body for list:
//   { "action": "list" }
//
// Request body for check:
//   { "action": "check", "blockedIdentifier": string }
//
// Response 200:
//   block/unblock → { "ok": true, "action": "...", "identifier": "..." }
//   list          → { "blocked": [...] }
//   check         → { "blocked": boolean }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, createUserClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const BLOCK_LIMIT   = { maxRequests: 20, windowSeconds: 60, name: "block_contact" };
const LIST_LIMIT    = { maxRequests: 30, windowSeconds: 60, name: "list_blocked" };

interface Body {
  action?: string;
  blockedIdentifier?: string;
  blockedUserId?: string;
}

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); }
  catch { return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED); }

  const action = (body.action ?? "").toLowerCase();
  // Use the USER's JWT for the security_definer RPC (auth.uid() must resolve).
  // For direct table queries (list/check) we use the user client too so RLS
  // applies — the owner-policy ensures the caller only sees their own blocks.
  const userClient = createUserClient(authHeader);
  const adminClient = createAdminClient();

  if (action === "block" || action === "unblock") {
    const rl = checkRateLimit(req, userId, BLOCK_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    const identifier = (body.blockedIdentifier ?? "").trim();
    if (!identifier) {
      return errorResponse("blockedIdentifier is required", 422, ErrorCode.VALIDATION_FAILED);
    }
    if (identifier.length > 120) {
      return errorResponse("blockedIdentifier too long (max 120)", 422, ErrorCode.VALIDATION_FAILED);
    }

    const blockedUserId = body.blockedUserId && isUuid(body.blockedUserId) ? body.blockedUserId : null;

    const { error } = await userClient.rpc("set_contact_blocked", {
      p_blocked_identifier: identifier,
      p_blocked_user_id: blockedUserId,
      p_blocked: action === "block",
    });
    if (error) {
      console.error(`${action} failed`, error);
      return errorResponse(`Failed to ${action} contact`, 500, ErrorCode.INTERNAL_ERROR);
    }
    return json({ ok: true, action, identifier, ...(blockedUserId ? { blockedUserId } : {}) });
  }

  if (action === "list") {
    const rl = checkRateLimit(req, userId, LIST_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    const { data, error } = await adminClient
      .from("blocked_contacts")
      .select("id, blocked_user_id, blocked_identifier, created_at")
      .eq("user_id", userId)
      .order("created_at", { ascending: false })
      .limit(500);
    if (error) {
      console.error("list blocked failed", error);
      return errorResponse("Failed to list blocked contacts", 500, ErrorCode.INTERNAL_ERROR);
    }

    // Enrich rows that map to a real user with profile display data (one
    // batched lookup). Rows without a resolvable profile keep only
    // blocked_identifier (null-safe — phone/legacy rows stay untouched).
    const rows = data ?? [];
    const profileIds = rows
      .map(r => r.blocked_user_id)
      .filter((id): id is string => typeof id === "string" && isUuid(id));
    const byId = new Map<string, { full_name: string | null; username: string | null; avatar_url: string | null }>();
    if (profileIds.length > 0) {
      const { data: profiles, error: pError } = await adminClient
        .from("profiles")
        .select("id, full_name, username, avatar_url")
        .in("id", profileIds);
      if (pError) {
        console.warn("list blocked: profile enrichment failed", pError);
      }
      for (const p of profiles ?? []) byId.set(p.id, p);
    }

    return json({
      blocked: rows.map(r => {
        const p = r.blocked_user_id ? byId.get(r.blocked_user_id) : undefined;
        if (!p) return r;
        return {
          ...r,
          display_name: p.full_name || p.username || null,
          avatar_url: p.avatar_url ?? null,
        };
      }),
    });
  }

  if (action === "check") {
    const rl = checkRateLimit(req, userId, LIST_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    // Preferred: check by the peer's auth UUID (stable even when a display
    // name changes or collides). Legacy: check by identifier string.
    const blockedUserId = (body.blockedUserId ?? "").trim();
    if (blockedUserId) {
      if (!isUuid(blockedUserId)) return errorResponse("blockedUserId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
      const { data, error } = await adminClient
        .from("blocked_contacts")
        .select("id")
        .eq("user_id", userId)
        .eq("blocked_user_id", blockedUserId)
        .maybeSingle();
      if (error) {
        console.error("check blocked failed", error);
        return errorResponse("Failed to check blocked status", 500, ErrorCode.INTERNAL_ERROR);
      }
      return json({ blocked: !!data });
    }

    const identifier = (body.blockedIdentifier ?? "").trim();
    if (!identifier) return errorResponse("blockedIdentifier or blockedUserId is required", 422, ErrorCode.VALIDATION_FAILED);

    const { data, error } = await adminClient
      .from("blocked_contacts")
      .select("id")
      .eq("user_id", userId)
      .eq("blocked_identifier", identifier)
      .maybeSingle();
    if (error) {
      console.error("check blocked failed", error);
      return errorResponse("Failed to check blocked status", 500, ErrorCode.INTERNAL_ERROR);
    }
    return json({ blocked: !!data });
  }

  return errorResponse(`Unknown action: ${action}. Use "block", "unblock", "list", or "check".`, 422, ErrorCode.VALIDATION_FAILED);
}

serve(handler, { port: 9022 });
