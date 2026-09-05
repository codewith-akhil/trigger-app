// Edge function: sync-user-profile
// ----------------------------------------------------------------------------
// Updates the caller's profile row (name, username, about, avatar, links,
// phone, country, gender, dob, language) in `public.profiles`.
//
// Auth: requires a valid Supabase JWT.
//
// Request body (all optional):
//   { "fullName"?: string, "username"?: string, "about"?: string,
//     "avatarUrl"?: string, "avatarBucket"?: string, "phone"?: string,
//     "phoneVerified"?: boolean, "countryIso"?: string, "languageCode"?: string,
//     "links"?: Array<{label:string,url:string}>,
//     "isOnline"?: boolean, "gender"?: string, "dob"?: string }
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

function isValidDate(s: string): boolean {
  const d = new Date(s);
  return !isNaN(d.getTime()) && /^\d{4}-\d{2}-\d{2}$/.test(s);
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

  const supabase = createAdminClient();

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
  if (Array.isArray(body.links)) {
    // Validate links: max 3, each with label (1-25 chars) + valid URL
    const cleanLinks: Array<{ label: string; url: string }> = [];
    for (const link of body.links) {
      if (cleanLinks.length >= 3) break;
      const label = (link?.label ?? "").toString().trim().slice(0, 25);
      const url = (link?.url ?? "").toString().trim();
      if (!label || !url) continue;
      try {
        const u = new URL(url);
        if (u.protocol !== "http:" && u.protocol !== "https:") {
          return errorResponse(`Link "${label}" must use http or https`, 422);
        }
      } catch {
        return errorResponse(`Link "${label}" has an invalid URL`, 422);
      }
      cleanLinks.push({ label, url });
    }
    patch.links = cleanLinks;
  }
  if (typeof body.isOnline === "boolean") {
    patch.is_online = body.isOnline;
    patch.last_seen_at = new Date().toISOString();
  }
  if (typeof body.gender === "string") patch.gender = body.gender ? body.gender.trim() : null;
  if (typeof body.dob === "string") patch.dob = body.dob ? body.dob.trim() : null;

  if (Object.keys(patch).length === 0) {
    return errorResponse("No profile fields supplied to sync", 422);
  }

  // ===========================================================================
  // SERVER-SIDE VALIDATION (defense in depth — DB constraints + edge function)
  // ===========================================================================

  // --- full_name: 2-50 chars, no leading/trailing whitespace (already trimmed) ---
  if (patch.full_name !== undefined) {
    const name = patch.full_name as string;
    if (name.length < 2) {
      return errorResponse("Name must be at least 2 characters", 422);
    }
    if (name.length > 50) {
      return errorResponse("Name must be 50 characters or fewer", 422);
    }
    // Block emoji / control chars
    if (/[\u0000-\u001F\u007F]/.test(name)) {
      return errorResponse("Name contains invalid characters", 422);
    }
  }

  // --- username: 5-25 chars, [a-z0-9_.], not reserved ---
  if (patch.username !== undefined && patch.username !== null) {
    const u = patch.username as string;
    if (u.length < 5 || u.length > 25) {
      return errorResponse("Username must be 5-25 characters", 422);
    }
    if (!/^[a-z0-9_.]+$/.test(u)) {
      return errorResponse("Username can only contain letters, numbers, underscores, and dots", 422);
    }
  }

  // --- about: max 350 chars ---
  if (patch.about !== undefined && (patch.about as string).length > 350) {
    return errorResponse("About must be 350 characters or fewer", 422);
  }

  // --- gender: must exist in genders table (FK enforces, but give a friendly error) ---
  if (patch.gender !== undefined && patch.gender !== null) {
    const { data: genderRow } = await supabase
      .from("genders")
      .select("name")
      .eq("name", patch.gender)
      .maybeSingle();
    if (!genderRow) {
      return errorResponse("Please select a valid gender from the list", 422);
    }
  }

  // --- country_code: must exist in countries table ---
  if (patch.country_code !== undefined && patch.country_code !== null) {
    const { data: countryRow } = await supabase
      .from("countries")
      .select("currency_code")
      .eq("currency_code", patch.country_code)
      .maybeSingle();
    if (!countryRow) {
      return errorResponse("Please select a valid country from the list", 422);
    }
  }

  // --- dob: valid date, age >= 13, age <= 120 ---
  if (patch.dob !== undefined && patch.dob !== null) {
    const dobStr = patch.dob as string;
    if (!isValidDate(dobStr)) {
      return errorResponse("Invalid date of birth format (use YYYY-MM-DD)", 422);
    }
    const dobDate = new Date(dobStr);
    const ageMs = Date.now() - dobDate.getTime();
    const ageYears = ageMs / (1000 * 60 * 60 * 24 * 365.25);
    if (ageYears < 13) {
      return errorResponse("You must be at least 13 years old to use Trigger App", 422);
    }
    if (ageYears > 120) {
      return errorResponse("Please enter a valid date of birth", 422);
    }
  }

  // ===========================================================================
  // USERNAME HISTORY (1-hour cooldown on old usernames)
  // ===========================================================================
  let oldUsername: string | null = null;
  if (patch.username !== undefined) {
    const { data: currentProfile } = await supabase
      .from("profiles")
      .select("username")
      .eq("id", userId)
      .maybeSingle();
    oldUsername = currentProfile?.username ?? null;
    if (oldUsername && oldUsername !== patch.username) {
      await supabase.from("username_history").insert({
        user_id: userId,
        old_username: oldUsername,
        new_username: patch.username,
        released_at: new Date().toISOString(),
      });
    }
  }

  // ===========================================================================
  // EXECUTE UPDATE
  // ===========================================================================
  const { data, error } = await supabase
    .from("profiles")
    .update(patch)
    .eq("id", userId)
    .select()
    .single();

  if (error) {
    console.error("profile sync failed", error);
    if (error.code === "23505") {
      // unique_violation — username taken
      return errorResponse("That username is already taken", 409);
    }
    if (error.code === "23503") {
      // foreign_key_violation — gender or country_code not in referenced table
      return errorResponse("Invalid value: the selected gender or country does not exist", 422);
    }
    if (error.code === "23514") {
      // check_violation — full_name length or dob age
      return errorResponse("Validation failed: please check your input values", 422);
    }
    return errorResponse("Failed to sync profile", 500);
  }

  return json({ synced: true, profile: data });
}

serve(handler, { port: 9012 });
