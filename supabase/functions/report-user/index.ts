// Edge function: report-user
// Reports a user for inappropriate behavior. Stores in support_tickets with reported_user_id.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { reported_user_id?: string; reason?: string; conversation_id?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const reportedId = body.reported_user_id ?? ""; const reason = (body.reason ?? "").trim();
  if (!reportedId) return json({ error: "reported_user_id required" }, 422);
  // UUID-validate — an arbitrary string hit the DB cast and surfaced as 500.
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!UUID_RE.test(reportedId)) return json({ error: "reported_user_id must be a UUID" }, 422);
  if (!reason) return json({ error: "reason required" }, 422);
  if (reason.length > 1000) return json({ error: "Reason too long (max 1000)" }, 422);
  const supabase = createAdminClient();
  const { error } = await supabase.from("support_tickets").insert({
    user_id: userId, reported_user_id: reportedId, subject: "User Report",
    message: reason, category: "bug", status: "open",
  });
  if (error) return json({ error: "Failed to submit report" }, 500);
  return json({ reported: true });
}
serve(handler, { port: 9042 });
