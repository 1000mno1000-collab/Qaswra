"""
Pure-Python Edge Neural Read Aloud TTS client (zero API keys, zero credit card).
Uses `edge_tts` >= 7.3.0 when installed, or falls back directly to the Microsoft Edge
Read Aloud WebSocket + Sec-MS-GEC v2 handshake using Python's standard library.
"""
from __future__ import annotations

import asyncio
import base64
import email.utils
from functools import lru_cache
import html
import os
import secrets
import shutil
import socket
import ssl
import struct
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from typing import Dict, Optional, Tuple

TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
CHROMIUM_FULL_VERSION = "143.0.3650.75"
CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.split(".", maxsplit=1)[0]
SEC_MS_GEC_VERSION = f"2-{CHROMIUM_FULL_VERSION}"
EDGE_HOST = "speech.platform.bing.com"
EDGE_WS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
WIN_EPOCH = 11644473600

_U32_MASK = 0xFFFFFFFF
_SHA256_INITIAL = (
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
)
_SHA256_STATE = (
    0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
    0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
)
_SHA256_SCHEDULE = (
    0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
    0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
    0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
    0xD258B060, 0x72766564,
)
_SHA256_ROUND_CONSTANTS = (
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
)
_SHA256_ADJUSTMENT = 0xC9DC06A7
_SHA256_BLOCK_CONSTANTS = _SHA256_ROUND_CONSTANTS[:14] + (_SHA256_ADJUSTMENT,) + _SHA256_ROUND_CONSTANTS[15:]
_SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
_SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0
_CS64_COEFFICIENTS = (
    (0xE82E91B1, 0xB32700D0), (0x44DE24C6, 0x88DFF51D), (0x3BEB65BC, 0x29514038), (0xC89C5933, 0xB328CBDF),
    (0x8D1EC211, 0xD405B4D5), (0x314EF5D5, 0x26E55F3C), (0xE92491B1, 0x607801C6), (0xF0A5B9D5, 0x82B2C1FB),
    (0x06064CF3, 0xFA51A3DA), (0xDA79FA60, 0x207CC464), (0xBED20560, 0x3C3C74F3), (0x89F9C9F8, 0xD2D14B61),
    (0x35FA38C6, 0xBB04A2C1), (0xCB49E3C6, 0xC5626167),
)
_CS64_FACTOR = 0x82893933
_CS64_ADJUSTMENT0 = 0xF2F90B1A
_CS64_ADJUSTMENT1 = 0x0B1A0000

_clock_skew_seconds: float = 0.0

ARABIC_VOICE_MAP: Dict[Tuple[str, str], str] = {
    ("iraqi", "female"): "ar-IQ-RanaNeural",
    ("iraqi", "male"): "ar-IQ-BasselNeural",
    ("gulf", "female"): "ar-AE-FatimaNeural",
    ("gulf", "male"): "ar-AE-HamdanNeural",
    ("saudi", "female"): "ar-SA-ZariyahNeural",
    ("saudi", "male"): "ar-SA-HamedNeural",
    ("levantine", "female"): "ar-SY-AmanyNeural",
    ("levantine", "male"): "ar-SY-LaithNeural",
    ("egyptian", "female"): "ar-EG-SalmaNeural",
    ("egyptian", "male"): "ar-EG-ShakirNeural",
    ("moroccan", "female"): "ar-MA-MounaNeural",
    ("moroccan", "male"): "ar-MA-JamalNeural",
    ("jordanian", "female"): "ar-JO-SanaNeural",
    ("jordanian", "male"): "ar-JO-TaimNeural",
    ("msa", "female"): "ar-SA-ZariyahNeural",
    ("msa", "male"): "ar-SA-HamedNeural",
}


def resolve_edge_voice(language: str, dialect: str, category: str) -> str:
    is_female = category in ("female", "child")
    gender_key = "female" if is_female else "male"
    if language == "ar":
        return ARABIC_VOICE_MAP.get((dialect, gender_key), "ar-SA-ZariyahNeural" if is_female else "ar-SA-HamedNeural")
    lang_map = {
        "en": "en-US-EmmaMultilingualNeural" if is_female else "en-US-AndrewMultilingualNeural",
        "fa": "fa-IR-DilaraNeural" if is_female else "fa-IR-FaridNeural",
        "tr": "tr-TR-EmelNeural" if is_female else "tr-TR-AhmetNeural",
        "fr": "fr-FR-DeniseNeural" if is_female else "fr-FR-HenriNeural",
        "es": "es-ES-ElviraNeural" if is_female else "es-ES-AlvaroNeural",
        "de": "de-DE-KatjaNeural" if is_female else "de-DE-ConradNeural",
    }
    return lang_map.get(language, "ar-SA-HamedNeural")


def _rol16(v: int) -> int:
    v &= _U32_MASK
    return ((v << 16) | (v >> 16)) & _U32_MASK


def _rotr32(v: int, n: int) -> int:
    v &= _U32_MASK
    return ((v >> n) | (v << (32 - n))) & _U32_MASK


def _sigma0(v: int) -> int:
    return ((v >> 7) ^ (v << 25) ^ (v >> 18) ^ (v << 14) ^ (v >> 3)) & _U32_MASK


def _sigma1(v: int) -> int:
    return ((v >> 17) ^ (v << 15) ^ (v >> 19) ^ (v << 13) ^ (v >> 10)) & _U32_MASK


def _words_le4_even(data: bytes):
    padded = data + b"\x00" * ((-len(data)) % 4)
    if not padded:
        return []
    words = list(struct.unpack(f"<{len(padded) // 4}I", padded))
    if len(words) % 2:
        words.append(0)
    return words


def _sha256_rounds(state, words, first_round: int = 0):
    a, b, c, d, e, f, g, h = state
    mask = _U32_MASK
    constants = _SHA256_BLOCK_CONSTANTS if first_round == 14 else _SHA256_ROUND_CONSTANTS
    for idx in range(first_round, 64):
        s1 = (_rotr32(e, 6) ^ _rotr32(e, 11) ^ _rotr32(e, 25)) & mask
        ch = ((e & f) ^ ((~e & mask) & g)) & mask
        t1 = (h + s1 + ch + constants[idx] + words[idx]) & mask
        s0 = (_rotr32(a, 2) ^ _rotr32(a, 13) ^ _rotr32(a, 22)) & mask
        maj = ((a & b) ^ (a & c) ^ (b & c)) & mask
        t2 = (s0 + maj) & mask
        h, g, f, e, d, c, b, a = g, f, e, (d + t1) & mask, c, b, a, (t1 + t2) & mask
    return (a, b, c, d, e, f, g, h)


def _expand_sha256_schedule(words, start: int = 16) -> None:
    mask = _U32_MASK
    for idx in range(start, 64):
        s0 = _sigma0(words[idx - 15])
        s1 = _sigma1(words[idx - 2])
        words.append((words[idx - 16] + s0 + words[idx - 7] + s1) & mask)


@lru_cache(maxsize=2)
def _sha256_init(salt: bytes):
    mask = _U32_MASK
    words = [0] * 14 + [int.from_bytes(salt[:3], "big"), int.from_bytes(salt[3:7], "big")]
    for idx in range(16, 30):
        val = _SHA256_SCHEDULE[idx - 16]
        if idx - 2 >= 14:
            val += (_sigma1(words[idx - 2]) ^ _SHA256_SIGMA1_ADJUSTMENT) if idx == 16 else _sigma1(words[idx - 2])
        if idx - 7 >= 14:
            val += words[idx - 7]
        if idx - 15 >= 14:
            val += (_sigma0(words[idx - 15]) ^ _SHA256_SIGMA0_ADJUSTMENT) if idx == 29 else _sigma0(words[idx - 15])
        words.append(val & mask)
    words.append(
        (_sigma1(words[28]) + words[23] + _sigma0(words[15]) + words[14] + _SHA256_ADJUSTMENT - _SHA256_ROUND_CONSTANTS[14]) & mask
    )
    _expand_sha256_schedule(words, 31)
    working = _sha256_rounds(_SHA256_STATE, words, 14)
    return tuple((x + y) & mask for x, y in zip(_SHA256_INITIAL, working))


def _sha256_digest(data: bytes) -> bytes:
    mask = _U32_MASK
    length = 57 + len(data)
    padded = data[7:] + b"\x80" + b"\x00" * ((55 - length) % 64) + struct.pack(">Q", length * 8)
    state = _sha256_init(data[:7])
    for offset in range(0, len(padded), 64):
        words = list(struct.unpack(">16I", padded[offset : offset + 64]))
        _expand_sha256_schedule(words)
        working = _sha256_rounds(state, words)
        state = tuple((x + y) & mask for x, y in zip(state, working))
    return struct.pack(">8I", *state)


def _cs64_digest(data: bytes, digest: bytes) -> Tuple[int, int]:
    mask = _U32_MASK
    key_a, key_b = struct.unpack_from("<II", digest)
    key_a |= 1
    key_b |= 1
    factors = ((key_a * _CS64_FACTOR) & mask, (key_b * _CS64_FACTOR) & mask)
    terms = [(coeff * factors[idx % 2]) & mask for idx, (coeff, _) in enumerate(_CS64_COEFFICIENTS)]
    offsets = [off for _, off in _CS64_COEFFICIENTS]
    for idx, word in enumerate(_words_le4_even(b"\x00" + data)):
        term = word * (key_a if idx % 2 == 0 else key_b)
        off = _rol16(word) * (0xEF0569FB if idx % 2 == 0 else 0xC31713DB)
        if idx == 0:
            term += _CS64_ADJUSTMENT0 * factors[0]
            off += _CS64_ADJUSTMENT1
        terms.append(term & mask)
        offsets.append(off & mask)
    swap_acc = swap_last = state = acc = 0
    for idx in range(0, len(terms), 2):
        x, y = terms[idx], terms[idx + 1]
        t = _rol16((offsets[idx] + x) & mask)
        q = (t * 0x76F50F34) & mask
        swap_acc += (_rol16(q) * 0xC3EFEA97 + q) & mask
        t = _rol16((offsets[idx + 1] + y) & mask)
        q = (t * 0x1881CE3C) & mask
        swap_last = (_rol16(q) * 0x35BD1EC9 + q) & mask
        swap_acc = (swap_acc + swap_last) & mask
        state = (state * key_a + x) & mask
        state = (_rol16(state) * 0xCF98B111) & mask
        state = (_rol16(state) * 0x87085B9F) & mask
        state = (_rol16(state) * 0x12CEB96D) & mask
        state = (_rol16(state) * 0x257E1D83) & mask
        acc += state
        t = (state * key_b + y) & mask
        t = (_rol16(t) * 0xA27416F5) & mask
        t = (_rol16(t) * 0xD38396FF) & mask
        state = (_rol16((_rol16(t) * 0x7C932B89) & mask) * 0xBFA49F69) & mask
        acc = (acc + state) & mask
    return swap_last ^ state, swap_acc ^ acc


@lru_cache(maxsize=2)
def _token_hash(data: bytes) -> Tuple[str, str]:
    digest = _sha256_digest(data)
    cs64 = struct.pack("<II", *_cs64_digest(data, digest)).hex().upper()
    return cs64, digest.hex().upper()


def generate_sec_ms_gec_v2(unix_timestamp: Optional[float] = None) -> str:
    ticks = float(unix_timestamp) if unix_timestamp is not None else (time.time() + _clock_skew_seconds)
    ticks += WIN_EPOCH
    ticks -= ticks % 300
    ticks *= 1e7
    cs64, sha256 = _token_hash(f"{ticks:.0f}{TRUSTED_CLIENT_TOKEN}".encode("ascii"))
    return f"{cs64}-{sha256}"


def _rate_to_str(speaking_rate: float) -> str:
    pct = int(round((speaking_rate - 1.0) * 100.0))
    pct = max(-50, min(100, pct))
    return f"+{pct}%" if pct >= 0 else f"{pct}%"


def _pitch_to_str(pitch_semitones: float) -> str:
    hz = int(round(pitch_semitones * 4.5))
    hz = max(-50, min(50, hz))
    return f"+{hz}Hz" if hz >= 0 else f"{hz}Hz"


def _synthesize_mp3_stdlib(text: str, voice: str, rate_str: str, pitch_str: str, retry: bool = True) -> bytes:
    global _clock_skew_seconds
    gec = generate_sec_ms_gec_v2()
    muid = secrets.token_hex(16).upper()
    conn_id = uuid.uuid4().hex
    path = f"{EDGE_WS_PATH}?TrustedClientToken={TRUSTED_CLIENT_TOKEN}&Sec-MS-GEC={gec}&Sec-MS-GEC-Version={SEC_MS_GEC_VERSION}&ConnectionId={conn_id}"
    ctx = ssl.create_default_context()
    sock = ctx.wrap_socket(socket.create_connection((EDGE_HOST, 443), timeout=20), server_hostname=EDGE_HOST)
    try:
        ws_key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {EDGE_HOST}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {ws_key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Pragma: no-cache\r\n"
            "Cache-Control: no-cache\r\n"
            "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
            "Accept-Encoding: identity\r\n"
            "Accept-Language: en-US,en;q=0.9\r\n"
            f"Cookie: muid={muid};\r\n"
            f"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 Edg/{CHROMIUM_MAJOR_VERSION}.0.0.0\r\n\r\n"
        )
        sock.sendall(req.encode("ascii"))
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buf += chunk
        hdr_bytes, rest = (buf.split(b"\r\n\r\n", 1) + [b""])[:2]
        status_line = hdr_bytes.split(b"\r\n", 1)[0].decode("latin-1", errors="ignore")
        if "101 " not in status_line:
            if "403" in status_line and retry:
                for line in hdr_bytes.decode("latin-1", errors="ignore").splitlines():
                    if line.lower().startswith("date:"):
                        parsed = email.utils.parsedate_to_datetime(line.split(":", 1)[1].strip())
                        _clock_skew_seconds = parsed.timestamp() - time.time()
                        sock.close()
                        return _synthesize_mp3_stdlib(text, voice, rate_str, pitch_str, retry=False)
            raise RuntimeError(f"Edge Neural WS handshake failed: {status_line}")

        def send_text(msg: str) -> None:
            payload = msg.encode("utf-8")
            mask = os.urandom(4)
            header = bytearray([0x81])
            ln = len(payload)
            if ln < 126:
                header.append(0x80 | ln)
            elif ln < 65536:
                header.append(0x80 | 126)
                header.extend(struct.pack(">H", ln))
            else:
                header.append(0x80 | 127)
                header.extend(struct.pack(">Q", ln))
            header.extend(mask)
            masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            sock.sendall(bytes(header) + masked)

        ts = time.strftime("%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)", time.gmtime())
        send_text(
            f"X-Timestamp:{ts}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"
            '{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}\r\n'
        )
        safe_text = html.escape(text.strip(), quote=False)
        xml_lang = "-".join(voice.split("-")[:2]) if "-" in voice else "ar-SA"
        ssml = (
            f"<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='{xml_lang}'>"
            f"<voice name='{voice}'>"
            f"<prosody pitch='{pitch_str}' rate='{rate_str}' volume='+0%'>{safe_text}</prosody>"
            "</voice></speak>"
        )
        req_id = uuid.uuid4().hex
        send_text(f"X-RequestId:{req_id}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:{ts}Z\r\nPath:ssml\r\n\r\n{ssml}")

        data_buf = bytearray(rest)

        def read_exact(n: int) -> bytes:
            while len(data_buf) < n:
                chunk = sock.recv(8192)
                if not chunk:
                    raise EOFError("Edge Neural WS closed before turn.end")
                data_buf.extend(chunk)
            out = bytes(data_buf[:n])
            del data_buf[:n]
            return out

        audio = bytearray()
        while True:
            b1, b2 = read_exact(2)
            opcode = b1 & 0x0F
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", read_exact(8))[0]
            if b2 & 0x80:
                mask = read_exact(4)
                raw = read_exact(length)
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(raw))
            else:
                payload = read_exact(length)
            if opcode == 8:
                break
            if opcode == 9:
                continue
            if opcode == 1:
                if b"Path:turn.end" in payload:
                    break
            elif opcode == 2:
                hlen = struct.unpack(">H", payload[:2])[0]
                if b"Path:audio" in payload[2 : 2 + hlen]:
                    audio.extend(payload[2 + hlen :])
        if not audio:
            raise RuntimeError("Edge Neural TTS returned empty audio stream.")
        return bytes(audio)
    finally:
        sock.close()


def synthesize_edge_neural_mp3(
    text: str,
    language: str = "ar",
    dialect: str = "msa",
    category: str = "male",
    speaking_rate: float = 1.0,
    pitch_semitones: float = 0.0,
) -> Tuple[bytes, str]:
    voice = resolve_edge_voice(language, dialect, category)
    rate_str = _rate_to_str(speaking_rate)
    pitch_str = _pitch_to_str(pitch_semitones)
    try:
        import edge_tts  # type: ignore

        async def _collect() -> bytes:
            comm = edge_tts.Communicate(text=text.strip(), voice=voice, rate=rate_str, pitch=pitch_str)
            chunks = bytearray()
            async for item in comm.stream():
                if item.get("type") == "audio":
                    chunks.extend(item["data"])
            return bytes(chunks)

        mp3_bytes = asyncio.run(_collect())
        if mp3_bytes:
            return mp3_bytes, voice
    except ImportError:
        pass
    mp3_bytes = _synthesize_mp3_stdlib(text=text, voice=voice, rate_str=rate_str, pitch_str=pitch_str)
    return mp3_bytes, voice


def mp3_bytes_to_mono_pcm(mp3_bytes: bytes) -> Tuple["np.ndarray", int]:
    import numpy as np
    import soundfile as sf
    import io

    ffmpeg_bin = shutil.which("ffmpeg")
    if ffmpeg_bin:
        proc = subprocess.run(
            [ffmpeg_bin, "-hide_banner", "-loglevel", "error", "-f", "mp3", "-i", "pipe:0", "-ar", "24000", "-ac", "1", "-f", "wav", "pipe:1"],
            input=mp3_bytes,
            capture_output=True,
            check=True,
        )
        arr, sr = sf.read(io.BytesIO(proc.stdout), dtype="float32")
        if arr.ndim > 1:
            arr = np.mean(arr, axis=-1)
        return arr.astype(np.float32), int(sr)

    arr, sr = sf.read(io.BytesIO(mp3_bytes), dtype="float32")
    if arr.ndim > 1:
        arr = np.mean(arr, axis=-1)
    return arr.astype(np.float32), int(sr)
