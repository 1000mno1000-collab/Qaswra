package com.example.audio

import android.content.Context
import com.example.model.StereoAudioBuffer
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt

data class EdgeTtsSynthResult(
    val buffer: StereoAudioBuffer,
    val mp3File: File,
)

/**
 * Pure-Kotlin TLS RFC-6455 WebSocket client for Microsoft Edge Read Aloud Neural TTS.
 * Requires no API key and gracefully returns null if the WebSocket endpoint cannot be reached
 * so [com.example.audio.tts.SmartVoiceRouter] can try alternative cloud HD voice providers.
 */
object EdgeTtsClient {
    private const val HOST = "speech.platform.bing.com"
    private const val PORT = 443
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val ORIGIN_HEADER = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_FULL_VERSION Safari/537.36 Edg/$CHROMIUM_FULL_VERSION"

    private const val WIN_EPOCH_SEC = 11644473600L

    private fun generateSecMsGec(): String {
        val unixSeconds = System.currentTimeMillis() / 1000L
        var ticksSeconds = unixSeconds + WIN_EPOCH_SEC
        ticksSeconds -= (ticksSeconds % 300L)
        val ticks100Ns = ticksSeconds * 10_000_000L
        val payload = "${ticks100Ns}$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun jsUtcString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun escapeXml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatNaturalSsmlText(rawText: String): String {
        val escaped = escapeXml(rawText)
        return escaped
            .replace(Regex("([،,])\\s*"), "$1 <break time='140ms'/> ")
            .replace(Regex("([.!؟?])\\s+"), "$1 <break time='220ms'/> ")
    }

    suspend fun synthesizeRawMp3(
        text: String,
        voiceName: String,
        localeTag: String = voiceName.split("-").take(2).joinToString("-").ifBlank { "ar-IQ" },
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        pitchOffsetHz: Int = 0,
    ): ByteArray? {
        val cleanInput = text.trim()
        if (cleanInput.isEmpty()) return null

        val combinedRate = clamp(speechRate, 0.5f, 1.8f)
        val ratePercent = ((combinedRate - 1.0f) * 100f).roundToInt()
        val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"

        val pitchFromSliderHz = ((pitch - 1.0f) * 36f).roundToInt()
        val totalPitchHz = (pitchFromSliderHz + pitchOffsetHz).coerceIn(-60, 60)
        val pitchStr = if (totalPitchHz >= 0) "+${totalPitchHz}Hz" else "${totalPitchHz}Hz"

        val cleanText = formatNaturalSsmlText(cleanInput)
        val ssml =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$localeTag'>" +
                "<voice name='$voiceName'>" +
                "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>$cleanText</prosody>" +
                "</voice></speak>"

        return synthesizeRawSsmlMp3(ssml)
    }

    suspend fun synthesizeRawSsmlMp3(
        ssml: String,
    ): ByteArray? = withTimeoutOrNull(16_000L) {
        withContext(Dispatchers.IO) {
            val cleanSsml = ssml.trim()
            if (cleanSsml.isEmpty()) return@withContext null

            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val secMsGec = generateSecMsGec()
            val path =
                "/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                    "&Sec-MS-GEC=$secMsGec" +
                    "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
                    "&ConnectionId=$connectionId"

            var socket: SSLSocket? = null
            val mp3Out = ByteArrayOutputStream()
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                socket = (factory.createSocket(HOST, PORT) as SSLSocket).apply {
                    soTimeout = 12_000
                    startHandshake()
                }
                val input = BufferedInputStream(socket.inputStream, 16384)
                val output = socket.outputStream

                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val wsKey = Base64.getEncoder().encodeToString(keyBytes)

                val handshake = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $HOST\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Origin: $ORIGIN_HEADER\r\n")
                    append("User-Agent: $USER_AGENT\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("\r\n")
                }
                output.write(handshake.toByteArray(Charsets.UTF_8))
                output.flush()

                val statusLine = readHttpHandshake(input)
                if (!statusLine.contains("101")) {
                    return@withContext null
                }

                val timestamp = jsUtcString()
                val configMsg =
                    "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                sendTextFrame(output, configMsg)

                val ssmlMsg =
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${jsUtcString()}\r\n" +
                        "Path:ssml\r\n\r\n" +
                        cleanSsml
                sendTextFrame(output, ssmlMsg)

                var done = false
                while (!done) {
                    val frame = readWebSocketFrame(input) ?: break
                    when (frame.opcode) {
                        0x1 -> { // text
                            val msgText = frame.payload.toString(Charsets.UTF_8)
                            if (msgText.contains("Path:turn.end")) {
                                done = true
                            }
                        }
                        0x2 -> { // binary audio chunk
                            val data = frame.payload
                            if (data.size >= 2) {
                                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                                val audioOffset = 2 + headerLen
                                if (audioOffset <= data.size) {
                                    val headerStr = data.copyOfRange(2, audioOffset).toString(Charsets.US_ASCII)
                                    if (headerStr.contains("Path:audio") && audioOffset < data.size) {
                                        mp3Out.write(data, audioOffset, data.size - audioOffset)
                                    }
                                }
                            }
                        }
                        0x8 -> { // close
                            done = true
                        }
                        0x9 -> { // ping -> pong
                            sendControlFrame(output, 0xA, frame.payload)
                        }
                    }
                }

                val mp3Bytes = mp3Out.toByteArray()
                if (mp3Bytes.size < 256) null else mp3Bytes
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
    ): EdgeTtsSynthResult? = withContext(Dispatchers.IO) {
        val voiceName = if (gender == TtsGender.MALE) dialect.maleVoiceName else dialect.femaleVoiceName
        val mp3Bytes = synthesizeRawMp3(
            text = text,
            voiceName = voiceName,
            localeTag = dialect.localeTag,
            speechRate = speechRate * style.rateFactor,
            pitch = pitch,
            pitchOffsetHz = style.pitchOffsetHz,
        ) ?: return@withContext null

        val cacheDir = File(context.cacheDir, "edge_tts").apply { mkdirs() }
        val mp3File = File(cacheDir, "tts_${UUID.randomUUID()}.mp3")
        mp3File.writeBytes(mp3Bytes)
        val decoded = AudioDecoder.decodeFile(mp3File) ?: run {
            mp3File.delete()
            return@withContext null
        }
        val mastered = com.example.audio.fx.AudioSkillsPresets.applyStudioVocalMastering(
            buffer = decoded,
            extraWarmthDb = (style.warmthDb - 2.0f).coerceAtLeast(0f),
            extraPresenceDb = if (style == TtsDeliveryStyle.RADIO_ANCHOR) 1.6f else 0.8f,
        )
        EdgeTtsSynthResult(buffer = mastered, mp3File = mp3File)
    }

    private fun readHttpHandshake(input: InputStream): String {
        val lineBuf = ByteArrayOutputStream()
        var statusLine = ""
        var first = true
        while (true) {
            lineBuf.reset()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) throw EOFException("Unexpected EOF during WS handshake")
                if (prev == '\r'.code && b == '\n'.code) {
                    break
                }
                if (b != '\r'.code) {
                    lineBuf.write(b)
                }
                prev = b
            }
            val line = lineBuf.toString(Charsets.UTF_8.name())
            if (first) {
                statusLine = line
                first = false
            }
            if (line.isEmpty()) break
        }
        return statusLine
    }

    private class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw EOFException("EOF reading WebSocket frame")
            off += n
        }
        return buf
    }

    private fun readWebSocketFrame(input: InputStream): WsFrame? {
        val msgStream = ByteArrayOutputStream()
        var firstOpcode = -1
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null

            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            if (opcode != 0x0 && firstOpcode == -1) {
                firstOpcode = opcode
            }
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                val ext = readExact(input, 2)
                length = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
            } else if (length == 127L) {
                val ext = readExact(input, 8)
                length = 0L
                for (i in 0 until 8) {
                    length = (length shl 8) or (ext[i].toLong() and 0xFFL)
                }
            }
            val maskKey = if (masked) readExact(input, 4) else null
            if (length > 16 * 1024 * 1024L) throw IllegalStateException("Frame too large")
            val payload = readExact(input, length.toInt())
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            if (opcode == 0x8 || opcode == 0x9 || opcode == 0xA) {
                return WsFrame(opcode, payload)
            }
            msgStream.write(payload)
            if (fin) {
                return WsFrame(firstOpcode, msgStream.toByteArray())
            }
        }
    }

    private fun sendTextFrame(output: OutputStream, text: String) {
        sendFrame(output, 0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun sendControlFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        sendFrame(output, opcode, payload)
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> header.write(0x80 or len)
            len <= 65535 -> {
                header.write(0x80 or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val l = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((l ushr shift) and 0xFFL).toInt())
                }
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(mask)
        val maskedPayload = ByteArray(len) { i ->
            (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        output.write(header.toByteArray())
        output.write(maskedPayload)
        output.flush()
    }
}
