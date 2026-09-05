// Edge function: toggle-star-message
// Toggles the is_starred flag on a message.
// Auth: JWT required. Any participant can star a message.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const messageId = body.message_id ?? "";
  if (!messageId) return json({ error: "message_id is required" }, 422);

  const supabase = createAdminClient();

  // Get current starred state
  const { data: msg, error: fetchError } = await supabase
    .from("messages").select("is_starred").eq("id", messageId).maybeSingle();

  if (fetchError || !msg) return json({ error: "Message not found" }, 404);

  // Toggle
  const newStarred = !msg.is_starred;
  const { error: updateError } = await supabase
    .from("messages").update({ is_starred: newStarred }).eq("id", messageId);

  if (updateError) return json({ error: "Failed to toggle star" }, 500);
  return json({ starred: newStarred });
}

serve(handler, { port: 9040 });
