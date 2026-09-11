// Edge function: purge-expired-media
// ----------------------------------------------------------------------------
// Phase 6 legal-retention compliance — the 14-day HARD PURGE.
//
// Daily pg_cron job `trigger-purge-expired-media` (04:00 UTC, pg_net POST)
// calls this function; it can also be invoked manually with the service-role
// key. For every media_archive row with status='retained' and
// purge_at <= now():
//   1. DELETE the media_vault object via the storage API (service role),
//   2. mark the row status='purged', purged_at=now().
// The ROW IS NEVER DELETED — the ledger must prove deletion happened.
// Rows in status 'legal_hold' are deliberately never touched.
//
// Auth: SERVICE ROLE KEY ONLY. Any other Authorization is rejected with 401
// (timing-safe comparison). Deploy with --no-verify-jwt (self-validates).
//
// Response 200: { "ok": true, "purged": n, "failed": m, "scanned": n+m }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

const VAULT_BUCKET = "media_vault";
const BATCH_LIMIT = 500; // per invocation — daily cron drains far more than enough

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

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  // Service-role key ONLY (the pg_cron job sends exactly this header).
  const expected = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const provided = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!expected || !timingSafeEqualStr(provided, expected)) {
    return errorResponse("Unauthorized", 401);
  }

  const supabase = createAdminClient();

  // Rows due for purge — oldest first.
  const { data: due, error: selErr } = await supabase
    .from("media_archive")
    .select("id, message_id, vault_path")
    .eq("status", "retained")
    .lte("purge_at", new Date().toISOString())
    .order("purge_at", { ascending: true })
    .limit(BATCH_LIMIT);
  if (selErr) {
    console.error("purge-expired-media: select failed", selErr.message);
    return errorResponse("Failed to query archive ledger", 500);
  }

  const rows = (due ?? []) as { id: string; message_id: string; vault_path: string | null }[];
  let purged = 0;
  let failed = 0;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const baseUrl = Deno.env.get("SUPABASE_URL") ?? "";

  for (const row of rows) {
    // No vault object recorded (e.g. archived before the copy step ran) —
    // there is nothing to delete; the ledger row still flips to 'purged'.
    if (!row.vault_path) {
      const { error: updErr } = await supabase
        .from("media_archive")
        .update({ status: "purged", purged_at: new Date().toISOString() })
        .eq("id", row.id);
      if (updErr) { console.error("ledger update failed", row.id, updErr.message); failed++; }
      else purged++;
      continue;
    }

    try {
      const delRes = await fetch(
        `${baseUrl}/storage/v1/object/${VAULT_BUCKET}/${encodeObjectPath(row.vault_path)}`,
        { method: "DELETE", headers: { "Authorization": `Bearer ${serviceKey}` } },
      );
      // 200 = deleted; 404 "NoSuchKey"/"Object not found" = already gone —
      // both satisfy the compliance guarantee, so both mark purged.
      if (delRes.ok || delRes.status === 404) {
        const { error: updErr } = await supabase
          .from("media_archive")
          .update({ status: "purged", purged_at: new Date().toISOString() })
          .eq("id", row.id);
        if (updErr) {
          console.error("ledger update failed", row.id, updErr.message);
          failed++;
        } else {
          purged++;
        }
      } else {
        console.error("vault delete failed", row.vault_path, delRes.status,
          await delRes.text().catch(() => ""));
        failed++; // row stays 'retained' — retried by the next daily run
      }
    } catch (e) {
      console.error("vault delete threw", row.vault_path, e);
      failed++;
    }
  }

  return json({ ok: true, purged, failed, scanned: rows.length });
}

serve(handler);
