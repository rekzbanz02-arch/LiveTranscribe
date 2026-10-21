package com.rekzbanz.livetranscribe

import android.os.Handler
import android.os.Looper

/**
 * Shared transcript state between the foreground service (writer) and the UI (reader).
 *
 * The server sends three kinds of results:
 *  - interim (is_final=false): live guess for the current chunk
 *  - chunk final (is_final=true, speech_final=false): chunk is locked in, utterance continues
 *  - utterance final (both true): utterance finished -> becomes a finished line
 */
object TranscriptBus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()
    private val chunks = ArrayList<String>()
    private var pending = ""

    @Volatile
    var listener: (() -> Unit)? = null

    @Volatile
    var running = false
        private set

    @Volatile
    var status = ""
        private set

    fun setState(isRunning: Boolean, message: String) {
        running = isRunning
        status = message
        changed()
    }

    fun setStatus(message: String) {
        status = message
        changed()
    }

    /** Returns the finished line when this result completed an utterance, else null. */
    @Synchronized
    fun onTranscript(text: String, isFinal: Boolean, speechFinal: Boolean): String? {
        val t = text.trim()
        if (t.isNotEmpty()) {
            val joined = chunks.joinToString(" ")
            val cumulative = joined.isNotEmpty() && t.startsWith(joined)
            if (isFinal) {
                // If the server repeats the whole utterance so far, replace instead of appending.
                if (cumulative) chunks.clear()
                chunks.add(t)
                pending = ""
            } else {
                pending = if (cumulative) t.removePrefix(joined).trim() else t
            }
        }
        val finished = if (isFinal && speechFinal) flushLocked() else null
        changed()
        return finished
    }

    @Synchronized
    fun flush() {
        flushLocked()
        changed()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        chunks.clear()
        pending = ""
        changed()
    }

    /** Pair(finished lines, live text of the utterance in progress). */
    @Synchronized
    fun texts(): Pair<String, String> {
        val live = (chunks + pending).filter { it.isNotBlank() }.joinToString(" ")
        return Pair(lines.joinToString("\n\n"), live)
    }

    @Synchronized
    fun plainText(): String {
        val (done, live) = texts()
        return listOf(done, live).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun flushLocked(): String? {
        val text = (chunks + pending).filter { it.isNotBlank() }.joinToString(" ")
        chunks.clear()
        pending = ""
        if (text.isEmpty()) return null
        lines.add(text)
        return text
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }
}
