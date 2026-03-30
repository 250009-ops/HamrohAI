# Jarvis Backend (Railway)

This service provides three app-facing APIs:

- `POST /v1/llm/respond`
- `WS /v1/asr/stream`
- `POST /v1/tts/synthesize`

## Environment Variables

Required:

- `OPENAI_API_KEY`
- `WHISPER_UPSTREAM_WS`
- `TTS_UPSTREAM_URL`

Optional:

- `OPENAI_MODEL` (default: `gpt-4.1-mini`)
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
- `{"event":"partial","text":"..."}`
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

Response: WAV bytes.

## Run Locally

```bash
npm install
npm run start
```

Health:

```bash
curl http://localhost:8080/healthz
```
