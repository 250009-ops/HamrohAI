package com.jarvis.audio

import android.content.Context
import android.util.Log

enum class SessionErrorType {
    NetworkDrop,
    AsrTimeout,
    AsrFailure,
    TtsFailure,
    NetworkTimeout,
    ProtocolParseError,
    ServerUnavailable,
    AudioDecodeFailure,
    StreamSendFailure
}

class SessionTelemetry(private val context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_telemetry", Context.MODE_PRIVATE)

    fun trackInfo(event: String) {
        Log.i("JarvisTelemetry", redact(event))
    }

    fun trackError(type: SessionErrorType, detail: String) {
        val key = "error_${type.name}"
        val next = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, next).apply()
        Log.e("JarvisTelemetry", "${type.name}: ${redact(detail)}")
    }

    private fun redact(raw: String): String {
        return raw.replace(Regex("\\+?\\d{7,15}"), "[redacted_phone]")
    }
}
