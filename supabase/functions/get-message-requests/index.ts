// Edge function: get-message-requests
// Returns pending message requests for the caller, including the canonical
// conversation id + how many messages the sender has sent so far (the
// pre-accept thread preview), so the client can open the pending chat and
// show "N/3 messages".
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
    .select("id, sender_id, sender_name, sender_username, sender_avatar_url, initial_message, conversation_id, created_at")
    .eq("receiver_id", userId)
    .eq("status", "pending")
    .order("created_at", { ascending: false })
    .limit(50);

  if (error) return json({ error: "Failed to fetch requests" }, 500);

  // Attach the pre-accept message count per request (batched, one query per
  // request row is avoided by grouping on distinct conversation ids).
  const requests = data ?? [];
  const convIds = Array.from(new Set(requests.map(r => r.conversation_id).filter(Boolean))) as string[];
  const counts: Record<string, number> = {};
  if (convIds.length > 0) {
    const { data: msgs } = await supabase
      .from("messages")
      .select("conversation_id, sender_id")
      .in("conversation_id", convIds)
      .limit(500);
    for (const m of msgs ?? []) {
      if (m.sender_id && requests.some(r => r.sender_id === m.sender_id && r.conversation_id === m.conversation_id)) {
        counts[m.conversation_id] = (counts[m.conversation_id] ?? 0) + 1;
      }
    }
  }

  return json({
    requests: requests.map(r => ({
      ...r,
      message_count: r.conversation_id ? (counts[r.conversation_id] ?? 0) : 0,
    })),
  });
}
serve(handler, { port: 9035 });
