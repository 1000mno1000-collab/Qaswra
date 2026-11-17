package com.example.audio.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class CinematicReshadePreset(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val contrastBoost: Float,
    val vibranceBoost: Float,
    val sharpnessAmount: Float,
    val warmthShift: Float,
    val vignetteAmount: Float,
) {
    HDR_VIBRANT(
        id = "hdr_vibrant",
        titleAr = "HDR سينمائي مشبع",
        titleEn = "HDR Vibrant Cinema",
        contrastBoost = 0.22f,
        vibranceBoost = 0.35f,
        sharpnessAmount = 0.32f,
        warmthShift = 0.05f,
        vignetteAmount = 0.14f,
    ),
    GAMING_PUBG_SHARP(
        id = "gaming_sharp",
        titleAr = "وضوح الألعاب (PUBG ReShade)",
        titleEn = "Gaming LumaSharpen",
        contrastBoost = 0.28f,
        vibranceBoost = 0.42f,
        sharpnessAmount = 0.65f,
        warmthShift = -0.02f,
        vignetteAmount = 0.06f,
    ),
    CINEMA_FILM_ACES(
        id = "cinema_aces",
        titleAr = "طابع الأفلام الذهبي (Teal & Gold)",
        titleEn = "Teal & Gold Film",
        contrastBoost = 0.30f,
        vibranceBoost = 0.20f,
        sharpnessAmount = 0.22f,
        warmthShift = 0.24f,
        vignetteAmount = 0.28f,
    ),
    NIGHT_NEON_POP(
        id = "night_neon",
        titleAr = "توهج ليلي نيون (Neon Night)",
        titleEn = "Neon Night Pop",
        contrastBoost = 0.32f,
        vibranceBoost = 0.48f,
        sharpnessAmount = 0.40f,
        warmthShift = -0.18f,
        vignetteAmount = 0.20f,
    ),
}

data class CinematicRenderParams(
    val contrastBoost: Float = 0f,
    val vibranceBoost: Float = 0f,
    val sharpnessAmount: Float = 0f,
    val warmthShift: Float = 0f,
    val vignetteAmount: Float = 0f,
)

/**
 * Hardware-accelerated OpenGL ES 2.0 ReShade-style Cinematic Video Renderer.
 * Decodes source video frame-by-frame, applies GLSL HDR Vibrance / LumaSharpen / Filmic Contrast,
 * and preserves the exact original presentation timestamps (60 / 90 / 120 FPS) and lossless audio track.
 */
object CinematicVideoRenderer {

    suspend fun renderCinematicVideo(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        params: CinematicRenderParams,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var encoderInputSurface: Surface? = null
        var decoderOutputSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null

        try {
            videoExtractor.setDataSource(context, sourceUri, null)
            var vTrackIdx = -1
            var vFormat: MediaFormat? = null
            var aTrackIdx = -1
            var aFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vTrackIdx < 0) {
                    vTrackIdx = i
                    vFormat = fmt
                } else if (mime.startsWith("audio/") && aTrackIdx < 0) {
                    aTrackIdx = i
                    aFormat = fmt
                }
            }
            if (vTrackIdx < 0 || vFormat == null) return@withContext false

            val rawWidth = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val width = (rawWidth and 0xFFFE).coerceAtLeast(320)
            val height = (rawHeight and 0xFFFE).coerceAtLeast(240)
            val durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) {
                vFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                10_000_000L
            }
            val sourceFps = if (vFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                vFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(24, 120)
            } else {
                60
            }
            val rotation = if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                vFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }

            // Preserve 60/90/120 FPS fluid motion with high bitrate
            val targetBitrate = ((width.toLong() * height.toLong() * sourceFps) / 14L)
                .coerceIn(6_000_000L, 28_000_000L)
                .toInt()

            val outVideoFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(outVideoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // Set up EGL14 context bound to encoder Surface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val versions = IntArray(2)
            EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = configs[0] ?: return@withContext false

            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                encoderInputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val glRenderer = ReshadeGlFilter(width, height, params)
            val frameSync = FrameSync()
            surfaceTexture = SurfaceTexture(glRenderer.oesTextureId).apply {
                setOnFrameAvailableListener { frameSync.signal() }
            }
            decoderOutputSurface = Surface(surfaceTexture)

            val inMime = vFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext false
            videoExtractor.selectTrack(vTrackIdx)
            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(vFormat, decoderOutputSurface, null, 0)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                if (rotation != 0) setOrientationHint(rotation)
            }
            var muxVideoTrack = -1
            var muxAudioTrack = -1

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var decOutputDone = false
            var encOutputDone = false

            while (!encOutputDone) {
                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) videoExtractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                if (!decOutputDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 10_000L)
                    if (decOutIdx >= 0) {
                        val doRender = decInfo.size > 0 && (decInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        decoder.releaseOutputBuffer(decOutIdx, doRender)
                        if (doRender) {
                            frameSync.awaitNewImage()
                            surfaceTexture.updateTexImage()
                            val stMatrix = FloatArray(16)
                            surfaceTexture.getTransformMatrix(stMatrix)
                            glRenderer.drawFrame(stMatrix)
                            // Preserve exact source FPS timestamps (60 / 90 / 120 FPS)
                            EGLExt.eglPresentationTimeANDROID(
                                eglDisplay,
                                eglSurface,
                                decInfo.presentationTimeUs * 1000L,
                            )
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                            onProgress?.invoke(((decInfo.presentationTimeUs.toFloat() / durationUs) * 0.85f).coerceIn(0.02f, 0.85f))
                        }
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Drain encoded H.264 packets
                val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 5_000L)
                when {
                    encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (aTrackIdx >= 0 && aFormat != null) {
                                muxAudioTrack = runCatching { muxer.addTrack(aFormat) }.getOrDefault(-1)
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encOutIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(encOutIdx)
                        if (outBuf != null && encInfo.size > 0 &&
                            (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted
                        ) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxVideoTrack, outBuf, encInfo)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encOutputDone = true
                        }
                    }
                }
            }

            // Copy original audio track losslessly (-c:a copy)
            if (muxerStarted && muxAudioTrack >= 0) {
                audioExtractor.setDataSource(context, sourceUri, null)
                audioExtractor.selectTrack(aTrackIdx)
                val aBuf = ByteBuffer.allocate(256 * 1024)
                val aInfo = MediaCodec.BufferInfo()
                while (true) {
                    aInfo.offset = 0
                    val sampleSize = audioExtractor.readSampleData(aBuf, 0)
                    if (sampleSize < 0) break
                    aInfo.size = sampleSize
                    aInfo.presentationTimeUs = audioExtractor.sampleTime
                    aInfo.flags = audioExtractor.sampleFlags
                    aBuf.position(0)
                    aBuf.limit(sampleSize)
                    muxer.writeSampleData(muxAudioTrack, aBuf, aInfo)
                    audioExtractor.advance()
                }
            }
            onProgress?.invoke(1f)
            muxerStarted && outputFile.exists() && outputFile.length() > 1024L
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoderOutputSurface?.release() }
            runCatching { surfaceTexture?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
            runCatching { encoderInputSurface?.release() }
        }
    }

    private class FrameSync {
        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        private var available = false

        fun signal() {
            lock.withLock {
                available = true
                cond.signalAll()
            }
        }

        fun awaitNewImage() {
            lock.withLock {
                val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200)
                while (!available) {
                    val remaining = deadlineNs - System.nanoTime()
                    if (remaining <= 0L) break
                    cond.awaitNanos(remaining)
                }
                available = false
            }
        }
    }

    private class ReshadeGlFilter(
        private val width: Int,
        private val height: Int,
        private val params: CinematicRenderParams,
    ) {
        val oesTextureId: Int
        private val program: Int
        private val uTexMatrixHandle: Int
        private val uMvpMatrixHandle: Int
        private val uTexelSizeHandle: Int
        private val uContrastHandle: Int
        private val uVibranceHandle: Int
        private val uSharpnessHandle: Int
        private val uWarmthHandle: Int
        private val uVignetteHandle: Int
        private val aPosHandle: Int
        private val aTexHandle: Int

        private val mvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val quadBuf: FloatBuffer

        init {
            val vertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            )
            quadBuf = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(vertices)
                    position(0)
                }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also { p ->
                GLES20.glAttachShader(p, vs)
                GLES20.glAttachShader(p, fs)
                GLES20.glLinkProgram(p)
            }

            aPosHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTexelSizeHandle = GLES20.glGetUniformLocation(program, "uTexelSize")
            uContrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
            uVibranceHandle = GLES20.glGetUniformLocation(program, "uVibrance")
            uSharpnessHandle = GLES20.glGetUniformLocation(program, "uSharpness")
            uWarmthHandle = GLES20.glGetUniformLocation(program, "uWarmth")
            uVignetteHandle = GLES20.glGetUniformLocation(program, "uVignette")
        }

        fun drawFrame(stMatrix: FloatArray) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            quadBuf.position(0)
            GLES20.glVertexAttribPointer(aPosHandle, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
            GLES20.glEnableVertexAttribArray(aPosHandle)

            quadBuf.position(2)
            GLES20.glVertexAttribPointer(aTexHandle, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
            GLES20.glEnableVertexAttribArray(aTexHandle)

            GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glUniform2f(uTexelSizeHandle, 1f / width, 1f / height)
            GLES20.glUniform1f(uContrastHandle, params.contrastBoost)
            GLES20.glUniform1f(uVibranceHandle, params.vibranceBoost)
            GLES20.glUniform1f(uSharpnessHandle, params.sharpnessAmount)
            GLES20.glUniform1f(uWarmthHandle, params.warmthShift)
            GLES20.glUniform1f(uVignetteHandle, params.vignetteAmount)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun compileShader(type: Int, src: String): Int {
            return GLES20.glCreateShader(type).also { s ->
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
            }
        }
    }

    private const val VERTEX_SHADER = """
        uniform mat4 uMvpMatrix;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uTexelSize;
        uniform float uContrast;
        uniform float uVibrance;
        uniform float uSharpness;
        uniform float uWarmth;
        uniform float uVignette;

        void main() {
            vec3 c = texture2D(sTexture, vTexCoord).rgb;

            // LumaSharpen ReShade pass
            if (uSharpness > 0.001) {
                vec3 n = texture2D(sTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 s = texture2D(sTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                vec3 e = texture2D(sTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 w = texture2D(sTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
                vec3 blur = (n + s + e + w) * 0.25;
                c = clamp(c + (c - blur) * (uSharpness * 1.6), 0.0, 1.0);
            }

            // Filmic S-curve HDR contrast
            if (abs(uContrast) > 0.001) {
                float factor = 1.0 + uContrast * 0.9;
                c = clamp((c - 0.5) * factor + 0.5, 0.0, 1.0);
            }

            // Smart Vibrance boost
            float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
            float maxC = max(c.r, max(c.g, c.b));
            float minC = min(c.r, min(c.g, c.b));
            float sat = maxC - minC;
            float vibAmount = uVibrance * (1.0 - sat * 0.65);
            c = clamp(mix(vec3(luma), c, 1.0 + vibAmount), 0.0, 1.0);

            // Warmth / Teal & Gold split tone
            if (abs(uWarmth) > 0.001) {
                c.r = clamp(c.r + uWarmth * 0.14, 0.0, 1.0);
                c.b = clamp(c.b - uWarmth * 0.12, 0.0, 1.0);
            }

            // Cinema Vignette
            if (uVignette > 0.001) {
                vec2 uv = vTexCoord - vec2(0.5);
                float dist = dot(uv, uv);
                c *= clamp(1.0 - dist * uVignette * 2.2, 0.45, 1.0);
            }

            gl_FragColor = vec4(c, 1.0);
        }
    """
}
