package com.jarvis.asr

class VadGate(
    private val speechRmsThreshold: Float = 4.0f,
    private val silenceHoldMs: Long = 900L
) {
    private var speechActive = false
    private var lastSpeechMs = 0L

    fun onRms(rms: Float): Boolean {
        val now = System.currentTimeMillis()
        if (rms >= speechRmsThreshold) {
            speechActive = true
            lastSpeechMs = now
        } else if (speechActive && now - lastSpeechMs > silenceHoldMs) {
            speechActive = false
        }
        return speechActive
    }

    fun isSpeechActive(): Boolean = speechActive

    fun reset() {
        speechActive = false
        lastSpeechMs = 0L
    }
}
