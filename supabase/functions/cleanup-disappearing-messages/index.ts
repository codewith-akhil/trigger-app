// Edge function: cleanup-disappearing-messages
// ----------------------------------------------------------------------------
// Cron-driven sweep for expired disappearing (auto-delete) messages.
//
// Replaces the plpgsql pg_cron sweep (20260909) which could only DELETE DB
// rows — the corresponding chat_media/voice_notes/documents storage objects
// stayed playable forever (audit finding F5). This function:
//   1. fetches a batch of expired messages (activation-floor aware) via the
//      security-definer RPC expired_disappearing_batch,
//   2. removes their storage objects (media + thumbnail) with the service
//      key,
//   3. deletes the message rows (realtime DELETE events propagate to any
//      open chat, same as the old cron path),
//   4. purges expired live_location_shares (expiry was never enforced).
//
// Auth: x-cron-secret header (same gate as update-fx-rates). Safe to re-run
// (idempotent: expired rows simply disappear from the batch).
//
// Response 200: { "ok": true, "deletedMessages": n, "purgedObjects": n, "purgedLocationShares": n }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// A "bare path" is "{uid}/{uuid}.ext" (Task 24 shape). Legacy full URLs are
// skipped defensively (20260924 §3 already migrated them to bare paths).
function isObjectPath(v: unknown): v is string {
  return typeof v === "string" && v.length > 0 && !v.startsWith("http") && v.includes("/");
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const cronSecret = Deno.env.get("CRON_SECRET");
  const providedSecret = req.headers.get("x-cron-secret") ?? "";
  if (!cronSecret || !timingSafeEqualHex(providedSecret, cronSecret)) {
    return errorResponse("Unauthorized", 401);
  }

  const supabase = createAdminClient();

  // 1. Batch of expired messages (oldest first, ≤1000 per run — the cron
  //    fires every 15 minutes so the backlog drains quickly).
  const { data: batch, error: batchErr } = await supabase.rpc("expired_disappearing_batch", {
    p_limit: 1000,
  });
  if (batchErr) {
    console.error("expired_disappearing_batch failed", batchErr);
    return errorResponse("Failed to fetch expired batch", 500);
  }

  const rows = (batch ?? []) as { msg_id: string; bucket: string | null; path: string | null }[];
  let purgedObjects = 0;

  // 2. Storage purge, grouped per bucket (remove() takes one bucket at a time).
  const byBucket = new Map<string, string[]>();
  for (const r of rows) {
    if (r.bucket && isObjectPath(r.path)) {
      const list = byBucket.get(r.bucket) ?? [];
      list.push(r.path);
      byBucket.set(r.bucket, list);
    }
  }
  for (const [bucket, paths] of byBucket) {
    // Chunk to keep request bodies reasonable.
    for (let i = 0; i < paths.length; i += 50) {
      const chunk = paths.slice(i, i + 50);
      try {
        const { error: rmErr } = await supabase.storage.from(bucket).remove(chunk);
        if (rmErr) console.error(`storage remove failed for ${bucket}`, rmErr.message);
        else purgedObjects += chunk.length;
      } catch (e) {
        console.error(`storage remove threw for ${bucket}`, e);
      }
    }
  }

  // 3. Delete the message rows (realtime DELETE events propagate).
  let deletedMessages = 0;
  const ids = rows.map((r) => r.msg_id);
  for (let i = 0; i < ids.length; i += 100) {
    const chunk = ids.slice(i, i + 100);
    const { error: delErr, count } = await supabase
      .from("messages")
      .delete({ count: "exact" })
      .in("id", chunk);
    if (delErr) console.error("message delete failed", delErr.message);
    else deletedMessages += count ?? 0;
  }

  // 4. Expired live-location shares (never purged anywhere before).
  let purgedLocationShares = 0;
  try {
    const { error: llErr, count } = await supabase
      .from("live_location_shares")
      .delete({ count: "exact" })
      .lt("expires_at", new Date().toISOString());
    if (llErr) console.error("live_location_shares purge failed", llErr.message);
    else purgedLocationShares = count ?? 0;
  } catch (e) {
    // Table might not exist in some environments — non-fatal.
    console.error("live_location_shares purge threw", e);
  }

  return json({ ok: true, deletedMessages, purgedObjects, purgedLocationShares });
}

serve(handler);
