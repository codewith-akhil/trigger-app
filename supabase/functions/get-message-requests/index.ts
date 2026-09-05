// Edge function: get-message-requests
// Returns pending message requests for the caller.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const supabase = createAdminClient();
  const { data, error } = await supabase
    .from("message_requests")
    .select("id, sender_id, sender_name, sender_username, sender_avatar_url, initial_message, created_at")
    .eq("receiver_id", userId)
    .eq("status", "pending")
    .order("created_at", { ascending: false })
    .limit(50);

  if (error) return json({ error: "Failed to fetch requests" }, 500);
  return json({ requests: data ?? [] });
}
serve(handler, { port: 9035 });
