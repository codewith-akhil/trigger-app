// Edge function: search-messages
// Server-side message search: text, media, documents, links, by date.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  conversation_id?: string; query?: string; search_type?: string;
  date_from?: string; date_to?: string; limit?: number;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req); if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);
  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);
  let body: Body; try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }
  const convId = body.conversation_id ?? ""; const query = (body.query ?? "").trim();
  const searchType = body.search_type ?? "text"; const limit = Math.min(typeof body.limit === "number" ? body.limit : 50, 100);
  if (!convId) return json({ error: "conversation_id required" }, 422);

  const supabase = createAdminClient();

  // SECURITY: participant check. This function previously used the service-role
  // client with NO participant verification, so any authenticated user could
  // read ANY conversation's messages (IDOR). Verify the caller is the owner or
  // peer of the conversation before querying messages.
  const { data: conv, error: convErr } = await supabase
    .from("conversations")
    .select("id, owner_id, peer_id")
    .eq("id", convId)
    .maybeSingle();
  if (convErr) return errorResponse("Lookup failed", 500, ErrorCode.INTERNAL_ERROR);
  if (!conv || (conv.owner_id !== userId && conv.peer_id !== userId)) {
    return errorResponse("Conversation not found", 404, ErrorCode.NOT_FOUND);
  }

  let dbQuery = supabase.from("messages").select("id, text, type, sender_id, created_at, media_url, file_name, is_deleted_for_everyone")
    .eq("conversation_id", convId).eq("is_deleted_for_everyone", false).order("created_at", { ascending: false }).limit(limit);

  switch (searchType) {
    case "text":
      if (!query) return json({ error: "query required for text search" }, 422);
      dbQuery = dbQuery.ilike("text", `%${query}%`);
      break;
    case "media":
      dbQuery = dbQuery.in("type", ["IMAGE", "VIDEO"]);
      break;
    case "documents":
      dbQuery = dbQuery.eq("type", "DOCUMENT");
      break;
    case "links":
      dbQuery = dbQuery.eq("type", "TEXT").ilike("text", "%http%");
      break;
    case "date":
      if (body.date_from) dbQuery = dbQuery.gte("created_at", body.date_from);
      if (body.date_to) dbQuery = dbQuery.lte("created_at", body.date_to);
      break;
    default:
      return json({ error: "Invalid search_type. Use: text, media, documents, links, date" }, 422);
  }

  const { data, error } = await dbQuery;
  if (error) return json({ error: "Search failed" }, 500);
  return json({ results: data ?? [], count: data?.length ?? 0 });
}
serve(handler, { port: 9043 });
