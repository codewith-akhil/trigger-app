// Edge function: get-follow-info
// Follow state + counts for a target user, from the CALLER's perspective.
//
// Body: { targetUserId: uuid }
// Returns: { isFollowing, followsYou, followersCount, followingCount }
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

  let body: { targetUserId?: string };
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const targetUserId = body.targetUserId ?? "";
  if (!isUuid(targetUserId)) return json({ error: "targetUserId is required" }, 422);

  const supabase = createAdminClient();

  const { data: target } = await supabase.from("profiles").select("id").eq("id", targetUserId).maybeSingle();
  if (!target) return json({ error: "User not found" }, 404);

  const [mine, theirs, followers, following] = await Promise.all([
    supabase.from("follows").select("id").eq("follower_id", userId).eq("following_id", targetUserId).maybeSingle(),
    supabase.from("follows").select("id").eq("follower_id", targetUserId).eq("following_id", userId).maybeSingle(),
    supabase.from("follows").select("id", { count: "exact", head: true }).eq("following_id", targetUserId),
    supabase.from("follows").select("id", { count: "exact", head: true }).eq("follower_id", targetUserId),
  ]);

  return json({
    isFollowing: !!mine.data,
    followsYou: !!theirs.data,
    followersCount: followers.count ?? 0,
    followingCount: following.count ?? 0,
  });
}
serve(handler, { port: 9042 });
