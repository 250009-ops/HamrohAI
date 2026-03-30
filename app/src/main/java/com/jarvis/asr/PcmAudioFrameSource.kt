package com.jarvis.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class PcmAudioFrameSource(private val context: Context) {

    interface Listener {
        fun onFrame(frame: ByteArray, rmsDb: Float)
        fun onError(errorCode: Int)
    }

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    fun start(listener: Listener) {
        if (running.get()) return
        if (!hasPermission()) {
            listener.onError(ERROR_RECORD_PERMISSION_MISSING)
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listener.onError(ERROR_AUDIO_RECORD_INIT)
            return
        }

        val bufferSize = (minBuffer * 2).coerceAtLeast(FRAME_BYTES * 2)
        val localRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
            localRecorder.release()
            listener.onError(ERROR_AUDIO_RECORD_INIT)
            return
        }

        recorder = localRecorder
        running.set(true)
        localRecorder.startRecording()

        thread(name = "pcm-frame-source", isDaemon = true) {
            val readBuffer = ByteArray(FRAME_BYTES)
            while (running.get()) {
                val read = localRecorder.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                val frame = if (read == readBuffer.size) {
                    readBuffer.copyOf()
                } else {
                    readBuffer.copyOf(read)
                }
                listener.onFrame(frame, rmsDb(frame))
            }
        }
    }

    fun stop() {
        running.set(false)
        recorder?.runCatching {
            stop()
            release()
        }
        recorder = null
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun rmsDb(frame: ByteArray): Float {
        if (frame.size < 2) return -90f
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return -90f
        val rms = sqrt(sumSquares / count)
        if (rms <= 0.0) return -90f
        return (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-90f, 0f)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val FRAME_MS = 40
        private const val FRAME_BYTES = SAMPLE_RATE_HZ * 2 * FRAME_MS / 1000

        const val ERROR_RECORD_PERMISSION_MISSING = 2101
        const val ERROR_AUDIO_RECORD_INIT = 2102
    }
}
