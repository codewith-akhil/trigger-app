// Shared Supabase admin client factory.
// Uses the SERVICE_ROLE_KEY (server-only) so edge functions can bypass RLS for
// trusted operations: OTP storage, push-token lookup, account deletion, etc.
//
// Env vars (set via `supabase secrets set`):
//   SUPABASE_URL
//   SUPABASE_SERVICE_ROLE_KEY
//
// Import pattern:
//   import { createAdminClient } from "../_shared/supabase.ts";
//   const supabase = createAdminClient();

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export function createAdminClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) {
    throw new Error("Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY env var");
  }
  return createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

/**
 * Create a Supabase client that acts on behalf of the calling user by
 * forwarding their JWT. Use this for operations that must respect RLS.
 */
export function createUserClient(authHeader: string | null): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!url || !anonKey) {
    throw new Error("Missing SUPABASE_URL or SUPABASE_ANON_KEY env var");
  }
  const token = (authHeader ?? "").replace(/^Bearer\s+/i, "");
  return createClient(url, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  });
}

/**
 * Resolve the calling user's id from the Authorization header.
 * Returns null when unauthenticated.
 */
export async function resolveUserId(authHeader: string | null): Promise<string | null> {
  if (!authHeader) return null;
  const supabase = createUserClient(authHeader);
  const { data, error } = await supabase.auth.getUser();
  if (error || !data?.user) return null;
  return data.user.id;
}
