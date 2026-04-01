import http from "node:http";
import { randomUUID } from "node:crypto";
import express from "express";
import OpenAI from "openai";
import { toFile } from "openai/uploads";
import { WebSocketServer, WebSocket } from "ws";
import { z } from "zod";

const env = {
  port: Number(process.env.PORT || 8080),
  requestTimeoutMs: Number(process.env.REQUEST_TIMEOUT_MS || 10000),
  openAiApiKey: process.env.OPENAI_API_KEY || "",
  openAiModel: process.env.OPENAI_MODEL || "gpt-4.1-mini",
  openAiWhisperModel: process.env.OPENAI_WHISPER_MODEL || "whisper-1",
  openAiTtsModel: process.env.OPENAI_TTS_MODEL || "tts-1",
  openAiTtsVoice: process.env.OPENAI_TTS_VOICE || "nova",
  whisperUpstreamWs: process.env.WHISPER_UPSTREAM_WS || "",
  ttsUpstreamUrl: process.env.TTS_UPSTREAM_URL || ""
};

const startupErrors = [];
if (!env.openAiApiKey) startupErrors.push("OPENAI_API_KEY is required");

const whisperMode = env.whisperUpstreamWs ? "relay" : "openai";
const ttsMode = env.ttsUpstreamUrl ? "relay" : "openai";

const openai = env.openAiApiKey ? new OpenAI({ apiKey: env.openAiApiKey }) : null;
const app = express();
app.use(express.json({ limit: "1mb" }));

const llmSchema = z.object({
  system_prompt: z.string().min(1),
  response_style: z.string().optional(),
  max_output_tokens: z.number().int().positive().max(1024).optional(),
  messages: z.array(
    z.object({
      role: z.enum(["system", "user", "assistant"]),
      content: z.string().min(1)
    })
  ).min(1)
});

const ttsSchema = z.object({
  text: z.string().min(1).max(1200),
  language: z.string().default("uz"),
  format: z.enum(["wav", "pcm"]).default("wav"),
  sample_rate: z.number().int().min(8000).max(48000).default(22050)
});

app.get("/healthz", (_req, res) => {
  const hasCritical = startupErrors.length > 0;
  res.status(hasCritical ? 503 : 200).json({
    ok: !hasCritical,
    whisper_mode: whisperMode,
    tts_mode: ttsMode,
    required_env: {
      openai: Boolean(env.openAiApiKey),
      whisper_relay: Boolean(env.whisperUpstreamWs),
      tts_relay: Boolean(env.ttsUpstreamUrl)
    },
    errors: startupErrors
  });
});

app.post("/v1/llm/respond", async (req, res) => {
  const requestId = randomUUID();
  const parsed = llmSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "invalid_request", request_id: requestId });
    return;
  }
  if (!openai) {
    res.status(503).json({ error: "openai_not_configured", request_id: requestId });
    return;
  }

  const timeout = AbortSignal.timeout(env.requestTimeoutMs);
  try {
    const { system_prompt, messages, max_output_tokens } = parsed.data;
    const input = [
      { role: "system", content: [{ type: "input_text", text: system_prompt }] },
      ...messages.map((m) => ({
        role: m.role,
        content: [{ type: "input_text", text: m.content }]
      }))
    ];

    const response = await openai.responses.create(
      {
        model: env.openAiModel,
        input,
        max_output_tokens: max_output_tokens ?? 280
      },
      { signal: timeout }
    );
    const text = forceUzbek(response.output_text || "");
    res.status(200).json({ text: text || "Uzr, javob tayyor bo'lmadi." });
  } catch (error) {
    safeLog("llm_error", requestId, error);
    res.status(502).json({ error: "llm_upstream_failure", request_id: requestId });
  }
});

app.post("/v1/tts/synthesize", async (req, res) => {
  const requestId = randomUUID();
  const parsed = ttsSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "invalid_request", request_id: requestId });
    return;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), env.requestTimeoutMs);
  try {
    if (env.ttsUpstreamUrl) {
      const upstream = await fetch(env.ttsUpstreamUrl, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(parsed.data),
        signal: controller.signal
      });
      if (!upstream.ok) {
        const errorText = await safeReadText(upstream);
        safeLog("tts_upstream_error", requestId, errorText);
        res.status(502).json({ error: "tts_upstream_failure", request_id: requestId });
        return;
      }
      const audio = Buffer.from(await upstream.arrayBuffer());
      if (audio.length > 10 * 1024 * 1024) {
        res.status(413).json({ error: "tts_payload_too_large", request_id: requestId });
        return;
      }
      res.status(200).set({
        "content-type": upstream.headers.get("content-type") || "audio/wav",
        "cache-control": "no-store"
      });
      res.send(audio);
      return;
    }

    if (!openai) {
      res.status(503).json({ error: "tts_not_configured", request_id: requestId });
      return;
    }

    const speech = await openai.audio.speech.create(
      {
        model: env.openAiTtsModel,
        voice: env.openAiTtsVoice,
        input: parsed.data.text,
        response_format: "wav"
      },
      { signal: controller.signal }
    );
    const audio = Buffer.from(await speech.arrayBuffer());
    const contentType = "audio/wav";
    res.status(200).set({
      "content-type": contentType,
      "cache-control": "no-store"
    });
    res.send(audio);
  } catch (error) {
    safeLog("tts_error", requestId, error);
    res.status(504).json({ error: "tts_timeout_or_network", request_id: requestId });
  } finally {
    clearTimeout(timeout);
  }
});

const server = http.createServer(app);
const wsServer = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  if (req.url !== "/v1/asr/stream") {
    socket.destroy();
    return;
  }
  wsServer.handleUpgrade(req, socket, head, (clientSocket) => {
    wsServer.emit("connection", clientSocket, req);
  });
});

wsServer.on("connection", (clientSocket) => {
  const sessionTimeout = setTimeout(() => {
    sendJson(clientSocket, { event: "error", code: "session_timeout" });
    clientSocket.close(1000, "timeout");
  }, 2 * 60 * 1000);

  const clearSession = () => clearTimeout(sessionTimeout);

  if (env.whisperUpstreamWs) {
    attachRelayWhisper(clientSocket, clearSession);
    return;
  }

  if (openai) {
    attachOpenAiWhisper(clientSocket, clearSession);
    return;
  }

  sendJson(clientSocket, { event: "error", code: "asr_not_configured" });
  clearSession();
  clientSocket.close(1011, "asr_not_configured");
});

function attachRelayWhisper(clientSocket, clearSession) {
  let started = false;
  let upstreamSocket = null;

  clientSocket.on("message", (data, isBinary) => {
    if (!started && !isBinary) {
      const parsed = parseJsonSafe(data.toString());
      if (!parsed || parsed.event !== "start") {
        sendJson(clientSocket, { event: "error", code: "invalid_start_event" });
        closePair(clientSocket, upstreamSocket, 1002, "invalid_start");
        return;
      }
      started = true;
      upstreamSocket = openWhisperUpstream(clientSocket, parsed);
      return;
    }

    if (!started) return;
    if (upstreamSocket && upstreamSocket.readyState === WebSocket.OPEN) {
      upstreamSocket.send(data, { binary: isBinary });
    }
  });

  clientSocket.on("close", () => {
    clearSession();
    closePair(clientSocket, upstreamSocket, 1000, "client_closed");
  });

  clientSocket.on("error", () => {
    clearSession();
    closePair(clientSocket, upstreamSocket, 1011, "client_error");
  });
}

function attachOpenAiWhisper(clientSocket, clearSession) {
  let started = false;
  let speechBuf = Buffer.alloc(0);
  let inSpeech = false;
  let silenceMs = 0;
  let busy = false;

  const SAMPLE_RATE = 16_000;
  const BYTES_PER_MS = (SAMPLE_RATE * 2) / 1000;
  const MIN_BYTES = Math.floor(BYTES_PER_MS * 450);
  const MAX_BYTES = SAMPLE_RATE * 2 * 25;
  const SILENCE_END_MS = 750;
  const VOICE_DB = -42;

  const flush = async (asFinal, minBytesOverride) => {
    const minB = minBytesOverride ?? MIN_BYTES;
    if (busy || speechBuf.length < minB) return;
    busy = true;
    const pcm = speechBuf;
    speechBuf = Buffer.alloc(0);
    inSpeech = false;
    silenceMs = 0;

    const requestId = randomUUID();
    try {
      const wav = pcm16leMonoToWav(pcm, SAMPLE_RATE);
      const file = await toFile(wav, "utterance.wav", { type: "audio/wav" });
      const tr = await openai.audio.transcriptions.create({
        file,
        model: env.openAiWhisperModel,
        language: "uz"
      });
      const text = (tr.text || "").trim();
      if (clientSocket.readyState !== WebSocket.OPEN) return;
      if (text) {
        sendJson(clientSocket, { event: asFinal ? "final" : "partial", text });
      }
    } catch (error) {
      safeLog("openai_whisper_error", requestId, error);
      if (clientSocket.readyState === WebSocket.OPEN) {
        sendJson(clientSocket, { event: "error", code: "upstream_error" });
      }
    } finally {
      busy = false;
    }
  };

  clientSocket.on("message", async (data, isBinary) => {
    if (!started && !isBinary) {
      const parsed = parseJsonSafe(data.toString());
      if (!parsed || parsed.event !== "start") {
        sendJson(clientSocket, { event: "error", code: "invalid_start_event" });
        clientSocket.close(1002, "invalid_start");
        return;
      }
      started = true;
      sendJson(clientSocket, { event: "ready", protocol: "jarvis-whisper-v1" });
      return;
    }

    if (!started) return;

    if (!isBinary) {
      const parsed = parseJsonSafe(data.toString());
      if (parsed?.event === "stop") {
        inSpeech = false;
        silenceMs = 0;
        if (speechBuf.length >= MIN_BYTES) {
          await flush(true);
        } else if (speechBuf.length >= 3200) {
          await flush(true, 3200);
        }
        return;
      }
      return;
    }

    const frame = Buffer.from(data);
    const db = pcmFrameRmsDb(frame);
    const voiced = db > VOICE_DB;

    if (voiced) {
      inSpeech = true;
      silenceMs = 0;
      if (speechBuf.length + frame.length > MAX_BYTES) {
        await flush(true);
      }
      speechBuf = Buffer.concat([speechBuf, frame]);
      return;
    }

    if (inSpeech) {
      silenceMs += frameMs(frame, BYTES_PER_MS);
      if (silenceMs >= SILENCE_END_MS && speechBuf.length >= MIN_BYTES) {
        await flush(true);
      }
    }
  });

  clientSocket.on("close", () => {
    clearSession();
  });

  clientSocket.on("error", () => {
    clearSession();
  });
}

function pcm16leMonoToWav(pcm, sampleRate) {
  const headerSize = 44;
  const dataSize = pcm.length;
  const buf = Buffer.alloc(headerSize + dataSize);
  buf.write("RIFF", 0);
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write("WAVE", 8);
  buf.write("fmt ", 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36);
  buf.writeUInt32LE(dataSize, 40);
  pcm.copy(buf, headerSize);
  return buf;
}

function pcmFrameRmsDb(buf) {
  if (buf.length < 2) return -90;
  let sum = 0;
  let n = 0;
  for (let i = 0; i + 1 < buf.length; i += 2) {
    const s = buf.readInt16LE(i);
    sum += s * s;
    n += 1;
  }
  if (n === 0) return -90;
  const rms = Math.sqrt(sum / n);
  if (rms <= 0) return -90;
  const db = 20 * Math.log10(rms / 32768);
  return Math.max(-90, Math.min(0, db));
}

function frameMs(frame, bytesPerMs) {
  return frame.length / bytesPerMs;
}

function openWhisperUpstream(clientSocket, startPayload) {
  const upstream = new WebSocket(env.whisperUpstreamWs);
  upstream.on("open", () => {
    sendJson(clientSocket, { event: "ready", protocol: "jarvis-whisper-v1" });
    sendJson(upstream, {
      event: "start",
      language: startPayload.language || "uz",
      sample_rate: startPayload.sample_rate || 16000,
      encoding: startPayload.encoding || "pcm_s16le",
      partial_results: true
    });
  });

  upstream.on("message", (payload, isBinary) => {
    if (isBinary) return;
    const data = parseJsonSafe(payload.toString());
    if (!data) {
      sendJson(clientSocket, { event: "error", code: "protocol_parse_error" });
      return;
    }
    const mapped = mapAsrEvent(data);
    if (mapped) sendJson(clientSocket, mapped);
  });

  upstream.on("error", () => {
    sendJson(clientSocket, { event: "error", code: "upstream_error" });
    closePair(clientSocket, upstream, 1011, "upstream_error");
  });

  upstream.on("close", () => {
    if (clientSocket.readyState === WebSocket.OPEN) {
      sendJson(clientSocket, { event: "error", code: "upstream_closed" });
      clientSocket.close(1000, "upstream_closed");
    }
  });

  return upstream;
}

function mapAsrEvent(data) {
  const event = data.event || data.type || "";
  if (event === "partial" || data.partial) {
    return { event: "partial", text: data.text || data.partial || "" };
  }
  if (event === "final" || data.final) {
    return { event: "final", text: data.text || data.final || "" };
  }
  if (event === "error") {
    return { event: "error", code: data.code || "server_error" };
  }
  if (event === "ready") {
    return { event: "ready", protocol: data.protocol || "jarvis-whisper-v1" };
  }
  return null;
}

function parseJsonSafe(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function closePair(clientSocket, upstreamSocket, code, reason) {
  if (upstreamSocket && upstreamSocket.readyState === WebSocket.OPEN) {
    upstreamSocket.close(code, reason);
  }
  if (clientSocket && clientSocket.readyState === WebSocket.OPEN) {
    clientSocket.close(code, reason);
  }
}

function sendJson(socket, payload) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function safeLog(topic, requestId, error) {
  const text = typeof error === "string" ? error : error?.message || "unknown";
  const redacted = text.replace(/sk-[A-Za-z0-9-_]+/g, "[redacted_api_key]");
  console.error(`[${topic}] request_id=${requestId} message=${redacted}`);
}

function forceUzbek(text) {
  if (!text) return "";
  const lowered = text.toLowerCase();
  const englishHints = [" the ", " is ", " are ", " what ", " how ", " where "];
  const looksEnglish = englishHints.some((hint) => lowered.includes(hint));
  if (!looksEnglish) return text;
  return "Javob o'zbek tilida qayta tayyorlanmoqda. Iltimos, yana bir marta so'rang.";
}

async function safeReadText(response) {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

server.listen(env.port, () => {
  const mode = startupErrors.length > 0 ? "degraded" : "healthy";
  console.log(`jarvis-backend listening on :${env.port} (${mode}) whisper=${whisperMode} tts=${ttsMode}`);
  if (startupErrors.length > 0) {
    console.error(`startup configuration issues: ${startupErrors.join("; ")}`);
  }
});
