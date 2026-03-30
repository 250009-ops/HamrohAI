package com.jarvis.asr

import android.content.Context

class StreamingAsrClient(private val context: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onRmsChanged(rmsdB: Float)
    }

    private val vadGate = VadGate()
    private val frameSource = PcmAudioFrameSource(context)
    private var activeProvider: AsrProvider? = null
    private var fallbackProvider: AsrProvider? = null
    private var isListening = false
    private var silentFrameCounter = 0

    fun start(listener: Listener) {
        if (isListening) return
        val primary = if (WhisperStreamingProvider().isAvailable()) {
            WhisperStreamingProvider()
        } else {
            AndroidSpeechRecognizerProvider(context)
        }
        val secondary = if (primary is AndroidSpeechRecognizerProvider) {
            null
        } else {
            AndroidSpeechRecognizerProvider(context)
        }
        activeProvider = primary
        fallbackProvider = secondary
        vadGate.reset()
        silentFrameCounter = 0
        isListening = true
        startProvider(primary, listener)
        startFramePump(listener)
    }

    fun stop() {
        isListening = false
        activeProvider?.stop()
        fallbackProvider?.stop()
        frameSource.stop()
        activeProvider = null
        fallbackProvider = null
    }

    private fun startProvider(provider: AsrProvider, listener: Listener) {
        provider.start(object : AsrProvider.Listener {
            override fun onPartial(text: String) {
                if (vadGate.isSpeechActive()) {
                    listener.onPartial(text)
                }
            }

            override fun onFinal(text: String) {
                isListening = false
                listener.onFinal(text)
            }

            override fun onError(code: Int) {
                val fallback = fallbackProvider
                if (isListening && fallback != null && provider != fallback && fallback.isAvailable()) {
                    activeProvider = fallback
                    fallbackProvider = null
                    startProvider(fallback, listener)
                    return
                }
                isListening = false
                listener.onError(code)
            }

            override fun onRmsChanged(rmsdB: Float) {
                vadGate.onRms(rmsdB)
                listener.onRmsChanged(rmsdB)
            }
        })
    }

    private fun startFramePump(listener: Listener) {
        frameSource.start(object : PcmAudioFrameSource.Listener {
            override fun onFrame(frame: ByteArray, rmsDb: Float) {
                if (!isListening) return
                val speech = vadGate.onRms(rmsDb)
                listener.onRmsChanged(rmsDb)
                val provider = activeProvider ?: return

                // VAD-aware throttling: always send speech frames, sample sparse silence frames.
                if (speech) {
                    silentFrameCounter = 0
                    provider.onAudioFrame(frame, rmsDb)
                    return
                }
                silentFrameCounter += 1
                if (silentFrameCounter >= SILENCE_FRAME_SEND_INTERVAL) {
                    provider.onAudioFrame(frame, rmsDb)
                    silentFrameCounter = 0
                }
            }

            override fun onError(errorCode: Int) {
                if (isListening) {
                    listener.onError(errorCode)
                }
            }
        })
    }

    fun activeProviderName(): String {
        return activeProvider?.providerName.orEmpty()
    }

    fun isSpeechActive(): Boolean {
        return vadGate.isSpeechActive()
    }

    fun isPrimaryWhisperConfigured(): Boolean {
        return WhisperStreamingProvider().isAvailable()
    }

    fun selectedPathName(): String {
        return if (isPrimaryWhisperConfigured()) {
            "whisper_primary_android_fallback"
        } else {
            "android_primary"
        }
    }

    companion object {
        private const val SILENCE_FRAME_SEND_INTERVAL = 6
    }
}
