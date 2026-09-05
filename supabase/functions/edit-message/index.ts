// Edge function: edit-message
// Edits a message's text. Only the sender can edit. Sets edited_at timestamp.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; new_text?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? ""; const newText = (body.new_text ?? "").trim();
  if (!messageId) return json({ error: "message_id required" }, 422);
  if (!newText) return json({ error: "new_text required" }, 422);
  if (newText.length > 10000) return json({ error: "Text too long (max 10000)" }, 422);
  const supabase = createAdminClient();
  const { data: msg } = await supabase.from("messages").select("sender_id").eq("id", messageId).maybeSingle();
  if (!msg) return json({ error: "Message not found" }, 404);
  if (msg.sender_id !== userId) return json({ error: "Only sender can edit" }, 403);
  const { error } = await supabase.from("messages").update({ text: newText, edited_at: new Date().toISOString() }).eq("id", messageId);
  if (error) return json({ error: "Failed to edit" }, 500);
  return json({ edited: true });
}
serve(handler, { port: 9041 });
