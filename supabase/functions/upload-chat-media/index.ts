// Edge function: upload-chat-media
// ----------------------------------------------------------------------------
// Receives a media file (image/video/audio/document) from the Android client,
// validates the file size SERVER-SIDE, and uploads it to the appropriate
// Supabase Storage bucket. Returns the public URL.
//
// The client sends the file as raw binary in the request body with metadata
// in headers (X-File-Name, X-File-Type, X-Mime-Type, X-File-Size).
//
// Auth: requires a valid Supabase JWT.
// Server-side size limits (authoritative — client limits are just UX hints):
//   IMAGE: 50 MB, VIDEO: 250 MB, AUDIO/VOICE: 55 MB, DOCUMENT: 55 MB
// ----------------------------------------------------------------------------

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { handleOptions, json, errorResponse, ErrorCode } from "../_shared/cors.ts";
import { checkRateLimit } from "../_shared/rate_limit.ts";
import { createAdminClient, resolveUserId } from "../_shared/supabase.ts";

const MAX_SIZES: Record<string, number> = {
  IMAGE: 50 * 1024 * 1024,
  VIDEO: 250 * 1024 * 1024,
  AUDIO: 55 * 1024 * 1024,
  VOICE_NOTE: 55 * 1024 * 1024,
  DOCUMENT: 55 * 1024 * 1024,
};

const UPLOAD_LIMIT = { maxRequests: 30, windowSeconds: 3600, name: "upload_chat_media" };

const BUCKETS: Record<string, string> = {
  IMAGE: "chat_media",
  VIDEO: "chat_media",
  AUDIO: "voice_notes",
  VOICE_NOTE: "voice_notes",
  DOCUMENT: "documents",
};

async function handler(req: Request): Promise<Response> {
  const preflight = handleOptions(req);
  if (preflight) return preflight;
  if (req.method !== "POST") return errorResponse("Method not allowed", 405, ErrorCode.METHOD_NOT_ALLOWED);

  const userId = await resolveUserId(req.headers.get("Authorization"));
  if (!userId) return errorResponse("Unauthorized", 401, ErrorCode.UNAUTHORIZED);

  // Per-user rate limit — 250 MB/req endpoints without one are a DoS magnet.
  const rl = checkRateLimit(req, userId, UPLOAD_LIMIT);
  if (!rl.allowed) {
    return json({ error: rl.message, code: ErrorCode.RATE_LIMITED, retryAfter: rl.retryAfter }, 429);
  }

  // Read metadata from headers
  const fileType = (req.headers.get("x-file-type") ?? "").toUpperCase();
  // The Android client percent-encodes this header (OkHttp headers are
  // Latin-1; non-ASCII filenames previously crashed the client).
  let fileName = req.headers.get("x-file-name") ?? `file_${Date.now()}`;
  try {
    const decoded = decodeURIComponent(fileName);
    fileName = decoded;
  } catch (_) { /* keep raw */ }
  const mimeType = req.headers.get("x-mime-type") ?? "application/octet-stream";
  const declaredSize = parseInt(req.headers.get("x-file-size") ?? "0", 10);

  if (!fileType || !MAX_SIZES[fileType]) {
    return json({ error: "Invalid or missing X-File-Type header. Must be IMAGE, VIDEO, AUDIO, VOICE_NOTE, or DOCUMENT.", code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  // MIME whitelist per type — the mime came straight from the client header
  // and was stored as the object's content type.
  const MIME_WHITELIST: Record<string, string[]> = {
    IMAGE: ["image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"],
    VIDEO: ["video/mp4", "video/webm", "video/3gpp", "video/quicktime"],
    AUDIO: ["audio/aac", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/wav"],
    VOICE_NOTE: ["audio/aac", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/wav"],
    DOCUMENT: ["application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "text/plain", "text/csv", "application/zip"],
  };
  const allowedMimes = MIME_WHITELIST[fileType] ?? [];
  if (!allowedMimes.includes(mimeType.toLowerCase())) {
    return json({ error: `Unsupported ${fileType} mime type: ${mimeType}`, code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  // --- Server-side file size validation (authoritative) ---
  const maxSize = MAX_SIZES[fileType];
  if (declaredSize > maxSize) {
    return json({ error: `File too large. Max ${maxSize / 1024 / 1024}MB for ${fileType}`, code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  // Read the file bytes
  const fileBytes = new Uint8Array(await req.arrayBuffer());

  // Double-check actual size matches declared size (prevent lying about size)
  if (fileBytes.length > maxSize) {
    return json({ error: `Actual file size (${fileBytes.length} bytes) exceeds max ${maxSize} bytes for ${fileType}`, code: ErrorCode.VALIDATION_FAILED }, 422);
  }

  const bucket = BUCKETS[fileType];
  // Generate a unique path: userId/timestamp_random.ext — the extension is
  // whitelisted to safe chars (an unsanitized filename gave paths like
  // "…/x./../../evil" fragments).
  const rawExt = fileName.includes(".") ? fileName.split(".").pop()! : "bin";
  const ext = /^[A-Za-z0-9]{1,8}$/.test(rawExt) ? rawExt.toLowerCase() : "bin";
  const storagePath = `${userId}/${Date.now()}_${Math.random().toString(36).slice(2, 8)}.${ext}`;

  const supabase = createAdminClient();
  const { error: uploadError } = await supabase.storage
    .from(bucket)
    .upload(storagePath, fileBytes, {
      contentType: mimeType,
      upsert: false,
    });

  if (uploadError) {
    console.error("upload-chat-media failed", uploadError);
    return json({ error: "Failed to upload file", code: ErrorCode.INTERNAL_ERROR }, 500);
  }

  // chat_media/voice_notes are PRIVATE — return a short-lived signed URL,
  // never a permanent public URL (which would 403 anyway since the 20260924
  // privatization). The client sends only the object PATH as media_url and
  // signs at render time; the signed url here is a convenience for legacy
  // callers.
  const { data: signedData } = await supabase.storage.from(bucket).createSignedUrl(storagePath, 3600);
  const signedUrl = signedData?.signedUrl ?? null;
  if (!signedUrl) {
    console.error("upload-chat-media: failed to sign uploaded object", storagePath);
  }

  return json({
    uploaded: true,
    url: signedUrl ?? "",
    path: storagePath,
    bucket,
    path: storagePath,
    fileName,
    fileSize: fileBytes.length,
    mimeType,
    fileType,
  });
}

serve(handler, { port: 9038 });
