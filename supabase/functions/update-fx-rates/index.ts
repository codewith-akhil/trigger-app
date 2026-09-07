// Edge function: update-fx-rates
// ----------------------------------------------------------------------------
// Fetches the latest USD-based exchange rates from exchangerate-api.com and
// updates the fx_rate column in the countries table. Scheduled to run twice
// daily (2am + 2pm UTC) via pg_cron.
//
// Env var: EXCHANGERATE_API_KEY (server-only)
// Auth: CRON_SECRET header (same as cron-auto-start-streams)
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

/** Length-safe, early-exit-free string compare. */
function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  // Auth: CRON_SECRET
  const cronSecret = Deno.env.get("CRON_SECRET");
  const providedSecret = req.headers.get("x-cron-secret") ?? "";
  if (!cronSecret || !timingSafeEqualHex(providedSecret, cronSecret)) {
    return errorResponse("Unauthorized", 401);
  }

  const apiKey = Deno.env.get("EXCHANGERATE_API_KEY");
  if (!apiKey) return errorResponse("EXCHANGERATE_API_KEY not configured", 500);

  // Fetch latest rates
  const url = `https://v6.exchangerate-api.com/v6/${apiKey}/latest/USD`;
  const res = await fetch(url);
  const data = await res.json();

  if (!data || data.result !== "success" || !data.conversion_rates) {
    console.error("FX API error:", JSON.stringify(data).slice(0, 200));
    return errorResponse("Failed to fetch exchange rates", 502);
  }

  const rates = data.conversion_rates as Record<string, number>;
  const supabase = createAdminClient();

  // Get all countries from DB
  const { data: countries, error: fetchError } = await supabase
    .from("countries")
    .select("id, currency_code");

  if (fetchError || !countries) {
    console.error("Failed to fetch countries:", fetchError);
    return errorResponse("Failed to fetch countries", 500);
  }

  let updated = 0;
  for (const country of countries) {
    const rate = rates[country.currency_code];
    if (rate !== undefined) {
      await supabase
        .from("countries")
        .update({ fx_rate: rate, fx_updated_at: new Date().toISOString() })
        .eq("id", country.id);
      updated++;
    }
  }

  return json({
    success: true,
    updated,
    total: countries.length,
    source: "exchangerate-api.com",
    base: "USD",
    timestamp: new Date().toISOString(),
  });
}

serve(handler, { port: 9048 });
