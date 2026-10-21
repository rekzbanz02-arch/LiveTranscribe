package com.rekzbanz.livetranscribe

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Helpers for the xAI API key.
 *
 * Why this exists: xAI answers HTTP 400 (not only 401) when the API key is wrong, so a rejected
 * WebSocket handshake ("Expected HTTP 101 response but was '400 Bad Request'") is almost always a
 * bad key. [diagnose] asks xAI directly so the app can say so instead of showing a bare HTTP code.
 */
object KeyCheck {

    /**
     * Strips everything a copy/paste can drag along: spaces, line breaks, quotes, a leading
     * "Bearer " / "Authorization:", and invisible or non-ASCII characters.
     */
    fun clean(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("(?i)^authorization:?\\s*"), "")
        s = s.replace(Regex("(?i)^bearer\\s+"), "")
        return s.filter { it.code in 33..126 && it != '"' && it != '\'' && it != '`' }
    }

    /** xAI keys start with "xai-". Used only for a soft warning, never to block. */
    fun looksLikeXaiKey(key: String): Boolean = key.startsWith("xai-")

    /** Safe-to-display fingerprint, e.g. "xai-…a1b2 (84 characters)". */
    fun fingerprint(key: String): String =
        if (key.length <= 8) "${key.length} characters"
        else "${key.take(4)}…${key.takeLast(4)} (${key.length} characters)"

    /**
     * Asks xAI whether it accepts this key (GET /v1/api-key). Blocking network call: run it on a
     * background thread. Returns a one-line verdict, or "" when the check was inconclusive
     * (offline, timeout, unexpected answer), in which case the caller shows only its own error.
     */
    fun diagnose(apiKey: String): String {
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder()
                .url("https://api.x.ai/v1/api-key")
                .header("Authorization", "Bearer $apiKey")
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            val body = try {
                response.body?.string().orEmpty()
            } finally {
                response.close()
            }
            return judge(code, body)
        } catch (t: Throwable) {
            return ""
        } finally {
            try {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun judge(code: Int, body: String): String {
        if (code == 400 || code == 401) {
            return "xAI says this API key is invalid or incorrect. " +
                "Copy it again from console.x.ai (it starts with xai-) and paste it with nothing extra."
        }
        if (code !in 200..299) return ""
        val json = JSONObject(body)
        val blocked = json.optBoolean("api_key_blocked") ||
            json.optBoolean("api_key_disabled") ||
            json.optBoolean("team_blocked")
        return if (blocked) {
            "The key is recognised, but xAI reports it (or its team) as blocked or disabled. " +
                "Check the key and your credits at console.x.ai."
        } else {
            "xAI accepts this key, so the key is not the problem."
        }
    }
}
