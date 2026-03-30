# JARVIS V1 Beta Checklist

## Latency Targets
- First partial ASR transcript under 500 ms in stable network conditions.
- First spoken token under 1200 ms after end-of-utterance.

## Reliability Scenarios
- AirPods disconnect during active session should trigger rerouting and continue listening.
- Network loss should return Uzbek fallback response instead of app crash.
- ASR error should auto-retry listening loop in under 1 second.

## Battery And Thermal
- 30-minute continuous session should not exceed acceptable thermal limits for test device.
- Foreground service notification should remain active for full session without drops.

## Permissions And Safety
- Verify behavior when microphone permission denied.
- Verify reminder/alarm/open-app actions show Uzbek fallback when action not allowed.
- Verify call action always asks for explicit user confirmation.

## Privacy
- Confirm local memory can be cleared from memory settings screen.
- Confirm export action only returns local summary/facts and excludes raw audio.
