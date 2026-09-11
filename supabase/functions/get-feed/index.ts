// Edge function: get-feed
// ----------------------------------------------------------------------------
// Serves the REAL feed — no mock data anywhere. Newest posts always first.
//
// Scopes:
//   feed   (default) — published posts, ordered published_at DESC (newest on top)
//   drafts           — the caller's own drafts, ordered updated_at DESC
//   post             — one post by id (postId required)
//
// Media in the PRIVATE `feed-media` bucket is only exposed through short-lived
// signed URLs:
//   • free posts / own posts / posts unlocked by the caller → full signed URLs
//   • locked paid posts → images get a TINY transformed preview (blur fodder);
//     videos get NO url at all (gradient + lock UI).
//
// Auth: requires a valid Supabase JWT.
//
// Request body: { "scope"?: "feed" | "drafts" | "post", "postId"?: string,
//                 "limit"?: number, "offset"?: number }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import {
  createAdminClient,
  resolveUserId,
  resolveStorageServiceKey,
} from "../_shared/supabase.ts";

const MEDIA_BUCKET = "feed-media";
const SIGN_TTL_SECONDS = 3600;

interface SignedEntry {
  path: string;
  url: string | null;
  error?: string | null;
}

/**
 * Batch-create signed URLs. `transform` (image preview) is applied to every
 * path in the batch, so previews and full-res are signed in separate calls.
 */
async function signPaths(
  paths: string[],
  transform: { width: number } | null,
  storageKey: string,
): Promise<Map<string, string>> {
  const result = new Map<string, string>();
  if (paths.length === 0) return result;

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  if (!storageKey || !supabaseUrl) return result;

  const body: Record<string, unknown> = { expiresIn: SIGN_TTL_SECONDS, paths };
  if (transform) body.transform = { width: transform.width, resize: "fill" };

  const res = await fetch(`${supabaseUrl}/storage/v1/object/sign/${MEDIA_BUCKET}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${storageKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !Array.isArray(data)) {
    console.error("sign error", res.status, JSON.stringify(data).slice(0, 300));
    return result;
  }
  for (const item of data as Array<{ path?: string; signedURL?: string; error?: string }>) {
    if (item.path && item.signedURL) {
      result.set(item.path, `${supabaseUrl}/storage/v1${item.signedURL}`);
    }
  }
  return result;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: { scope?: string; postId?: string; limit?: number; offset?: number };
  try {
    body = await req.json();
  } catch {
    body = {};
  }

  const supabase = createAdminClient();
  // storage rejects some runtime service keys (new API-key system) — resolve
  // a key that actually works for storage signing
  const storageKey = await resolveStorageServiceKey(supabase);
  const scope = body.scope ?? "feed";
  const limit = Math.min(Math.max(Number(body.limit ?? 20), 1), 50);
  const offset = Math.max(Number(body.offset ?? 0), 0);

  // ---------------------------------------------------------------------
  // Fetch posts + authors
  // ---------------------------------------------------------------------
  let postsQuery = supabase
    .from("feed_posts")
    .select(`
      id, author_id, description, media_type, post_type, price_amount,
      currency, status, created_at, updated_at, published_at
    `);

  if (scope === "drafts") {
    postsQuery = postsQuery
      .eq("author_id", userId)
      .eq("status", "draft")
      .order("updated_at", { ascending: false })
      .range(offset, offset + limit - 1);
  } else if (scope === "post") {
    if (!body.postId) return errorResponse("postId is required for scope=post", 422);
    postsQuery = postsQuery.eq("id", body.postId).limit(1);
  } else {
    postsQuery = postsQuery
      .eq("status", "published")
      .order("published_at", { ascending: false, nullsFirst: false })
      .range(offset, offset + limit - 1);
  }

  const { data: posts, error: postsError } = await postsQuery;
  if (postsError) return errorResponse("Failed to load feed: " + postsError.message, 500);
  if (!posts || posts.length === 0) return json({ posts: [], nextOffset: null });

  const postIds = posts.map((p) => p.id);
  const authorIds = Array.from(new Set(posts.map((p) => p.author_id)));

  // Authors
  const { data: authors } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url")
    .in("id", authorIds);
  const authorMap = new Map((authors ?? []).map((a) => [a.id, a]));

  // Media rows (ordered)
  const { data: mediaRows } = await supabase
    .from("feed_post_media")
    .select("post_id, position, storage_path, mime_type, width, height, duration_ms")
    .in("post_id", postIds)
    .order("position", { ascending: true });

  // Caller's unlocks
  const { data: unlocks } = await supabase
    .from("post_unlocks")
    .select("post_id")
    .eq("buyer_id", userId)
    .in("post_id", postIds);
  const unlockedSet = new Set((unlocks ?? []).map((u) => u.post_id));

  // ---------------------------------------------------------------------
  // Signed URLs — full only when visible; tiny preview for locked images
  // ---------------------------------------------------------------------
  const fullPaths: string[] = [];
  const previewPaths: string[] = [];

  for (const p of posts) {
    const media = (mediaRows ?? []).filter((m) => m.post_id === p.id);
    const canSeeFull =
      p.author_id === userId ||
      p.status === "draft" ||
      p.post_type === "free" ||
      unlockedSet.has(p.id);

    for (const m of media) {
      if (canSeeFull) {
        fullPaths.push(m.storage_path);
      } else if (p.media_type === "image") {
        previewPaths.push(m.storage_path);
      }
    }
  }

  const [fullSigned, previewSigned] = await Promise.all([
    signPaths(fullPaths, null, storageKey),
    signPaths(previewPaths, { width: 24 }, storageKey),
  ]);

  // ---------------------------------------------------------------------
  // Shape the response
  // ---------------------------------------------------------------------
  const out = posts.map((p) => {
    const author = authorMap.get(p.author_id);
    const media = (mediaRows ?? [])
      .filter((m) => m.post_id === p.id)
      .map((m) => {
        const canSeeFull =
          p.author_id === userId ||
          p.status === "draft" ||
          p.post_type === "free" ||
          unlockedSet.has(p.id);
        return {
          url: canSeeFull ? fullSigned.get(m.storage_path) ?? null : null,
          previewUrl: canSeeFull ? null : previewSigned.get(m.storage_path) ?? null,
          mimeType: m.mime_type,
          width: m.width,
          height: m.height,
          durationMs: m.duration_ms,
        };
      });

    const isMine = p.author_id === userId;
    const isUnlocked =
      isMine || p.post_type === "free" || unlockedSet.has(p.id) || p.status === "draft";

    return {
      id: p.id,
      authorId: p.author_id,
      authorName: author?.full_name ?? "Creator",
      authorUsername: author?.username ?? "creator",
      authorAvatarUrl: author?.avatar_url ?? null,
      description: p.description ?? "",
      mediaType: p.media_type,
      postType: p.post_type,
      priceAmount: Number(p.price_amount ?? 0),
      currency: p.currency,
      status: p.status,
      publishedAt: p.published_at,
      updatedAt: p.updated_at,
      media,
      isMine,
      isUnlocked,
    };
  });

  const nextOffset = scope === "feed" && posts.length === limit ? offset + limit : null;
  return json({ posts: out, nextOffset });
}

serve(handler, { port: 9032 });
