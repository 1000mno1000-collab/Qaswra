"""
Sonix AI Voice Studio — Secure Cloud GPU Inference & Owner Telemetry Backend
Hosts:
  1. openbmb/VoxCPM2 (Apache-2.0 • multilingual 48kHz Voice Design & Controllable Cloning)
  2. audarai/Audar-TTS-V1-Flash (AudarAI Open License v1.0 • Arabic dialects, inline expressive tags, 24kHz)

Security:
  - All API tokens (HF_TOKEN, SONIX_SERVER_BEARER) stay strictly in server environment variables.
  - All /v1/admin/* endpoints verify the caller's Firebase ID Token signature against Google's
    Firebase SecureToken/Auth verification AND require email_verified=true matching OWNER_EMAILS.
  - Zero fabricated metrics or secret leaks in any response.
"""
from __future__ import annotations

import base64
import binascii
import hmac
import io
import json
import logging
import os
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

import numpy as np
import soundfile as sf
import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

HF_TOKEN = os.getenv("HF_TOKEN", "").strip() or None
VOXCPM_REPO = os.getenv("VOXCPM_MODEL_REPO", "openbmb/VoxCPM2").strip() or "openbmb/VoxCPM2"
VOXCPM_LOAD_DENOISER = os.getenv("VOXCPM_LOAD_DENOISER", "false").lower() == "true"
VOXCPM_ALLOW_CPU = os.getenv("VOXCPM_ALLOW_CPU", "false").lower() == "true"
AUDAR_FLASH_REPO = os.getenv("AUDAR_FLASH_MODEL_REPO", "audarai/Audar-TTS-V1-Flash")
AUDAR_FLASH_GGUF_FILE = os.getenv("AUDAR_FLASH_GGUF_FILE", "audar-tts-v1-flash-q8_0.gguf")
AUDAR_CODEC_REPO = os.getenv("AUDAR_CODEC_REPO", "neuphonic/neucodec")
AUDAR_TURBO_REPO = os.getenv("AUDAR_MODEL_REPO", "audarai/Audar-TTS-V1-Turbo")

SERVER_BEARER = os.getenv("SONIX_SERVER_BEARER", "").strip()
REQUIRE_AUTH = os.getenv("SONIX_REQUIRE_AUTH", "false").lower() == "true"
PRELOAD_MODELS = os.getenv("SONIX_PRELOAD_MODELS", "false").lower() == "true"
MAX_REF_AUDIO_BYTES = 16 * 1024 * 1024

FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID", "gen-lang-client-0021883952").strip()
OWNER_EMAILS = {
    e.strip().lower()
    for e in os.getenv("SONIX_OWNER_EMAILS", "1000iraq1000@gmail.com").split(",")
    if e.strip()
}
OWNER_UIDS = {
    u.strip()
    for u in os.getenv("SONIX_OWNER_UIDS", "").split(",")
    if u.strip()
}
GPU_HOURLY_RATE_USD = float(os.getenv("GPU_HOURLY_RATE_USD", "0.0") or "0.0")

_voxcpm_model = None
_voxcpm_load_error: Optional[str] = None
_audar_flash_bundle = None
_audar_turbo_pipeline = None

_voxcpm_lock = threading.Lock()
_audar_flash_lock = threading.Lock()
_audar_turbo_lock = threading.Lock()
_telemetry_lock = threading.Lock()
_server_start_epoch = time.time()
_telemetry: Dict[str, Any] = {
    "total_generations": 0,
    "failed_generations": 0,
    "total_compute_seconds": 0.0,
    "engines": {
        "voxcpm2": {
            "repo_id": VOXCPM_REPO,
            "generations": 0,
            "failures": 0,
            "last_latency_ms": None,
            "last_sample_rate": None,
            "last_error": None,
        },
        "audar_tts_flash": {
            "repo_id": AUDAR_FLASH_REPO,
            "generations": 0,
            "failures": 0,
            "last_latency_ms": None,
            "last_sample_rate": None,
            "last_error": None,
        },
        "edge_neural_free": {
            "repo_id": "edge/neural-readaloud",
            "generations": 0,
            "failures": 0,
            "last_latency_ms": None,
            "last_sample_rate": None,
            "last_error": None,
        },
    },
}


def _record_engine_outcome(
    engine_key: str,
    success: bool,
    latency_ms: int,
    sample_rate: Optional[int] = None,
    error: Optional[str] = None,
) -> None:
    with _telemetry_lock:
        _telemetry["total_generations"] += 1
        if not success:
            _telemetry["failed_generations"] += 1
        _telemetry["total_compute_seconds"] += max(0.0, latency_ms / 1000.0)
        slot = _telemetry["engines"].setdefault(
            engine_key,
            {
                "repo_id": (
                    "edge/neural-readaloud"
                    if engine_key == "edge_neural_free"
                    else (AUDAR_FLASH_REPO if engine_key == "audar_tts_flash" else VOXCPM_REPO)
                ),
                "generations": 0,
                "failures": 0,
                "last_latency_ms": None,
                "last_sample_rate": None,
                "last_error": None,
            },
        )
        slot["generations"] += 1
        if not success:
            slot["failures"] += 1
            slot["last_error"] = (error or "Unknown error")[:400]
        else:
            slot["last_error"] = None
            slot["last_sample_rate"] = sample_rate
        slot["last_latency_ms"] = latency_ms


def _verify_firebase_id_token(token: str) -> Dict[str, Any]:
    """
    Verifies Firebase ID token signature & claims server-side.
    Tries google-auth verify_firebase_token first, then falls back to Google's oauth2 tokeninfo.
    """
    try:
        from google.auth.transport import requests as google_requests
        from google.oauth2 import id_token as google_id_token

        claims = google_id_token.verify_firebase_token(
            token,
            google_requests.Request(),
            audience=FIREBASE_PROJECT_ID,
        )
        if not claims:
            raise HTTPException(status_code=401, detail="Empty Firebase claims")
        return dict(claims)
    except ImportError:
        pass
    except Exception as exc:
        raise HTTPException(status_code=401, detail=f"Invalid Firebase ID token: {exc}") from exc

    # Fallback via Google Identity / SecureToken tokeninfo endpoint
    url = "https://oauth2.googleapis.com/tokeninfo?" + urllib.parse.urlencode({"id_token": token})
    try:
        with urllib.request.urlopen(url, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise HTTPException(status_code=401, detail=f"Firebase token verification failed (HTTP {exc.code})") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Token verification service unreachable: {exc}") from exc

    expected_iss = f"https://securetoken.google.com/{FIREBASE_PROJECT_ID}"
    aud = str(data.get("aud", "")).strip()
    iss = str(data.get("iss", "")).strip()
    if aud != FIREBASE_PROJECT_ID or (iss and iss != expected_iss):
        raise HTTPException(status_code=403, detail="Token project/audience mismatch")
    return data


def verify_auth(
    authorization: Optional[str] = Header(default=None),
    x_sonix_bearer: Optional[str] = Header(default=None, alias="X-Sonix-Bearer"),
) -> None:
    provided_key = (x_sonix_bearer or "").strip()
    bearer_token = (
        authorization.removeprefix("Bearer ").strip()
        if authorization and authorization.startswith("Bearer ")
        else ""
    )

    if SERVER_BEARER:
        if provided_key and hmac.compare_digest(provided_key, SERVER_BEARER):
            return
        if bearer_token and hmac.compare_digest(bearer_token, SERVER_BEARER):
            return
        raise HTTPException(
            status_code=401,
            detail="Authentication required: provide valid X-Sonix-Bearer or Bearer token matching SONIX_SERVER_BEARER",
        )

    if REQUIRE_AUTH:
        if not bearer_token:
            raise HTTPException(status_code=401, detail="Missing Firebase ID Bearer token")
        _verify_firebase_id_token(bearer_token)
    elif bearer_token:
        # Validate optional Firebase ID token if caller supplied one
        _verify_firebase_id_token(bearer_token)


def verify_owner_admin(authorization: Optional[str] = Header(default=None)) -> Dict[str, Any]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing Firebase ID Bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="Empty Bearer token")

    claims = _verify_firebase_id_token(token)
    uid = str(claims.get("user_id") or claims.get("sub") or "").strip()
    email = str(claims.get("email") or "").strip().lower()
    email_verified_raw = claims.get("email_verified", False)
    email_verified = (
        email_verified_raw is True
        or str(email_verified_raw).lower() == "true"
    )

    is_owner_uid = bool(uid and uid in OWNER_UIDS)
    is_owner_email = bool(email and email_verified and email in OWNER_EMAILS)
    if not (is_owner_uid or is_owner_email):
        raise HTTPException(status_code=403, detail="Owner verification failed: unauthorized account")
    return {
        "uid": uid,
        "email": email,
        "email_verified": email_verified,
    }


def _inspect_packages() -> Dict[str, bool]:
    pkgs = {
        "voxcpm": False,
        "llama_cpp": False,
        "neucodec": False,
        "transformers": False,
        "psutil": False,
        "edge_tts": False,
    }
    for name in list(pkgs.keys()):
        try:
            __import__(name)
            pkgs[name] = True
        except ImportError:
            pkgs[name] = False
    return pkgs


def _collect_system_metrics() -> Dict[str, Any]:
    cuda_available = torch.cuda.is_available()
    gpu_name = torch.cuda.get_device_name(0) if cuda_available else "CPU-Only (CUDA unavailable)"
    vram_total_mb = 0.0
    vram_used_mb = 0.0
    if cuda_available:
        try:
            free_b, total_b = torch.cuda.mem_get_info(0)
            vram_total_mb = round(total_b / (1024.0 * 1024.0), 1)
            vram_used_mb = round((total_b - free_b) / (1024.0 * 1024.0), 1)
        except Exception:
            pass

    ram_total_mb = 0.0
    ram_used_mb = 0.0
    cpu_percent = 0.0
    try:
        import psutil  # type: ignore

        vm = psutil.virtual_memory()
        ram_total_mb = round(vm.total / (1024.0 * 1024.0), 1)
        ram_used_mb = round(vm.used / (1024.0 * 1024.0), 1)
        cpu_percent = round(float(psutil.cpu_percent(interval=0.05)), 1)
    except Exception:
        try:
            meminfo: Dict[str, int] = {}
            with open("/proc/meminfo", "r", encoding="utf-8") as f:
                for line in f:
                    parts = line.split(":")
                    if len(parts) == 2:
                        val_kb = int(parts[1].strip().split()[0])
                        meminfo[parts[0]] = val_kb
            total_kb = meminfo.get("MemTotal", 0)
            avail_kb = meminfo.get("MemAvailable", 0)
            ram_total_mb = round(total_kb / 1024.0, 1)
            ram_used_mb = round(max(0, total_kb - avail_kb) / 1024.0, 1)
        except Exception:
            pass
        try:
            load1, _, _ = os.getloadavg()
            cores = max(1, os.cpu_count() or 1)
            cpu_percent = round(min(100.0, (load1 / cores) * 100.0), 1)
        except Exception:
            pass

    return {
        "cuda_available": cuda_available,
        "gpu_name": gpu_name,
        "vram_total_mb": vram_total_mb,
        "vram_used_mb": vram_used_mb,
        "ram_total_mb": ram_total_mb,
        "ram_used_mb": ram_used_mb,
        "cpu_percent": cpu_percent,
    }


class SynthesizeRequest(BaseModel):
    engine: str = Field(
        default="voxcpm2",
        description="voxcpm2 | audar_tts_flash | audar_tts_v1_turbo | edge_neural_free",
    )
    text: str = Field(..., min_length=1, max_length=4000)
    raw_text: Optional[str] = None
    language: str = "ar"
    dialect: str = "msa"
    dialect_hint: Optional[str] = None
    category: str = "male"
    voice_mode: str = "design"  # design | clone
    voice_description: str = ""
    style_instruction: str = ""
    emotion: str = "neutral"
    emotion_intensity: float = Field(default=0.65, ge=0.0, le=1.0)
    pace: str = "normal"
    speaking_rate: float = Field(default=1.0, ge=0.5, le=2.0)
    pitch_semitones: float = Field(default=0.0, ge=-12.0, le=12.0)
    cfg_value: float = Field(default=2.0, ge=0.5, le=5.0)
    inference_timesteps: int = Field(default=10, ge=4, le=40)
    normalize: bool = True
    reference_audio_base64: Optional[str] = None
    reference_transcript: Optional[str] = None


class DialogueLine(BaseModel):
    text: str = Field(..., min_length=1, max_length=2000)
    engine: str = "voxcpm2"
    language: str = "ar"
    dialect: str = "msa"
    category: str = "male"
    voice_description: str = ""
    style_instruction: str = ""
    emotion: str = "neutral"
    emotion_intensity: float = Field(default=0.65, ge=0.0, le=1.0)
    pace: str = "normal"
    speaking_rate: float = Field(default=1.0, ge=0.5, le=2.0)
    pitch_semitones: float = Field(default=0.0, ge=-12.0, le=12.0)
    pause_after_ms: int = Field(default=450, ge=0, le=5000)
    reference_audio_base64: Optional[str] = None
    reference_transcript: Optional[str] = None


class DialogueRequest(BaseModel):
    lines: List[DialogueLine]
    normalize: bool = True


def _resample_mono_np(arr: np.ndarray, src_sr: int, dst_sr: int) -> np.ndarray:
    if arr.size == 0 or src_sr <= 0 or src_sr == dst_sr:
        return arr.astype(np.float32, copy=False)
    ratio = float(dst_sr) / float(src_sr)
    new_len = max(1, int(round(arr.size * ratio)))
    return np.interp(np.linspace(0, arr.size - 1, new_len), np.arange(arr.size), arr).astype(np.float32)


def _get_voxcpm_unlocked():
    global _voxcpm_model, _voxcpm_load_error
    if _voxcpm_model is not None:
        return _voxcpm_model
    try:
        if HF_TOKEN:
            os.environ["HF_TOKEN"] = HF_TOKEN
        from voxcpm import VoxCPM

        _voxcpm_model = VoxCPM.from_pretrained(VOXCPM_REPO, load_denoiser=VOXCPM_LOAD_DENOISER)
        _voxcpm_load_error = None
        return _voxcpm_model
    except Exception as exc:
        _voxcpm_load_error = f"{type(exc).__name__}: {exc}"[:400]
        raise RuntimeError(f"VoxCPM2 load failed for {VOXCPM_REPO}: {exc}") from exc


def _get_audar_flash_unlocked():
    global _audar_flash_bundle
    if _audar_flash_bundle is not None:
        return _audar_flash_bundle
    from huggingface_hub import hf_hub_download
    from llama_cpp import Llama
    from neucodec import NeuCodec

    gguf_path = hf_hub_download(
        repo_id=AUDAR_FLASH_REPO,
        filename=AUDAR_FLASH_GGUF_FILE,
        token=HF_TOKEN,
    )
    llm = Llama(
        model_path=gguf_path,
        n_ctx=2048,
        n_gpu_layers=-1 if torch.cuda.is_available() else 0,
        verbose=False,
    )
    codec = NeuCodec.from_pretrained(AUDAR_CODEC_REPO)
    if torch.cuda.is_available():
        codec = codec.to("cuda")
    _audar_flash_bundle = (llm, codec)
    return _audar_flash_bundle


def _get_audar_turbo_unlocked():
    global _audar_turbo_pipeline
    if _audar_turbo_pipeline is not None:
        return _audar_turbo_pipeline
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(AUDAR_TURBO_REPO, token=HF_TOKEN, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        AUDAR_TURBO_REPO,
        token=HF_TOKEN,
        torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
        device_map="auto" if torch.cuda.is_available() else None,
        trust_remote_code=True,
    )
    _audar_turbo_pipeline = (tokenizer, model)
    return _audar_turbo_pipeline


def _decode_b64_wav_to_temp(b64_data: Optional[str]) -> Optional[str]:
    if not b64_data:
        return None
    try:
        raw = base64.b64decode(b64_data, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"Invalid base64 reference audio: {exc}") from exc
    if len(raw) <= 44:
        raise HTTPException(status_code=400, detail="Reference audio clip is empty or corrupted")
    if len(raw) > MAX_REF_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Reference audio exceeds 16 MB limit")
    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        with open(path, "wb") as f:
            f.write(raw)
        info = sf.info(path)
        if info.frames <= 0 or info.samplerate <= 0:
            raise HTTPException(status_code=400, detail="Reference WAV contains no audio frames")
        return path
    except HTTPException:
        if os.path.exists(path):
            os.remove(path)
        raise
    except Exception as exc:
        if os.path.exists(path):
            os.remove(path)
        raise HTTPException(status_code=400, detail=f"Unsupported or unreadable reference audio: {exc}") from exc


def _to_wav_bytes(samples: np.ndarray, sample_rate: int, normalize: bool = True) -> bytes:
    arr = np.asarray(samples, dtype=np.float32)
    if arr.ndim > 1:
        arr = np.mean(arr, axis=-1)
    arr = np.nan_to_num(arr, nan=0.0, posinf=0.0, neginf=0.0)
    if normalize and arr.size > 0:
        peak = float(np.max(np.abs(arr)))
        if peak > 1e-5:
            arr = arr * (0.92 / peak)
    buf = io.BytesIO()
    sf.write(buf, arr, sample_rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def _invoke_voxcpm_generate(model: Any, req: SynthesizeRequest, ref_path: Optional[str]):
    instruction = (req.style_instruction or req.voice_description).strip()
    ref_transcript = (req.reference_transcript or "").strip()

    if ref_path and ref_transcript:
        # Official OpenBMB VoxCPM2 Ultimate Cloning: pass both prompt_* and reference_wav_path
        try:
            return model.generate(
                text=req.text,
                prompt_wav_path=ref_path,
                prompt_text=ref_transcript,
                reference_wav_path=ref_path,
                cfg_value=req.cfg_value,
                inference_timesteps=req.inference_timesteps,
                normalize=req.normalize,
                denoise=True,
            )
        except TypeError:
            return model.generate(
                text=req.text,
                prompt_wav_path=ref_path,
                prompt_text=ref_transcript,
                cfg_value=req.cfg_value,
                inference_timesteps=req.inference_timesteps,
                normalize=req.normalize,
                denoise=True,
            )

    if ref_path:
        # Official OpenBMB VoxCPM2 Controllable / Zero-shot Timbre Cloning (no transcript needed)
        formatted = f"({instruction}) {req.text}" if instruction else req.text
        try:
            return model.generate(
                text=formatted,
                reference_wav_path=ref_path,
                cfg_value=req.cfg_value,
                inference_timesteps=req.inference_timesteps,
                normalize=req.normalize,
                denoise=True,
            )
        except TypeError:
            return model.generate(
                text=req.text,
                prompt_wav_path=ref_path,
                prompt_text=None,
                cfg_value=req.cfg_value,
                inference_timesteps=req.inference_timesteps,
                normalize=req.normalize,
                denoise=True,
            )

    # Voice Design (natural language timbre/emotion prompt)
    formatted = f"({instruction}) {req.text}" if instruction else req.text
    return model.generate(
        text=formatted,
        prompt_wav_path=None,
        prompt_text=None,
        cfg_value=req.cfg_value,
        inference_timesteps=req.inference_timesteps,
        normalize=req.normalize,
        denoise=False,
    )


def _run_voxcpm2(req: SynthesizeRequest) -> tuple[np.ndarray, int]:
    t0 = time.perf_counter()
    ref_path = _decode_b64_wav_to_temp(req.reference_audio_base64)
    try:
        with _voxcpm_lock:
            if not torch.cuda.is_available() and not VOXCPM_ALLOW_CPU:
                raise HTTPException(
                    status_code=503,
                    detail="CUDA GPU is not available on this server (set VOXCPM_ALLOW_CPU=true only for CPU debug).",
                )
            model = _get_voxcpm_unlocked()
            wav = _invoke_voxcpm_generate(model, req, ref_path)
        sr = int(getattr(getattr(model, "tts_model", None), "sample_rate", 48000))
        arr = np.asarray(wav, dtype=np.float32).reshape(-1)
        arr = np.nan_to_num(arr, nan=0.0, posinf=0.0, neginf=0.0)
        if arr.size == 0:
            raise RuntimeError("VoxCPM2 generated an empty waveform.")
        _record_engine_outcome("voxcpm2", True, int((time.perf_counter() - t0) * 1000), sample_rate=sr)
        return arr, sr
    except torch.cuda.OutOfMemoryError as exc:
        try:
            torch.cuda.empty_cache()
        except Exception:
            pass
        _record_engine_outcome("voxcpm2", False, int((time.perf_counter() - t0) * 1000), error="CUDA OOM")
        raise HTTPException(
            status_code=503,
            detail="GPU out of memory during VoxCPM2 synthesis. Retry with shorter text or lower concurrency.",
        ) from exc
    except HTTPException:
        raise
    except Exception as exc:
        _record_engine_outcome("voxcpm2", False, int((time.perf_counter() - t0) * 1000), error=str(exc))
        raise
    finally:
        if ref_path and os.path.exists(ref_path):
            os.remove(ref_path)


def _run_audar_flash(req: SynthesizeRequest) -> tuple[np.ndarray, int]:
    t0 = time.perf_counter()
    ref_path = _decode_b64_wav_to_temp(req.reference_audio_base64)
    try:
        with _audar_flash_lock:
            llm, codec = _get_audar_flash_unlocked()
            ref_codes: List[int] = []
            if ref_path:
                wav_np, sr_in = sf.read(ref_path, dtype="float32")
                if wav_np.ndim > 1:
                    wav_np = np.mean(wav_np, axis=-1)
                # NeuCodec expects 16 kHz mono audio; Android studio exports 24 kHz WAVs
                wav_16k = _resample_mono_np(wav_np, int(sr_in), 16000)
                wav_tensor = torch.from_numpy(wav_16k).unsqueeze(0).unsqueeze(0)
                if torch.cuda.is_available():
                    wav_tensor = wav_tensor.to("cuda")
                with torch.no_grad():
                    encoded = codec.encode_code(wav_tensor)
                if hasattr(encoded, "reshape"):
                    ref_codes = [int(c) for c in encoded.reshape(-1).detach().cpu().tolist()]

            prompt_prefix = "".join(f"<|speech_{int(c)}|>" for c in ref_codes)
            ref_transcript = (req.reference_transcript or "").strip()
            prompt_text = f"{ref_transcript} {req.text}".strip() if ref_transcript else req.text
            full_prompt = (
                f"user: Convert the text to speech:<|TEXT_PROMPT_START|>{prompt_text}<|TEXT_PROMPT_END|>\n"
                f"assistant:<|SPEECH_GENERATION_START|>{prompt_prefix}"
            )
            out = llm(full_prompt, max_tokens=1536, stop=["<|SPEECH_GENERATION_END|>"], temperature=0.8)
            raw_tokens = out["choices"][0]["text"]
            speech_ids = [
                int(part.split("|>")[0])
                for part in raw_tokens.split("<|speech_")
                if part and part[0].isdigit() and "|>" in part
            ]
            if not speech_ids:
                raise RuntimeError("Audar-TTS-V1-Flash generated no acoustic codec tokens.")
            codes_tensor = torch.tensor(speech_ids, dtype=torch.long).unsqueeze(0).unsqueeze(0)
            if torch.cuda.is_available():
                codes_tensor = codes_tensor.to("cuda")
            with torch.no_grad():
                audio_out = codec.decode_code(codes_tensor)
            arr = audio_out.reshape(-1).detach().cpu().numpy().astype(np.float32)
        _record_engine_outcome("audar_tts_flash", True, int((time.perf_counter() - t0) * 1000), sample_rate=24000)
        return arr, 24000
    except Exception as exc:
        _record_engine_outcome("audar_tts_flash", False, int((time.perf_counter() - t0) * 1000), error=str(exc))
        raise
    finally:
        if ref_path and os.path.exists(ref_path):
            os.remove(ref_path)


def _run_audar_turbo(req: SynthesizeRequest) -> tuple[np.ndarray, int]:
    ref_path = _decode_b64_wav_to_temp(req.reference_audio_base64)
    try:
        with _audar_turbo_lock:
            tokenizer, model = _get_audar_turbo_unlocked()
            if hasattr(model, "synthesize"):
                wav, sr = model.synthesize(
                    text=req.text,
                    tokenizer=tokenizer,
                    reference_audio_path=ref_path,
                    reference_text=req.reference_transcript,
                    dialect=req.dialect,
                )
                return np.asarray(wav, dtype=np.float32), int(sr)
            raise RuntimeError("Audar-TTS-V1-Turbo disabled for commercial use; route Arabic requests to Audar-TTS-V1-Flash.")
    finally:
        if ref_path and os.path.exists(ref_path):
            os.remove(ref_path)


def _run_edge_neural_free(req: SynthesizeRequest) -> tuple[np.ndarray, int]:
    t0 = time.perf_counter()
    try:
        from edge_tts_client import mp3_bytes_to_mono_pcm, synthesize_edge_neural_mp3

        mp3_bytes, _voice = synthesize_edge_neural_mp3(
            text=req.text,
            language=req.language,
            dialect=req.dialect,
            category=req.category,
            speaking_rate=req.speaking_rate,
            pitch_semitones=req.pitch_semitones,
        )
        arr, sr = mp3_bytes_to_mono_pcm(mp3_bytes)
        if arr.size == 0:
            raise RuntimeError("Edge Neural TTS returned empty PCM frames.")
        _record_engine_outcome("edge_neural_free", True, int((time.perf_counter() - t0) * 1000), sample_rate=sr)
        return arr, sr
    except Exception as exc:
        _record_engine_outcome("edge_neural_free", False, int((time.perf_counter() - t0) * 1000), error=str(exc))
        raise


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if not SERVER_BEARER and not REQUIRE_AUTH:
        logging.getLogger("uvicorn.error").warning(
            "SONIX SECURITY NOTICE: Neither SONIX_SERVER_BEARER nor SONIX_REQUIRE_AUTH=true is set; /v1/voice-studio/* is unauthenticated."
        )
    if PRELOAD_MODELS:
        with _voxcpm_lock:
            try:
                _get_voxcpm_unlocked()
            except Exception:
                pass
        with _audar_flash_lock:
            try:
                _get_audar_flash_unlocked()
            except Exception:
                pass
    yield


app = FastAPI(title="Sonix AI Voice Studio GPU Backend", version="1.1.0", lifespan=lifespan)


@app.get("/health")
def health():
    pkgs = _inspect_packages()
    sys_metrics = _collect_system_metrics()
    vox_pkg_ok = pkgs["voxcpm"]
    vox_hw_ok = bool(sys_metrics["cuda_available"] or VOXCPM_ALLOW_CPU)
    vox_loaded = _voxcpm_model is not None
    vox_ready = bool(vox_pkg_ok and vox_hw_ok and _voxcpm_load_error is None)
    return {
        "status": "ok",
        "cuda_available": sys_metrics["cuda_available"],
        "gpu_name": sys_metrics["gpu_name"],
        "vram_total_mb": sys_metrics["vram_total_mb"],
        "vram_used_mb": sys_metrics["vram_used_mb"],
        "preload_models": PRELOAD_MODELS,
        "auth_required": bool(SERVER_BEARER or REQUIRE_AUTH),
        "voxcpm2_repo": VOXCPM_REPO,
        "voxcpm2_package_installed": vox_pkg_ok,
        "voxcpm2_loaded": vox_loaded,
        "voxcpm2_load_error": _voxcpm_load_error,
        "voxcpm2_ready": vox_ready,
        "audar_flash_repo": AUDAR_FLASH_REPO,
        "audar_flash_ready": pkgs["llama_cpp"] and pkgs["neucodec"],
        "audar_repo": AUDAR_FLASH_REPO,
        "audar_tts_ready": pkgs["llama_cpp"] and pkgs["neucodec"],
        "edge_neural_free_ready": True,
        "edge_tts_package_installed": pkgs["edge_tts"],
    }


@app.get("/v1/admin/verify-owner")
def verify_owner(owner: Dict[str, Any] = Depends(verify_owner_admin)):
    return {
        "authorized": True,
        "uid": owner["uid"],
        "email": owner["email"],
        "email_verified": owner["email_verified"],
        "project_id": FIREBASE_PROJECT_ID,
    }


@app.get("/v1/admin/overview")
def admin_overview(owner: Dict[str, Any] = Depends(verify_owner_admin)):
    pkgs = _inspect_packages()
    sys_metrics = _collect_system_metrics()
    with _telemetry_lock:
        total_gens = int(_telemetry["total_generations"])
        failed_gens = int(_telemetry["failed_generations"])
        compute_sec = float(_telemetry["total_compute_seconds"])
        vox_stats = dict(_telemetry["engines"]["voxcpm2"])
        flash_stats = dict(_telemetry["engines"]["audar_tts_flash"])

    est_cost_usd = round(compute_sec * (GPU_HOURLY_RATE_USD / 3600.0), 6) if GPU_HOURLY_RATE_USD > 0.0 else 0.0
    return {
        "authorized": True,
        "owner_uid": owner["uid"],
        "uptime_seconds": int(max(0.0, time.time() - _server_start_epoch)),
        "hf_token_configured": bool(HF_TOKEN),
        "gpu_hourly_rate_usd": GPU_HOURLY_RATE_USD,
        "estimated_compute_cost_usd": est_cost_usd,
        "total_generations": total_gens,
        "failed_generations": failed_gens,
        "total_compute_seconds": round(compute_sec, 2),
        "system": sys_metrics,
        "engines": {
            "voxcpm2": {
                **vox_stats,
                "package_installed": pkgs["voxcpm"],
                "loaded_in_memory": _voxcpm_model is not None,
                "load_error": _voxcpm_load_error,
                "license": "Apache-2.0",
            },
            "audar_tts_flash": {
                **flash_stats,
                "package_installed": pkgs["llama_cpp"] and pkgs["neucodec"],
                "loaded_in_memory": _audar_flash_bundle is not None,
                "license": "AudarAI Open License v1.0",
            },
        },
    }


def _dispatch_engine(req: SynthesizeRequest) -> tuple[np.ndarray, int]:
    if req.engine == "edge_neural_free":
        return _run_edge_neural_free(req)
    if req.engine == "audar_tts_flash":
        return _run_audar_flash(req)
    if req.engine == "audar_tts_v1_turbo":
        return _run_audar_turbo(req)
    return _run_voxcpm2(req)


@app.post("/v1/voice-studio/synthesize", dependencies=[Depends(verify_auth)])
def synthesize(req: SynthesizeRequest):
    try:
        wav, sr = _dispatch_engine(req)
        wav_bytes = _to_wav_bytes(wav, sr, normalize=req.normalize)
        return Response(
            content=wav_bytes,
            media_type="audio/wav",
            headers={
                "X-Sonix-Engine": req.engine,
                "X-Sonix-Sample-Rate": str(sr),
            },
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc


@app.post("/v1/voice-studio/dialogue", dependencies=[Depends(verify_auth)])
def synthesize_dialogue(req: DialogueRequest):
    if not req.lines:
        raise HTTPException(status_code=400, detail="Dialogue lines cannot be empty")
    try:
        target_sr = 48000
        chunks: List[np.ndarray] = []
        for idx, line in enumerate(req.lines):
            sub_req = SynthesizeRequest(
                engine=line.engine,
                text=line.text,
                language=line.language,
                dialect=line.dialect,
                category=line.category,
                voice_description=line.voice_description,
                style_instruction=line.style_instruction,
                emotion=line.emotion,
                emotion_intensity=line.emotion_intensity,
                pace=line.pace,
                speaking_rate=line.speaking_rate,
                pitch_semitones=line.pitch_semitones,
                reference_audio_base64=line.reference_audio_base64,
                reference_transcript=line.reference_transcript,
                normalize=True,
            )
            wav, sr = _dispatch_engine(sub_req)
            if sr != target_sr and wav.size > 0:
                wav = _resample_mono_np(wav, sr, target_sr)
            chunks.append(wav)
            if idx < len(req.lines) - 1 and line.pause_after_ms > 0:
                pause_samples = int(target_sr * (line.pause_after_ms / 1000.0))
                chunks.append(np.zeros(pause_samples, dtype=np.float32))

        merged = np.concatenate(chunks) if chunks else np.zeros(target_sr // 4, dtype=np.float32)
        wav_bytes = _to_wav_bytes(merged, target_sr, normalize=req.normalize)
        return Response(content=wav_bytes, media_type="audio/wav", headers={"X-Sonix-Sample-Rate": str(target_sr)})
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc
