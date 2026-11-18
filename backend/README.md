# Sonix AI Voice Studio — GPU Inference Backend

هذا المجلد يحتوي على الخادم الخلفي الآمن لتشغيل نماذج الذكاء الاصطناعي الصوتية الثقيلة التي تحتاج GPU ولا يمكن تشغيلها مباشرة على الهاتف أو عبر Inference Providers المجانية على Hugging Face:

1. **FenomAI/VoxCPM2** (2B params — 48kHz Voice Design & Controllable Voice Cloning)
2. **audarai/Audar-TTS-V1-Turbo** (1.64B params — Arabic-first dialects & `[laughs]`/`[whispers]`/`[excited]` tags)

## التشغيل على خادم GPU (RunPod / Modal / Cloud VM / Docker)

```bash
cd backend
export HF_TOKEN="hf_your_server_token_only"
export SONIX_SERVER_BEARER="" # اختياري لحماية الخادم
docker build -t sonix-ai-voice-backend .
docker run --gpus all -p 8080:8080 -e HF_TOKEN="$HF_TOKEN" sonix-ai-voice-backend
```

أو مباشرة عبر Python:

```bash
pip install -r requirements.txt
HF_TOKEN="hf_..." uvicorn app:app --host 0.0.0.0 --port 8080
```

## الربط بالتطبيق
- يجب تعريض الخادم عبر رابط **HTTPS** (عبر Cloudflare Tunnel / Reverse Proxy / RunPod Proxy / Modal URL).
- افتح داخل التطبيق: **AI VOICE STUDIO ✦ ← إعدادات السيرفر والربط** وأدخل رابط `https://your-gpu-server` ثم اضغط **فحص الاتصال بالخادم والمحركات**.
- لا يتم وضع `HF_TOKEN` داخل تطبيق الأندرويد نهائياً.
