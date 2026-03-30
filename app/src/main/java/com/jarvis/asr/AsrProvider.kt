package com.jarvis.asr

interface AsrProvider {
    val providerName: String
    fun isAvailable(): Boolean
    fun start(listener: Listener)
    fun onAudioFrame(frame: ByteArray, rmsDb: Float)
    fun stop()

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onRmsChanged(rmsdB: Float)
    }
}
