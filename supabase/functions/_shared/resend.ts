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
      return { error: (data && (data.message || data.error)) || `Resend HTTP ${res.status}` };
    }
    return { id: data?.id };
  } catch (e) {
    return { error: e instanceof Error ? e.message : "Unknown Resend error" };
  }
}

// --- Email templates -------------------------------------------------------

function brandShell(title: string, bodyHtml: string): string {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body style="margin:0;padding:0;background:#0b141a;font-family:'Segoe UI',Roboto,Arial,sans-serif;color:#e9edef;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;min-height:100%;">
    <tr><td align="center" style="padding:32px 16px;">
      <table role="presentation" width="480" cellspacing="0" cellpadding="0" style="background:#111b21;border-radius:14px;overflow:hidden;max-width:480px;width:100%;">
        <tr><td style="padding:28px 32px 8px 32px;text-align:center;">
          <div style="font-size:22px;font-weight:700;color:#00a884;letter-spacing:.5px;">&#9889; Trigger App</div>
          <h1 style="margin:18px 0 6px 0;font-size:22px;color:#e9edef;">${title}</h1>
        </td></tr>
        <tr><td style="padding:8px 32px 4px 32px;">${bodyHtml}</td></tr>
        <tr><td style="padding:18px 32px 28px 32px;">
          <p style="margin:0;color:#8696a0;font-size:12px;line-height:1.5;border-top:1px solid #2a3942;padding-top:14px;">
            &copy; Trigger App. Never share verification codes with anyone — Trigger App will never ask for them.
          </p>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body></html>`;
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
    <p style="margin:0 0 14px 0;color:#8696a0;font-size:14px;line-height:1.5;">Enter this 6-digit code in the app to ${label[purpose] ?? "continue"}. It expires in 10 minutes. You can request a new code after 60 seconds.</p>
    <div style="text-align:center;">
      <div style="display:inline-block;background:#0b141a;border:1px dashed #2a3942;border-radius:12px;padding:18px 28px;">
        <span style="font-size:34px;font-weight:700;letter-spacing:10px;color:#00a884;">${code}</span>
      </div>
    </div>`;
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
    <p style="margin:0 0 10px 0;color:#e9edef;font-size:15px;">Hi ${opts.hostName},</p>
    <p style="margin:0 0 14px 0;color:#8696a0;font-size:14px;line-height:1.6;">Your stream has been scheduled.</p>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;border-radius:10px;">
      <tr><td style="padding:14px 16px;color:#8696a0;font-size:13px;">Title</td><td style="padding:14px 16px;color:#e9edef;font-size:14px;font-weight:600;">${opts.streamTitle}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Category</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${opts.category}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">When</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${opts.scheduledDateTime}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Slots</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${opts.slotInfo}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Pricing</td><td style="padding:10px 16px;color:#00a884;font-size:14px;font-weight:600;">${opts.pricingBadge}</td></tr>
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
    <p style="margin:0 0 10px 0;color:#e9edef;font-size:15px;">Hi ${opts.attendeeName},</p>
    <p style="margin:0 0 14px 0;color:#8696a0;font-size:14px;line-height:1.6;">Your slot is confirmed. Here are the details:</p>
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:#0b141a;border-radius:10px;">
      <tr><td style="padding:14px 16px;color:#8696a0;font-size:13px;">Stream</td><td style="padding:14px 16px;color:#e9edef;font-size:14px;font-weight:600;">${opts.streamTitle}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Host</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${opts.hostName}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">When</td><td style="padding:10px 16px;color:#e9edef;font-size:14px;">${opts.scheduledDateTime}</td></tr>
      <tr><td style="padding:10px 16px;color:#8696a0;font-size:13px;">Paid</td><td style="padding:10px 16px;color:#00a884;font-size:14px;font-weight:600;">${opts.pricingBadge}</td></tr>
    </table>
    <p style="margin:16px 0 6px 0;"><a href="${opts.shareLink}" style="display:inline-block;background:#00a884;color:#0b141a;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:24px;font-size:14px;">Join stream</a></p>`;
  return brandShell("Slot confirmed", body);
}
