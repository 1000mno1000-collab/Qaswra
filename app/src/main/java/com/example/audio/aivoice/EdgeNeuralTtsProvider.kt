package com.example.audio.aivoice

import android.content.Context
import com.example.audio.OnlineGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt

/**
 * Pure-Kotlin Sec-MS-GEC v2 DRM token generator matching Microsoft Edge Read Aloud v2 handshake.
 * Requires no API key, no credit card, and no remote GPU server.
 */
object EdgeNeuralDrm {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "143"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private const val WIN_EPOCH = 11644473600L
    private const val M32 = 0xFFFFFFFFL

    @Volatile
    var clockSkewSeconds: Long = 0L
        private set

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )
    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val SHA256_BLOCK_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().apply {
        this[14] = SHA256_ADJUSTMENT
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L), longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L), longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L), longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L), longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL), longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L), longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L), longArrayOf(0xCB49E3C6L, 0xC5626167L),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rotr32(v: Long, bits: Int): Long {
        val u = v and M32
        return ((u ushr bits) or (u shl (32 - bits))) and M32
    }

    private fun rol16(v: Long): Long {
        val u = v and M32
        return ((u shl 16) or (u ushr 16)) and M32
    }

    private fun sigma0(v: Long): Long {
        val u = v and M32
        return ((u ushr 7) xor (u shl 25) xor (u ushr 18) xor (u shl 14) xor (u ushr 3)) and M32
    }

    private fun sigma1(v: Long): Long {
        val u = v and M32
        return ((u ushr 17) xor (u shl 15) xor (u ushr 19) xor (u shl 13) xor (u ushr 10)) and M32
    }

    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int = 0): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (idx in firstRound until 64) {
            val s1 = rotr32(e, 6) xor rotr32(e, 11) xor rotr32(e, 25)
            val ch = ((e and f) xor ((e.inv() and M32) and g)) and M32
            val t1 = (h + s1 + ch + constants[idx] + words[idx]) and M32
            val s0 = rotr32(a, 2) xor rotr32(a, 13) xor rotr32(a, 22)
            val maj = ((a and b) xor (a and c) xor (b and c)) and M32
            val t2 = (s0 + maj) and M32
            h = g
            g = f
            f = e
            e = (d + t1) and M32
            d = c
            c = b
            b = a
            a = (t1 + t2) and M32
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: LongArray, start: Int = 16) {
        for (idx in start until 64) {
            words[idx] = (words[idx - 16] + sigma0(words[idx - 15]) + words[idx - 7] + sigma1(words[idx - 2])) and M32
        }
    }

    private fun sha256Init(salt: ByteArray): LongArray {
        val words = LongArray(64)
        words[14] = ((salt[0].toLong() and 0xFF) shl 16) or
            ((salt[1].toLong() and 0xFF) shl 8) or
            (salt[2].toLong() and 0xFF)
        words[15] = ((salt[3].toLong() and 0xFF) shl 24) or
            ((salt[4].toLong() and 0xFF) shl 16) or
            ((salt[5].toLong() and 0xFF) shl 8) or
            (salt[6].toLong() and 0xFF)

        for (idx in 16 until 30) {
            var v = SHA256_SCHEDULE[idx - 16]
            if (idx - 2 >= 14) {
                val s1 = sigma1(words[idx - 2])
                v += if (idx == 16) (s1 xor SHA256_SIGMA1_ADJUSTMENT) else s1
            }
            if (idx - 7 >= 14) v += words[idx - 7]
            if (idx - 15 >= 14) {
                val s0 = sigma0(words[idx - 15])
                v += if (idx == 29) (s0 xor SHA256_SIGMA0_ADJUSTMENT) else s0
            }
            words[idx] = v and M32
        }
        words[30] = (
            sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
            ) and M32
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return LongArray(8) { i -> (SHA256_INITIAL[i] + working[i]) and M32 }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val totalLen = 57L + data.size.toLong()
        val tailLen = data.size - 7
        val zeroPad = ((55 - totalLen.toInt()) % 64 + 64) % 64
        val padded = ByteArray(tailLen + 1 + zeroPad + 8)
        System.arraycopy(data, 7, padded, 0, tailLen)
        padded[tailLen] = 0x80.toByte()
        ByteBuffer.wrap(padded, padded.size - 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(totalLen * 8L)

        var state = sha256Init(data.copyOfRange(0, 7))
        val words = LongArray(64)
        var offset = 0
        while (offset < padded.size) {
            val bb = ByteBuffer.wrap(padded, offset, 64).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until 16) {
                words[i] = bb.int.toLong() and M32
            }
            expandSchedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            for (i in 0 until 8) {
                state[i] = (state[i] + working[i]) and M32
            }
            offset += 64
        }
        val out = ByteArray(32)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        for (s in state) outBuf.putInt(s.toInt())
        return out
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val rem = (-data.size % 4 + 4) % 4
        val padded = data.copyOf(data.size + rem)
        var count = padded.size / 4
        if (count % 2 != 0) count += 1
        val out = LongArray(count)
        val bb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        var idx = 0
        while (bb.remaining() >= 4) {
            out[idx++] = bb.int.toLong() and M32
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val bb = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (bb.int.toLong() and M32) or 1L
        val keyB = (bb.int.toLong() and M32) or 1L
        val factorA = (keyA * CS64_FACTOR) and M32
        val factorB = (keyB * CS64_FACTOR) and M32

        val leWords = wordsLe4Even(byteArrayOf(0) + data)
        val totalTerms = CS64_COEFFICIENTS.size + leWords.size
        val terms = LongArray(totalTerms)
        val offsets = LongArray(totalTerms)
        for (i in CS64_COEFFICIENTS.indices) {
            terms[i] = (CS64_COEFFICIENTS[i][0] * if (i % 2 == 0) factorA else factorB) and M32
            offsets[i] = CS64_COEFFICIENTS[i][1]
        }
        for (i in leWords.indices) {
            val w = leWords[i]
            var term = (w * if (i % 2 == 0) keyA else keyB) and M32
            var off = (rol16(w) * if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL) and M32
            if (i == 0) {
                term = (term + CS64_ADJ0 * factorA) and M32
                off = (off + CS64_ADJ1) and M32
            }
            val pos = CS64_COEFFICIENTS.size + i
            terms[pos] = term
            offsets[pos] = off
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M32)
            var q = (t * 0x76F50F34L) and M32
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and M32)) and M32

            t = rol16((offsets[i + 1] + y) and M32)
            q = (t * 0x1881CE3CL) and M32
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M32
            swapAcc = (swapAcc + swapLast) and M32

            state = (state * keyA + x) and M32
            state = (rol16(state) * 0xCF98B111L) and M32
            state = (rol16(state) * 0x87085B9FL) and M32
            state = (rol16(state) * 0x12CEB96DL) and M32
            state = (rol16(state) * 0x257E1D83L) and M32
            acc = (acc + state) and M32

            t = (state * keyB + y) and M32
            t = (rol16(t) * 0xA27416F5L) and M32
            t = (rol16(t) * 0xD38396FFL) and M32
            state = (rol16((rol16(t) * 0x7C932B89L) and M32) * 0xBFA49F69L) and M32
            acc = (acc + state) and M32
            i += 2
        }
        return ((swapLast xor state) and M32) to ((swapAcc xor acc) and M32)
    }

    private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

    fun generateSecMsGecV2(unixSecondsOverride: Long? = null): String {
        val baseUnix = unixSecondsOverride ?: ((System.currentTimeMillis() / 1000L) + clockSkewSeconds)
        var ticks = baseUnix + WIN_EPOCH
        ticks -= (ticks % 300L)
        val fileTimeTicks = ticks * 10_000_000L
        val payload = "${fileTimeTicks}$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(payload)
        val (c0, c1) = cs64Digest(payload, digest)
        val cs64Bytes = ByteArray(8)
        ByteBuffer.wrap(cs64Bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(c0.toInt())
            .putInt(c1.toInt())
        return "${cs64Bytes.toHexUpper()}-${digest.toHexUpper()}"
    }

    fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHexUpper()
    }

    fun updateClockSkewFromDateHeader(dateHeader: String?): Boolean {
        if (dateHeader.isNullOrBlank()) return false
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val parsed = fmt.parse(dateHeader.trim()) ?: return false
            val serverSec = parsed.time / 1000L
            clockSkewSeconds = serverSec - (System.currentTimeMillis() / 1000L)
            true
        }.getOrDefault(false)
    }
}

object EdgeNeuralVoiceResolver {
    fun resolveVoice(language: AiVoiceLanguage, dialect: AiArabicDialect, category: AiVoiceCategory): String {
        val isFemale = category == AiVoiceCategory.FEMALE || category == AiVoiceCategory.CHILD
        return when (language) {
            AiVoiceLanguage.AR -> when (dialect) {
                AiArabicDialect.IRAQI -> if (isFemale) "ar-IQ-RanaNeural" else "ar-IQ-BasselNeural"
                AiArabicDialect.GULF -> if (isFemale) "ar-AE-FatimaNeural" else "ar-AE-HamdanNeural"
                AiArabicDialect.SAUDI -> if (isFemale) "ar-SA-ZariyahNeural" else "ar-SA-HamedNeural"
                AiArabicDialect.LEVANTINE -> if (isFemale) "ar-SY-AmanyNeural" else "ar-SY-LaithNeural"
                AiArabicDialect.EGYPTIAN -> if (isFemale) "ar-EG-SalmaNeural" else "ar-EG-ShakirNeural"
                AiArabicDialect.MOROCCAN -> if (isFemale) "ar-MA-MounaNeural" else "ar-MA-JamalNeural"
                AiArabicDialect.JORDANIAN -> if (isFemale) "ar-JO-SanaNeural" else "ar-JO-TaimNeural"
                AiArabicDialect.MSA -> if (isFemale) "ar-SA-ZariyahNeural" else "ar-SA-HamedNeural"
            }
            AiVoiceLanguage.EN -> if (isFemale) "en-US-EmmaMultilingualNeural" else "en-US-AndrewMultilingualNeural"
            AiVoiceLanguage.FA -> if (isFemale) "fa-IR-DilaraNeural" else "fa-IR-FaridNeural"
            AiVoiceLanguage.TR -> if (isFemale) "tr-TR-EmelNeural" else "tr-TR-AhmetNeural"
            AiVoiceLanguage.FR -> if (isFemale) "fr-FR-DeniseNeural" else "fr-FR-HenriNeural"
            AiVoiceLanguage.ES -> if (isFemale) "es-ES-ElviraNeural" else "es-ES-AlvaroNeural"
            AiVoiceLanguage.DE -> if (isFemale) "de-DE-KatjaNeural" else "de-DE-ConradNeural"
            AiVoiceLanguage.RU -> if (isFemale) "ru-RU-SvetlanaNeural" else "ru-RU-DmitryNeural"
            AiVoiceLanguage.IT -> if (isFemale) "it-IT-ElsaNeural" else "it-IT-DiegoNeural"
            AiVoiceLanguage.PT -> if (isFemale) "pt-BR-FranciscaNeural" else "pt-BR-AntonioNeural"
            AiVoiceLanguage.HI -> if (isFemale) "hi-IN-SwaraNeural" else "hi-IN-MadhurNeural"
            AiVoiceLanguage.UR -> if (isFemale) "ur-PK-UzmaNeural" else "ur-PK-AsadNeural"
            AiVoiceLanguage.ID -> if (isFemale) "id-ID-GadisNeural" else "id-ID-ArdiNeural"
            AiVoiceLanguage.ZH -> if (isFemale) "zh-CN-XiaoxiaoNeural" else "zh-CN-YunxiNeural"
            AiVoiceLanguage.JA -> if (isFemale) "ja-JP-NanamiNeural" else "ja-JP-KeitaNeural"
            AiVoiceLanguage.KO -> if (isFemale) "ko-KR-SunHiNeural" else "ko-KR-InJoonNeural"
        }
    }

    fun formatRateString(request: AiVoiceSynthRequest): String {
        val combined = (request.pace.speedMultiplier * request.emotion.speedBiasFactor).coerceIn(0.60f, 1.65f)
        val pct = ((combined - 1.0f) * 100f).roundToInt().coerceIn(-45, 70)
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    fun formatPitchString(request: AiVoiceSynthRequest): String {
        val catOffset = when (request.category) {
            AiVoiceCategory.CHILD -> 3.0f
            AiVoiceCategory.ELDERLY -> -1.5f
            AiVoiceCategory.NARRATOR -> -1.0f
            else -> 0f
        }
        val semitones = (request.pitchOffsetSemitones + request.emotion.pitchBiasSemitones + catOffset).coerceIn(-10f, 10f)
        val hz = (semitones * 4.5f).roundToInt().coerceIn(-45, 45)
        return if (hz >= 0) "+${hz}Hz" else "${hz}Hz"
    }

    fun buildSsml(text: String, voiceName: String, rateStr: String, pitchStr: String): String {
        val clean = sanitizeText(text)
        val escaped = clean
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val xmlLang = voiceName.split("-").take(2).joinToString("-").ifBlank { "ar-SA" }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$xmlLang'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>$escaped</prosody>" +
            "</voice></speak>"
    }

    private fun sanitizeText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.trim()) {
            val code = ch.code
            sb.append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else ch)
        }
        return sb.toString()
    }
}

/**
 * Independent Free Cloud Neural TTS Engine using Microsoft Edge Read Aloud (zero API keys, zero credit card, zero GPU).
 * Supports both Backend Proxy Mode (when Backend HTTPS URL is configured) and direct zero-config Kotlin TLS WebSocket synthesis.
 */
class EdgeNeuralCloudProvider : AiVoiceStudioEngine {
    override val kind: AiVoiceEngineKind = AiVoiceEngineKind.EDGE_NEURAL_FREE

    companion object {
        private const val EDGE_HOST = "speech.platform.bing.com"
        private const val EDGE_WS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
        private const val VOICES_LIST_PATH = "/consumer/speech/synthesize/readaloud/voices/list"
    }

    override suspend fun checkHealth(
        context: Context,
        backendUrl: String,
        bearerToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!OnlineGuard.isOnline(context)) return@withContext false
        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isNotEmpty()) {
            val (code, body) = runCatching {
                BackendHttpHelper.getJson("$cleanBase/health", bearerToken = bearerToken)
            }.getOrDefault(0 to "")
            if (code in 200..299) {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                return@withContext obj?.optBoolean("edge_neural_free_ready", true) ?: true
            }
        }
        val gec = EdgeNeuralDrm.generateSecMsGecV2()
        val url = "https://$EDGE_HOST$VOICES_LIST_PATH?TrustedClientToken=${EdgeNeuralDrm.TRUSTED_CLIENT_TOKEN}&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=${EdgeNeuralDrm.SEC_MS_GEC_VERSION}"
        val (code, _) = runCatching {
            BackendHttpHelper.getJson(url, timeoutMs = 10_000)
        }.getOrDefault(0 to "")
        code in 200..299
    }

    override suspend fun synthesizeChunk(
        context: Context,
        request: AiVoiceSynthRequest,
        textChunk: String,
        backendUrl: String,
        outWavFile: File,
        bearerToken: String,
    ): AiVoiceSynthOutcome = withContext(Dispatchers.IO) {
        if (!OnlineGuard.isOnline(context)) {
            return@withContext AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "لا يوجد اتصال نشط بالإنترنت لتشغيل محرك Edge Neural المجاني.",
                messageEn = "No verified internet connection for Edge Neural TTS synthesis.",
            )
        }

        val voiceName = EdgeNeuralVoiceResolver.resolveVoice(request.language, request.dialect, request.category)
        val rateStr = EdgeNeuralVoiceResolver.formatRateString(request)
        val pitchStr = EdgeNeuralVoiceResolver.formatPitchString(request)

        val cleanBase = backendUrl.trim().trimEnd('/')
        if (cleanBase.isNotEmpty()) {
            val payload = AiVoicePromptBuilder.buildBackendPayloadJson(request, textChunk, null)
            val backendRes = try {
                BackendHttpHelper.postSynthesizeJson(
                    context = context,
                    endpointUrl = "$cleanBase/v1/voice-studio/synthesize",
                    payload = payload,
                    bearerToken = bearerToken,
                    timeoutMs = 60_000,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            if (backendRes != null && backendRes.first in 200..299 && backendRes.second.size > 44) {
                return@withContext persistAudioBytesAsWav(
                    context = context,
                    rawBytes = backendRes.second,
                    outWavFile = outWavFile,
                    engineUsed = kind,
                    appliedPromptSummary = "$voiceName • ${request.dialect.titleAr} (Backend)",
                )
            }
        }

        return@withContext try {
            val mp3Bytes = synthesizeDirectWebSocket(
                text = textChunk,
                voiceName = voiceName,
                rateStr = rateStr,
                pitchStr = pitchStr,
                allowSkewRetry = true,
            )
            persistAudioBytesAsWav(
                context = context,
                rawBytes = mp3Bytes,
                outWavFile = outWavFile,
                engineUsed = kind,
                appliedPromptSummary = "$voiceName • $rateStr • $pitchStr (مجاني بدون GPU)",
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AiVoiceSynthOutcome.Error(
                engine = kind,
                messageAr = "تعذر التوليد عبر Edge Neural TTS: ${t.localizedMessage ?: t.javaClass.simpleName}",
                messageEn = "Edge Neural TTS synthesis failed: ${t.localizedMessage ?: t.javaClass.simpleName}",
                technicalDetail = t.toString(),
            )
        }
    }

    private fun synthesizeDirectWebSocket(
        text: String,
        voiceName: String,
        rateStr: String,
        pitchStr: String,
        allowSkewRetry: Boolean,
    ): ByteArray {
        val gec = EdgeNeuralDrm.generateSecMsGecV2()
        val muid = EdgeNeuralDrm.generateMuid()
        val connId = UUID.randomUUID().toString().replace("-", "")
        val path = "$EDGE_WS_PATH?TrustedClientToken=${EdgeNeuralDrm.TRUSTED_CLIENT_TOKEN}" +
            "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=${EdgeNeuralDrm.SEC_MS_GEC_VERSION}&ConnectionId=$connId"

        val socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
            soTimeout = 25_000
            connect(InetSocketAddress(EDGE_HOST, 443), 15_000)
            startHandshake()
        }
        try {
            val wsKeyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = android.util.Base64.encodeToString(wsKeyBytes, android.util.Base64.NO_WRAP)
            val upgradeReq = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(EDGE_HOST).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Pragma: no-cache\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Accept-Language: en-US,en;q=0.9\r\n")
                append("Cookie: muid=").append(muid).append(";\r\n")
                append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ")
                append("(KHTML, like Gecko) Chrome/${EdgeNeuralDrm.CHROMIUM_MAJOR_VERSION}.0.0.0 ")
                append("Safari/537.36 Edg/${EdgeNeuralDrm.CHROMIUM_MAJOR_VERSION}.0.0.0\r\n\r\n")
            }
            val out = socket.outputStream
            out.write(upgradeReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            val inp = DataInputStream(socket.inputStream)
            val statusAndHeaders = readHttpHeaders(inp)
            val statusLine = statusAndHeaders.firstOrNull().orEmpty()
            if (!statusLine.contains("101")) {
                val dateLine = statusAndHeaders.firstOrNull { it.startsWith("Date:", ignoreCase = true) }
                if (statusLine.contains("403") && allowSkewRetry && dateLine != null) {
                    val dateVal = dateLine.substringAfter(":", "").trim()
                    if (EdgeNeuralDrm.updateClockSkewFromDateHeader(dateVal)) {
                        socket.close()
                        return synthesizeDirectWebSocket(text, voiceName, rateStr, pitchStr, allowSkewRetry = false)
                    }
                }
                throw IllegalStateException("Edge Neural WS handshake failed: $statusLine")
            }

            val tsFormat = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val ts = tsFormat.format(java.util.Date())
            val configMsg = "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" + "\r\n"
            writeWsTextFrame(out, configMsg)

            val ssml = EdgeNeuralVoiceResolver.buildSsml(text, voiceName, rateStr, pitchStr)
            val reqId = UUID.randomUUID().toString().replace("-", "")
            val ssmlMsg = "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
            writeWsTextFrame(out, ssmlMsg)

            val audioOut = ByteArrayOutputStream()
            while (true) {
                val frame = readWsFrame(inp) ?: break
                val opcode = frame.first
                val payload = frame.second
                when (opcode) {
                    8 -> break
                    9 -> writeWsControlFrame(out, 0x8A, payload)
                    1 -> {
                        val txt = payload.toString(Charsets.UTF_8)
                        if (txt.contains("Path:turn.end")) break
                    }
                    2 -> if (payload.size > 2) {
                        val headerLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                        if (2 + headerLen <= payload.size) {
                            val headerStr = String(payload, 2, headerLen, Charsets.UTF_8)
                            if (headerStr.contains("Path:audio")) {
                                audioOut.write(payload, 2 + headerLen, payload.size - 2 - headerLen)
                            }
                        }
                    }
                }
            }
            val result = audioOut.toByteArray()
            if (result.isEmpty()) {
                throw IllegalStateException("Edge Neural TTS returned 0 bytes of MP3 audio.")
            }
            return result
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHttpHeaders(inp: DataInputStream): List<String> {
        val lines = mutableListOf<String>()
        val lineBuf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val s = lineBuf.toString("ISO-8859-1").trimEnd('\r')
                lineBuf.reset()
                if (s.isEmpty()) break
                lines.add(s)
            } else {
                lineBuf.write(b)
            }
        }
        return lines
    }

    private fun writeWsTextFrame(out: java.io.OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        writeWsMaskedFrame(out, 0x81, payload)
    }

    private fun writeWsControlFrame(out: java.io.OutputStream, firstByte: Int, payload: ByteArray) {
        writeWsMaskedFrame(out, firstByte, payload)
    }

    private fun writeWsMaskedFrame(out: java.io.OutputStream, firstByte: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val hdr = ByteArrayOutputStream(14)
        hdr.write(firstByte)
        val len = payload.size
        when {
            len < 126 -> hdr.write(0x80 or len)
            len < 65536 -> {
                hdr.write(0x80 or 126)
                hdr.write((len ushr 8) and 0xFF)
                hdr.write(len and 0xFF)
            }
            else -> {
                hdr.write(0x80 or 127)
                val l = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    hdr.write(((l ushr shift) and 0xFF).toInt())
                }
            }
        }
        hdr.write(mask)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        out.write(hdr.toByteArray())
        out.write(masked)
        out.flush()
    }

    private fun readWsFrame(inp: DataInputStream): Pair<Int, ByteArray>? {
        val b1: Int
        val b2: Int
        try {
            b1 = inp.readUnsignedByte()
            b2 = inp.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var length = (b2 and 0x7F).toLong()
        if (length == 126L) {
            length = inp.readUnsignedShort().toLong()
        } else if (length == 127L) {
            length = inp.readLong()
        }
        if (length < 0 || length > 32L * 1024L * 1024L) {
            throw IllegalStateException("Unexpected WebSocket frame length: $length")
        }
        val maskKey = if (masked) ByteArray(4).also { inp.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        if (payload.isNotEmpty()) {
            inp.readFully(payload)
        }
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }
}
