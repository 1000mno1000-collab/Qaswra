import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GoogleGenAI } from "@google/genai";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const HOST = "0.0.0.0";

function getAiClient(): GoogleGenAI {
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("GEMINI_API_KEY is not configured on the server.");
  }
  return new GoogleGenAI({
    apiKey,
    httpOptions: {
      headers: {
        "User-Agent": "aistudio-build",
      },
    },
  });
}

function sendSse(res: express.Response, payload: Record<string, unknown>) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: "30mb" }));

  app.get("/api/health", (_req, res) => {
    res.json({ ok: true });
  });

  // Streaming audio transcription via Gemini 3.5 Transcribe
  app.post("/api/ai/transcribe", async (req, res) => {
    res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
    res.setHeader("Cache-Control", "no-cache, no-transform");
    res.setHeader("Connection", "keep-alive");

    try {
      const { audioBase64, mimeType = "audio/wav", languageHint = "ar" } = req.body ?? {};
      if (!audioBase64 || typeof audioBase64 !== "string") {
        sendSse(res, { error: "يرجى توفير بيانات الصوت لتفريغها." });
        res.end();
        return;
      }

      const ai = getAiClient();
      const hintInstruction =
        languageHint === "en"
          ? "Transcribe this spoken audio accurately in English with clean punctuation. Add speaker breaks if multiple speakers appear. Output only the clean transcription text."
          : languageHint === "fa"
            ? "این فایل صوتی را با دقت و علائم نگارشی صحیح به فارسی (یا زبان گفتاری موجود در فایل) پیاده‌سازی کنید. فقط متن نهایی پیاده‌سازی را خروجی دهید."
            : "استمع جيداً لهذا التسجيل الصوتي وقم بتفريغه بدقة كاملة مع علامات الترقيم الصحيحة. إذا كان هناك أكثر من متحدث فافصل بين فقراتهم بوضوح. أخرج النص المفرّغ فقط بدون مقدمات.";

      const stream = await ai.models.generateContentStream({
        model: "gemini-3.5-transcribe",
        contents: {
          parts: [
            {
              inlineData: {
                mimeType,
                data: audioBase64,
              },
            },
            { text: hintInstruction },
          ],
        },
      });

      for await (const chunk of stream) {
        const text = chunk.text;
        if (text) {
          sendSse(res, { chunk: text });
        }
      }
      sendSse(res, { done: true });
      res.end();
    } catch (err: unknown) {
      const msg =
        err instanceof Error
          ? err.message
          : "حدث خطأ غير متوقع أثناء الاتصال بمحرك التفريغ الصوتي.";
      sendSse(res, { error: msg });
      res.end();
    }
  });

  // Grounded Audio Engineering Search Assistant
  app.post("/api/ai/search", async (req, res) => {
    res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
    res.setHeader("Cache-Control", "no-cache, no-transform");
    res.setHeader("Connection", "keep-alive");

    try {
      const { query, language = "ar" } = req.body ?? {};
      if (!query || typeof query !== "string" || !query.trim()) {
        sendSse(res, { error: "يرجى إدخال سؤالك أو الموضوع المطلوب البحث عنه." });
        res.end();
        return;
      }

      const ai = getAiClient();
      const systemHint =
        language === "en"
          ? "You are a professional audio & DSP sound engineer inside Sonix / Voice Pro. Answer clearly with practical studio EQ, compressor, reverb, and noise-removal numbers."
          : language === "fa"
            ? "شما مهندس حرفه‌ای صدا و DSP در استودیو Sonix / Voice Pro هستید. با اعداد دقیق EQ، کمپرسور و ریورب پاسخ عملی و روشن بدهید."
            : "أنت مهندس صوت خبير داخل استوديو Voice Pro / Sonix. أجب بشكل واضح ومنظم وعملي مع ذكر قيم عملية لـ EQ والضاغط والصدى عند الحاجة.";

      const stream = await ai.models.generateContentStream({
        model: "gemini-4-hydrogen",
        contents: `${systemHint}\n\nالسؤال / Question: ${query.trim()}`,
        config: {
          tools: [{ googleSearch: {} }],
        },
      });

      const seenSources = new Map<string, { title: string; uri: string; domain: string }>();
      const searchQueries = new Set<string>();

      for await (const chunk of stream) {
        const text = chunk.text;
        if (text) {
          sendSse(res, { chunk: text });
        }
        const meta = chunk.candidates?.[0]?.groundingMetadata;
        if (meta?.webSearchQueries) {
          for (const q of meta.webSearchQueries) {
            if (q) searchQueries.add(q);
          }
        }
        if (meta?.groundingChunks) {
          for (const gc of meta.groundingChunks) {
            const uri = gc.web?.uri;
            if (uri && !seenSources.has(uri)) {
              let domain = "";
              try {
                domain = new URL(uri).hostname.replace(/^www\./, "");
              } catch {
                domain = uri;
              }
              seenSources.set(uri, {
                title: gc.web?.title || domain,
                uri,
                domain,
              });
            }
          }
        }
      }

      sendSse(res, {
        done: true,
        searchQueries: Array.from(searchQueries),
        sources: Array.from(seenSources.values()),
      });
      res.end();
    } catch (err: unknown) {
      const msg =
        err instanceof Error ? err.message : "تعذر إتمام البحث الفوري الآن.";
      sendSse(res, { error: msg });
      res.end();
    }
  });

  // High-fidelity multi-dialect TTS synthesis returning WAV bytes for studio insertion
  app.post("/api/ai/tts", async (req, res) => {
    try {
      const { text, dialectTitle = "Arabic", dialectTag = "ar", speechRate = 1.0, pitch = 1.0 } =
        req.body ?? {};
      if (!text || typeof text !== "string" || !text.trim()) {
        res.status(400).json({ error: "Text is required." });
        return;
      }

      const ai = getAiClient();
      const paceDesc =
        speechRate < 0.85
          ? "slow and deliberate pace"
          : speechRate > 1.15
            ? "brisk and energetic pace"
            : "natural studio pace";
      const pitchDesc =
        pitch < 0.9
          ? "deep warm voice register"
          : pitch > 1.1
            ? "bright high voice register"
            : "balanced natural register";

      const voiceName = pitch < 0.92 ? "Charon" : pitch > 1.08 ? "Zephyr" : "Kore";

      const response = await ai.models.generateContent({
        model: "gemini-3.8-flash-lite-tts",
        contents: [
          {
            role: "user",
            parts: [
              {
                text: `Speak clearly as a studio narrator in ${dialectTitle} (${dialectTag}) with ${paceDesc} and ${pitchDesc}: ${text.trim()}`,
              },
            ],
          },
        ],
        config: {
          responseModalities: ["AUDIO"],
          speechConfig: {
            voiceConfig: {
              prebuiltVoiceConfig: { voiceName },
            },
          },
        },
      });

      const base64Audio =
        response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
      if (!base64Audio) {
        res.status(502).json({ error: "No audio returned by TTS model." });
        return;
      }

      res.json({
        audioBase64: base64Audio,
        mimeType: "audio/wav",
      });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "TTS synthesis failed.";
      res.status(500).json({ error: msg });
    }
  });

  if (process.env.NODE_ENV !== "production") {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true, hmr: false },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(__dirname, "dist");
    app.use(express.static(distDir));
    app.get("*splat", (_req, res) => {
      res.sendFile(path.join(distDir, "index.html"));
    });
  }

  app.listen(PORT, HOST, () => {
    console.log(`Voice Pro (Sonix) server listening on http://${HOST}:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error("Failed to start Voice Pro server:", err);
  process.exit(1);
});
