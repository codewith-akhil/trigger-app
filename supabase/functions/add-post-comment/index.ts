// Edge function: add-post-comment
// ----------------------------------------------------------------------------
// POST { postId, body } → { comment: {...}, commentCount: number }
// Adds a comment to a feed post. Paid posts require an unlock (post author
// exempt). Returns the fully-shaped comment (author profile included) so the
// client can render it immediately without a refetch.
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BODY = 500;

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

  let body: { postId?: string; body?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }
  const postId = (body.postId ?? "").trim();
  const text = (body.body ?? "").trim();
  if (!UUID_RE.test(postId)) return errorResponse("postId must be a valid uuid", 422);
  if (text.length < 1 || text.length > MAX_BODY) {
    return errorResponse(`Comment must be 1-${MAX_BODY} characters`, 422);
  }

  const supabase = createAdminClient();
  if (!(await canViewPost(supabase, postId, userId))) {
    return errorResponse("Post not found or locked", 403);
  }

  const { data: inserted, error: insertError } = await supabase
    .from("feed_comments")
    .insert({ post_id: postId, author_id: userId, body: text })
    .select("id, post_id, author_id, body, created_at")
    .maybeSingle();
  if (insertError || !inserted) {
    return errorResponse("Failed to add comment: " + (insertError?.message ?? "no row"), 500);
  }

  const { data: profile } = await supabase
    .from("profiles")
    .select("full_name, username, avatar_url")
    .eq("id", userId)
    .maybeSingle();

  const { data: post } = await supabase
    .from("feed_posts")
    .select("comment_count")
    .eq("id", postId)
    .maybeSingle();

  return json({
    comment: {
      id: inserted.id,
      postId: inserted.post_id,
      authorId: inserted.author_id,
      authorName: profile?.full_name ?? "Creator",
      authorUsername: profile?.username ?? "creator",
      authorAvatarUrl: profile?.avatar_url ?? null,
      body: inserted.body,
      createdAt: inserted.created_at,
      likesCount: 0,
      likedByMe: false,
    },
    commentCount: post?.comment_count ?? 0,
  });
}

serve(handler, { port: 9041 });
