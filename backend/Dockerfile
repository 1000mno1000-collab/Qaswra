FROM pytorch/pytorch:2.6.0-cuda12.4-cudnn9-devel

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/app/.cache/huggingface \
    VOXCPM_MODEL_REPO=openbmb/VoxCPM2 \
    VOXCPM_LOAD_DENOISER=false \
    SONIX_PRELOAD_MODELS=false \
    GGML_CUDA=1 \
    CMAKE_ARGS="-DGGML_CUDA=on" \
    PORT=8080

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    ffmpeg \
    git \
    curl \
    build-essential \
    cmake \
    ninja-build \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

COPY app.py edge_tts_client.py ./

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD sh -c "curl -fsS http://127.0.0.1:${PORT:-8080}/health || exit 1"

# Keep --workers 1 so 2B GPU weights are loaded once per container without duplicate VRAM usage
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-8080} --workers 1 --timeout-keep-alive 120 --proxy-headers --forwarded-allow-ips='*'"]
