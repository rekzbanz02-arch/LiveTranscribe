package com.rekzbanz.livetranscribe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service: captures audio (mic, other apps' playback, or both),
 * streams it to xAI speech-to-text and pushes results into [TranscriptBus].
 */
class TranscribeService : Service() {

    companion object {
        const val ACTION_START = "com.rekzbanz.livetranscribe.START"
        const val ACTION_STOP = "com.rekzbanz.livetranscribe.STOP"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        const val SOURCE_MIC = 0
        const val SOURCE_PLAYBACK = 1
        const val SOURCE_BOTH = 2

        private const val CHANNEL_ID = "transcribe"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 100 ms at 16 kHz
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var client: XaiSttClient? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var apiKey = ""
    private var language = ""
    private var reconnectAttempts = 0

    private val audioFormat: AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_START) {
            // Must enter the foreground first, whatever happens next.
            val needsProjection = intent.getIntExtra(EXTRA_SOURCE, SOURCE_MIC) != SOURCE_MIC
            try {
                createChannel()
                enterForeground(needsProjection)
            } catch (t: Throwable) {
                TranscriptBus.setState(false, "Cannot start service: ${describe(t)}")
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                startSession(intent)
            } catch (t: Throwable) {
                fail("Error: ${describe(t)}")
            }
        } else {
            stopSession("Stopped")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) stopSession("Stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private fun startSession(intent: Intent) {
        if (running) stopSession(null)

        val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_MIC)
        apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty()
        reconnectAttempts = 0
        val needsProjection = source != SOURCE_MIC

        if (needsProjection) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
            if (data == null) {
                fail("Capture permission missing")
                return
            }
            try {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { if (running) fail("Capture stopped by the system") }
                    }
                }
                p.registerCallback(cb, main)
                projection = p
                projectionCallback = cb
            } catch (t: Throwable) {
                fail("Cannot start capture: ${describe(t)}")
                return
            }
        }

        running = true
        TranscriptBus.setState(true, "Connecting…")
        connect()
        startCapture(source)
    }

    private fun enterForeground(needsProjection: Boolean) {
        val notification = buildNotification(getString(R.string.notif_listening))
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val projectionType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // If the system refuses the combined type, fall back to a narrower one.
        val attempts = if (needsProjection) listOf(projectionType or mic, projectionType) else listOf(mic)
        var last: Throwable? = null
        for (types in attempts) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notification, types)
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("startForeground failed")
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "no details"}"

    private fun stopSession(message: String?) {
        running = false
        main.removeCallbacksAndMessages(null)

        client?.close()
        client = null

        val p = projection
        if (p != null) {
            projectionCallback?.let { p.unregisterCallback(it) }
            p.stop()
        }
        projection = null
        projectionCallback = null

        TranscriptBus.setState(false, message ?: "Stopped")
        // Let the server's last results arrive, then close out the current line.
        main.postDelayed({ TranscriptBus.flush() }, 1700)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun fail(message: String) {
        stopSession(message)
        stopSelf()
    }

    // ------------------------------------------------------------ connection

    private fun connect() {
        var self: XaiSttClient? = null
        val callbacks = object : XaiSttClient.Callbacks {
            override fun onReady() {
                main.post {
                    if (client === self) {
                        reconnectAttempts = 0
                        TranscriptBus.setStatus(getString(R.string.status_listening))
                    }
                }
            }

            override fun onTranscript(text: String, isFinal: Boolean, speechFinal: Boolean) {
                val finished = TranscriptBus.onTranscript(text, isFinal, speechFinal)
                if (finished != null) main.post { updateNotification(finished) }
            }

            override fun onServerError(message: String) {
                main.post { if (client === self) TranscriptBus.setStatus("Server: $message") }
            }

            override fun onDisconnected(reason: String, fatal: Boolean) {
                main.post { if (client === self) handleDisconnect(reason, fatal) }
            }
        }
        val c = XaiSttClient(apiKey, language, callbacks)
        self = c
        client = c
        c.connect()
    }

    private fun handleDisconnect(reason: String, fatal: Boolean) {
        if (!running) return
        client?.discard()
        client = null
        TranscriptBus.flush()
        if (fatal) {
            fail(reason)
            return
        }
        reconnectAttempts++
        if (reconnectAttempts > 8) {
            fail("Connection lost: $reason")
            return
        }
        TranscriptBus.setStatus("Reconnecting… ($reason)")
        val delay = minOf(1000L * reconnectAttempts, 8000L)
        main.postDelayed({
            if (running && client == null) {
                try {
                    connect()
                } catch (t: Throwable) {
                    fail("Reconnect failed: ${describe(t)}")
                }
            }
        }, delay)
    }

    // --------------------------------------------------------------- capture

    private fun startCapture(source: Int) {
        var mic: AudioRecord? = null
        var playback: AudioRecord? = null
        try {
            if (source != SOURCE_PLAYBACK) mic = buildMicRecord()
            if (source != SOURCE_MIC) playback = buildPlaybackRecord(projection!!)
        } catch (t: Throwable) {
            mic?.release()
            playback?.release()
            fail("Audio capture failed: ${describe(t)}")
            return
        }

        if (mic != null && playback != null) {
            val micQueue = LinkedBlockingQueue<ShortArray>(40)
            val playQueue = LinkedBlockingQueue<ShortArray>(40)
            startReader("mic-reader", mic) { offerLatest(micQueue, it) }
            startReader("playback-reader", playback) { offerLatest(playQueue, it) }
            startMixer(micQueue, playQueue)
        } else {
            val rec = mic ?: playback!!
            startReader("reader", rec) { sendFrame(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildMicRecord(): AudioRecord {
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes())
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("microphone not available")
        }
        return rec
    }

    @SuppressLint("MissingPermission")
    private fun buildPlaybackRecord(p: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes())
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("phone audio capture not available")
        }
        return rec
    }

    private fun bufferBytes(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return maxOf(min, FRAME_SAMPLES * 2 * 4)
    }

    private fun startReader(name: String, rec: AudioRecord, sink: (ShortArray) -> Unit) {
        Thread({
            try {
                rec.startRecording()
                val buf = ShortArray(FRAME_SAMPLES)
                var filled = 0
                while (running) {
                    val n = rec.read(buf, filled, FRAME_SAMPLES - filled)
                    if (n < 0) {
                        main.post { if (running) fail("Audio read error ($n)") }
                        break
                    }
                    if (n == 0) {
                        Thread.sleep(5)
                        continue
                    }
                    filled += n
                    if (filled == FRAME_SAMPLES) {
                        sink(buf.copyOf())
                        filled = 0
                    }
                }
            } catch (t: Throwable) {
                main.post { if (running) fail("Audio error: ${describe(t)}") }
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                    // already stopped
                }
                rec.release()
            }
        }, name).start()
    }

    private fun offerLatest(queue: LinkedBlockingQueue<ShortArray>, frame: ShortArray) {
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    /** Sums mic + phone audio frame by frame (with clipping) and streams the result. */
    private fun startMixer(
        micQueue: LinkedBlockingQueue<ShortArray>,
        playQueue: LinkedBlockingQueue<ShortArray>
    ) {
        Thread({
            try {
                while (running) {
                    val a = micQueue.poll(200, TimeUnit.MILLISECONDS)
                    val b = playQueue.poll()
                    if (a == null && b == null) continue
                    val out = ShortArray(FRAME_SAMPLES)
                    for (i in out.indices) {
                        val sum = (a?.get(i)?.toInt() ?: 0) + (b?.get(i)?.toInt() ?: 0)
                        out[i] = sum.coerceIn(-32768, 32767).toShort()
                    }
                    sendFrame(out)
                }
            } catch (t: Throwable) {
                main.post { if (running) fail("Audio mix error: ${describe(t)}") }
            }
        }, "mixer").start()
    }

    private fun sendFrame(frame: ShortArray) {
        val c = client ?: return
        if (!c.ready) return
        val bytes = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(frame)
        c.sendAudio(bytes.array())
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TranscribeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
