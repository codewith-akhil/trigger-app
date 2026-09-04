// Shared Resend email client.
// Sends transactional emails via Resend SMTP API (https://resend.com).
//
// Env vars:
//   RESEND_API_KEY   — server-side Resend API key (re_test_... or re_...)
//   FROM_EMAIL       — verified sender, e.g. "Trigger App <no-reply@triggerapp.com>"
//   REPLY_TO_EMAIL   — optional reply-to address
//
// Import pattern:
//   import { sendEmail, renderOtpEmail } from "../_shared/resend.ts";

export interface EmailPayload {
  to: string | string[];
  subject: string;
  html: string;
  from?: string;
  replyTo?: string;
  tags?: { name: string; value: string }[];
}

export async function sendEmail(payload: EmailPayload): Promise<{ id?: string; error?: string }> {
  const apiKey = Deno.env.get("RESEND_API_KEY");
  const defaultFrom = Deno.env.get("FROM_EMAIL") ?? "Trigger App <no-reply@triggerapp.com>";
  const defaultReplyTo = Deno.env.get("REPLY_TO_EMAIL");

  if (!apiKey) {
    return { error: "RESEND_API_KEY is not configured on the server" };
  }

  const body: Record<string, unknown> = {
    from: payload.from ?? defaultFrom,
    to: Array.isArray(payload.to) ? payload.to : [payload.to],
    subject: payload.subject,
    html: payload.html,
  };
  if (payload.replyTo ?? defaultReplyTo) {
    body.reply_to = payload.replyTo ?? defaultReplyTo;
  }
  if (payload.tags?.length) body.tags = payload.tags;

  try {
    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      // Surface the full Resend error so callers can debug (domain not
      // verified, test-mode restriction, invalid recipient, etc.).
      const errMsg = (data && (data.message || data.error)) || `Resend HTTP ${res.status}`;
      const errName = (data && data.name) || "";
      return { error: errName ? `${errName}: ${errMsg}` : errMsg };
    }
    return { id: data?.id };
  } catch (e) {
    return { error: e instanceof Error ? e.message : "Unknown Resend error" };
  }
}


function escapeHtml(s: string): string {
  return (s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
// --- Email templates -------------------------------------------------------

/**
 * Branded email shell — WhatsApp-style dark theme with:
 * - gradient header band
 * - lightning logo + app name
 * - accent divider
 * - social proof footer with security reminder
 * - accessible alt text + table-based layout for max client compat
 */
function brandShell(title: string, bodyHtml: string, accentColor = "#00a884"): string {
  return `<!DOCTYPE html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1.0"/>
  <meta name="color-scheme" content="dark light"/>
  <meta name="supported-color-schemes" content="dark light"/>
  <meta name="x-apple-disable-message-reformatting"/>
  <title>${title}</title>
  <!--[if mso]><noscript><xml><o:OfficeDocumentSettings><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml></noscript><![endif]-->
  <style>
    /* Render well in dark-mode clients that invert colors. */
    @media (prefers-color-scheme: dark) {
      .trigger-card { background:#111b21 !important; }
      .trigger-code-box { background:#0b141a !important; }
    }
    /* Prevent iOS auto-zoom on small inputs. */
    a, code { -webkit-text-size-adjust:100%; }
  </style>
</head>
<body style="margin:0;padding:0;background:#0b141a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#e9edef;-webkit-font-smoothing:antialiased;">
  <!-- Outer wrapper — full-viewport dark backdrop -->
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;min-height:100%;">
    <tr>
      <td align="center" style="padding:32px 16px;">
        <!-- Card -->
        <table role="presentation" width="480" cellspacing="0" cellpadding="0" class="trigger-card" style="background:#111b21;border-radius:16px;overflow:hidden;max-width:480px;width:100%;box-shadow:0 4px 20px rgba(0,0,0,0.25);">
          <!-- Gradient header band -->
          <tr>
            <td style="padding:0;">
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:linear-gradient(135deg,${accentColor} 0%,#008069 100%);">
                <tr>
                  <td align="center" style="padding:24px 32px 20px 32px;">
                    <div style="font-size:28px;line-height:1;">&#9889;</div>
                    <div style="font-size:13px;font-weight:600;color:#0b141a;letter-spacing:2px;margin-top:6px;text-transform:uppercase;">Trigger App</div>
                  </td>
                </tr>
              </table>
            </td>
          </tr>
          <!-- Title -->
          <tr>
            <td style="padding:24px 32px 4px 32px;text-align:center;">
              <h1 style="margin:0;font-size:21px;font-weight:700;color:#e9edef;letter-spacing:-0.2px;">${title}</h1>
            </td>
          </tr>
          <!-- Body -->
          <tr>
            <td style="padding:12px 32px 8px 32px;">${bodyHtml}</td>
          </tr>
          <!-- Divider -->
          <tr>
            <td style="padding:8px 32px 0 32px;">
              <div style="height:1px;background:linear-gradient(90deg,transparent 0%,#2a3942 50%,transparent 100%);"></div>
            </td>
          </tr>
          <!-- Footer -->
          <tr>
            <td style="padding:18px 32px 28px 32px;">
              <p style="margin:0 0 12px 0;color:#8696a0;font-size:12px;line-height:1.55;">
                <strong style="color:#e9edef;">&#128274; Security tip:</strong> Never share this code with anyone — Trigger App will never call, SMS, or email you to ask for it.
              </p>
              <p style="margin:0 0 14px 0;color:#8696a0;font-size:11px;line-height:1.5;">
                If you didn't request this, you can safely ignore this email. Your account is secure.
              </p>
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                <tr>
                  <td style="font-size:11px;color:#54656f;">
                    &copy; ${new Date().getFullYear()} Trigger App &middot;
                    <a href="https://triggerapp.com/privacy" style="color:#54656f;text-decoration:underline;">Privacy</a> &middot;
                    <a href="https://triggerapp.com/terms" style="color:#54656f;text-decoration:underline;">Terms</a> &middot;
                    <a href="https://triggerapp.com/support" style="color:#54656f;text-decoration:underline;">Support</a>
                  </td>
                </tr>
              </table>
            </td>
          </tr>
        </table>
        <!-- Preheader (hidden) -->
        <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;mso-hide:all;">
          Your Trigger App verification code is inside. Open the app and enter it to continue.
        </div>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

export function renderOtpEmail(code: string, purpose: string): string {
  const label: Record<string, string> = {
    signup: "Confirm your email",
    recovery: "Reset your password",
    magic_link: "Your login code",
    email_change: "Confirm your new email",
    phone_verify: "Verify your phone",
    vault_reset: "Reset your vault PIN",
  };
  const body = `
    <p style="margin:0 0 16px 0;color:#8696a0;font-size:14px;line-height:1.6;">Enter this 6-digit code in the app to ${label[purpose] ?? "continue"}.</p>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
      <tr>
        <td align="center" style="padding:8px 0 12px 0;">
          <div class="trigger-code-box" style="display:inline-block;background:#0b141a;border:1px dashed #2a3942;border-radius:14px;padding:22px 36px;">
            <span style="font-size:36px;font-weight:700;letter-spacing:12px;color:#00a884;font-family:'SF Mono',Menlo,Consolas,monospace;">${code}</span>
          </div>
        </td>
      </tr>
    </table>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
      <tr>
        <td align="center" style="padding:4px 0 0 0;">
          <p style="margin:0;color:#8696a0;font-size:12px;line-height:1.5;">
            <span style="display:inline-block;color:#00a884;font-weight:600;">&#9201;</span>
            Expires in 10 minutes &nbsp;&middot;&nbsp; Resend available after 60 seconds
          </p>
        </td>
      </tr>
    </table>`;
  return brandShell(label[purpose] ?? "Your verification code", body);
}

export function renderStreamScheduledEmail(opts: {
  hostName: string;
  streamTitle: string;
  category: string;
  scheduledDateTime: string;
  slotInfo: string;
  pricingBadge: string;
  shareLink: string;
}): string {
  const body = `
    <p style="margin:0 0 10px 0;color:#e9edef;font-size:15px;">Hi ${escapeHtml(opts.hostName)},</p>
    <p style="margin:0 0 14px 0;color:#8696a0;font-size:14px;line-height:1.6;">Your stream has been scheduled.</p>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;border-radius:10px;">
      <tr><td style="padding:14px 16px;color:#8696a0;font-size:13px;">Title</td><td style="padding:14px 16px;color:#e9edef;font-size:14px;font-weight:600;">${escapeHtml(opts.streamTitle)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Category</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${escapeHtml(opts.category)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">When</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${escapeHtml(opts.scheduledDateTime)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Slots</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${escapeHtml(opts.slotInfo)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Pricing</td><td style="padding:10px 16px;color:#00a884;font-size:14px;font-weight:600;">${escapeHtml(opts.pricingBadge)}</td></tr>
    </table>
    <p style="margin:16px 0 6px 0;"><a href="${opts.shareLink}" style="display:inline-block;background:#00a884;color:#0b141a;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:24px;font-size:14px;">Share stream link</a></p>`;
  return brandShell("Stream scheduled", body);
}

export function renderBookingConfirmationEmail(opts: {
  attendeeName: string;
  streamTitle: string;
  hostName: string;
  scheduledDateTime: string;
  pricingBadge: string;
  shareLink: string;
}): string {
  const body = `
    <p style="margin:0 0 10px 0;color:#e9edef;font-size:15px;">Hi ${escapeHtml(opts.attendeeName)},</p>
    <p style="margin:0 0 14px 0;color:#8696a0;font-size:14px;line-height:1.6;">Your slot is confirmed. Here are the details:</p>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;border-radius:10px;">
      <tr><td style="padding:14px 16px;color:#8696a0;font-size:13px;">Stream</td><td style="padding:14px 16px;color:#e9edef;font-size:14px;font-weight:600;">${escapeHtml(opts.streamTitle)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Host</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${escapeHtml(opts.hostName)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">When</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${escapeHtml(opts.scheduledDateTime)}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Paid</td><td style="padding:10px 16px;color:#00a884;font-size:14px;font-weight:600;">${escapeHtml(opts.pricingBadge)}</td></tr>
    </table>
    <p style="margin:16px 0 6px 0;"><a href="${opts.shareLink}" style="display:inline-block;background:#00a884;color:#0b141a;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:24px;font-size:14px;">Join stream</a></p>`;
  return brandShell("Slot confirmed", body);
}
