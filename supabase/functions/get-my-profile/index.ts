// Edge function: get-my-profile
// ----------------------------------------------------------------------------
// Returns the caller's full profile row (including email) for hydration on
// app launch / after login / after OTP verification.
//
// Auth: requires a valid Supabase JWT.
//
// Response 200: { "profile": { ...all columns, email } }
// Response 401: { "error": "Unauthorized" }
// Response 404: { "error": "Profile not found" }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "GET" && req.method !== "POST") return errorResponse("Method not allowed", 405);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401);

  const supabase = createAdminClient();

  // Fetch the profile row. JOIN with countries to resolve country_name.
  const { data, error } = await supabase
    .from("profiles")
    .select("*, country:countries(country_name)")
    .eq("id", userId)
    .maybeSingle();

  if (error) {
    console.error("get-my-profile failed", error);
    return errorResponse("Failed to fetch profile", 500);
  }
  if (!data) return errorResponse("Profile not found", 404);

  // Email is the source of truth in auth.users. profiles.email is denormalized
  // but may be stale during the brief window between signup and the trigger.
  // Always fall back to auth.users.email.
  let email = data.email ?? "";
  if (!email) {
    const { data: authUser } = await supabase.auth.admin.getUserById(userId);
    email = authUser?.user?.email ?? "";
  }

  const countryName = data.country?.country_name ?? null;

  // Hydrate saved app settings too — Notifications/Account/Privacy/Storage
  // screens read `settings` from this response; it was never included so
  // every settings screen rendered dead defaults after re-install.
  const { data: settingsRow } = await supabase
    .from("user_settings")
    .select("*")
    .eq("user_id", userId)
    .maybeSingle();

  // user_settings columns are snake_case; the client + update-user-settings
  // wire format is camelCase — map before returning.
  const settings: Record<string, unknown> = {};
  if (settingsRow) {
    const map: Record<string, string> = {
      security_notifications: "securityNotifications",
      two_step_enabled: "twoStepEnabled",
      read_receipts: "readReceipts",
      fingerprint_lock: "fingerprintLock",
      last_seen: "lastSeen",
      profile_photo_visibility: "profilePhotoVisibility",
      about_visibility: "aboutVisibility",
      groups_visibility: "groupsVisibility",
      disappearing_default: "disappearingDefault",
      enter_is_send: "enterIsSend",
      media_visibility: "mediaVisibility",
      font_size: "fontSize",
      conversation_tones: "conversationTones",
      high_priority_messages: "highPriorityMessages",
      message_tone: "messageTone",
      message_vibrate: "messageVibrate",
      group_tone: "groupTone",
      call_ringtone: "callRingtone",
      use_less_data_for_calls: "useLessDataForCalls",
      mobile_data_media: "mobileDataMedia",
      wifi_media: "wifiMedia",
      roaming_media: "roamingMedia",
      app_language: "appLanguage",
    };
    for (const [col, key] of Object.entries(map)) {
      if (settingsRow[col] !== undefined && settingsRow[col] !== null) {
        settings[key] = settingsRow[col];
      }
    }
  }

  return json({
    settings,
    profile: {
      id: data.id,
      full_name: data.full_name ?? "",
      email,
      username: data.username ?? "",
      about: data.about ?? "",
      avatar_url: data.avatar_url ?? null,
      avatar_bucket: data.avatar_bucket ?? "avatars",
      phone: data.phone ?? null,
      phone_verified: data.phone_verified ?? false,
      country_code: data.country_code ?? null,
      country_name: countryName,
      language_code: data.language_code ?? "en",
      links: data.links ?? [],
      is_online: data.is_online ?? false,
      last_seen_at: data.last_seen_at ?? null,
      gender: data.gender ?? null,
      dob: data.dob ?? null,
      created_at: data.created_at,
      updated_at: data.updated_at,
    },
  });
}

serve(handler, { port: 9030 });
