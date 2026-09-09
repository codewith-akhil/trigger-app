// Edge function: toggle-follow-user
// Instagram-style follow / unfollow. Follow does NOT create chat access —
// messaging is governed exclusively by the message-request flow.
//
// Body: { targetUserId: uuid, action: "follow" | "unfollow" }
// Returns: { following: boolean, followersCount, followingCount }
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";

const FOLLOW_LIMIT = { maxRequests: 60, windowSeconds: 60, name: "toggle_follow" };

function isUuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

async function countFollows(supabase: ReturnType<typeof createAdminClient>, column: "follower_id" | "following_id", userId: string): Promise<number> {
  const { count } = await supabase
    .from("follows")
    .select("id", { count: "exact", head: true })
    .eq(column, userId);
  return count ?? 0;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const rl = checkRateLimit(req, userId, FOLLOW_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: { targetUserId?: string; action?: string };
  try { body = await req.json(); } catch { return json({ error: "Invalid body" }, 400); }

  const targetUserId = body.targetUserId ?? "";
  const action = (body.action ?? "").toLowerCase();
  if (!isUuid(targetUserId)) return json({ error: "targetUserId is required" }, 422);
  if (targetUserId === userId) return json({ error: "You cannot follow yourself" }, 422);
  if (action !== "follow" && action !== "unfollow") {
    return json({ error: "action must be 'follow' or 'unfollow'" }, 422);
  }

  const supabase = createAdminClient();

  const { data: target } = await supabase.from("profiles").select("id").eq("id", targetUserId).maybeSingle();
  if (!target) return json({ error: "User not found" }, 404);

  if (action === "follow") {
    // Detect a NEW follow first: the upsert below is idempotent
    // (unique follower_id+following_id pair), but the notification + push must
    // fire only when the edge did not exist before.
    const { data: existing } = await supabase
      .from("follows")
      .select("id")
      .eq("follower_id", userId)
      .eq("following_id", targetUserId)
      .maybeSingle();

    // Idempotent insert (unique follower_id+following_id pair protects us).
    const { error: insertErr } = await supabase
      .from("follows")
      .upsert(
        { follower_id: userId, following_id: targetUserId },
        { onConflict: "follower_id,following_id", ignoreDuplicates: true },
      );
    if (insertErr) {
      console.error("toggle-follow-user: insert failed", insertErr);
      return json({ error: "Failed to follow user" }, 500);
    }

    if (!existing) {
      // NEW follow → in-app notification row for the target. Non-fatal:
      // a notification failure must NEVER fail the follow itself.
      try {
        const { error: notifErr } = await supabase.from("user_notifications").insert({
          user_id: targetUserId,
          actor_id: userId,
          type: "follow",
        });
        if (notifErr) {
          console.warn("toggle-follow-user: notification insert failed", notifErr);
        }
      } catch (notifErr) {
        console.warn("toggle-follow-user: notification insert threw", notifErr);
      }

      // Fire-and-forget push to the target's devices — same server-side FCM
      // helper the send-push-notification edge fn uses internally (the HTTP
      // fn itself only pushes to the CALLER, so server flows use sendFcmBatch
      // directly, like cron-auto-start-streams). Non-fatal, failures logged.
      try {
        const { data: actorProfile } = await supabase
          .from("profiles")
          .select("full_name")
          .eq("id", userId)
          .maybeSingle();
        const actorName = actorProfile?.full_name?.trim() || "Someone";
        const { data: tokens } = await supabase
          .from("push_tokens")
          .select("fcm_token")
          .eq("user_id", targetUserId)
          .eq("is_active", true);
        if (tokens && tokens.length > 0) {
          const { sendFcmBatch } = await import("../_shared/firebase.ts");
          await sendFcmBatch(
            {
              title: actorName,
              body: "started following you",
              data: { type: "follow", actor_id: userId },
              androidChannelId: "trigger_default",
              priority: "normal",
            },
            tokens.map((t) => t.fcm_token),
          );
        }
      } catch (pushErr) {
        console.warn("toggle-follow-user: push failed", pushErr);
      }
    }
  } else {
    const { error: deleteErr } = await supabase
      .from("follows")
      .delete()
      .eq("follower_id", userId)
      .eq("following_id", targetUserId);
    if (deleteErr) {
      console.error("toggle-follow-user: delete failed", deleteErr);
      return json({ error: "Failed to unfollow user" }, 500);
    }
  }

  const { data: row } = await supabase
    .from("follows")
    .select("id")
    .eq("follower_id", userId)
    .eq("following_id", targetUserId)
    .maybeSingle();

  // followers of X = rows where following_id = X; following of X = rows
  // where follower_id = X. (These were swapped once — keep the mapping.)
  const [followersCount, followingCount] = await Promise.all([
    countFollows(supabase, "following_id", targetUserId),
    countFollows(supabase, "follower_id", targetUserId),
  ]);

  return json({
    following: !!row,
    followersCount,
    followingCount,
  });
}
serve(handler, { port: 9041 });
