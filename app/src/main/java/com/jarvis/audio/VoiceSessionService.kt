package com.jarvis.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.R
import com.jarvis.actions.ActionDispatcher
import com.jarvis.asr.PcmAudioFrameSource
import com.jarvis.asr.StreamingAsrClient
import com.jarvis.asr.WhisperStreamingProvider
import com.jarvis.llm.DialogueOrchestrator
import com.jarvis.memory.LocalMemoryStore
import com.jarvis.policy.UzbekOnlyGuard
import com.jarvis.tts.UzbekTtsEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VoiceSessionService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        telemetry.trackError(SessionErrorType.TtsFailure, "service_exception=${throwable.message.orEmpty()}")
        updateNotification("Ichki xato: sessiya tiklanmoqda...")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private lateinit var asrClient: StreamingAsrClient
    private lateinit var ttsEngine: UzbekTtsEngine
    private lateinit var orchestrator: DialogueOrchestrator
    private lateinit var audioRouter: BluetoothAudioRouter
    private lateinit var telemetry: SessionTelemetry
    private lateinit var latencyTracker: LatencyTracker
    private var active = false
    private val interruptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                telemetry.trackInfo("Audio route changed, trying reroute")
                audioRouter.startRouting()
                updateNotification("Audio yo'li o'zgardi, qayta ulanyapti...")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioRouter = BluetoothAudioRouter(this)
        asrClient = StreamingAsrClient(this)
        ttsEngine = UzbekTtsEngine(this)
        telemetry = SessionTelemetry(this)
        latencyTracker = LatencyTracker(this)
        orchestrator = DialogueOrchestrator(
            uzbekGuard = UzbekOnlyGuard(),
            actionDispatcher = ActionDispatcher(this),
            memoryStore = LocalMemoryStore.get(this)
        )
        runCatching {
            registerReceiver(interruptionReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession()
            ACTION_STOP_SESSION -> stopSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        ttsEngine.shutdown()
        runCatching {
            unregisterReceiver(interruptionReceiver)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession() {
        if (active) return
        active = true
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_text)))
        telemetry.trackInfo("Voice session started")
        latencyTracker.startTurn()
        runCatching { audioRouter.startRouting() }
            .onFailure {
                telemetry.trackError(SessionErrorType.TtsFailure, "audio_route_start_failed")
            }
        telemetry.trackInfo("ASR path: ${asrClient.selectedPathName()}")
        startListeningLoop()
    }

    private fun stopSession() {
        if (!active) return
        active = false
        asrClient.stop()
        ttsEngine.stop()
        runCatching { audioRouter.stopRouting() }
        telemetry.trackInfo("Voice session stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startListeningLoop() {
        asrClient.start(object : StreamingAsrClient.Listener {
            override fun onPartial(text: String) {
                latencyTracker.markPartialFirst()
                updateNotification("Eshitilyapti: $text")
            }

            override fun onFinal(text: String) {
                latencyTracker.markFinalAsr()
                updateNotification("Qayta ishlanyapti...")
                asrClient.stop()
                scope.launch {
                    val replyDeferred = async {
                        latencyTracker.markLlmStart()
                        orchestrator.respondTo(
                            userText = text,
                            shortResponseMode = true,
                            onFirstToken = { latencyTracker.markLlmFirstToken() }
                        )
                    }
                    val quickReply = withTimeoutOrNull(450) { replyDeferred.await() }
                    if (quickReply != null) {
                        speakFinalReply(quickReply.text)
                        return@launch
                    }

                    latencyTracker.markTtsStart()
                    ttsEngine.speak(
                        text = orchestrator.prefetchAcknowledgementPhrase(),
                        onFirstAudio = { latencyTracker.markTtsFirstAudio() },
                        onError = {
                            telemetry.trackError(SessionErrorType.TtsFailure, "ack_tts_failed")
                        },
                        onDone = {
                            scope.launch replyLaunch@{
                                val finalReply = runCatching { replyDeferred.await() }.getOrNull()
                                if (finalReply == null) {
                                    telemetry.trackError(SessionErrorType.ServerUnavailable, "reply_await_failed")
                                    if (active) {
                                        startListeningLoop()
                                    }
                                    return@replyLaunch
                                }
                                speakFinalReply(finalReply.text)
                            }
                        }
                    )
                }
            }

            override fun onError(code: Int) {
                updateNotification("ASR xato kodi: $code")
                telemetry.trackError(mapAsrError(code), "asr_code=$code")
                scope.launch {
                    delay(450)
                    if (active) {
                        startListeningLoop()
                    }
                }
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
        })
    }

    private fun speakFinalReply(text: String) {
        updateNotification("Javob berilyapti...")
        latencyTracker.markTtsStart()
        ttsEngine.speak(
            text = text,
            onFirstAudio = { latencyTracker.markTtsFirstAudio() },
            onError = {
                telemetry.trackError(SessionErrorType.TtsFailure, "reply_tts_failed")
            },
            onDone = {
                latencyTracker.markTurnDone()
                val snapshot = latencyTracker.snapshot()
                telemetry.trackInfo(
                    "Latency p50/p95 partial=${snapshot.partialP50Ms}/${snapshot.partialP95Ms}ms " +
                        "firstAudio=${snapshot.firstAudioP50Ms}/${snapshot.firstAudioP95Ms}ms"
                )
                if (active) {
                    latencyTracker.startTurn()
                    startListeningLoop()
                }
            }
        )
    }

    private fun mapAsrError(code: Int): SessionErrorType {
        return when (code) {
            WhisperStreamingProvider.ERROR_TIMEOUT -> SessionErrorType.NetworkTimeout
            WhisperStreamingProvider.ERROR_PROTOCOL_PARSE_FAILED -> SessionErrorType.ProtocolParseError
            WhisperStreamingProvider.ERROR_SERVER_REPORTED -> SessionErrorType.ServerUnavailable
            WhisperStreamingProvider.ERROR_STREAM_SEND_FAILED -> SessionErrorType.StreamSendFailure
            PcmAudioFrameSource.ERROR_AUDIO_RECORD_INIT,
            PcmAudioFrameSource.ERROR_RECORD_PERMISSION_MISSING -> SessionErrorType.AsrFailure
            else -> SessionErrorType.AsrFailure
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JarvisVoiceChannel",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START_SESSION = "com.jarvis.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.jarvis.action.STOP_SESSION"
        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 42
    }
}
