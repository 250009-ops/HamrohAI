package com.jarvis.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LatencySnapshot(
    val partialP50Ms: Long,
    val partialP95Ms: Long,
    val firstAudioP50Ms: Long,
    val firstAudioP95Ms: Long
)

class LatencyTracker(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_latency", Context.MODE_PRIVATE)
    private var current = TurnMarkers()

    fun startTurn() {
        current = TurnMarkers(micStartMs = now())
    }

    fun markPartialFirst() {
        if (current.partialFirstMs == 0L) current.partialFirstMs = now()
    }

    fun markFinalAsr() {
        current.finalAsrMs = now()
    }

    fun markLlmStart() {
        current.llmStartMs = now()
    }

    fun markLlmFirstToken() {
        if (current.llmFirstTokenMs == 0L) current.llmFirstTokenMs = now()
    }

    fun markTtsStart() {
        current.ttsStartMs = now()
    }

    fun markTtsFirstAudio() {
        if (current.ttsFirstAudioMs == 0L) current.ttsFirstAudioMs = now()
    }

    fun markTurnDone() {
        current.turnDoneMs = now()
        persistCurrent()
    }

    fun snapshot(): LatencySnapshot {
        val partial = readSeries(KEY_PARTIAL_SERIES)
        val firstAudio = readSeries(KEY_FIRST_AUDIO_SERIES)
        return LatencySnapshot(
            partialP50Ms = percentile(partial, 50),
            partialP95Ms = percentile(partial, 95),
            firstAudioP50Ms = percentile(firstAudio, 50),
            firstAudioP95Ms = percentile(firstAudio, 95)
        )
    }

    private fun persistCurrent() {
        val partialMs = duration(current.micStartMs, current.partialFirstMs)
        val firstAudioMs = duration(current.finalAsrMs, current.ttsFirstAudioMs)
        val fullTurnMs = duration(current.micStartMs, current.turnDoneMs)

        appendSeries(KEY_PARTIAL_SERIES, partialMs)
        appendSeries(KEY_FIRST_AUDIO_SERIES, firstAudioMs)
        appendSeries(KEY_TURN_SERIES, fullTurnMs)
        prefs.edit()
            .putString(KEY_LAST_TURN_JSON, JSONObject().apply {
                put("micStart", current.micStartMs)
                put("partialFirst", current.partialFirstMs)
                put("finalAsr", current.finalAsrMs)
                put("llmStart", current.llmStartMs)
                put("llmFirstToken", current.llmFirstTokenMs)
                put("ttsStart", current.ttsStartMs)
                put("ttsFirstAudio", current.ttsFirstAudioMs)
                put("turnDone", current.turnDoneMs)
            }.toString())
            .apply()
    }

    private fun appendSeries(key: String, value: Long) {
        if (value <= 0) return
        val arr = JSONArray(prefs.getString(key, "[]"))
        arr.put(value)
        while (arr.length() > MAX_SAMPLES) {
            arr.remove(0)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readSeries(key: String): List<Long> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        val out = mutableListOf<Long>()
        for (i in 0 until arr.length()) {
            out += arr.optLong(i)
        }
        return out
    }

    private fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun duration(start: Long, end: Long): Long {
        if (start <= 0L || end <= 0L || end < start) return 0L
        return end - start
    }

    private fun now(): Long = System.currentTimeMillis()

    private data class TurnMarkers(
        var micStartMs: Long = 0L,
        var partialFirstMs: Long = 0L,
        var finalAsrMs: Long = 0L,
        var llmStartMs: Long = 0L,
        var llmFirstTokenMs: Long = 0L,
        var ttsStartMs: Long = 0L,
        var ttsFirstAudioMs: Long = 0L,
        var turnDoneMs: Long = 0L
    )

    companion object {
        private const val MAX_SAMPLES = 120
        private const val KEY_PARTIAL_SERIES = "latency_partial_series"
        private const val KEY_FIRST_AUDIO_SERIES = "latency_first_audio_series"
        private const val KEY_TURN_SERIES = "latency_turn_series"
        private const val KEY_LAST_TURN_JSON = "latency_last_turn_json"
    }
}
