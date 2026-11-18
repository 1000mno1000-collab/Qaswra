# Sonix AI Voice Studio — GPU Inference Backend

هذا المجلد يحتوي على الخادم الخلفي الآمن (FastAPI + Docker) لتشغيل نماذج الذكاء الاصطناعي الصوتية الثقيلة التي تتطلب بطاقة NVIDIA GPU:

1. **`openbmb/VoxCPM2`** (Apache-2.0 • 2B params • 48kHz • تصميم الصوت بالوصف + الاستنساخ القابل للتحكم + 30 لغة بينها العربية)
2. **`audarai/Audar-TTS-V1-Flash`** (AudarAI Open License v1.0 • 24kHz • اللهجات العربية + وسوم المشاعر `[laughs]` / `[whispers]` / `[excited]`)

---

## متطلبات خادم GPU
- **بطاقة GPU:** NVIDIA بذاكرة **VRAM ≥ 12–16 GB** (مثل T4 16GB / L4 24GB / A10G / RTX 3090 أو أعلى).
- **البيئة:** CUDA 12.4+ (أو محرك Docker مع `nvidia-container-toolkit`)، وصورة الحاوية مبنية على `pytorch/pytorch:2.6.0-cuda12.4-cudnn9-devel` مع `GGML_CUDA=1` لبناء `llama-cpp-python` بدعم CUDA.
- **ملاحظة مهمة:** يجب تشغيل `uvicorn` بعامل واحد فقط (`--workers 1`) لتفادي تكرار تحميل أوزان 2B في VRAM، ويدعم `Dockerfile` متغير `$PORT` تلقائياً.

---

## متغيرات البيئة (Environment Variables)
لا تضع أي مفاتيح سرية داخل كود التطبيق؛ اضبطها على الخادم فقط:

| المتغير | الافتراضي | الوصف |
|---|---|---|
| `VOXCPM_MODEL_REPO` | `openbmb/VoxCPM2` | معرف نموذج VoxCPM2 على Hugging Face |
| `VOXCPM_LOAD_DENOISER` | `false` | تفعيل مزيل الضجيج المدمج لعينات الاستنساخ |
| `VOXCPM_ALLOW_CPU` | `false` | للسماح بالاختبار على CPU فقط (غير موصى به للإنتاج) |
| `SONIX_PRELOAD_MODELS` | `false` | اجعلها `true` لتحميل الأوزان عند بدء التشغيل وتفادي مهلة أول طلب |
| `SONIX_SERVER_BEARER` | فارغ | **موصى بتعيينه:** توكن سري يفرض الحماية عبر `X-Sonix-Bearer` / `Bearer` على نقاط التوليد |
| `SONIX_REQUIRE_AUTH` | `false` | اجعلها `true` لفرض توثيق Firebase ID Token عندما يكون `SONIX_SERVER_BEARER` فارغاً |
| `HF_TOKEN` | فارغ | توكن Hugging Face عند الحاجة لتنزيل النماذج المقيدة |
| `FIREBASE_PROJECT_ID` | `gen-lang-client-0021883952` | معرّف مشروع Firebase للتحقق من توكن المالك والعملاء |
| `SONIX_OWNER_EMAILS` | `1000iraq1000@gmail.com` | بريد المالك المعتمد لنقاط `/v1/admin/*` |

---

## التشغيل عبر Docker (الموصى به)
```bash
cd backend
docker build -t sonix-ai-voice-backend .
docker run --gpus all -p 8080:8080 \
  -v sonix_hf_cache:/app/.cache/huggingface \
  -e SONIX_PRELOAD_MODELS=true \
  -e SONIX_SERVER_BEARER="replace_with_strong_random_secret" \
  sonix-ai-voice-backend
```
*(أضف `-e HF_TOKEN="hf_..."` فقط إذا استدعت النماذج صلاحية وصول).*

أو مباشرة عبر Python:
```bash
cd backend
pip install -r requirements.txt
SONIX_PRELOAD_MODELS=true SONIX_SERVER_BEARER="replace_with_strong_random_secret" uvicorn app:app --host 0.0.0.0 --port 8080 --workers 1
```

---

## فحص الصحة واختبار الصوت الحقيقي بعد التشغيل على GPU
1. فحص الحالة:
   ```bash
   curl -fsS https://your-gpu-server/health
   ```
   تأكد من `"cuda_available": true` و`"voxcpm2_ready": true` و`"auth_required": true`.
2. اختبار توليد صوت فعلي وحفظه كملف WAV:
   ```bash
   curl -fsS -X POST https://your-gpu-server/v1/voice-studio/synthesize \
     -H "Content-Type: application/json" \
     -H "X-Sonix-Bearer: replace_with_strong_random_secret" \
     -d '{"engine":"voxcpm2","text":"مرحباً بكم في اختبار VoxCPM2 الفعلي.","language":"ar","dialect":"msa","style_instruction":"male adult speaker speaking Modern Standard Arabic with clear balanced warm voice"}' \
     --output test_voxcpm2.wav
   ```

## الربط بتطبيق الأندرويد
- التطبيق يفرض **HTTPS** (`cleartextTrafficPermitted="false"`) ويحوّل تلقائياً الروابط المدخلة بدون `https://` أو بصيغة `http://` إلى `https://`، لذا يجب تعريض الخادم عبر HTTPS بشهادة موثوقة (RunPod Proxy / Modal URL / Cloudflare Tunnel / Nginx TLS).
- افتح داخل التطبيق: **AI VOICE STUDIO ← السيرفر والربط**، وأدخل رابط `https://your-gpu-server` ورمز `SONIX_SERVER_BEARER` ثم اضغط **حفظ الإعدادات** أو **فحص الاتصال** مباشرة.
