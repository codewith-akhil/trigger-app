// Edge function: get-contacts
// Returns the caller's contacts (users who have accepted message requests) + their profiles.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  const supabase = createAdminClient();

  // Get contact user IDs
  const { data: contacts, error: cError } = await supabase
    .from("contacts")
    .select("contact_user_id")
    .eq("user_id", userId);

  if (cError) return json({ error: "Failed to fetch contacts" }, 500);

  if (!contacts || contacts.length === 0) return json({ contacts: [] });

  // Fetch profiles for those contacts
  const contactIds = contacts.map(c => c.contact_user_id);
  const { data: profiles, error: pError } = await supabase
    .from("profiles")
    .select("id, full_name, username, avatar_url, is_online, last_seen_at")
    .in("id", contactIds);

  if (pError) return json({ error: "Failed to fetch contact profiles" }, 500);
  return json({ contacts: profiles ?? [] });
}
serve(handler, { port: 9036 });
