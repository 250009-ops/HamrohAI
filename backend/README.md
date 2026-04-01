# Jarvis Backend (Railway)

This service provides three app-facing APIs:

- `POST /v1/llm/respond`
- `WS /v1/asr/stream`
- `POST /v1/tts/synthesize`

## Environment variables

**Required**

- `OPENAI_API_KEY` — powers LLM **and** (by default) **Whisper transcription** + **TTS audio**.

**Optional — relay mode**

If both are set, the server **proxies** ASR/TTS to your own upstreams instead of calling OpenAI for those two:

- `WHISPER_UPSTREAM_WS` — WebSocket URL of a compatible streaming Whisper server.
- `TTS_UPSTREAM_URL` — HTTP URL that accepts the same JSON body as `/v1/tts/synthesize` and returns audio bytes.

If these are **empty**, the server uses:

- **ASR:** OpenAI `audio.transcriptions` (`OPENAI_WHISPER_MODEL`, default `whisper-1`) with language auto-detection (no forced `uz` parameter). The WebSocket contract to the app is unchanged; audio is buffered and transcribed after pauses (not token-level streaming partials).
- **TTS:** OpenAI `audio.speech` (`OPENAI_TTS_MODEL` / `OPENAI_TTS_VOICE`), returning **WAV** to the app.

**Other optional variables**

- `OPENAI_MODEL` (default: `gpt-4.1-mini`)
- `OPENAI_WHISPER_MODEL` (default: `whisper-1`)
- `OPENAI_TTS_MODEL` (default: `tts-1`)
- `OPENAI_TTS_VOICE` (default: `nova`)
- `REQUEST_TIMEOUT_MS` (default: `10000`)
- `PORT` (default: `8080`)

## Contracts

### LLM

Request:

```json
{
  "system_prompt": "string",
  "response_style": "short_uzbek",
  "max_output_tokens": 280,
  "messages": [{ "role": "user", "content": "Salom" }]
}
```

Response:

```json
{ "text": "Salom, qanday yordam beray?" }
```

### ASR WebSocket

Client start message:

```json
{
  "event": "start",
  "language": "uz",
  "sample_rate": 16000,
  "encoding": "pcm_s16le"
}
```

Then client sends binary PCM16 mono frames.

Server messages:

- `{"event":"ready","protocol":"jarvis-whisper-v1"}`
- `{"event":"partial","text":"..."}` (relay upstream only; OpenAI mode is mostly end‑of‑utterance `final`)
- `{"event":"final","text":"..."}`
- `{"event":"error","code":"..."}`

### TTS

Request:

```json
{
  "text": "Salom",
  "language": "uz",
  "format": "wav",
  "sample_rate": 22050
}
```

Response: WAV bytes (`audio/wav`).

## Run locally

```bash
npm install
npm run start
```

Health:

```bash
curl http://localhost:8080/healthz
```

Expect `"ok": true` and `"whisper_mode": "openai"` / `"tts_mode": "openai"` when relays are unset.
