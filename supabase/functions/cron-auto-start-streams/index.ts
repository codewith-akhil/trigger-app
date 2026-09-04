// Edge function: cron-auto-start-streams
// ----------------------------------------------------------------------------
// Designed to be invoked by the Supabase Cron (pg_cron) scheduler every minute.
// Transitions scheduled_streams whose scheduled_date + scheduled_time has
// passed from status='scheduled' → 'live', and creates a corresponding
// live_streams row so the audience can find + join the broadcast.
//
// This closes the gap where a host schedules a stream but never manually
// presses "Go Live" — the stream auto-becomes discoverable at its scheduled
// time, and the host can join the channel from the app.
//
// Auth: NONE (called by the Supabase Cron scheduler with the service role).
// Deploy with --no-verify-jwt. Schedule via:
//   select cron.schedule(
//     'auto-start-streams',
//     '* * * * *',
//     $$ select net.http_post(
//       url := 'https://uazkcainrajcgxecomly.functions.supabase.co/cron-auto-start-streams',
//       headers := jsonb_build_object('Content-Type', 'application/json'),
//       body := jsonb_build_object()
//     ) $$
//   );
// (see migration 20260905_cron_and_views.sql)
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse } from "../_shared/cors.ts";
import { createAdminClient } from "../_shared/supabase.ts";

interface ScheduledStream {
  id: string;
  host_id: string;
  title: string;
  description: string | null;
  category: string;
  scheduled_date: string;   // YYYY-MM-DD
  scheduled_time: string;   // HH:MM
  channel_name: string | null;
  slot_limit: string;
  pricing_type: string;
  amount: number;
  currency: string;
  slots_booked: number;
  status: string;
}

function buildChannelName(streamId: string): string {
  // Sanitize: keep only alphanumerics, prefix with "stream_".
  const safe = streamId.replace(/[^A-Za-z0-9]/g, "").substring(0, 40);
  return `stream_${safe}`;
}

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405);

  // Auth: require a CRON_SECRET header to prevent external abuse.
  // The pg_cron job sets this via the Authorization header; only the
  // scheduler + Supabase admins know the secret.
  const cronSecret = Deno.env.get("CRON_SECRET");
  const providedSecret = req.headers.get("x-cron-secret") ?? "";
  if (!cronSecret || providedSecret !== cronSecret) {
    return errorResponse("Unauthorized", 401);
  }

  const supabase = createAdminClient();

  // Find scheduled streams due to start — now timezone-aware via the
  // streams_due_to_start view (added in migration 20260908). The view computes
  // scheduled_local_ts = scheduled_date + scheduled_time + host_timezone as a
  // timestamptz, so streams start at the correct wall-clock moment in the
  // host's local timezone (not UTC). Falls back to UTC if host_timezone is
  // NULL (the column defaults to 'UTC').
  const { data: dueStreams, error: fetchError } = await supabase
    .from("streams_due_to_start")
    .select("id, host_id, title, description, category, scheduled_date, scheduled_time, channel_name, slot_limit, pricing_type, amount, currency, slots_booked, status, scheduled_local_ts, host_name, host_avatar_url")
    .order("scheduled_local_ts", { ascending: true })
    .limit(50);

  if (fetchError) {
    console.error("auto-start: fetch failed", fetchError);
    return errorResponse("Failed to fetch due streams", 500);
  }

  if (!dueStreams || dueStreams.length === 0) {
    return json({ started: 0, message: "No streams due" });
  }

  const started: string[] = [];
  const skipped: string[] = [];

  for (const stream of dueStreams as any[]) {
    // The streams_due_to_start view already filtered to streams whose
    // scheduled_local_ts <= now(), so no further time comparison needed here.
    const channelName = stream.channel_name ?? buildChannelName(stream.id);

    // --- Create a live_streams row if one doesn't already exist --------------
    const { data: existingLive } = await supabase
      .from("live_streams")
      .select("id, status")
      .eq("channel_name", channelName)
      .maybeSingle();

    if (existingLive) {
      // A live stream row already exists (host may have started manually).
      // Just mark the scheduled_stream as 'live' so it stops being picked up.
      await supabase
        .from("scheduled_streams")
        .update({ status: "live" })
        .eq("id", stream.id);
      started.push(stream.id);
      continue;
    }

    // Look up the host's display name for streamer_name.
    const { data: hostProfile } = await supabase
      .from("profiles")
      .select("full_name, avatar_url")
      .eq("id", stream.host_id)
      .maybeSingle();

    const { error: liveInsertError } = await supabase.from("live_streams").insert({
      host_id: stream.host_id,
      channel_name: channelName,
      title: stream.title,
      description: stream.description,
      category: stream.category,
      status: "live",
      visibility: "public",
      viewer_count: 0,
      total_likes: 0,
      started_at: new Date().toISOString(),
      streamer_name: hostProfile?.full_name ?? "Broadcaster",
      host_avatar_url: hostProfile?.avatar_url ?? null,
    });

    if (liveInsertError) {
      console.error(`auto-start: failed to create live_streams for ${stream.id}`, liveInsertError);
      skipped.push(stream.id);
      continue;
    }

    // Mark the scheduled_stream as live + persist the channel_name.
    await supabase
      .from("scheduled_streams")
      .update({ status: "live", channel_name: channelName })
      .eq("id", stream.id);

    // --- Send a push notification to all booked attendees -------------------
    try {
      const { data: bookings } = await supabase
        .from("stream_bookings")
        .select("user_id")
        .eq("stream_id", stream.id);
      if (bookings && bookings.length > 0) {
        const attendeeIds = bookings.map((b) => b.user_id);
        // Look up FCM tokens for all attendees.
        const { data: tokens } = await supabase
          .from("push_tokens")
          .select("fcm_token")
          .in("user_id", attendeeIds)
          .eq("is_active", true);
        if (tokens && tokens.length > 0) {
          // Send push via the shared FCM helper (inline import to avoid a circular dep).
          const { sendFcmBatch } = await import("../_shared/firebase.ts");
          await sendFcmBatch(
            {
              title: `🔴 ${stream.title} is now live!`,
              body: stream.description ?? "Tap to join the broadcast.",
              data: { stream_id: stream.id, channel_name: channelName, type: "stream_started" },
              androidChannelId: "trigger_stream_notifications",
              priority: "high",
            },
            tokens.map((t) => t.fcm_token),
          );
        }
      }
    } catch (pushErr) {
      // Push failure is non-fatal — the stream still started.
      console.warn(`auto-start: push to attendees failed for ${stream.id}`, pushErr);
    }

    started.push(stream.id);
  }

  return json({
    started: started.length,
    skipped: skipped.length,
    startedIds: started,
    skippedIds: skipped,
    checkedAt: new Date().toISOString(),
  });
}

serve(handler, { port: 9015 });
