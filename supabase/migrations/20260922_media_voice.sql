-- ============================================================================
-- 20260922_media_voice.sql
-- Media speed + voice-note playback unblock.
--
-- 1. VOICE_NOTES → PUBLIC READ
--    The client has always stored the bucket's PUBLIC url on the message row
--    (send-message fn getPublicUrl), but the bucket was private → every voice
--    note 403'd for BOTH parties ("recording shows but never plays"). Making it
--    public mirrors chat_media (20260918_chat_fixes.sql) so the already-stored
--    URLs simply start working — no backfill needed, the public URL is
--    deterministic from the object path. Object names are unguessable
--    (uuid.m4a) and the bucket holds only the user's own voice recordings.
--
-- 2. CHAT_MEDIA / VOICE_NOTES owner-folder WRITE tightening
--    The client now uploads DIRECTLY to Storage (no edge-function relay):
--    PUT /storage/v1/object/{bucket}/{auth_uid}/{uuid}.{ext}. The pre-existing
--    *_owner_write policies already scope INSERT to owner = auth.uid() (the
--    column is set from the JWT on direct upload). These folder-scoped
--    policies add defense-in-depth so a user can only seed THEIR OWN folder.
-- ============================================================================

-- ---------------------------------------------------------------- voice_notes
update storage.buckets set public = true where id = 'voice_notes';

drop policy if exists "voice_notes_authenticated_read" on storage.objects;
create policy "voice_notes_authenticated_read"
  on storage.objects for select to authenticated
  using (bucket_id = 'voice_notes');

drop policy if exists "voice_notes_owner_folder_write" on storage.objects;
create policy "voice_notes_owner_folder_write"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'voice_notes'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

drop policy if exists "voice_notes_owner_folder_delete" on storage.objects;
create policy "voice_notes_owner_folder_delete"
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'voice_notes'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

-- ----------------------------------------------------------------- chat_media
-- Same folder-scoped write tightening for chat_media (read stays public from
-- 20260918_chat_fixes.sql; *_owner_write/*_owner_delete from 20260904 remain).
drop policy if exists "chat_media_owner_folder_write" on storage.objects;
create policy "chat_media_owner_folder_write"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'chat_media'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

-- --------------------------------------------------------------- upload rate
-- The client no longer relays bytes through the upload-chat-media function;
-- its 30/hour rate limit is irrelevant to the new path (send-message keeps its
-- own send rate limits). Left untouched for backward compatibility with older
-- app builds that still use the relay.
