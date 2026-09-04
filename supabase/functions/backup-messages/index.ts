// Edge function: backup-messages
// ----------------------------------------------------------------------------
// Manages chat backups for the StorageSettingsScreen "Back Up Now" button.
// The Android app currently simulates a 1200ms backup and shows "Local backup:
// Today, 2:45 PM (42 MB)" — this function persists real backup metadata to
// the `chat_backups` table so backups survive device loss + are queryable.
//
// Two actions:
//   action="create"  — records a new backup row. The client uploads the
//                      backup file to the `backups` Storage bucket first,
//                      then calls this with the storage_path + metadata.
//   action="list"    — returns the caller's backup history (most recent first).
//   action="restore" — marks a backup as restored (for analytics / auditing).
//
// Auth: requires a valid Supabase JWT.
// Rate limit: create=5/hour, list=20/60s, restore=2/hour.
//
// Request body for "create":
//   { "action": "create",
//     "storagePath": string,         // path in 'backups' bucket
//     "fileName"?: string,
//     "fileSize"?: number,           // bytes
//     "messageCount"?: number,
//     "conversationCount"?: number,
//     "backupVersion"?: number }     // schema version, default 1
//
// Request body for "list":
//   { "action": "list", "limit"?: number }   // default 10, max 50
//
// Request body for "restore":
//   { "action": "restore", "backupId": string }
//
// Response 200:
//   create → { "created": true, "backup": {...} }
//   list   → { "backups": [...], "latest"?: {...} }
//   restore→ { "restored": true, "backupId": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const CREATE_LIMIT  = { maxRequests: 5,  windowSeconds: 3600, name: "backup_create" };
const LIST_LIMIT    = { maxRequests: 20, windowSeconds: 60,   name: "backup_list" };
const RESTORE_LIMIT = { maxRequests: 2,  windowSeconds: 3600, name: "backup_restore" };

interface Body {
  action?: string;
  storagePath?: string;
  fileName?: string;
  fileSize?: number;
  messageCount?: number;
  conversationCount?: number;
  backupVersion?: number;
  limit?: number;
  backupId?: string;
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
  const supabase = createAdminClient();

  if (action === "create") {
    const rl = checkRateLimit(req, userId, CREATE_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    const storagePath = (body.storagePath ?? "").trim();
    if (!storagePath) return errorResponse("storagePath is required", 422, ErrorCode.VALIDATION_FAILED);
    if (storagePath.length > 512) return errorResponse("storagePath too long (max 512)", 422, ErrorCode.VALIDATION_FAILED);

    const fileSize = typeof body.fileSize === "number" && body.fileSize >= 0 ? Math.min(body.fileSize, Number.MAX_SAFE_INTEGER) : 0;
    const messageCount = typeof body.messageCount === "number" && body.messageCount >= 0 ? Math.min(body.messageCount, 1_000_000) : 0;
    const conversationCount = typeof body.conversationCount === "number" && body.conversationCount >= 0 ? Math.min(body.conversationCount, 100_000) : 0;
    const backupVersion = typeof body.backupVersion === "number" && body.backupVersion > 0 ? body.backupVersion : 1;

    const { data, error } = await supabase
      .from("chat_backups")
      .insert({
        user_id: userId,
        storage_path: storagePath,
        file_name: (body.fileName ?? "").slice(0, 256) || null,
        file_size: fileSize,
        message_count: messageCount,
        conversation_count: conversationCount,
        backup_version: backupVersion,
        status: "completed",
      })
      .select()
      .single();

    if (error) {
      console.error("backup create failed", error);
      return errorResponse("Failed to record backup", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({ created: true, backup: data });
  }

  if (action === "list") {
    const rl = checkRateLimit(req, userId, LIST_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    const limit = Math.min(Math.max(body.limit ?? 10, 1), 50);
    const { data, error } = await supabase
      .from("chat_backups")
      .select("*")
      .eq("user_id", userId)
      .order("created_at", { ascending: false })
      .limit(limit);

    if (error) {
      console.error("backup list failed", error);
      return errorResponse("Failed to list backups", 500, ErrorCode.INTERNAL_ERROR);
    }

    return json({ backups: data ?? [], latest: (data && data.length > 0) ? data[0] : null });
  }

  if (action === "restore") {
    const rl = checkRateLimit(req, userId, RESTORE_LIMIT);
    if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

    if (!isUuid(body.backupId)) {
      return errorResponse("backupId must be a UUID", 422, ErrorCode.VALIDATION_FAILED);
    }
    const { error } = await supabase
      .from("chat_backups")
      .update({ status: "restored" })
      .eq("id", body.backupId)
      .eq("user_id", userId);
    if (error) {
      console.error("backup restore failed", error);
      return errorResponse("Failed to mark backup as restored", 500, ErrorCode.INTERNAL_ERROR);
    }
    return json({ restored: true, backupId: body.backupId });
  }

  return errorResponse(`Unknown action: ${action}. Use "create", "list", or "restore".`, 422, ErrorCode.VALIDATION_FAILED);
}

serve(handler, { port: 9021 });
