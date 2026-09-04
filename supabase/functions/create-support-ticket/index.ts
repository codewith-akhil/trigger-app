// Edge function: create-support-ticket
// ----------------------------------------------------------------------------
// Creates a support ticket from the HelpSettingsScreen "Contact Support"
// dialog. The Android app currently shows a Snackbar "Thank you! Your
// feedback has been dispatched to info@triggerapp.com." but never actually
// sends the message anywhere. This function persists the ticket to the
// `support_tickets` table AND emails it to the support inbox via Resend, so
// the support team can reply.
//
// Auth: requires a valid Supabase JWT.
// Rate limit: 5 requests / hour per user (RATE_LIMITS.CREATE_SUPPORT_TICKET).
//
// Request body:
//   {
//     "subject"?: string,        // optional, max 120 chars
//     "message": string,         // required, 1..4000 chars
//     "category"?: string,       // optional, one of: general|bug|billing|stream|account
//     "contactEmail"?: string   // optional override; defaults to the user's auth email
//   }
//
// Response 200: { "created": true, "ticketId": string }
// Response 4xx: { "error": string, "code": string }
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";
import { checkRateLimit, RATE_LIMITS } from "../_shared/rate_limit.ts";
import { sendEmail } from "../_shared/resend.ts";

interface Body {
  subject?: string;
  message?: string;
  category?: string;
  contactEmail?: string;
}

const CATEGORIES = ["general", "bug", "billing", "stream", "account"] as const;
const SUPPORT_INBOX = "support@triggerapp.com";

function renderSupportEmail(opts: {
  userName: string;
  userEmail: string;
  subject: string;
  category: string;
  message: string;
  ticketId: string;
}): string {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body style="margin:0;padding:0;background:#f7f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1a1a1a;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#f7f8fa;min-height:100%;">
    <tr><td align="center" style="padding:24px 16px;">
      <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background:#ffffff;border-radius:12px;overflow:hidden;max-width:600px;width:100%;box-shadow:0 2px 8px rgba(0,0,0,0.06);">
        <tr>
          <td style="padding:20px 28px;background:#00a884;color:#ffffff;">
            <div style="font-size:20px;font-weight:700;">&#9889; New support ticket</div>
            <div style="font-size:13px;opacity:.9;margin-top:4px;">#${opts.ticketId.slice(0, 8)} &middot; ${opts.category}</div>
          </td>
        </tr>
        <tr>
          <td style="padding:24px 28px;">
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
              <tr><td style="padding:6px 0;color:#666;font-size:12px;width:80px;">From</td><td style="padding:6px 0;color:#1a1a1a;font-size:14px;"><strong>${opts.userName}</strong> &lt;${opts.userEmail}&gt;</td></tr>
              <tr><td style="padding:6px 0;color:#666;font-size:12px;">Subject</td><td style="padding:6px 0;color:#1a1a1a;font-size:14px;">${opts.subject}</td></tr>
            </table>
            <div style="height:1px;background:#eee;margin:16px 0;"></div>
            <div style="font-size:14px;line-height:1.6;color:#1a1a1a;white-space:pre-wrap;">${escapeHtml(opts.message)}</div>
          </td>
        </tr>
        <tr>
          <td style="padding:14px 28px;background:#fafafa;border-top:1px solid #eee;font-size:11px;color:#999;">
            Reply directly to this email to respond to the user. Ticket ID: ${opts.ticketId}
          </td>
        </tr>
      </table>
    </td></tr>
  </table>
</body></html>`;
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const authHeader = req.headers.get("Authorization");
  const userId = await resolveUserId(authHeader);
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  // --- Rate limit (5 / hour) ----------------------------------------------
  const rl = checkRateLimit(req, userId, RATE_LIMITS.CREATE_SUPPORT_TICKET);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return errorResponse("Invalid JSON body", 400, ErrorCode.VALIDATION_FAILED);
  }

  const message = (body.message ?? "").trim();
  if (!message) return errorResponse("Message is required", 422, ErrorCode.VALIDATION_FAILED);
  if (message.length > 4000) return errorResponse("Message must be 4000 characters or fewer", 422, ErrorCode.VALIDATION_FAILED);

  const subject = (body.subject ?? "Trigger App Support Inquiry").trim().slice(0, 120);
  const category = body.category ?? "general";
  if (!CATEGORIES.includes(category as any)) {
    return errorResponse(`category must be one of: ${CATEGORIES.join(", ")}`, 422, ErrorCode.VALIDATION_FAILED);
  }

  // --- Resolve user info for the ticket + email ----------------------------
  const supabase = createAdminClient();
  const { data: userData, error: userError } = await supabase.auth.admin.getUserById(userId);
  if (userError || !userData?.user) {
    return errorResponse("Unable to resolve user", 500, ErrorCode.INTERNAL_ERROR);
  }
  const contactEmail = (body.contactEmail ?? userData.user.email ?? "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contactEmail)) {
    return errorResponse("A valid contact email is required", 422, ErrorCode.VALIDATION_FAILED);
  }

  const { data: profile } = await supabase
    .from("profiles")
    .select("full_name")
    .eq("id", userId)
    .maybeSingle();
  const userName = profile?.full_name ?? contactEmail.split("@")[0];

  // --- Insert the ticket row ----------------------------------------------
  const { data: ticket, error: insertError } = await supabase
    .from("support_tickets")
    .insert({
      user_id: userId,
      subject,
      message,
      category,
      status: "open",
    })
    .select("id")
    .single();

  if (insertError) {
    console.error("support ticket insert failed", insertError);
    return errorResponse("Failed to create ticket", 500, ErrorCode.INTERNAL_ERROR);
  }

  // --- Email the support inbox (best-effort) -------------------------------
  const emailResult = await sendEmail({
    to: SUPPORT_INBOX,
    replyTo: contactEmail,
    subject: `[${category}] ${subject} — #${ticket.id.slice(0, 8)}`,
    html: renderSupportEmail({
      userName,
      userEmail: contactEmail,
      subject,
      category,
      message,
      ticketId: ticket.id,
    }),
    tags: [{ name: "type", value: "support_ticket" }, { name: "category", value: category }],
  });
  if (emailResult.error) {
    // Ticket was created, but the email failed — log + still return success.
    console.warn("support ticket email failed", emailResult.error);
  }

  return json({ created: true, ticketId: ticket.id, emailed: !emailResult.error });
}

serve(handler, { port: 9017 });
