// Edge function: delete-user-account
// ----------------------------------------------------------------------------
// Permanently deletes the caller's Supabase auth account AND cascades all
// related rows (profiles, conversations, messages, wallet, vault, settings,
// push tokens, etc. via ON DELETE CASCADE FKs defined in the migration).
//
// Uses the service-role key to call the Admin API (the user cannot delete
// their own auth.users row through the anon/auth API without reauth).
//
// Auth: requires a valid Supabase JWT belonging to the account being deleted.
//
// Request body: { "confirm": "DELETE" }   // safety double-confirm
//
// Response 200: { "deleted": true }
// Response 4xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

interface Body {
  confirm?: string;
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
  if (body.confirm !== "DELETE") {
    return errorResponse("Confirmation missing — send { confirm: 'DELETE' }", 422);
  }

  const supabase = createAdminClient();
  const { error: adminError } = await supabase.auth.admin.deleteUser(userId);
  if (adminError) {
    console.error("User delete failed", adminError);
    return errorResponse("Failed to delete account", 500);
  }

  // ON DELETE CASCADE handles profiles, conversations, messages, wallet,
  // vault, settings, push_tokens, etc. (all FK → auth.users on delete cascade)
  return json({ deleted: true });
}

serve(handler, { port: 9008 });
