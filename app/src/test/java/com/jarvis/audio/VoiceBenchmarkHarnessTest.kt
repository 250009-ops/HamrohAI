package com.jarvis.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBenchmarkHarnessTest {
    private val harness = VoiceBenchmarkHarness()

    @Test
    fun `release gate accepts target latency`() {
        val run = BenchmarkRun(
            caseLabel = "conversation_medium",
            partialP50Ms = 380,
            partialP95Ms = 600,
            firstAudioP50Ms = 700,
            firstAudioP95Ms = 1050
        )
        assertTrue(harness.releaseGate(run))
    }

    @Test
    fun `release gate rejects slow first audio`() {
        val run = BenchmarkRun(
            caseLabel = "long_prompt",
            partialP50Ms = 410,
            partialP95Ms = 820,
            firstAudioP50Ms = 880,
            firstAudioP95Ms = 1600
        )
        assertFalse(harness.releaseGate(run))
    }
}
