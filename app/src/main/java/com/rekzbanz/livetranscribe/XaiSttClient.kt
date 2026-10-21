package com.rekzbanz.livetranscribe

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One streaming session to xAI speech-to-text (wss://api.x.ai/v1/stt).
 * Single use: create a new instance for every (re)connection.
 *
 * Protocol (from xAI docs): config goes in URL query params, wait for "transcript.created",
 * then send raw PCM16 little-endian mono as binary frames, finish with {"type":"audio.done"}.
 */
class XaiSttClient(
    private val apiKey: String,
    private val language: String, // "" = auto-detect (handles mixed-language speech)
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady()
        fun onTranscript(text: String, isFinal: Boolean, speechFinal: Boolean)
        fun onServerError(message: String)
        fun onDisconnected(reason: String, fatal: Boolean)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    var ready = false
        private set

    fun connect() {
        val url = "https://api.x.ai/v1/stt".toHttpUrl().newBuilder()
            .addQueryParameter("model", "grok-voice-transcribe-2.0")
            .addQueryParameter("encoding", "pcm")
            .addQueryParameter("sample_rate", "16000")
            .addQueryParameter("interim_results", "true")
            .addQueryParameter("endpointing", "600")
            .apply { if (language.isNotEmpty()) addQueryParameter("language", language) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${KeyCheck.clean(apiKey)}")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (t: Throwable) {
                    // a bad message must never take the app down
                }
            }

            private fun handleMessage(text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "transcript.created" -> {
                        ready = true
                        callbacks.onReady()
                    }
                    "transcript.partial" -> callbacks.onTranscript(
                        json.optString("text"),
                        json.optBoolean("is_final", false),
                        json.optBoolean("speech_final", false)
                    )
                    "error" -> {
                        val msg = json.optString("message", text)
                        lastError = msg
                        callbacks.onServerError(msg)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                if (!closedByUs) {
                    callbacks.onDisconnected(lastError ?: "Connection closed", false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                if (closedByUs) return
                val code = response?.code
                val detail = if (response != null) serverDetail(response) else ""
                // Any 4xx other than "timeout" / "rate limited" means our request or key is
                // rejected; retrying the identical request can never succeed.
                val fatal = code != null && code in 400..499 && code != 408 && code != 429
                val reason = when (code) {
                    null -> lastError ?: (t.message ?: "Network error")
                    401 -> "Invalid API key (401)"
                    403 -> "Access denied (403): check the key's permissions and credits"
                    429 -> "Rate limited (429)"
                    400 -> "xAI rejected the connection (400)" +
                        (if (detail.isNotEmpty()) ": $detail" else "")
                    else -> "Server answered $code" +
                        (if (detail.isNotEmpty()) ": $detail" else "")
                }
                callbacks.onDisconnected(reason, fatal)
            }
        })
    }

    /** The server's own explanation of a refused handshake (xAI sends a short JSON body). */
    private fun serverDetail(response: Response): String {
        return try {
            val text = response.peekBody(1024L).string().trim().replace(Regex("\\s+"), " ")
            val message = try {
                val json = JSONObject(text)
                json.optString("error").ifEmpty { json.optString("message") }
            } catch (t: Throwable) {
                ""
            }
            message.ifEmpty { text }.take(300)
        } catch (t: Throwable) {
            ""
        }
    }

    fun sendAudio(pcm16le: ByteArray): Boolean {
        val s = socket
        if (!ready || s == null) return false
        return s.send(pcm16le.toByteString(0, pcm16le.size))
    }

    /** Graceful stop: tells the server we're done, then leaves a moment for the last words. */
    fun close() {
        closedByUs = true
        val s = socket
        val wasReady = ready
        ready = false
        // Socket work must never run on the main thread (NetworkOnMainThreadException).
        Thread({
            try {
                if (s != null && wasReady) {
                    s.send("{\"type\":\"audio.done\"}")
                    Thread.sleep(1500) // let the server deliver the last words
                    s.close(1000, "done")
                } else {
                    s?.cancel()
                }
            } catch (t: Throwable) {
                // ignore: we are shutting down anyway
            }
            release()
        }, "stt-close").start()
    }

    /** Drop a dead connection without waiting. */
    fun discard() {
        closedByUs = true
        ready = false
        val s = socket
        Thread({
            try {
                s?.cancel()
            } catch (t: Throwable) {
                // ignore
            }
            release()
        }, "stt-discard").start()
    }

    /** Call from a background thread only: closing TLS sockets is network I/O. */
    private fun release() {
        try {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        } catch (t: Throwable) {
            // ignore
        }
    }
}
