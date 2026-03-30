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
    private val textToSpeech: TextToSpeech

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
                        val pcm = extractPcmData(bytes)
                        if (pcm.isEmpty()) {
                            return@use false
                        }
                        playPcm(pcm, sampleRate = 22050, utteranceId = utteranceId)
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
        runCatching {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.release()
        }
        activeTrack = null
    }

    override fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    override fun setListener(listener: SimpleUtteranceListener) {
        this.listener = listener
    }

    private fun extractPcmData(raw: ByteArray): ByteArray {
        if (raw.size < 44) return raw
        val riff = String(raw.copyOfRange(0, 4))
        val wave = String(raw.copyOfRange(8, 12))
        if (riff == "RIFF" && wave == "WAVE") {
            return raw.copyOfRange(44, raw.size)
        }
        return raw
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
        activeTrack = track
        listener?.onStart(utteranceId)
        track.play()
        var offset = 0
        while (offset < pcm.size && !stopRequested) {
            val toWrite = (pcm.size - offset).coerceAtMost(2048)
            val written = track.write(pcm, offset, toWrite)
            if (written <= 0) break
            offset += written
        }
        track.stop()
        track.flush()
        track.release()
        activeTrack = null
        if (stopRequested) {
            listener?.onError(utteranceId)
        } else {
            listener?.onDone(utteranceId)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 180L
    }
}
