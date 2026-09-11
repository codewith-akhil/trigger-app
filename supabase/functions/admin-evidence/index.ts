// Edge function: admin-evidence
// ----------------------------------------------------------------------------
// Phase 6 legal-retention compliance — OWNER-ONLY evidence endpoint.
//
// The single legitimate way to reach the retention archive: the project owner
// authenticates with the service-role key, filters the media_archive ledger,
// and receives — for rows still retained in the private media_vault bucket —
// short-TTL (15-minute) signed URLs to hand media to government authorities.
// Purged rows come back with signed_url=null: the ledger proves the media
// existed and was deleted at purged_at. End users can NEVER reach this
// endpoint (no user JWT, no anon key is accepted — service role only).
//
// Auth: SERVICE ROLE KEY ONLY. Any other Authorization is rejected with 401
// (timing-safe comparison). Deploy with --no-verify-jwt (self-validates).
//
// Input (query string on GET or JSON/query on POST — same names):
//   user_id         matches sender_id OR receiver_id
//   conversation_id exact match
//   message_id      exact match (messages.id)
//   date_from       ISO 8601 — filters received_at >=
//   date_to         ISO 8601 — filters received_at <=
//   status          one of retained | purged | legal_hold (optional)
//   limit           default 200, max 1000
// Response 200:
//   { ok: true, filters, count, signedUrlTtlSeconds: 900, generatedAt,
//     rows: [ { ...ledger row, signed_url, signed_url_expires_at } ] }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

const VAULT_BUCKET = "media_vault";
const SIGNED_TTL_SECONDS = 900; // 15 minutes
const VALID_STATUS = new Set(["retained", "purged", "legal_hold"]);
const ID_RE = /^[A-Za-z0-9_-]+$/; // uuids (and any future text id) — blocks PostgREST filter injection

function timingSafeEqualStr(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Storage object paths contain slashes that must stay literal. */
function encodeObjectPath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

function parseIso(value: string): string | null {
  const d = new Date(value);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "GET" && req.method !== "POST") {
    return errorResponse("Method not allowed", 405);
  }

  // Service-role key ONLY — the owner's admin channel, never the app.
  const expected = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const provided = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!expected || !timingSafeEqualStr(provided, expected)) {
    return errorResponse("Unauthorized", 401);
  }

  // Accept filters from query string AND/OR JSON body (POST).
  const url = new URL(req.url);
  const q = (k: string): string | null => url.searchParams.get(k);
  let bodyFilters: Record<string, unknown> = {};
  if (req.method === "POST") {
    try { bodyFilters = await req.json(); } catch { /* empty body is fine */ }
  }
  const pick = (k: string): string | null => {
    const v = bodyFilters[k] ?? q(k);
    return v == null ? null : String(v).trim();
  };

  const userId = pick("user_id");
  const conversationId = pick("conversation_id");
  const messageId = pick("message_id");
  const status = pick("status");
  const dateFromRaw = pick("date_from");
  const dateToRaw = pick("date_to");
  const limitRaw = pick("limit");

  const dateFrom = dateFromRaw ? parseIso(dateFromRaw) : null;
  const dateTo = dateToRaw ? parseIso(dateToRaw) : null;
  if (dateFromRaw && !dateFrom) return errorResponse("date_from must be ISO 8601", 422);
  if (dateToRaw && !dateTo) return errorResponse("date_to must be ISO 8601", 422);
  if (status && !VALID_STATUS.has(status)) {
    return errorResponse("status must be retained | purged | legal_hold", 422);
  }
  for (const [label, v] of [["user_id", userId], ["conversation_id", conversationId], ["message_id", messageId]] as const) {
    if (v && (!ID_RE.test(v) || v.length > 128)) {
      return errorResponse(`${label} contains invalid characters`, 422);
    }
  }
  const limit = Math.min(Math.max(parseInt(limitRaw ?? "", 10) || 200, 1), 1000);
  if (!userId && !conversationId && !messageId && !dateFrom && !dateTo && !status) {
    return errorResponse(
      "Provide at least one filter: user_id, conversation_id, message_id, date_from, date_to, status",
      422,
    );
  }

  const supabase = createAdminClient();
  let query = supabase.from("media_archive").select("*").order("received_at", { ascending: false }).limit(limit);
  if (userId) query = query.or(`sender_id.eq.${userId},receiver_id.eq.${userId}`);
  if (conversationId) query = query.eq("conversation_id", conversationId);
  if (messageId) query = query.eq("message_id", messageId);
  if (status) query = query.eq("status", status);
  if (dateFrom) query = query.gte("received_at", dateFrom);
  if (dateTo) query = query.lte("received_at", dateTo);

  const { data, error } = await query;
  if (error) {
    console.error("admin-evidence: query failed", error.message);
    return errorResponse("Failed to query archive ledger", 500);
  }

  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const baseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const expiresAt = new Date(Date.now() + SIGNED_TTL_SECONDS * 1000).toISOString();

  const rows = await Promise.all(((data ?? []) as Record<string, unknown>[]).map(async (row) => {
    // Only retained rows still have a vault object; purged rows deliberately
    // get no URL (the media no longer exists — the ledger row is the proof).
    if (row.status !== "retained" || typeof row.vault_path !== "string" || row.vault_path.length === 0) {
      return { ...row, signed_url: null, signed_url_expires_at: null };
    }
    try {
      const signRes = await fetch(
        `${baseUrl}/storage/v1/object/sign/${VAULT_BUCKET}/${encodeObjectPath(row.vault_path)}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "Authorization": `Bearer ${serviceKey}` },
          body: JSON.stringify({ expiresIn: SIGNED_TTL_SECONDS }),
        },
      );
      if (!signRes.ok) {
        console.error("sign failed", row.vault_path, signRes.status);
        return { ...row, signed_url: null, signed_url_expires_at: null };
      }
      const { signedURL } = await signRes.json() as { signedURL: string };
      return {
        ...row,
        signed_url: `${baseUrl}/storage/v1${signedURL}`,
        signed_url_expires_at: expiresAt,
      };
    } catch (e) {
      console.error("sign threw", row.vault_path, e);
      return { ...row, signed_url: null, signed_url_expires_at: null };
    }
  }));

  return json({
    ok: true,
    filters: { user_id: userId, conversation_id: conversationId, message_id: messageId, date_from: dateFrom, date_to: dateTo, status: status ?? null },
    count: rows.length,
    signedUrlTtlSeconds: SIGNED_TTL_SECONDS,
    generatedAt: new Date().toISOString(),
    rows,
  });
}

serve(handler);
