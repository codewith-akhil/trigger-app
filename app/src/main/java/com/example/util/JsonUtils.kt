package com.example.util

import org.json.JSONObject

/**
 * JsonUtils
 * ----------------------------------------------------------------------------
 * Safe JSONObject readers for PostgREST / edge-function payloads.
 *
 * Why this exists: Android's org.json [JSONObject.optString] returns the
 * LITERAL STRING "null" when the JSON value is an explicit null (PostgREST
 * and our edge functions emit explicit nulls for NULL columns). That string
 * passes every isNullOrBlank() guard downstream, so Coil receives
 * model="null" and renders a silently-failing blank image instead of the
 * letter/icon fallback. SupabaseClient already guards with isNull(...) in a
 * few places — this helper centralizes that pattern for every call-site.
 */

/** optString that maps JSON null (or literal "null") to null instead of "null". */
fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || optString(key).equals("null", true) || optString(key) == "") null else optString(key)
