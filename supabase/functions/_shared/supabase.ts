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

// ---------------------------------------------------------------------------
// Storage-capable service key
// ---------------------------------------------------------------------------
// On projects migrated to the new API-key system, the runtime-injected
// SUPABASE_SERVICE_ROLE_KEY can be a new-style secret that PostgREST accepts
// but the STORAGE service still rejects. The legacy JWT stored in
// public.app_secrets always works for storage, so probe the env key first and
// fall back to the DB copy when it is rejected. Cached per isolate.

let storageKeyCache: string | null = null;

export async function resolveStorageServiceKey(admin: SupabaseClient): Promise<string> {
  if (storageKeyCache) return storageKeyCache;

  const envKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const url = Deno.env.get("SUPABASE_URL") ?? "";

  if (envKey && url) {
    try {
      const probe = await fetch(`${url}/storage/v1/bucket`, {
        headers: { Authorization: `Bearer ${envKey}` },
      });
      // 200 = key accepted; anything 4xx/5xx auth-related (401/403) = rejected.
      if (probe.ok) {
        storageKeyCache = envKey;
        return storageKeyCache;
      }
      console.error("storage rejected env service key (status", probe.status, ") — using app_secrets fallback");
    } catch (e) {
      console.error("storage probe failed", e);
    }
  }

  const { data } = await admin
    .from("app_secrets")
    .select("value")
    .eq("key", "service_role_key")
    .maybeSingle();
  const stored = (data as { value?: string } | null)?.value ?? "";
  storageKeyCache = stored || envKey;
  return storageKeyCache;
}

/** Admin client bound to an explicit key (use with resolveStorageServiceKey). */
export function createAdminClientWithKey(serviceKey: string): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  if (!url || !serviceKey) {
    throw new Error("Missing SUPABASE_URL or service key");
  }
  return createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}
