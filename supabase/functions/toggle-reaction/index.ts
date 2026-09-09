// Edge function: toggle-reaction
// Toggles a reaction (emoji) on a message. Inserts or removes from message_reactions table.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
const REACTION_LIMIT = { maxRequests: 60, windowSeconds: 60, name: "toggle_reaction" };

interface Body { message_id?: string; emoji?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  const rl = checkRateLimit(req, userId, REACTION_LIMIT);
  if (!rl.allowed) return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);

  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const messageId = body.message_id ?? ""; const emoji = (body.emoji ?? "").trim();
  if (!messageId) return json({ error: "message_id required" }, 422);
  if (!emoji || emoji.length > 10) return json({ error: "Valid emoji required (max 10 chars)" }, 422);

  const supabase = createAdminClient();

  // SECURITY: participant check. Previously ANY authenticated user could
  // react to ANY message (IDOR write).
  const { data: conv, error: convErr } = await supabase
    .from("messages")
    .select("id, conversations!inner(owner_id, peer_id)")
    .eq("id", messageId)
    .maybeSingle();
  if (convErr || !conv) return json({ error: "Message not found" }, 404);
  const convOwner = (conv as any).conversations?.owner_id;
  const convPeer = (conv as any).conversations?.peer_id;
  if (convOwner !== userId && convPeer !== userId) {
    return errorResponse("Forbidden", 403, ErrorCode.FORBIDDEN);
  }

  // A user has AT MOST ONE reaction per message (unique(message_id, user_id)):
  // same emoji again → remove; different emoji → SWITCH. The old code only
  // looked for the same emoji, so switching ❤️→👍 hit the unique violation
  // and 500'd.
  const { data: existing } = await supabase
    .from("message_reactions")
    .select("id, emoji")
    .eq("message_id", messageId)
    .eq("user_id", userId)
    .maybeSingle();

  if (existing && existing.emoji === emoji) {
    // Remove the reaction
    await supabase.from("message_reactions").delete().eq("id", existing.id);
    return json({ reacted: false, emoji });
  } else if (existing) {
    // Switch to the new emoji
    const { error } = await supabase
      .from("message_reactions")
      .update({ emoji })
      .eq("id", existing.id);
    if (error) return json({ error: "Failed to update reaction" }, 500);
    return json({ reacted: true, emoji, switched: true });
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
