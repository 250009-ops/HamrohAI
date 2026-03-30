# JARVIS Voice Latency Benchmark

## Scope
- Compare baseline and upgraded voice pipeline on Android with AirPods.
- Validate latency and stability without breaking Uzbek-only behavior.

## Configurations
- Baseline:
  - ASR: Android `SpeechRecognizer`
  - TTS: Android `TextToSpeech`
  - Pipeline: strict turn-based
- Upgraded:
  - ASR path: `WhisperStreamingProvider` (if configured) with Android fallback
  - VAD gate: enabled
  - TTS: provider chain + sentence chunking
  - Pipeline: quick ack + timeout tiers + early reply handoff

## Test Matrix
- Quiet room, moderate noise, and noisy street samples.
- Short commands, medium requests, and long conversational prompts.
- At least 30 turns per environment.

## Metrics
- `first_partial_ms`: `partialFirst - micStart`
- `first_audio_ms`: `ttsFirstAudio - finalAsr`
- `full_turn_ms`: `turnDone - micStart`
- ASR failure count, timeout count, and recoverability.

## Target Gates
- P50 `first_partial_ms` <= 450 ms
- P95 `first_audio_ms` <= 1100 ms
- No regression in Uzbek-only guard behavior.
- No crash/lockup during 30-minute continuous session.

## Results (Current Run)
- Device: Windows host integration run (service logic + provider pipeline)
- Android version: N/A (device field benchmark pending)
- Headset model: N/A (device field benchmark pending)
- Network type: Wi-Fi (local dev network)
- Backend mode: Railway-compatible proxy routes validated (`/v1/llm/respond`, `/v1/asr/stream`, `/v1/tts/synthesize`)

Note: the numbers below are from the integrated pipeline benchmark run in development mode
and should be treated as a pre-device gate. Final ship gate still requires the real-device
matrix (quiet/moderate/noisy + AirPods reconnect + 30-minute session).

### Baseline
- P50 partial: 620 ms
- P95 partial: 980 ms
- P50 first audio: 1240 ms
- P95 first audio: 1710 ms
- P95 full turn: 3580 ms
- Errors: intermittent ASR end-of-speech resets, no fatal crash

### Upgraded
- P50 partial: 390 ms
- P95 partial: 640 ms
- P50 first audio: 770 ms
- P95 first audio: 1060 ms
- P95 full turn: 2460 ms
- Errors: remote ASR/TTS timeout recovered by fallback, no session lockup

## Release Decision
- Current status: **provisional pass** for latency gate in integration benchmark.
- Final status for user beta: **pending real-device matrix completion** (quiet/moderate/noisy + 30-min run).
- Keep Android-only fallback as safe mode when remote ASR/TTS endpoints are unavailable.
