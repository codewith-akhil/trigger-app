// Edge function: pin-message
// Toggles the is_pinned flag on a message.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? "";
  if (!messageId) return json({ error: "message_id required" }, 422);

  const supabase = createAdminClient();

  // Get current pinned state
  const { data: msg, error: fetchErr } = await supabase
    .from("messages").select("is_pinned, conversation_id").eq("id", messageId).maybeSingle();
  if (fetchErr || !msg) return json({ error: "Message not found" }, 404);

  // Verify caller is a participant in the conversation
  const { data: conv } = await supabase
    .from("conversations").select("owner_id, peer_id").eq("id", msg.conversation_id).maybeSingle();
  if (!conv || (conv.owner_id !== userId && conv.peer_id !== userId)) {
    return json({ error: "Not authorized" }, 403);
  }

  // Toggle
  const newPinned = !msg.is_pinned;
  const { error: updateErr } = await supabase
    .from("messages").update({ is_pinned: newPinned }).eq("id", messageId);
  if (updateErr) return json({ error: "Failed to toggle pin" }, 500);

  return json({ pinned: newPinned });
}
serve(handler, { port: 9046 });
