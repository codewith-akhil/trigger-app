// Edge function: update-user-settings
// ----------------------------------------------------------------------------
// Persists the user's Privacy / Chats / Notifications / Storage / Locale
// preferences to the `user_settings` table. Replaces the in-memory
// `remember { mutableStateOf(...) }` state in the Android settings screens
// (PrivacySettingsScreen, ChatsSettingsScreen, NotificationsSettingsScreen,
// StorageSettingsScreen, AccountSettingsScreen) so preferences sync across
// devices + survive app reinstall.
//
// The `user_settings` table has one row per user (created by the
// on_auth_user_created_settings trigger on signup). This function only UPDATEs
// the columns supplied in the body — any omitted field is left unchanged.
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 10 requests / 60s per user (RATE_LIMITS.UPDATE_SETTINGS).
//
// Request body (all optional — only supplied fields are updated):
//   {
//     "securityNotifications"?: boolean,
//     "twoStepEnabled"?: boolean,
//     "readReceipts"?: boolean,
//     "fingerprintLock"?: boolean,
//     "lastSeen"?: "everyone" | "contacts" | "nobody",
//     "profilePhotoVisibility"?: "everyone" | "contacts" | "nobody",
//     "aboutVisibility"?: "everyone" | "contacts" | "nobody",
//     "groupsVisibility"?: "everyone" | "contacts" | "nobody",
//     "disappearingDefault"?: "OFF" | "24H" | "7D" | "90D",
//     "enterIsSend"?: boolean,
//     "mediaVisibility"?: "on" | "off",
//     "fontSize"?: "small" | "medium" | "large",
//     "conversationTones"?: boolean,
//     "highPriorityMessages"?: boolean,
//     "messageTone"?: string,
//     "messageVibrate"?: boolean,
//     "groupTone"?: string,
//     "callRingtone"?: string,
//     "useLessDataForCalls"?: boolean,
//     "mobileDataMedia"?: "auto" | "on" | "off",
//     "wifiMedia"?: "auto" | "on" | "off",
//     "roamingMedia"?: boolean,
//     "appLanguage"?: string
//   }
//
// Response 200: { "updated": true, "settings": {...} }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit, RATE_LIMITS } from "../_shared/rate_limit.ts";

type Visibility = "everyone" | "contacts" | "nobody";
type Disappearing = "OFF" | "24H" | "7D" | "90D";
type FontSize = "small" | "medium" | "large";
type MediaMode = "auto" | "on" | "off";

interface Body {
  securityNotifications?: boolean;
  twoStepEnabled?: boolean;
  readReceipts?: boolean;
  fingerprintLock?: boolean;
  lastSeen?: Visibility;
  profilePhotoVisibility?: Visibility;
  aboutVisibility?: Visibility;
  groupsVisibility?: Visibility;
  disappearingDefault?: Disappearing;
  enterIsSend?: boolean;
  mediaVisibility?: MediaMode | "on" | "off";
  fontSize?: FontSize;
  conversationTones?: boolean;
  highPriorityMessages?: boolean;
  messageTone?: string;
  messageVibrate?: boolean;
  groupTone?: string;
  callRingtone?: string;
  useLessDataForCalls?: boolean;
  mobileDataMedia?: MediaMode;
  wifiMedia?: MediaMode;
  roamingMedia?: boolean;
  appLanguage?: string;
}

const VISIBILITY: Visibility[] = ["everyone", "contacts", "nobody"];
const DISAPPEARING: Disappearing[] = ["OFF", "24H", "7D", "90D"];
const FONT_SIZE: FontSize[] = ["small", "medium", "large"];
const MEDIA_MODE: MediaMode[] = ["auto", "on", "off"];

function isIn<T extends string>(v: unknown, allowed: readonly T[]): v is T {
  return typeof v === "string" && (allowed as readonly string[]).includes(v);
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  // --- Rate limit ---------------------------------------------------------
  const rl = checkRateLimit(req, userId, RATE_LIMITS.UPDATE_SETTINGS);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED);
  }

  // --- Build the patch with strict validation per field --------------------
  const patch: Record<string, unknown> = {};

  // Boolean fields
  for (const [field, value] of Object.entries(body)) {
    if (value === undefined) continue;
    // CamelCase → snake_case column name.
    const col = field.replace(/[A-Z]/g, (m) => "_" + m.toLowerCase());
    switch (field) {
      case "securityNotifications":
      case "twoStepEnabled":
      case "readReceipts":
      case "fingerprintLock":
      case "enterIsSend":
      case "conversationTones":
      case "highPriorityMessages":
      case "messageVibrate":
      case "useLessDataForCalls":
      case "roamingMedia":
        if (typeof value !== "boolean") {
          return errorResponse(`${field} must be a boolean`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "lastSeen":
      case "profilePhotoVisibility":
      case "aboutVisibility":
      case "groupsVisibility":
        if (!isIn(value, VISIBILITY)) {
          return errorResponse(`${field} must be one of: ${VISIBILITY.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "disappearingDefault":
        if (!isIn(value, DISAPPEARING)) {
          return errorResponse(`disappearingDefault must be one of: ${DISAPPEARING.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "fontSize":
        if (!isIn(value, FONT_SIZE)) {
          return errorResponse(`fontSize must be one of: ${FONT_SIZE.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "mediaVisibility":
        if (!isIn(value, ["on", "off"])) {
          return errorResponse(`mediaVisibility must be 'on' or 'off'`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "mobileDataMedia":
      case "wifiMedia":
        if (!isIn(value, MEDIA_MODE)) {
          return errorResponse(`${field} must be one of: ${MEDIA_MODE.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value;
        break;
      case "messageTone":
      case "groupTone":
      case "callRingtone":
        if (typeof value !== "string" || value.length > 64) {
          return errorResponse(`${field} must be a string (max 64 chars)`, 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value.trim() || "default";
        break;
      case "appLanguage":
        if (typeof value !== "string" || !/^[a-z]{2,5}$/i.test(value)) {
          return errorResponse("appLanguage must be a BCP-47 code (e.g. 'en', 'hi')", 422, ErrorCode.VALIDATION_FAILED);
        }
        patch[col] = value.toLowerCase();
        break;
      default:
        // Unknown field — reject to surface typos in the client.
        return errorResponse(`Unknown setting field: ${field}`, 422, ErrorCode.VALIDATION_FAILED);
    }
  }

  if (Object.keys(patch).length === 0) {
    return errorResponse("No settings supplied to update", 422, ErrorCode.VALIDATION_FAILED);
  }

  // --- Persist via service-role admin (bypass RLS for cross-device sync) ----
  const supabase = createAdminClient();
  const { data, error } = await supabase
    .from("user_settings")
    .update(patch)
    .eq("user_id", userId)
    .select()
    .single();

  if (error) {
    console.error("update-user-settings failed", error);
    return errorResponse("Failed to update settings", 500, ErrorCode.INTERNAL_ERROR);
  }

  return json({ updated: true, settings: data });
}

serve(handler, { port: 9016 });
