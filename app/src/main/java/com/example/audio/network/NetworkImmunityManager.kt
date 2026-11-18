package com.example.audio.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.audio.OnlineGuard
import com.example.diagnostics.AppDiagnosticLog
import com.example.util.SafeTextGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class NetworkBinaryResponse(
    val bytes: ByteArray,
    val contentType: String,
    val httpCode: Int,
    val urlUsed: String,
)

data class NetworkTextResponse(
    val body: String,
    val contentType: String,
    val httpCode: Int,
    val urlUsed: String,
)

sealed interface NetworkFetchOutcome<out T> {
    data class Success<T>(val value: T, val attemptCount: Int) : NetworkFetchOutcome<T>
    data class Offline(val reason: String) : NetworkFetchOutcome<Nothing>
    data class Failure(val errorCode: String, val message: String) : NetworkFetchOutcome<Nothing>
}

/**
 * 6-Layer Network Immunity Shield for all Sonix online operations:
 * 1. Real Socket / Ping reachability check (1.5s max) + OnlineGuard validation.
 * 2. Exponential Backoff Retry (3 attempts: 1s -> 2s -> 4s).
 * 3. Multi-Server / Multi-Endpoint automatic fallback.
 * 4. Payload & MIME-Type Inspection Shield (rejects < 1024B / HTML / JSON masquerading as audio).
 * 5. Strict HTTPS upgrade & TLS 1.2/1.3 SSLSocketFactory enforcement.
 * 6. Async isolation on Dispatchers.IO with 30s hard timeout and cooperative coroutine cancellation.
 */
object NetworkImmunityManager {
    private const val TAG = "NetworkImmunity"
    const val DEFAULT_NETWORK_TIMEOUT_MS = 30_000L
    const val PING_TIMEOUT_MS = 1_500
    const val MIN_VALID_AUDIO_BYTES = 1024

    private val BACKOFF_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    private val tls12PlusFactory: SSLSocketFactory? by lazy {
        runCatching {
            val ctx = try {
                SSLContext.getInstance("TLSv1.3")
            } catch (_: Throwable) {
                SSLContext.getInstance("TLSv1.2")
            }
            ctx.init(null, null, null)
            ctx.socketFactory
        }.getOrNull()
    }

    /**
     * Layer 5a: Always rewrite bare `http://` -> `https://` and prepend `https://` if scheme is missing.
     */
    fun enforceHttps(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + SafeTextGuard.safeSubstring(trimmed, 7)
            trimmed.contains("://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    /**
     * Layer 5b: Enforce TLS 1.2 / 1.3 socket factory on HttpsURLConnection connections.
     */
    fun enforceTls12Plus(connection: HttpURLConnection) {
        if (connection is HttpsURLConnection) {
            tls12PlusFactory?.let { factory ->
                runCatching { connection.sslSocketFactory = factory }
            }
        }
    }

    /**
     * Layer 1: Standard Android ConnectivityManager + NetworkCapabilities.NET_CAPABILITY_VALIDATED
     * reachability check, falling back to trusted HTTPS HEAD probes when no Context is supplied.
     */
    suspend fun pingReachable(
        context: Context? = null,
        timeoutMs: Int = PING_TIMEOUT_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        if (context != null) {
            val validated = runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@runCatching false
                val activeNetwork = cm.activeNetwork ?: return@runCatching false
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.getOrDefault(false)
            return@withContext validated && OnlineGuard.isOnline(context)
        }

        val trustedEndpoints = listOf(
            "https://www.google.com/generate_204",
            "https://www.google.com",
        )
        for (endpoint in trustedEndpoints) {
            coroutineContext.ensureActive()
            val ok = runCatching {
                var conn: HttpsURLConnection? = null
                try {
                    conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                        enforceTls12Plus(this)
                        requestMethod = "HEAD"
                        instanceFollowRedirects = false
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                    }
                    conn.responseCode in 200..399
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    /**
     * Layer 4: Strict Audio Payload & MIME Shield.
     * Prevents HTML/JSON/CAPTCHA pages or corrupt tiny blobs (< 1024B) from crashing MediaPlayer or FFmpegKit.
     */
    fun isValidAudioPayload(bytes: ByteArray, contentType: String? = null): Boolean {
        if (bytes.size <= MIN_VALID_AUDIO_BYTES) return false
        val mime = contentType?.lowercase(Locale.US).orEmpty()
        if (mime.contains("text/html") ||
            mime.contains("application/xhtml") ||
            mime.contains("application/json") ||
            mime.contains("text/plain")
        ) {
            return false
        }

        val headLen = min(48, bytes.size)
        val headAscii = bytes.copyOfRange(0, headLen).toString(Charsets.US_ASCII).trimStart()
        val headLower = headAscii.lowercase(Locale.US)
        if (headLower.startsWith("<!doctype") ||
            headLower.startsWith("<html") ||
            headLower.startsWith("<head") ||
            headLower.startsWith("<script") ||
            headLower.startsWith("<") ||
            headLower.startsWith("{") ||
            headLower.startsWith("[")
        ) {
            return false
        }

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val hasId3 = headAscii.startsWith("ID3")
        val hasMp3Sync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        val hasRiffWav = headAscii.startsWith("RIFF") && bytes.size >= 12 &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        val hasOgg = headAscii.startsWith("OggS")
        val hasFlac = headAscii.startsWith("fLaC")
        val hasFtyp = bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
        val hasAdtsAac = b0 == 0xFF && (b1 and 0xF6) == 0xF0

        return hasId3 || hasMp3Sync || hasRiffWav || hasOgg || hasFlac || hasFtyp || hasAdtsAac || mime.startsWith("audio/")
    }

    /**
     * Layer 2 + 3 + 5 + 6: Fetches validated audio bytes across prioritized fallback URLs
     * with exponential backoff (1s -> 2s -> 4s), 30s timeout isolation, and cooperative cancellation.
     */
    suspend fun fetchAudioWithFallback(
        urls: List<String>,
        context: Context? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_NETWORK_TIMEOUT_MS,
        maxBytes: Int = 25 * 1024 * 1024,
        providerLabel: String = "AudioCloud",
    ): NetworkFetchOutcome<NetworkBinaryResponse> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext NetworkFetchOutcome.Failure("EMPTY_URLS", "No endpoint URLs supplied")
        }
        if (context != null && !pingReachable(context)) {
            return@withContext NetworkFetchOutcome.Offline("No verified internet reachability")
        }

        val errors = mutableListOf<String>()
        var attemptsTotal = 0

        try {
            withTimeout(timeoutMs) {
                for (rawUrl in urls) {
                    val safeUrl = enforceHttps(rawUrl)
                    for (attempt in 0 until BACKOFF_DELAYS_MS.size) {
                        coroutineContext.ensureActive()
                        attemptsTotal++
                        val single = runCatching {
                            executeBinaryGet(
                                urlStr = safeUrl,
                                headers = headers,
                                connectTimeoutMs = 10_000,
                                readTimeoutMs = 20_000,
                                maxBytes = maxBytes,
                            )
                        }
                        val resp = single.getOrNull()
                        if (resp != null && resp.httpCode in 200..299 &&
                            isValidAudioPayload(resp.bytes, resp.contentType)
                        ) {
                            return@withTimeout NetworkFetchOutcome.Success(resp, attemptsTotal)
                        } else {
                            val errDesc = single.exceptionOrNull()?.let {
                                "${it.javaClass.simpleName}: ${it.message ?: "Network error"}"
                            } ?: if (resp == null) {
                                "Null response from $safeUrl"
                            } else {
                                "Invalid audio HTTP=${resp.httpCode} type=${resp.contentType} size=${resp.bytes.size}"
                            }
                            errors += errDesc
                            Log.w(TAG, "[$providerLabel] attempt=${attempt + 1} failed: $errDesc")
                        }

                        if (attempt < BACKOFF_DELAYS_MS.lastIndex) {
                            delay(BACKOFF_DELAYS_MS[attempt])
                        }
                    }
                }
                NetworkFetchOutcome.Failure(
                    errorCode = "NET_FALLBACK_EXHAUSTED",
                    message = errors.distinct().take(3).joinToString(" | ").ifBlank { "All fallback servers failed" },
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val code = t.javaClass.simpleName
            val msg = t.localizedMessage ?: "Network request timed out or failed"
            AppDiagnosticLog.logRecoverable(
                toolName = providerLabel,
                titleAr = "تعذر إتمام الطلب الشبكي الآمن",
                titleEn = "Safe Network Request Failed",
                causeAr = msg,
                causeEn = msg,
                errorCode = code,
                throwable = t,
            )
            NetworkFetchOutcome.Failure(code, msg)
        }
    }

    /**
     * Layer 2 + 3 + 5 + 6: Fetches text / JSON payloads across fallback endpoints with retries and 30s timeout.
     */
    suspend fun fetchTextWithFallback(
        urls: List<String>,
        context: Context? = null,
        headers: Map<String, String> = emptyMap(),
        postJsonBody: String? = null,
        timeoutMs: Long = DEFAULT_NETWORK_TIMEOUT_MS,
        providerLabel: String = "TextCloud",
    ): NetworkFetchOutcome<NetworkTextResponse> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext NetworkFetchOutcome.Failure("EMPTY_URLS", "No endpoint URLs supplied")
        }
        if (context != null && !pingReachable(context)) {
            return@withContext NetworkFetchOutcome.Offline("No verified internet reachability")
        }

        val errors = mutableListOf<String>()
        var attemptsTotal = 0

        try {
            withTimeout(timeoutMs) {
                for (rawUrl in urls) {
                    val safeUrl = enforceHttps(rawUrl)
                    for (attempt in 0 until BACKOFF_DELAYS_MS.size) {
                        coroutineContext.ensureActive()
                        attemptsTotal++
                        val single = runCatching {
                            executeTextRequest(
                                urlStr = safeUrl,
                                headers = headers,
                                postJsonBody = postJsonBody,
                                connectTimeoutMs = 9_000,
                                readTimeoutMs = 18_000,
                            )
                        }
                        val resp = single.getOrNull()
                        if (resp != null && resp.httpCode in 200..299 && resp.body.isNotBlank()) {
                            return@withTimeout NetworkFetchOutcome.Success(resp, attemptsTotal)
                        } else {
                            val errDesc = single.exceptionOrNull()?.let {
                                "${it.javaClass.simpleName}: ${it.message ?: "Request failed"}"
                            } ?: if (resp == null) {
                                "Empty response"
                            } else {
                                "HTTP ${resp.httpCode} bodyLength=${resp.body.length}"
                            }
                            errors += errDesc
                            Log.w(TAG, "[$providerLabel] attempt=${attempt + 1} failed: $errDesc")
                        }
                        if (attempt < BACKOFF_DELAYS_MS.lastIndex) {
                            delay(BACKOFF_DELAYS_MS[attempt])
                        }
                    }
                }
                NetworkFetchOutcome.Failure(
                    errorCode = "NET_TEXT_EXHAUSTED",
                    message = errors.distinct().take(3).joinToString(" | ").ifBlank { "Text servers unreachable" },
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            NetworkFetchOutcome.Failure(t.javaClass.simpleName, t.localizedMessage ?: "Timed out")
        }
    }

    /**
     * Fast single-attempt HTTPS/TLS1.2+ isolated request (no multi-second retry backoff)
     * intended for parallel model racing and concurrent metadata batches.
     */
    suspend fun fetchTextFast(
        url: String,
        headers: Map<String, String> = emptyMap(),
        postJsonBody: String? = null,
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 12_000,
        timeoutMs: Long = 14_000L,
    ): NetworkFetchOutcome<NetworkTextResponse> = withContext(Dispatchers.IO) {
        val safeUrl = enforceHttps(url)
        try {
            withTimeout(timeoutMs) {
                coroutineContext.ensureActive()
                val resp = executeTextRequest(
                    urlStr = safeUrl,
                    headers = headers,
                    postJsonBody = postJsonBody,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                )
                if (resp.httpCode in 200..299 && resp.body.isNotBlank()) {
                    NetworkFetchOutcome.Success(resp, 1)
                } else {
                    NetworkFetchOutcome.Failure(
                        errorCode = "HTTP_${resp.httpCode}",
                        message = "HTTP ${resp.httpCode}",
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            NetworkFetchOutcome.Failure(t.javaClass.simpleName, t.localizedMessage ?: "Fast request failed")
        }
    }

    private fun executeBinaryGet(
        urlStr: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Int,
    ): NetworkBinaryResponse {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                enforceTls12Plus(this)
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "audio/mpeg, audio/wav, audio/*;q=0.9, */*;q=0.5")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val bytes = if (code in 200..299) {
                readBoundedStream(conn.inputStream, maxBytes)
            } else {
                ByteArray(0)
            }
            return NetworkBinaryResponse(
                bytes = bytes,
                contentType = contentType,
                httpCode = code,
                urlUsed = urlStr,
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun executeTextRequest(
        urlStr: String,
        headers: Map<String, String>,
        postJsonBody: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): NetworkTextResponse {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                enforceTls12Plus(this)
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "SonixStudio/1.0 (Android)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (postJsonBody != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                } else {
                    requestMethod = "GET"
                }
            }
            if (postJsonBody != null) {
                conn.outputStream.use { out ->
                    out.write(postJsonBody.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                ""
            }
            return NetworkTextResponse(
                body = body,
                contentType = contentType,
                httpCode = code,
                urlUsed = urlStr,
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readBoundedStream(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("Response exceeds max size ($maxBytes bytes)")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }
}
