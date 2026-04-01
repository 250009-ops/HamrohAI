package com.jarvis.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.jarvis.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface TtsProvider {
    val providerName: String
    fun isAvailable(): Boolean
    fun speakChunk(text: String, utteranceId: String)
    fun stop()
    fun shutdown()
    fun setListener(listener: SimpleUtteranceListener)
}

class AndroidTtsProvider(context: Context) : TtsProvider {
    private var initialized = false
    private lateinit var textToSpeech: TextToSpeech

    override val providerName: String = "android_tts"

    init {
        textToSpeech = TextToSpeech(context.applicationContext) {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale("uz", "UZ")
                initialized = true
            }
        }
    }

    override fun isAvailable(): Boolean = initialized

    override fun speakChunk(text: String, utteranceId: String) {
        if (!initialized) return
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun stop() {
        if (initialized) textToSpeech.stop()
    }

    override fun shutdown() {
        if (initialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    override fun setListener(listener: SimpleUtteranceListener) {
        if (initialized) {
            textToSpeech.setOnUtteranceProgressListener(listener)
        }
    }
}

class RemoteOpenSourceTtsProvider : TtsProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(4500, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var stopRequested = false
    private var listener: SimpleUtteranceListener? = null
    private val trackLock = Any()
    private var activeTrack: AudioTrack? = null

    override val providerName: String = "remote_open_source_tts"

    override fun isAvailable(): Boolean = BuildConfig.TTS_ENDPOINT.isNotBlank()

    override fun speakChunk(text: String, utteranceId: String) {
        if (!isAvailable()) return
        stopRequested = false
        executor.execute {
            var completed = false
            repeat(MAX_ATTEMPTS) { attempt ->
                if (completed || stopRequested) return@repeat
                val payload = JSONObject().apply {
                    put("text", text)
                    put("language", "uz")
                    put("format", "wav")
                    put("sample_rate", 22050)
                }
                val request = Request.Builder()
                    .url(BuildConfig.TTS_ENDPOINT)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val ok = runCatching {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@use false
                        }
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        val (pcm, rate) = parseWavForPlayback(bytes)
                        if (pcm.isEmpty()) {
                            return@use false
                        }
                        playPcm(pcm, rate, utteranceId = utteranceId)
                        return@use true
                    }
                }.getOrElse { false }
                if (ok) {
                    completed = true
                } else if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            if (!completed && !stopRequested) {
                listener?.onError(utteranceId)
            }
        }
    }

    override fun stop() {
        stopRequested = true
        releaseActiveTrack()
    }

    override fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    override fun setListener(listener: SimpleUtteranceListener) {
        this.listener = listener
    }

    private fun parseWavForPlayback(raw: ByteArray): Pair<ByteArray, Int> {
        if (raw.size < 44) return Pair(raw, 22050)
        val riff = String(raw.copyOfRange(0, 4), Charsets.US_ASCII)
        val wave = String(raw.copyOfRange(8, 12), Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return Pair(raw, 22050)
        val sr = readLeU32(raw, 24) ?: 24000
        val dataOffset = findWavDataOffset(raw) ?: 44
        if (dataOffset >= raw.size) return Pair(ByteArray(0), sr)
        return Pair(raw.copyOfRange(dataOffset, raw.size), sr)
    }

    private fun findWavDataOffset(raw: ByteArray): Int? {
        var i = 12
        while (i + 8 <= raw.size) {
            val id = String(raw.copyOfRange(i, i + 4), Charsets.US_ASCII)
            val size = readLeU32(raw, i + 4) ?: return null
            if (id == "data") return i + 8
            val padded = size + (size % 2)
            i += 8 + padded
        }
        return null
    }

    private fun readLeU32(raw: ByteArray, off: Int): Int? {
        if (off + 4 > raw.size) return null
        return (raw[off].toInt() and 0xFF) or
            ((raw[off + 1].toInt() and 0xFF) shl 8) or
            ((raw[off + 2].toInt() and 0xFF) shl 16) or
            ((raw[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun playPcm(pcm: ByteArray, sampleRate: Int, utteranceId: String) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listener?.onError(utteranceId)
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        synchronized(trackLock) {
            activeTrack = track
        }
        listener?.onStart(utteranceId)
        runCatching {
            track.play()
            var offset = 0
            while (offset < pcm.size && !stopRequested) {
                val toWrite = (pcm.size - offset).coerceAtMost(2048)
                val written = track.write(pcm, offset, toWrite)
                if (written <= 0) break
                offset += written
            }
        }.onFailure {
            listener?.onError(utteranceId)
        }
        releaseTrack(track)
        synchronized(trackLock) {
            if (activeTrack === track) {
                activeTrack = null
            }
        }
        if (stopRequested) {
            listener?.onError(utteranceId)
        } else {
            listener?.onDone(utteranceId)
        }
    }

    private fun releaseActiveTrack() {
        val track = synchronized(trackLock) {
            val t = activeTrack
            activeTrack = null
            t
        }
        if (track != null) {
            releaseTrack(track)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 180L
    }
}
