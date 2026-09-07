// Edge function: get-follow-list
// Followers / following list for a user (defaults to the caller), joined to
// profiles for display. Powers the Instagram-style social surfaces.
//
// NOTE: profile data is resolved with a SECOND batched query instead of a
// PostgREST embed — the follows FKs target auth.users (not profiles), and
// explicit-name embeds would be brittle if constraint naming changes.
//
// Body: { userId?: uuid (default: caller), type: "followers" | "following",
//         limit?: number (max 100), offset?: number }
// Returns: { users: [{ id, full_name, username, avatar_url }], count }
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: { userId?: string; type?: string; limit?: number; offset?: number };
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const targetUserId = body.userId && isUuid(body.userId) ? body.userId : userId;
  const type = (body.type ?? "following").toLowerCase();
  if (type !== "followers" && type !== "following") {
    return json({ error: "type must be 'followers' or 'following'" }, 422);
  }
  const limit = Math.min(Math.max(body.limit ?? 50, 1), 100);
  const offset = Math.max(body.offset ?? 0, 0);

  const supabase = createAdminClient();

  // followers  → rows where following_id = target (the FOLLOWER id is in follower_id)
  // following  → rows where follower_id = target (the FOLLOWED id is in following_id)
  const column = type === "followers" ? "following_id" : "follower_id";
  const idColumn = type === "followers" ? "follower_id" : "following_id";

  const { data: rows, error, count } = await supabase
    .from("follows")
    .select(`id, ${idColumn}, created_at`, { count: "exact" })
    .eq(column, targetUserId)
    .order("created_at", { ascending: false })
    .range(offset, offset + limit - 1);

  if (error) {
    console.error("get-follow-list failed", error);
    return json({ error: "Failed to fetch list" }, 500);
  }

  const list = rows ?? [];
  const profileIds = Array.from(new Set(list.map(r => r[idColumn]).filter(isUuid))) as string[];
  if (profileIds.length === 0) return json({ users: [], count: count ?? 0 });

  const { data: profiles } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url")
    .in("id", profileIds);
  const byId = new Map((profiles ?? []).map(p => [p.id, p]));

  const users = list
    .map(r => byId.get(r[idColumn]))
    .filter(Boolean);

  return json({ users, count: count ?? users.length });
}
serve(handler, { port: 9043 });
