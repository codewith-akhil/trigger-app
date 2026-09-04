-- ============================================================================
-- Trigger App — User settings RPC + push-token cleanup + indexes
-- Migration: 20260906_settings_and_indexes.sql
-- ----------------------------------------------------------------------------
-- Adds:
--   1. An `upsert_user_settings` RPC for atomic full-row upsert (the
--      update-user-settings edge function uses UPDATE on individual columns,
--      but a full upsert is useful when syncing the whole row from a backup).
--   2. A `deactivate_push_token` RPC (for the Android app to call when a user
--      logs out — the current register-push-token only adds; logout should
--      deactivate the device's token so the logged-out user doesn't get
--      notifications meant for the next user of the same device).
--   3. Indexes on hot read paths (messages by conversation, push_tokens by
--      active user).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. upsert_user_settings RPC
-- ----------------------------------------------------------------------------
create or replace function public.upsert_user_settings(
  p_security_notifications  boolean default null,
  p_two_step_enabled         boolean default null,
  p_read_receipts            boolean default null,
  p_fingerprint_lock         boolean default null,
  p_last_seen                text    default null,
  p_profile_photo_visibility text    default null,
  p_about_visibility         text    default null,
  p_groups_visibility        text    default null,
  p_disappearing_default     text    default null,
  p_enter_is_send            boolean default null,
  p_media_visibility         text    default null,
  p_font_size                 text    default null,
  p_conversation_tones       boolean default null,
  p_high_priority_messages   boolean default null,
  p_message_tone             text    default null,
  p_message_vibrate          boolean default null,
  p_group_tone               text    default null,
  p_call_ringtone            text    default null,
  p_use_less_data_for_calls  boolean default null,
  p_mobile_data_media        text    default null,
  p_wifi_media               text    default null,
  p_roaming_media            boolean default null,
  p_app_language             text    default null
)
returns public.user_settings
language plpgsql
security definer set search_path = public
as $$
declare
  v_row public.user_settings%rowtype;
begin
  insert into public.user_settings (user_id) values (auth.uid())
  on conflict (user_id) do nothing;

  update public.user_settings set
    security_notifications  = coalesce(p_security_notifications,  security_notifications),
    two_step_enabled         = coalesce(p_two_step_enabled,         two_step_enabled),
    read_receipts            = coalesce(p_read_receipts,             read_receipts),
    fingerprint_lock         = coalesce(p_fingerprint_lock,          fingerprint_lock),
    last_seen                = coalesce(p_last_seen,                last_seen),
    profile_photo_visibility = coalesce(p_profile_photo_visibility, profile_photo_visibility),
    about_visibility         = coalesce(p_about_visibility,         about_visibility),
    groups_visibility        = coalesce(p_groups_visibility,        groups_visibility),
    disappearing_default     = coalesce(p_disappearing_default,     disappearing_default),
    enter_is_send            = coalesce(p_enter_is_send,            enter_is_send),
    media_visibility         = coalesce(p_media_visibility,         media_visibility),
    font_size                 = coalesce(p_font_size,                 font_size),
    conversation_tones       = coalesce(p_conversation_tones,       conversation_tones),
    high_priority_messages   = coalesce(p_high_priority_messages,   high_priority_messages),
    message_tone             = coalesce(p_message_tone,             message_tone),
    message_vibrate          = coalesce(p_message_vibrate,          message_vibrate),
    group_tone               = coalesce(p_group_tone,               group_tone),
    call_ringtone            = coalesce(p_call_ringtone,            call_ringtone),
    use_less_data_for_calls  = coalesce(p_use_less_data_for_calls,  use_less_data_for_calls),
    mobile_data_media        = coalesce(p_mobile_data_media,        mobile_data_media),
    wifi_media               = coalesce(p_wifi_media,                wifi_media),
    roaming_media            = coalesce(p_roaming_media,             roaming_media),
    app_language             = coalesce(p_app_language,             app_language)
  where user_id = auth.uid()
  returning * into v_row;

  return v_row;
end;
$$;

-- ----------------------------------------------------------------------------
-- 2. deactivate_push_token RPC (call on logout)
-- ----------------------------------------------------------------------------
create or replace function public.deactivate_push_token(p_device_id text)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.push_tokens set is_active = false
  where user_id = auth.uid() and device_id = p_device_id;
end;
$$;

-- ----------------------------------------------------------------------------
-- 3. Indexes on hot read paths
-- ----------------------------------------------------------------------------
create index if not exists push_tokens_active_user_idx
  on public.push_tokens (user_id)
  where is_active = true;

create index if not exists messages_conv_created_idx
  on public.messages (conversation_id, created_at desc);

create index if not exists otp_codes_purpose_active_idx
  on public.otp_codes (identifier, purpose, consumed_at, expires_at);

create index if not exists scheduled_streams_due_idx
  on public.scheduled_streams (scheduled_date, scheduled_time)
  where status = 'scheduled';

-- ============================================================================
-- Done.
-- ============================================================================
