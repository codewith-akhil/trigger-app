// Edge function: toggle-comment-like
// ----------------------------------------------------------------------------
// POST { commentId } → { liked: boolean, likesCount: number }
// Toggles the caller's like on a feed comment. Requires the comment's post to
// be visible to the caller (paid wall enforced server-side).
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: { commentId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }
  const commentId = (body.commentId ?? "").trim();
  if (!UUID_RE.test(commentId)) return errorResponse("commentId must be a valid uuid", 422);

  const supabase = createAdminClient();

  // comment + its post (for the paid-wall check)
  const { data: comment } = await supabase
    .from("feed_comments")
    .select("id, post_id")
    .eq("id", commentId)
    .maybeSingle();
  if (!comment) return errorResponse("Comment not found", 404);

  const { data: post } = await supabase
    .from("feed_posts")
    .select("author_id, post_type, status")
    .eq("id", comment.post_id)
    .maybeSingle();
  if (!post || post.status !== "published") return errorResponse("Comment not available", 403);

  if (post.post_type !== "free" && post.author_id !== userId) {
    const { data: unlock } = await supabase
      .from("post_unlocks")
      .select("post_id")
      .eq("post_id", comment.post_id)
      .eq("buyer_id", userId)
      .maybeSingle();
    if (!unlock) return errorResponse("Comment not available", 403);
  }

  // toggle
  const { error: insertError } = await supabase
    .from("feed_comment_likes")
    .insert({ comment_id: commentId, user_id: userId });

  let liked: boolean;
  if (insertError) {
    const { error: deleteError } = await supabase
      .from("feed_comment_likes")
      .delete()
      .eq("comment_id", commentId)
      .eq("user_id", userId);
    if (deleteError) return errorResponse("Failed to unlike: " + deleteError.message, 500);
    liked = false;
  } else {
    liked = true;
  }

  const { data: fresh } = await supabase
    .from("feed_comments")
    .select("like_count")
    .eq("id", commentId)
    .maybeSingle();

  return json({ liked, likesCount: fresh?.like_count ?? 0 });
}

serve(handler, { port: 9042 });
