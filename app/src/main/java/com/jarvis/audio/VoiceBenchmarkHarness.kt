package com.jarvis.audio

data class BenchmarkCase(
    val label: String,
    val environment: String
)

data class BenchmarkRun(
    val caseLabel: String,
    val partialP50Ms: Long,
    val partialP95Ms: Long,
    val firstAudioP50Ms: Long,
    val firstAudioP95Ms: Long
)

class VoiceBenchmarkHarness {
    fun defaultCases(): List<BenchmarkCase> {
        return listOf(
            BenchmarkCase(label = "short_command", environment = "quiet"),
            BenchmarkCase(label = "conversation_medium", environment = "moderate_noise"),
            BenchmarkCase(label = "long_prompt", environment = "noisy")
        )
    }

    fun releaseGate(run: BenchmarkRun): Boolean {
        return run.partialP50Ms <= 450 && run.firstAudioP95Ms <= 1100
    }
}
