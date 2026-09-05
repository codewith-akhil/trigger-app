// Edge function: search-users
// Search by username, name, or phone. Returns matching users (excluding self).
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body { query?: string; }

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const query = (body.query ?? "").trim().toLowerCase().replace(/^@/, "");
  if (query.length < 2) return json({ users: [] });

  const supabase = createAdminClient();
  // Search by username (ilike), full_name (ilike), or phone (ilike)
  const { data, error } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url, phone")
    .neq("id", userId)
    .or(`username.ilike.%${query}%,full_name.ilike.%${query}%,phone.ilike.%${query}%`)
    .limit(20);

  if (error) return json({ error: "Search failed" }, 500);
  return json({ users: data ?? [] });
}
serve(handler, { port: 9032 });
