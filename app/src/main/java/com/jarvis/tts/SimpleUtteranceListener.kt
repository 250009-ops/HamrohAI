package com.jarvis.tts

import android.speech.tts.UtteranceProgressListener

class SimpleUtteranceListener(
    private val onStart: (String?) -> Unit,
    private val onDone: (String?) -> Unit,
    private val onError: (String?) -> Unit = onDone
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {
        onStart(utteranceId)
    }

    override fun onDone(utteranceId: String?) {
        onDone(utteranceId)
    }

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) {
        onError(utteranceId)
    }
}
