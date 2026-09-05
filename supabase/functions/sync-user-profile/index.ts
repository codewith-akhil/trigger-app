// Edge function: sync-user-profile
// ----------------------------------------------------------------------------
// Upserts the caller's profile row (name, username, about, avatar, links,
// phone, country, language) into `public.profiles`. Replaces the in-memory
// UserRepository that currently loses profile data on app restart.
//
// Auth: requires a valid Supabase JWT.
//
// Request body (all optional except where noted):
//   { "fullName"?: string, "username"?: string, "about"?: string,
//     "avatarUrl"?: string, "avatarBucket"?: string, "phone"?: string,
//     "phoneVerified"?: boolean, "countryIso"?: string, "languageCode"?: string,
//     "links"?: Array<{label:string,url:string}>,
//     "isOnline"?: boolean }
//
// Response 200: { "synced": true, "profile": {...} }
// Response 4xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  fullName?: string;
  username?: string;
  about?: string;
  avatarUrl?: string;
  avatarBucket?: string;
  phone?: string;
  phoneVerified?: boolean;
  countryIso?: string;
  country_code?: string;
  languageCode?: string;
  links?: Array<{ label: string; url: string }>;
  isOnline?: boolean;
  gender?: string;
  dob?: string;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400);
  }

  // Build patch object from supplied fields only.
  // NOTE: we use UPDATE (not UPSERT) because the handle_new_user trigger
  // guarantees a profile row exists for every auth user. PostgREST upsert with
  // a partial payload fails against the NOT NULL full_name column, whereas
  // update only touches the supplied columns.
  const patch: Record<string, unknown> = {};
  if (typeof body.fullName === "string") patch.full_name = body.fullName.trim();
  if (typeof body.username === "string") {
    const cleanUsername = body.username.trim().toLowerCase().replace(/^@/, "");
    patch.username = cleanUsername || null;
  }
  if (typeof body.about === "string") patch.about = body.about;
  if (typeof body.avatarUrl === "string") patch.avatar_url = body.avatarUrl;
  if (typeof body.avatarBucket === "string") patch.avatar_bucket = body.avatarBucket;
  if (typeof body.phone === "string") patch.phone = body.phone.trim();
  if (typeof body.phoneVerified === "boolean") patch.phone_verified = body.phoneVerified;
  if (typeof body.countryIso === "string") patch.country_iso = body.countryIso.toUpperCase().substring(0, 2);
  if (typeof body.country_code === "string") patch.country_code = body.country_code.toUpperCase().substring(0, 3);
  if (typeof body.languageCode === "string") patch.language_code = body.languageCode;
  if (Array.isArray(body.links)) patch.links = body.links;
  if (typeof body.isOnline === "boolean") {
    patch.is_online = body.isOnline;
    patch.last_seen_at = new Date().toISOString();
  }
  if (typeof body.gender === "string") patch.gender = body.gender || null;
  if (typeof body.dob === "string") patch.dob = body.dob || null;

  // --- Username change: record old username in history table ---
  if (patch.username !== undefined) {
    const supabase0 = createAdminClient();
    const { data: currentProfile } = await supabase0
      .from("profiles")
      .select("username")
      .eq("id", userId)
      .maybeSingle();
    oldUsername = currentProfile?.username ?? null;
    // Only record history if the username is actually changing
    if (oldUsername && oldUsername !== patch.username) {
      await supabase0.from("username_history").insert({
        user_id: userId,
        old_username: oldUsername,
        new_username: patch.username,
        released_at: new Date().toISOString(),
      });
    }
  }

  if (Object.keys(patch).length === 0) {
    return errorResponse("No profile fields supplied to sync", 422);
  }

  const supabase = createAdminClient();
  const { data, error } = await supabase
    .from("profiles")
    .update(patch)
    .eq("id", userId)
    .select()
    .single();

  if (error) {
    console.error("profile sync failed", error);
    if (error.code === "23505") {
      return errorResponse("That username is already taken", 409);
    }
    return errorResponse("Failed to sync profile", 500);
  }

  return json({ synced: true, profile: data });
}

serve(handler, { port: 9012 });
