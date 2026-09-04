// Edge function: send-booking-confirmation-email
// ----------------------------------------------------------------------------
// Sends a booking-confirmation email via Resend to the attendee (and a
// notification to the host) when a slot is booked for a scheduled stream.
//
// Env vars: RESEND_API_KEY, FROM_EMAIL, REPLY_TO_EMAIL
// Auth: requires a valid Supabase JWT.
//
// Request body:
//   { "attendeeEmail": string, "attendeeName": string, "hostEmail"?: string,
//     "hostName": string, "streamTitle": string, "scheduledDateTime": string,
//     "pricingBadge": string, "shareLink": string }
//
// Response 200: { "sent": true, "attendeeMessageId"?: string, "hostMessageId"?: string }
// Response 4xx/5xx: { "error": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { resolveUserId } from "../_shared/supabase.ts";
import { sendEmail, renderBookingConfirmationEmail } from "../_shared/resend.ts";

interface Body {
  attendeeEmail?: string;
  attendeeName?: string;
  hostEmail?: string;
  hostName?: string;
  streamTitle?: string;
  scheduledDateTime?: string;
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

  const attendeeEmail = (body.attendeeEmail ?? "").trim().toLowerCase();
  if (!isValidEmail(attendeeEmail)) return errorResponse("A valid attendeeEmail is required", 422);
  if (!body.streamTitle?.trim()) return errorResponse("streamTitle is required", 422);
  if (!body.scheduledDateTime?.trim()) return errorResponse("scheduledDateTime is required", 422);

  // --- Attendee confirmation email -----------------------------------------
  const attendeeResult = await sendEmail({
    to: attendeeEmail,
    subject: `🎟️ Slot Confirmed: ${body.streamTitle}`,
    html: renderBookingConfirmationEmail({
      attendeeName: body.attendeeName ?? "there",
      streamTitle: body.streamTitle,
      hostName: body.hostName ?? "the host",
      scheduledDateTime: body.scheduledDateTime,
      pricingBadge: body.pricingBadge ?? "FREE",
      shareLink: body.shareLink ?? "#",
    }),
    tags: [{ name: "type", value: "booking_attendee" }],
  });

  // --- Host notification email (best-effort) --------------------------------
  let hostMessageId: string | undefined;
  const hostEmail = (body.hostEmail ?? "").trim().toLowerCase();
  if (isValidEmail(hostEmail)) {
    const hostResult = await sendEmail({
      to: hostEmail,
      subject: `🎉 New Attendee Booked: ${body.streamTitle}`,
      html: `<div style="font-family:Arial,sans-serif;color:#111;">
        <h2>New booking 🎟️</h2>
        <p><strong>${body.attendeeName ?? "An attendee"}</strong> booked a slot for
        <strong>${body.streamTitle}</strong>.</p>
        <p>Scheduled: ${body.scheduledDateTime}</p>
        <p>Paid: ${body.pricingBadge ?? "FREE"}</p>
      </div>`,
      tags: [{ name: "type", value: "booking_host" }],
    });
    hostMessageId = hostResult.id;
    // host notification failure is non-fatal
  }

  if (attendeeResult.error) return errorResponse(attendeeResult.error, 502);
  return json({ sent: true, attendeeMessageId: attendeeResult.id, hostMessageId });
}

serve(handler, { port: 9004 });
