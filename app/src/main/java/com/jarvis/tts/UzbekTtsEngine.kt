package com.jarvis.tts

import android.content.Context

class UzbekTtsEngine(context: Context) {

    private val androidProvider = AndroidTtsProvider(context)
    private val remoteProvider = RemoteOpenSourceTtsProvider()
    private var provider: TtsProvider = androidProvider
    private var firstAudioSent = false

    fun speak(
        text: String,
        onFirstAudio: () -> Unit = {},
        onError: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val chunks = chunkText(text)
        speakChunks(chunks, onFirstAudio, onError, onDone)
    }

    fun speakChunks(
        chunks: List<String>,
        onFirstAudio: () -> Unit = {},
        onError: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        val normalized = chunks.filter { it.isNotBlank() }
        if (normalized.isEmpty()) {
            onDone()
            return
        }

        ensureProvider()
        if (!provider.isAvailable()) {
            onDone()
            return
        }
        firstAudioSent = false
        val utteranceIds = normalized.mapIndexed { index, _ -> "jarvis-utt-$index-${System.currentTimeMillis()}" }
        val expectedIds = utteranceIds.toMutableSet()
        provider.setListener(
            SimpleUtteranceListener(
                onStart = { utteranceId ->
                    if (!firstAudioSent && utteranceId != null) {
                        firstAudioSent = true
                        onFirstAudio()
                    }
                },
                onDone = { utteranceId ->
                    if (utteranceId != null) {
                        expectedIds.remove(utteranceId)
                    }
                    if (expectedIds.isEmpty()) {
                        onDone()
                    }
                },
                onError = {
                    onError()
                }
            )
        )
        normalized.forEachIndexed { index, chunk ->
            provider.speakChunk(chunk, utteranceIds[index])
        }
    }

    fun warmupAckChunk(): String {
        return "Bir soniya, tekshirib beryapman."
    }

    fun stop() {
        provider.stop()
    }

    fun shutdown() {
        provider.shutdown()
    }

    private fun ensureProvider() {
        provider = when {
            androidProvider.isAvailable() -> androidProvider
            remoteProvider.isAvailable() -> remoteProvider
            else -> androidProvider
        }
    }

    private fun chunkText(text: String): List<String> {
        val split = text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (split.isNotEmpty()) split else listOf(text)
    }
}

