package com.jarvis.asr

class VadGate(
    /** Legacy Android SpeechRecognizer scale (roughly positive voice intensity). */
    private val speechRmsThreshold: Float = 4.0f,
    /** PCM / dB scale from [PcmAudioFrameSource] (negative dB, louder is closer to 0). */
    private val speechRmsDbThreshold: Float = -38f,
    private val silenceHoldMs: Long = 900L
) {
    private var speechActive = false
    private var lastSpeechMs = 0L

    fun onRms(rms: Float): Boolean {
        val now = System.currentTimeMillis()
        val voiced = if (rms > 0f) {
            rms >= speechRmsThreshold
        } else {
            rms >= speechRmsDbThreshold
        }
        if (voiced) {
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
