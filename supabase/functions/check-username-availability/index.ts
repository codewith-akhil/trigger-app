// Edge function: check-username-availability
// ----------------------------------------------------------------------------
// Checks if a username is available (not taken by another user). Called in
// real-time as the user types in the username dialog (debounced client-side).
//
// Auth: requires a valid Supabase JWT (so we can exclude the caller's own
// username from the check — they can "re-reserve" their own username).
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

  // --- Input validation ---
  if (!username) {
    return json({ available: false, username: "", reason: "Username is required" }, 422);
  }
  if (username.length < 3) {
    return json({ available: false, username, reason: "Username must be at least 3 characters" }, 422);
  }
  if (username.length > 20) {
    return json({ available: false, username, reason: "Username must be 20 characters or fewer" }, 422);
  }
  // Only lowercase letters, numbers, underscores, dots
  if (!/^[a-z0-9_.]+$/.test(username)) {
    return json({ available: false, username, reason: "Only letters, numbers, underscores, and dots are allowed" }, 422);
  }
  // Reserved usernames
  const reserved = ["admin", "root", "support", "help", "api", "trigger", "official", "system", "moderator", "mod", "staff", "team", "info", "contact", "about", "settings", "login", "signup", "register", "auth", "user", "profile", "me", "self"];
  if (reserved.includes(username)) {
    return json({ available: false, username, reason: "This username is reserved" }, 422);
  }

  const supabase = createAdminClient();

  // Check if the username is taken by someone OTHER than the caller.
  const { data, error } = await supabase
    .from("profiles")
    .select("id")
    .eq("username", username)
    .neq("id", userId)
    .limit(1);

  if (error) {
    console.error("username check failed", error);
    return json({ error: "Failed to check username", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  const available = !data || data.length === 0;
  return json({
    available,
    username,
    reason: available ? undefined : "This username is already taken",
  });
}

serve(handler, { port: 9028 });
