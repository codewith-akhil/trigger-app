// Edge function: toggle-post-like
// ----------------------------------------------------------------------------
// POST { postId } → { liked: boolean, likeCount: number }
// Toggles the caller's like on a feed post. Counters are maintained by DB
// triggers; this function only inserts/deletes the (post_id, user_id) row and
// reads back the authoritative count.
// Visibility rule: published + (free | author | unlocked buyer) — same wall
// as the media itself. Server-enforced (RLS is defense in depth).
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function canViewPost(
  supabase: any,
  postId: string,
  userId: string,
): Promise<boolean> {
  const { data: post } = await supabase
    .from("feed_posts")
    .select("id, author_id, post_type, status")
    .eq("id", postId)
    .maybeSingle();
  if (!post || post.status !== "published") return false;
  if (post.post_type === "free") return true;
  if (post.author_id === userId) return true;
  const { data: unlock } = await supabase
    .from("post_unlocks")
    .select("post_id")
    .eq("post_id", postId)
    .eq("buyer_id", userId)
    .maybeSingle();
  return !!unlock;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: { postId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }
  const postId = (body.postId ?? "").trim();
  if (!UUID_RE.test(postId)) return errorResponse("postId must be a valid uuid", 422);

  const supabase = createAdminClient();
  if (!(await canViewPost(supabase, postId, userId))) {
    return errorResponse("Post not found or locked", 403);
  }

  // toggle: try insert → conflict means already liked → remove
  const { error: insertError } = await supabase
    .from("feed_post_likes")
    .insert({ post_id: postId, user_id: userId });

  let liked: boolean;
  if (insertError) {
    // unique/PK violation ⇒ currently liked → unlike
    const { error: deleteError } = await supabase
      .from("feed_post_likes")
      .delete()
      .eq("post_id", postId)
      .eq("user_id", userId);
    if (deleteError) return errorResponse("Failed to unlike: " + deleteError.message, 500);
    liked = false;
  } else {
    liked = true;
  }

  const { data: post } = await supabase
    .from("feed_posts")
    .select("like_count")
    .eq("id", postId)
    .maybeSingle();

  return json({ liked, likeCount: post?.like_count ?? 0 });
}

serve(handler, { port: 9040 });
