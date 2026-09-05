// Edge function: toggle-reaction
// Toggles a reaction (emoji) on a message. Inserts or removes from message_reactions table.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; emoji?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? ""; const emoji = (body.emoji ?? "").trim();
  if (!messageId) return json({ error: "message_id required" }, 422);
  if (!emoji || emoji.length > 10) return json({ error: "Valid emoji required (max 10 chars)" }, 422);

  const supabase = createAdminClient();

  // Check if reaction already exists
  const { data: existing } = await supabase
    .from("message_reactions")
    .select("id")
    .eq("message_id", messageId)
    .eq("user_id", userId)
    .eq("emoji", emoji)
    .maybeSingle();

  if (existing) {
    // Remove the reaction
    await supabase.from("message_reactions").delete().eq("id", existing.id);
    return json({ reacted: false, emoji });
  } else {
    // Add the reaction
    const { error } = await supabase.from("message_reactions").insert({
      message_id: messageId, user_id: userId, emoji,
    });
    if (error) return json({ error: "Failed to add reaction" }, 500);
    return json({ reacted: true, emoji });
  }
}
serve(handler, { port: 9045 });
