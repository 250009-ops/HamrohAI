package com.jarvis.asr

import com.jarvis.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WhisperStreamingProvider : AsrProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var listener: AsrProvider.Listener? = null
    private var webSocket: WebSocket? = null
    private val ready = AtomicBoolean(false)
    private var connectAttempts = 0

    override val providerName: String = "whisper_streaming_endpoint"

    override fun isAvailable(): Boolean = BuildConfig.WHISPER_ENDPOINT.isNotBlank()

    override fun start(listener: AsrProvider.Listener) {
        if (!isAvailable()) {
            listener.onError(ERROR_UNAVAILABLE)
            return
        }
        this.listener = listener
        connectAttempts = 0
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(toWebSocketUrl(BuildConfig.WHISPER_ENDPOINT)).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val handshake = JSONObject().apply {
                    put("event", "start")
                    put("language", "uz")
                    put("sample_rate", 16000)
                    put("encoding", "pcm_s16le")
                    put("partial_results", true)
                    put("client_version", 1)
                }
                webSocket.send(handshake.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerEvent(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.set(false)
                if (connectAttempts < MAX_CONNECT_ATTEMPTS) {
                    connectAttempts += 1
                    thread(isDaemon = true, name = "whisper-retry") {
                        Thread.sleep(RETRY_DELAY_MS)
                        openSocket()
                    }
                    return
                }
                val code = if (t.message.orEmpty().contains("timeout", ignoreCase = true)) {
                    ERROR_TIMEOUT
                } else {
                    ERROR_HEALTHCHECK_FAILED
                }
                this@WhisperStreamingProvider.listener?.onError(code)
            }
        })
    }

    override fun onAudioFrame(frame: ByteArray, rmsDb: Float) {
        if (!ready.get()) return
        val sent = webSocket?.send(frame.toByteString()) ?: false
        if (!sent) {
            listener?.onError(ERROR_STREAM_SEND_FAILED)
        }
    }

    override fun stop() {
        ready.set(false)
        runCatching {
            webSocket?.send(JSONObject().put("event", "stop").toString())
            webSocket?.close(1000, "client_stop")
        }
        webSocket = null
        listener = null
    }

    private fun parseServerEvent(raw: String) {
        runCatching {
            val payload = JSONObject(raw)
            when (payload.optString("event")) {
                "ready" -> {
                    val protocol = payload.optString("protocol")
                    if (protocol.isBlank()) {
                        listener?.onError(ERROR_PROTOCOL_NOT_CONFIGURED)
                    } else {
                        ready.set(true)
                    }
                }

                "partial" -> {
                    val text = payload.optString("text")
                    if (text.isNotBlank()) listener?.onPartial(text)
                }

                "final" -> {
                    val text = payload.optString("text")
                    if (text.isNotBlank()) listener?.onFinal(text)
                }

                "error" -> {
                    val code = payload.optString("code")
                    when (code) {
                        "session_timeout" -> listener?.onError(ERROR_TIMEOUT)
                        "protocol_parse_error", "invalid_start_event" -> listener?.onError(ERROR_PROTOCOL_PARSE_FAILED)
                        "upstream_error", "upstream_closed", "asr_not_configured" ->
                            listener?.onError(ERROR_SERVER_REPORTED)
                        else -> listener?.onError(ERROR_SERVER_REPORTED)
                    }
                }
            }
        }.getOrElse {
            listener?.onError(ERROR_PROTOCOL_PARSE_FAILED)
        }
    }

    private fun toWebSocketUrl(endpoint: String): String {
        return when {
            endpoint.startsWith("ws://") || endpoint.startsWith("wss://") -> endpoint
            endpoint.startsWith("https://") -> endpoint.replaceFirst("https://", "wss://")
            endpoint.startsWith("http://") -> endpoint.replaceFirst("http://", "ws://")
            else -> "ws://$endpoint"
        }
    }

    companion object {
        const val ERROR_UNAVAILABLE = 1101
        const val ERROR_HEALTHCHECK_FAILED = 1102
        const val ERROR_PROTOCOL_NOT_CONFIGURED = 1103
        const val ERROR_PROTOCOL_PARSE_FAILED = 1104
        const val ERROR_STREAM_SEND_FAILED = 1105
        const val ERROR_SERVER_REPORTED = 1106
        const val ERROR_TIMEOUT = 1107

        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 300L
    }
}
