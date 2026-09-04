// Edge function: send-stream-scheduled-email
// ----------------------------------------------------------------------------
// Sends a "stream scheduled" confirmation email via Resend to the host (and
// optionally to a mailing list). Called by the Android StreamScheduleService
// when a host creates a scheduled stream.
//
// Env vars: RESEND_API_KEY, FROM_EMAIL, REPLY_TO_EMAIL
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "hostEmail": string, "hostName": string, "streamTitle": string,
//     "category": string, "scheduledDateTime": string, "slotInfo": string,
//     "pricingBadge": string, "shareLink": string }
//
// Response 200: { "sent": true, "messageId"?: string }
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { resolveUserId } from "../_shared/supabase.ts";
import { sendEmail, renderStreamScheduledEmail } from "../_shared/resend.ts";

interface Body {
  hostEmail?: string;
  hostName?: string;
  streamTitle?: string;
  category?: string;
  scheduledDateTime?: string;
  slotInfo?: string;
  pricingBadge?: string;
  shareLink?: string;
}

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
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

  const hostEmail = (body.hostEmail ?? "").trim().toLowerCase();
  if (!isValidEmail(hostEmail)) return errorResponse("A valid hostEmail is required", 422);
  if (!body.streamTitle?.trim()) return errorResponse("streamTitle is required", 422);
  if (!body.scheduledDateTime?.trim()) return errorResponse("scheduledDateTime is required", 422);

  const result = await sendEmail({
    to: hostEmail,
    subject: `📡 Stream Scheduled: ${body.streamTitle}`,
    html: renderStreamScheduledEmail({
      hostName: body.hostName ?? "there",
      streamTitle: body.streamTitle,
      category: body.category ?? "General",
      scheduledDateTime: body.scheduledDateTime,
      slotInfo: body.slotInfo ?? "Unlimited",
      pricingBadge: body.pricingBadge ?? "FREE",
      shareLink: body.shareLink ?? "#",
    }),
    tags: [{ name: "type", value: "stream_scheduled" }],
  });

  if (result.error) return errorResponse(result.error, 502);
  return json({ sent: true, messageId: result.id });
}

serve(handler, { port: 9003 });
