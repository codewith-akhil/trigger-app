// Edge function: get-post-comments
// ----------------------------------------------------------------------------
// POST { postId, limit?, offset? } → { comments: [...], nextOffset: number|null }
// Lists a post's comments, newest first, with author profile and per-comment
// like state for the caller. Paid posts require an unlock (author exempt).
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

  let body: { postId?: string; limit?: number; offset?: number };
  try {
    body = await req.json();
  } catch {
    body = {};
  }
  const postId = (body.postId ?? "").trim();
  if (!UUID_RE.test(postId)) return errorResponse("postId must be a valid uuid", 422);
  const limit = Math.min(Math.max(Number(body.limit ?? 30), 1), 100);
  const offset = Math.max(Number(body.offset ?? 0), 0);

  const supabase = createAdminClient();

  // visibility wall
  const { data: post } = await supabase
    .from("feed_posts")
    .select("author_id, post_type, status")
    .eq("id", postId)
    .maybeSingle();
  if (!post || post.status !== "published") return errorResponse("Post not found", 404);
  if (post.post_type !== "free" && post.author_id !== userId) {
    const { data: unlock } = await supabase
      .from("post_unlocks")
      .select("post_id")
      .eq("post_id", postId)
      .eq("buyer_id", userId)
      .maybeSingle();
    if (!unlock) return errorResponse("Post is locked", 403);
  }

  const { data: rows, error } = await supabase
    .from("feed_comments")
    .select("id, post_id, author_id, body, created_at, like_count")
    .eq("post_id", postId)
    .order("created_at", { ascending: false })
    .range(offset, offset + limit - 1);
  if (error) return errorResponse("Failed to load comments: " + error.message, 500);

  // authors (separate query — author_id references auth.users, not profiles)
  const authorIds = Array.from(new Set((rows ?? []).map((r: any) => r.author_id)));
  const { data: authors } = authorIds.length
    ? await supabase
        .from("profiles")
        .select("id, full_name, username, avatar_url")
        .in("id", authorIds)
    : { data: [] };
  const authorMap = new Map((authors ?? []).map((a: any) => [a.id, a]));

  const ids = (rows ?? []).map((r: any) => r.id);
  let mine = new Set<string>();
  if (ids.length > 0) {
    const { data: likes } = await supabase
      .from("feed_comment_likes")
      .select("comment_id")
      .eq("user_id", userId)
      .in("comment_id", ids);
    mine = new Set((likes ?? []).map((l: any) => l.comment_id));
  }

  const comments = (rows ?? []).map((r: any) => {
    const author = authorMap.get(r.author_id);
    return {
      id: r.id,
      postId: r.post_id,
      authorId: r.author_id,
      authorName: author?.full_name ?? "Creator",
      authorUsername: author?.username ?? "creator",
      authorAvatarUrl: author?.avatar_url ?? null,
      body: r.body,
      createdAt: r.created_at,
      likesCount: r.like_count ?? 0,
      likedByMe: mine.has(r.id),
    };
  });

  const nextOffset = rows && rows.length === limit ? offset + limit : null;
  return json({ comments, nextOffset });
}

serve(handler, { port: 9043 });
