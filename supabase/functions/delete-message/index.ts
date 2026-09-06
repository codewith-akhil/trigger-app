// Edge function: delete-message
// Deletes a message (for everyone) or marks it as deleted.
// Auth: JWT required. Only the sender can delete for everyone.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { message_id?: string; action?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const messageId = body.message_id ?? "";
  const action = body.action ?? "delete_for_everyone";
  if (!messageId) return json({ error: "message_id is required" }, 422);

  const supabase = createAdminClient();

  // Get the message to check ownership
  const { data: msg, error: fetchError } = await supabase
    .from("messages").select("sender_id, is_deleted_for_everyone, media_url, media_thumbnail, file_name").eq("id", messageId).maybeSingle();

  if (fetchError || !msg) return json({ error: "Message not found" }, 404);

  if (action === "delete_for_everyone") {
    // Only the sender can delete for everyone
    if (msg.sender_id !== userId) {
      return json({ error: "Only the sender can delete for everyone" }, 403);
    }
    // Strip media references too — previously the tombstone kept media_url,
    // so "deleted" photos/videos/files stayed fetchable via their URL.
    const { error: updateError } = await supabase
      .from("messages")
      .update({
        is_deleted_for_everyone: true,
        text: "This message was deleted",
        media_url: null,
        media_thumbnail: null,
        file_name: null,
      })
      .eq("id", messageId);
    if (updateError) return json({ error: "Failed to delete" }, 500);
    return json({ deleted: true });
  }

  // delete_for_me is local-only (no server change needed — the message stays for the other user)
  return json({ deleted: true, local: true });
}

serve(handler, { port: 9039 });
