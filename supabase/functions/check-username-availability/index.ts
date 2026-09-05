// Edge function: check-username-availability
// ----------------------------------------------------------------------------
// Checks if a username is available (not taken by another user + not in the
// 1-hour cooldown period after another user released it).
//
// Rules:
//   - 5-25 characters
//   - Only letters, numbers, underscores, dots
//   - Reserved words blocked
//   - If the username was recently released by another user (within 1 hour),
//     it's NOT available until the cooldown expires
//
// Auth: requires a valid Supabase JWT (excludes caller's own username).
//
// Request body: { "username": string }
// Response 200: { "available": boolean, "username": string, "reason"?: string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  username?: string;
}

const RESERVED = [
  "admin","root","support","help","api","trigger","official","system",
  "moderator","mod","staff","team","info","contact","about","settings",
  "login","signup","register","auth","user","profile","me","self",
  "superuser","operator","service","bot","anonymous","guest","null",
  "undefined","test","demo","example","sample","owner","master",
];

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  let body: Body;
  try { body = await req.json(); }
  catch { return json({ error: "Invalid request body", code: ErrorCode.VALIDATION_FAILED }, 400); }

  const username = (body.username ?? "").trim().toLowerCase().replace(/^@/, "");

  // --- Validation: 5-25 chars, [a-z0-9_.] ---
  if (!username) {
    return json({ available: false, username: "", reason: "Username is required" }, 422);
  }
  if (username.length < 5) {
    return json({ available: false, username, reason: "Username must be at least 5 characters" }, 422);
  }
  if (username.length > 25) {
    return json({ available: false, username, reason: "Username must be 25 characters or fewer" }, 422);
  }
  if (!/^[a-z0-9_.]+$/.test(username)) {
    return json({ available: false, username, reason: "Only letters, numbers, underscores, and dots are allowed" }, 422);
  }
  if (RESERVED.includes(username)) {
    return json({ available: false, username, reason: "This username is reserved" }, 422);
  }

  const supabase = createAdminClient();

  // --- Check 1: Is the username taken by another user RIGHT NOW? ---
  const { data: existing } = await supabase
    .from("profiles")
    .select("id")
    .eq("username", username)
    .neq("id", userId)
    .limit(1);

  if (existing && existing.length > 0) {
    return json({
      available: false,
      username,
      reason: `${username} is already taken`,
    });
  }

  // --- Check 2: Is the username in the 1-hour cooldown after release? ---
  // Another user recently changed AWAY from this username. It becomes available
  // to others 1 hour after the change.
  const { data: history } = await supabase
    .from("username_history")
    .select("released_at, user_id")
    .eq("old_username", username)
    .neq("user_id", userId)
    .order("released_at", { ascending: false })
    .limit(1);

  if (history && history.length > 0) {
    const releasedAt = new Date(history[0].released_at);
    const availableAt = new Date(releasedAt.getTime() + 60 * 60 * 1000); // +1 hour
    if (new Date() < availableAt) {
      const waitMinutes = Math.ceil((availableAt.getTime() - Date.now()) / 60000);
      return json({
        available: false,
        username,
        reason: `${username} will be available in ${waitMinutes} minute${waitMinutes === 1 ? "" : "s"}`,
      });
    }
  }

  return json({ available: true, username });
}

serve(handler, { port: 9028 });
