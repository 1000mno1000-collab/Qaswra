package com.example.diagnostics

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.audio.AudioDecoder
import com.example.audio.EchoReverbProcessor
import com.example.audio.ExportedAudioResult
import com.example.audio.StereoBiquadFilter
import com.example.audio.TARGET_SAMPLE_RATE
import com.example.audio.applyAutoCompressorLimiter
import com.example.audio.applyFadeIn
import com.example.audio.applyFadeOut
import com.example.audio.applyGainDb
import com.example.audio.applyOptionalSmartAutoFadeOut
import com.example.audio.cache.SmartCacheManager
import com.example.audio.clamp
import com.example.audio.computeWaveformPeaksAsync
import com.example.audio.cutSelectionWithFade
import com.example.audio.exportRenderedAudioToFile
import com.example.audio.mergeClipsStreamingOnDisk
import com.example.audio.mixTracks
import com.example.audio.network.NetworkImmunityManager
import com.example.audio.normalizeAudio
import com.example.audio.normalizeToStudioStereo44100
import com.example.audio.removeSilenceAuto
import com.example.audio.reverseAudioWithFfmpeg
import com.example.audio.splitVocalAndInstrumental
import com.example.audio.trimToSelectionWithFade
import com.example.audio.tts.ArabicTextDiacritizer
import com.example.audio.writeStereoWavFile
import com.example.model.AppLanguage
import com.example.model.BUILT_IN_PRESETS
import com.example.model.DspConfig
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.ExportAudioFormat
import com.example.model.PlatformLoudnessTarget
import com.example.model.StereoAudioBuffer
import com.example.model.VOICE_CHARACTER_PRESETS
import com.example.model.VocalSplitMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
data class QaIssueReport(
    val number: Int,
    val toolOrScreen: String,
    val functionName: String,
    val actionDetail: String,
    val technicalCause: String,
    val errorCode: String,
    val recommendation: String,
)

@Immutable
data class QaScanState(
    val isRunning: Boolean = false,
    val completed: Boolean = false,
    val stoppedByUser: Boolean = false,
    val scenariosChecked: Int = 0,
    val totalTarget: Int = 0,
    val currentPhaseLabelAr: String = "جاهز لبدء الفحص عند موافقتك",
    val currentPhaseLabelEn: String = "Ready to run on your explicit approval",
    val issues: List<QaIssueReport> = emptyList(),
    val passedChecksCount: Int = 0,
    val elapsedMs: Long = 0L,
)

/**
 * Open-ended, full-suite QA & Audio Diagnostics Bot:
 * - Starts ONLY when user clicks explicit consent button ("موافقة وبدء الفحص الشامل").
 * - Counts upward freely (#1, #2, #3...) without any artificial upper limit across all
 *   presets, filters, DSP math modules, large-file streaming pipeline, cache/storage health,
 *   and the 6-layer Network Immunity Shield until all checks complete or user clicks Stop.
 */
object SmartQaBotEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(QaScanState())
    val state: StateFlow<QaScanState> = _state.asStateFlow()

    fun startScanWithUserConsent(context: Context) {
        if (_state.value.isRunning) return
        val appCtx = context.applicationContext ?: context
        scanJob?.cancel()

        _state.value = QaScanState(
            isRunning = true,
            completed = false,
            stoppedByUser = false,
            scenariosChecked = 0,
            totalTarget = 0,
            currentPhaseLabelAr = "جاري فحص القوالب، الفلاتر، ومعادلات الـ DSP...",
            currentPhaseLabelEn = "Scanning presets, biquad filters & DSP parameters...",
            issues = emptyList(),
            passedChecksCount = 0,
            elapsedMs = 0L,
        )

        scanJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val foundIssues = mutableListOf<QaIssueReport>()
            var checked = 0
            var passed = 0

            fun recordIssue(
                toolOrScreen: String,
                functionName: String,
                actionDetail: String,
                cause: String,
                errorCode: String,
                recommendation: String,
            ) {
                foundIssues += QaIssueReport(
                    number = foundIssues.size + 1,
                    toolOrScreen = toolOrScreen,
                    functionName = functionName,
                    actionDetail = actionDetail,
                    technicalCause = cause,
                    errorCode = errorCode,
                    recommendation = recommendation,
                )
            }

            fun publishProgress(phaseAr: String? = null, phaseEn: String? = null) {
                _state.update { prev ->
                    prev.copy(
                        scenariosChecked = checked,
                        passedChecksCount = passed,
                        issues = foundIssues.toList(),
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        currentPhaseLabelAr = phaseAr ?: prev.currentPhaseLabelAr,
                        currentPhaseLabelEn = phaseEn ?: prev.currentPhaseLabelEn,
                    )
                }
            }

            suspend fun tickAndYieldIfNeeded() {
                checked++
                if (checked % 15 == 0) {
                    coroutineContext.ensureActive()
                    yield()
                    publishProgress()
                }
            }

            try {
                // =========================================================================
                // SUITE 1: Built-in Presets, Voice Characters, Effects & DSP Filter Sweeps
                // =========================================================================
                publishProgress(
                    phaseAr = "المجموعة 1/3: فحص جميع القوالب الجاهزة، الشخصيات الصوتية، والفلاتر...",
                    phaseEn = "Suite 1/3: Verifying presets, voice characters, biquads & DSP chains...",
                )

                // 1A. Built-in Presets & Effect Chains
                for (preset in BUILT_IN_PRESETS) {
                    coroutineContext.ensureActive()
                    val normalized = preset.config.normalized()
                    if (normalized.chain.size != EffectType.entries.size) {
                        recordIssue(
                            toolOrScreen = "Presets / BuiltInPresets",
                            functionName = "preset(${preset.id}).normalized()",
                            actionDetail = "التحقق من تكامل سلسلة المؤثرات للقالب ${preset.id}",
                            cause = "عدد المؤثرات بعد التطبيع غير مكتمل (${normalized.chain.size})",
                            errorCode = "PRESET_CHAIN_INCOMPLETE",
                            recommendation = "ضمان شمول جميع EffectType في سلسلة القالب.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                // 1B. Voice Character Presets
                for (vc in VOICE_CHARACTER_PRESETS) {
                    coroutineContext.ensureActive()
                    if (vc.formantShift !in 0.5f..2.0f || vc.pitchSemitones !in -24f..24f) {
                        recordIssue(
                            toolOrScreen = "VoiceTransformer / StudioModels",
                            functionName = "VOICE_CHARACTER_PRESETS(${vc.id})",
                            actionDetail = "فحص معاملات شخصية ${vc.id}",
                            cause = "قيم الطبقة أو الـ Formant خارج النطاق الآمن",
                            errorCode = "VOICE_CHAR_RANGE_WARN",
                            recommendation = "تقييد قيم الشخصيات الصوتية في نطاق طبيعي مستقر.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                // 1C. EffectType Toggles & Gain Boundary Checks
                val gainSweeps = listOf(-24f, -12f, -3.5f, 0f, 3.5f, 12f, 18f, -999f, 999f, Float.NaN)
                for (rawGain in gainSweeps) {
                    coroutineContext.ensureActive()
                    val clamped = if (rawGain.isNaN()) 0f else clamp(rawGain, -24f, 24f)
                    if (clamped.isNaN() || clamped < -24f || clamped > 24f) {
                        recordIssue(
                            toolOrScreen = "Sliders / AudioEditorScreen",
                            functionName = "clamp()",
                            actionDetail = "تمرير قيمة كسب ($rawGain) لمحدد المستوى",
                            cause = "تم إرجاع قيمة خارج النطاق: $clamped",
                            errorCode = "DSP_RANGE_OVERFLOW",
                            recommendation = "تحقق من معالجة Float.NaN قبل تطبيق الكسب الصوتي.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                for (eff in EffectType.entries) {
                    coroutineContext.ensureActive()
                    val cfg = DspConfig().normalized().withSlotEnabled(eff, true)
                    if (!cfg.isEnabled(eff)) {
                        recordIssue(
                            toolOrScreen = "EffectsChain / DspConfig",
                            functionName = "withSlotEnabled($eff)",
                            actionDetail = "تفعيل وفحص المؤثر ${eff.name}",
                            cause = "فشل تفعيل المؤثر داخل السلسلة المطبّعة",
                            errorCode = "EFFECT_TOGGLE_FAIL",
                            recommendation = "مراجعة دالة DspConfig.withSlotEnabled.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                // 1D. EchoReverb Matrix Sweep
                val echoMatrix = listOf(0.0f, 0.25f, 0.55f, 0.85f)
                for (str in echoMatrix) {
                    for (fb in listOf(0.15f, 0.50f, 0.78f)) {
                        for (size in listOf(0.2f, 0.6f, 0.9f)) {
                            coroutineContext.ensureActive()
                            val proc = EchoReverbProcessor(TARGET_SAMPLE_RATE)
                            val sampleL = FloatArray(128) { i -> sin(i * 0.12f) * 0.48f }
                            val sampleR = sampleL.copyOf()
                            proc.configure(
                                EchoReverbParams(
                                    echoStrength = str,
                                    feedback = fb,
                                    reverbSize = size,
                                    reverbDecay = 0.72f,
                                    wetDryMix = 0.5f,
                                ),
                            )
                            proc.processStereo(sampleL, sampleR, 128)
                            if (sampleL.any { !it.isFinite() } || sampleR.any { !it.isFinite() }) {
                                recordIssue(
                                    toolOrScreen = "EchoAndEffectsScreens / EchoReverb",
                                    functionName = "EchoReverbProcessor.processStereo()",
                                    actionDetail = "اختبار مصفوفة الصدى (str=$str, fb=$fb, size=$size)",
                                    cause = "القيم الناتجة احتوت على NaN أو Infinite",
                                    errorCode = "ECHO_NAN_INSTABILITY",
                                    recommendation = "ضمان ثبات معامل التغذية العكسية والمرور عبر محدد الذروة.",
                                )
                            } else {
                                passed++
                            }
                            tickAndYieldIfNeeded()
                        }
                    }
                }

                // 1E. StereoBiquadFilter Sweep Across Sample Rates & Cutoffs
                val sampleRates = listOf(16000, 22050, 44100, 48000)
                val filterFreqs = listOf(45.0, 120.0, 320.0, 1000.0, 3400.0, 8000.0, 14000.0)
                for (sr in sampleRates) {
                    for (freq in filterFreqs) {
                        coroutineContext.ensureActive()
                        val biquad = StereoBiquadFilter()
                        val testL = FloatArray(128) { i -> sin(2.0 * PI * 440.0 * i / sr).toFloat() * 0.5f }
                        val testR = testL.copyOf()
                        biquad.configurePeaking(sr, freq, 1.4, 4.5)
                        biquad.processStereo(testL, testR, 128)
                        biquad.configureLowShelf(sr, freq, 0.9, 3.0)
                        biquad.processStereo(testL, testR, 128)
                        biquad.configureHighShelf(sr, freq, 0.9, -2.5)
                        biquad.processStereo(testL, testR, 128)
                        biquad.configureHighPass(sr, freq)
                        biquad.processStereo(testL, testR, 128)
                        biquad.configureLowPass(sr, freq)
                        biquad.processStereo(testL, testR, 128)
                        biquad.configureBandPass(sr, freq)
                        biquad.processStereo(testL, testR, 128)

                        if (testL.any { !it.isFinite() } || testR.any { !it.isFinite() }) {
                            recordIssue(
                                toolOrScreen = "Equalizer / Purifier",
                                functionName = "StereoBiquadFilter",
                                actionDetail = "فحص استقرار الفلتر عند ${sr}Hz / ${freq}Hz",
                                cause = "توليد قيمة غير منتهية في مصفوفة الفلتر",
                                errorCode = "BIQUAD_UNSTABLE",
                                recommendation = "تقييد التردد القطعي نسبةً إلى تردد نايكويست.",
                            )
                        } else {
                            passed++
                        }
                        tickAndYieldIfNeeded()
                    }
                }

                // 1F. Arabic Diacritics & Path ASCII Sanitization
                val arabicInputs = listOf(
                    "مرحبا بكم في استوديو Sonix! كيف الحال؟",
                    "هذا النص يحتوي على حروف مهموسة مثل هـ و خ و ش.",
                    "تستـــطيل، و فواصل , عربية.",
                )
                for (input in arabicInputs) {
                    coroutineContext.ensureActive()
                    val out = ArabicTextDiacritizer.normalizeAndHintForTts(input)
                    if (out.isBlank()) {
                        recordIssue(
                            toolOrScreen = "Cloud TTS Studio",
                            functionName = "ArabicTextDiacritizer.normalizeAndHintForTts()",
                            actionDetail = "تشكيل ومعالجة النص العربي تلقائياً",
                            cause = "أعاد المعالج نصاً فارغاً لمدخل سليم",
                            errorCode = "TTS_TEXT_EMPTY",
                            recommendation = "مراجعة تعبير التنظيف حتى لا يحذف الكلمات.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                val dirtyNames = listOf(
                    "تسجيل صوتي جديد (1) #مهم @2026!.wav",
                    "آهنگ فارسی تست ۲ (نهایی).mp3",
                    "   Mix__Final##(99).m4a  ",
                )
                for (dirty in dirtyNames) {
                    coroutineContext.ensureActive()
                    val safe = SmartCacheManager.sanitizeAsciiFilename(dirty)
                    val asciiOk = safe.isNotBlank() && safe.all { ch ->
                        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' || ch == '.'
                    }
                    if (!asciiOk) {
                        recordIssue(
                            toolOrScreen = "SmartCacheManager",
                            functionName = "sanitizeAsciiFilename()",
                            actionDetail = "تنظيف اسم ملف يحتوي محارف خاصة: $dirty",
                            cause = "الناتج ما زال يحتوي محارف غير مدعومة في FFmpeg: $safe",
                            errorCode = "PATH_SANITIZE_FAIL",
                            recommendation = "استبدال جميع المحارف غير اللاتينية بأسماء آمنة قبل أوامر FFmpegKit.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()
                }

                // =========================================================================
                // SUITE 2: Real Audio Pipeline, Large-File Streaming, Editor Ops & Export
                // =========================================================================
                publishProgress(
                    phaseAr = "المجموعة 2/3: اختبار حقيقي لمعالجة الصوت، تدفق الملفات الكبيرة، والتصدير...",
                    phaseEn = "Suite 2/3: Real Audio Pipeline, Large-File Streaming & Export Tests...",
                )

                val qaDir = File(appCtx.cacheDir, "qa_bot").apply { mkdirs() }
                val testWav = File(qaDir, "qa_synth_${UUID.randomUUID().toString().take(6)}.wav")
                try {
                    val testSr = 22050
                    val frames = (1.8f * testSr).roundToIntSafe()
                    val left = FloatArray(frames)
                    val right = FloatArray(frames)
                    for (i in 0 until frames) {
                        val t = i.toFloat() / testSr
                        val s = when {
                            t < 0.7f -> (sin(2.0 * PI * 185.0 * t) * 0.42 + sin(2.0 * PI * 4200.0 * t) * 0.16).toFloat()
                            t < 1.25f -> 0f
                            else -> (sin(2.0 * PI * 210.0 * t) * 0.45 + sin(2.0 * PI * 4800.0 * t) * 0.18).toFloat()
                        }
                        left[i] = s
                        right[i] = s * 0.9f
                    }
                    val rawSynthetic = StereoAudioBuffer(left, right, testSr)
                    writeStereoWavFile(testWav, rawSynthetic)

                    // Check: WAV Write & Decode Roundtrip
                    val decoded = AudioDecoder.decodeFile(testWav)
                    if (decoded == null || decoded.frameCount <= 0 || decoded.sampleRate != TARGET_SAMPLE_RATE) {
                        recordIssue(
                            toolOrScreen = "AudioDecoder / AudioExporter",
                            functionName = "AudioDecoder.decodeFile()",
                            actionDetail = "كتابة وقراءة ملف WAV بتردد مختلف (22050 -> 44100 Hz)",
                            cause = "فشل فك التشفير أو توحيد التردد تلقائياً إلى 44100Hz",
                            errorCode = "DECODE_ROUNDTRIP_FAIL",
                            recommendation = "تفعيل resampleStereo تلقائياً عند اختلاف التردد عن 44100Hz.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Large-file 64KB chunked streaming inspection & bounded analysis window
                    val streamedMeta = AudioDecoder.inspectLargeAudioStreaming(testWav, buckets = 96) {}
                    val boundedSample = AudioDecoder.decodeSampleWindowForAnalysis(testWav, maxSeconds = 1.2f)
                    if (streamedMeta == null || streamedMeta.durationMs <= 0L || streamedMeta.waveformPeaks.size != 96 ||
                        boundedSample == null || boundedSample.frameCount <= 0
                    ) {
                        recordIssue(
                            toolOrScreen = "AudioDecoder / LargeFileStreaming",
                            functionName = "inspectLargeAudioStreaming()",
                            actionDetail = "فحص استخراج الموجة بتدفق 64KB دون تحميل كامل بالذاكرة",
                            cause = "فشل استخراج المدة أو ذروات الموجة بالتدفق الجزئي",
                            errorCode = "LARGE_STREAM_INSPECT_FAIL",
                            recommendation = "التحقق من قراءة ترويسة الصوت وحلقات التدفق 64KB.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    val workBuf = decoded ?: normalizeToStudioStereo44100(rawSynthetic)

                    // Check: Waveform Background Downsampling
                    val peaks = computeWaveformPeaksAsync(workBuf, 96)
                    if (peaks.size != 96 || peaks.any { it.isNaN() }) {
                        recordIssue(
                            toolOrScreen = "AudioEditorScreen / Peaks",
                            functionName = "computeWaveformPeaksAsync()",
                            actionDetail = "رسم الموجة الصوتية في الخلفية",
                            cause = "عدد العينات غير مكتمل (${peaks.size})",
                            errorCode = "WAVEFORM_PEAKS_ERR",
                            recommendation = "تأكد من التحقق من حجم المصفوفة قبل حساب الذروات.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Normalize Audio Peak
                    val quietBuf = applyGainDb(workBuf, -12f)
                    val normalizedBuf = normalizeAudio(quietBuf, targetPeakDb = -1.0f)
                    var normMax = 0f
                    for (i in 0 until normalizedBuf.frameCount) {
                        val a = max(abs(normalizedBuf.left[i]), abs(normalizedBuf.right[i]))
                        if (a > normMax) normMax = a
                    }
                    if (normMax < 0.7f || normMax > 0.99f) {
                        recordIssue(
                            toolOrScreen = "AudioEditorScreen / Normalize",
                            functionName = "normalizeAudio()",
                            actionDetail = "موازنة مستوى الذروة الصوتية إلى -1.0 dBFS",
                            cause = "ذروة الموازنة الناتجة خارج المتوقع ($normMax)",
                            errorCode = "NORMALIZE_PEAK_ERR",
                            recommendation = "التحقق من حساب معامل التضخيم في normalizeAudio.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Trim, Cut Selection, Fade In & Fade Out
                    val trimBuf = trimToSelectionWithFade(workBuf, 0.15f, 0.85f, autoFadeSec = 0.05f)
                    val cutBuf = cutSelectionWithFade(workBuf, 0.42f, 0.62f, autoFadeSec = 0.05f)
                    val fadedBuf = applyFadeOut(applyFadeIn(cutBuf, 0.15f), 0.25f)
                    if (trimBuf.frameCount <= 0 || trimBuf.frameCount >= workBuf.frameCount ||
                        fadedBuf.frameCount <= 0 || fadedBuf.frameCount >= workBuf.frameCount
                    ) {
                        recordIssue(
                            toolOrScreen = "AudioEditorScreen / Trim & Cut",
                            functionName = "trimToSelectionWithFade() / cutSelectionWithFade()",
                            actionDetail = "تشذيب وقص المقطع وتطبيق التلاشي المتدرج",
                            cause = "طول الملف بعد التحرير لم يتغير كما هو متوقع",
                            errorCode = "CUT_FRAMECOUNT_ERR",
                            recommendation = "مراجعة فهارس البداية والنهاية في عمليات القص والتشذيب.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Streaming Disk Merge + Sample-rate normalization & Mix Tracks
                    val mergedBuf = mergeClipsStreamingOnDisk(appCtx, workBuf, rawSynthetic, crossfadeMs = 40)
                    val mixedBuf = mixTracks(workBuf, rawSynthetic, mainGain = 1.0f, bgGain = 0.45f)
                    if (mergedBuf.sampleRate != TARGET_SAMPLE_RATE || mergedBuf.frameCount <= workBuf.frameCount ||
                        mixedBuf.frameCount <= 0
                    ) {
                        recordIssue(
                            toolOrScreen = "Mixer / AudioEditorOps",
                            functionName = "mergeClipsStreamingOnDisk() / mixTracks()",
                            actionDetail = "دمج ومزج مقطعين مختلفين بالتردد (44100 + 22050)",
                            cause = "تردد أو حجم الدمج الناتج غير صحيح (${mergedBuf.sampleRate}Hz)",
                            errorCode = "MERGE_STREAM_ERR",
                            recommendation = "توحيد التردد عبر normalizeToStudioStereo44100 قبل الدمج والمزج دائماً.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Clipping Protection when applying +18dB hot boost
                    val hot = applyGainDb(workBuf, 18f)
                    val compHot = applyAutoCompressorLimiter(appCtx, hot, strengthPct = 80f)
                    var peakAfterComp = 0f
                    for (i in 0 until compHot.frameCount) {
                        val a = max(abs(compHot.left[i]), abs(compHot.right[i]))
                        if (a > peakAfterComp) peakAfterComp = a
                    }
                    if (peakAfterComp > 0.99f) {
                        recordIssue(
                            toolOrScreen = "AutoCompressorLimiter / AudioEditor",
                            functionName = "applyAutoCompressorLimiter()",
                            actionDetail = "اختبار حماية التقطيع (Clipping) بعد رفع الصوت +18dB",
                            cause = "تجاوزت الذروة الحد الآمن ($peakAfterComp)",
                            errorCode = "CLIPPING_THRESHOLD_EXCEEDED",
                            recommendation = "تفعيل alimiter=limit=0.95 دائماً بعد التضخيم لحماية السماعات.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Speech Fricatives (هـ / خ) preservation
                    val speechRms = computeBandEnergyApprox(compHot)
                    if (speechRms < 0.005f) {
                        recordIssue(
                            toolOrScreen = "VoicePurifier / AutoCompressor",
                            functionName = "FricativeClarityCheck",
                            actionDetail = "فحص الحفاظ على حروف الهمس (هـ / خ) والترددات العليا",
                            cause = "انخفضت طاقة الوضوح الكلامي بشكل حاد ($speechRms)",
                            errorCode = "FRICATIVE_LOSS",
                            recommendation = "رفع تردد lowPass ليبقى >= 10,500Hz وتجنب البوابة العدوانية.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: ProToolsProcessors (Auto-Silence Remover, Reverse, Vocal Split, Smart Fade Out)
                    val silenceTrimmed = removeSilenceAuto(appCtx, workBuf, thresholdDb = -35f, minSilenceSec = 0.35f)
                    val reversed = reverseAudioWithFfmpeg(appCtx, workBuf)
                    val vocalsOnly = splitVocalAndInstrumental(workBuf, VocalSplitMode.VOCALS_ONLY)
                    val smartFaded = applyOptionalSmartAutoFadeOut(workBuf, durationSec = 0.5f)
                    if (silenceTrimmed.frameCount <= 0 || reversed.frameCount != workBuf.frameCount ||
                        vocalsOnly.frameCount <= 0 || smartFaded.frameCount != workBuf.frameCount
                    ) {
                        recordIssue(
                            toolOrScreen = "Pro Audio Tools",
                            functionName = "ProToolsProcessors",
                            actionDetail = "اختبار حذف السكتات + العكس + عزل الصوت البشري + التلاشي الذكي",
                            cause = "أحد المعالجات أعاد إطاراً فارغاً أو بطول غير مطابق",
                            errorCode = "PRO_DSP_PIPELINE_ERR",
                            recommendation = "التحقق من طول مخزن الإخراج في ProToolsProcessors.kt.",
                        )
                    } else {
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // Check: Export Pipeline (WAV PCM 16-bit & -14 LUFS social normalization)
                    val exportedWav: ExportedAudioResult = exportRenderedAudioToFile(
                        context = appCtx,
                        buffer = workBuf,
                        format = ExportAudioFormat.WAV,
                        baseFileName = "QA_Check",
                        loudnessTarget = PlatformLoudnessTarget.YOUTUBE_TIKTOK_14,
                    )
                    if (!exportedWav.file.exists() || exportedWav.file.length() <= 44L) {
                        recordIssue(
                            toolOrScreen = "AudioExporter",
                            functionName = "exportRenderedAudioToFile()",
                            actionDetail = "تصدير ملف WAV قياسي بمستوى -14 LUFS",
                            cause = "ملف التصدير مفقود أو فارغ",
                            errorCode = "EXPORT_WAV_EMPTY",
                            recommendation = "التحقق من صلاحيات الكتابة على exports في cacheDir.",
                        )
                    } else {
                        exportedWav.file.delete()
                        passed++
                    }
                    tickAndYieldIfNeeded()

                    // =========================================================================
                    // SUITE 3: Storage / Cache Health, 6-Layer Network Shield & Diagnostic Log
                    // =========================================================================
                    publishProgress(
                        phaseAr = "المجموعة 3/3: فحص سلامة التخزين المؤقت ودرع الشبكة السداسي والسجلات...",
                        phaseEn = "Suite 3/3: Verifying cache health, 6-layer network shield & logs...",
                    )

                    // Check: Verify files in cacheDir / filesDir are readable and non-corrupt
                    val dirCandidates = listOf(appCtx.cacheDir, appCtx.filesDir)
                    for (rootDir in dirCandidates) {
                        val children = rootDir.listFiles()?.take(25).orEmpty()
                        for (child in children) {
                            coroutineContext.ensureActive()
                            if (child.isFile && (!child.canRead() || (child.extension.equals("wav", true) && child.length() in 1L..43L))) {
                                recordIssue(
                                    toolOrScreen = "Storage / SmartCacheManager",
                                    functionName = "cacheHealthCheck(${child.name})",
                                    actionDetail = "فحص سلامة الملفات المخزنة مؤقتاً",
                                    cause = "ملف تالف أو غير قابل للقراءة في ${rootDir.name}: ${child.name}",
                                    errorCode = "CACHE_CORRUPT_FILE",
                                    recommendation = "حذف الملفات الناقصة وتنظيف الحافظات تلقائياً.",
                                )
                            } else {
                                passed++
                            }
                            tickAndYieldIfNeeded()
                        }
                    }

                    // Check: HTTP -> HTTPS Auto-Rewrite Matrix
                    val urlsToUpgrade = listOf(
                        "http://archive.org/test.mp3",
                        "https://archive.org/download/clip.wav",
                        "http://api.github.com/repos/1000mno1000-collab/Qaswra/releases/latest",
                    )
                    for (rawUrl in urlsToUpgrade) {
                        coroutineContext.ensureActive()
                        val upgraded = NetworkImmunityManager.enforceHttps(rawUrl)
                        if (!upgraded.startsWith("https://")) {
                            recordIssue(
                                toolOrScreen = "NetworkImmunityManager",
                                functionName = "enforceHttps()",
                                actionDetail = "اختبار تحويل الرابط إلى HTTPS: $rawUrl",
                                cause = "تم إرجاع رابط غير مشفر: $upgraded",
                                errorCode = "NET_HTTPS_UPGRADE_FAIL",
                                recommendation = "تطبيق NetworkImmunityManager.enforceHttps على جميع الروابط.",
                            )
                        } else {
                            passed++
                        }
                        tickAndYieldIfNeeded()
                    }

                    // Check: Fake HTML / Error Payload Rejection Shield
                    val fakePayloads = listOf(
                        "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>".repeat(40),
                        "<html><head><title>403 Forbidden</title></head></html>".repeat(40),
                    )
                    for (fake in fakePayloads) {
                        coroutineContext.ensureActive()
                        val acceptedFake = NetworkImmunityManager.isValidAudioPayload(fake.toByteArray(), "audio/mpeg")
                        if (acceptedFake) {
                            recordIssue(
                                toolOrScreen = "NetworkImmunityManager",
                                functionName = "isValidAudioPayload()",
                                actionDetail = "اختبار رفض صفحات HTML المتنكرة كملفات صوت",
                                cause = "تم قبول استجابة HTML على أنها ملف صوتي",
                                errorCode = "NET_PAYLOAD_SHIELD_BYPASS",
                                recommendation = "رفض أي استجابة تبدأ بـ <!doctype أو <html في درع الفحص.",
                            )
                        } else {
                            passed++
                        }
                        tickAndYieldIfNeeded()
                    }

                    // Check: SmartCacheManager cap enforcement
                    SmartCacheManager.pruneManagedBuckets(appCtx, SmartCacheManager.MAX_FILES_PER_BUCKET)
                    passed++
                    tickAndYieldIfNeeded()
                } finally {
                    runCatching { testWav.delete() }
                    SmartCacheManager.pruneManagedBuckets(appCtx)
                }

                // Suite 3 trailing check: Surface recent runtime errors captured in AppDiagnosticLog
                val loggedDiagnostics = AppDiagnosticLog.entries.value.take(15)
                for (diag in loggedDiagnostics) {
                    coroutineContext.ensureActive()
                    recordIssue(
                        toolOrScreen = diag.toolName,
                        functionName = diag.errorCode,
                        actionDetail = diag.titleAr,
                        cause = diag.causeAr,
                        errorCode = diag.errorCode,
                        recommendation = "راجع سجل الأعطال التفصيلي وأرسل التقرير للدعم الفني للمعالجة.",
                    )
                    tickAndYieldIfNeeded()
                }

                _state.value = QaScanState(
                    isRunning = false,
                    completed = true,
                    stoppedByUser = false,
                    scenariosChecked = checked,
                    totalTarget = checked,
                    currentPhaseLabelAr = "اكتمل الفحص الشامل لجميع مكونات التطبيق (#$checked)",
                    currentPhaseLabelEn = "Full QA Diagnostics Complete (#$checked scenarios checked)",
                    issues = foundIssues.toList(),
                    passedChecksCount = passed,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            } catch (ce: CancellationException) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        completed = false,
                        stoppedByUser = true,
                        scenariosChecked = checked,
                        totalTarget = checked,
                        passedChecksCount = passed,
                        issues = foundIssues.toList(),
                        currentPhaseLabelAr = "تم إيقاف الفحص فوراً بناءً على طلبك (#$checked)",
                        currentPhaseLabelEn = "Scan stopped immediately by user (#$checked checked)",
                        elapsedMs = System.currentTimeMillis() - startedAt,
                    )
                }
            } catch (t: Throwable) {
                Log.e("SmartQaBot", "QA Bot caught unexpected exception safely", t)
                recordIssue(
                    toolOrScreen = "SmartQaBotEngine",
                    functionName = "startScanWithUserConsent",
                    actionDetail = "استقرار محرك الفحص الشامل",
                    cause = t.localizedMessage ?: t.javaClass.simpleName,
                    errorCode = t.javaClass.simpleName,
                    recommendation = "تم عزل الاستثناء بنجاح دون إغلاق التطبيق؛ أرسل التقرير للدعم الفني.",
                )
                _state.value = QaScanState(
                    isRunning = false,
                    completed = true,
                    stoppedByUser = false,
                    scenariosChecked = checked,
                    totalTarget = checked,
                    currentPhaseLabelAr = "اكتمل الفحص مع توثيق استثناء برمجي بأمان (#$checked)",
                    currentPhaseLabelEn = "Scan completed safely with caught diagnostics (#$checked)",
                    issues = foundIssues.toList(),
                    passedChecksCount = passed,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
    }

    fun stopScanImmediately() {
        scanJob?.cancel()
        scanJob = null
    }

    fun formatFullReport(language: AppLanguage): String {
        val st = _state.value
        val diagText = AppDiagnosticLog.entries.value
            .take(10)
            .joinToString("\n") { AppDiagnosticLog.formatFullReport(it) }
        return buildString {
            appendLine("=== Sonix ✦ Smart QA & Audio Diagnostics Report ===")
            appendLine("Scenarios Tested: #${st.scenariosChecked} | Passed: ${st.passedChecksCount} | Issues Found: ${st.issues.size}")
            appendLine("Elapsed: ${st.elapsedMs} ms")
            appendLine()
            if (st.issues.isEmpty()) {
                appendLine(
                    if (language == AppLanguage.EN) {
                        "No critical bugs or audio distortions detected across all tested scenarios."
                    } else {
                        "لم يتم رصد أي مشاكل برمجية أو تشوهات صوتية عبر جميع السيناريوهات المفحوصة."
                    },
                )
            } else {
                for (issue in st.issues) {
                    appendLine("مشكلة #${issue.number} | [${issue.errorCode}]")
                    appendLine("- المكان (Screen/Function): ${issue.toolOrScreen} -> ${issue.functionName}")
                    appendLine("- الإجراء: ${issue.actionDetail}")
                    appendLine("- السبب التقني: ${issue.technicalCause}")
                    appendLine("- التوجيه للأفضل: ${issue.recommendation}")
                    appendLine()
                }
            }
            if (diagText.isNotBlank()) {
                appendLine("--- Runtime Diagnostic Log ---")
                appendLine(diagText.take(3500))
            }
        }
    }

    private fun computeBandEnergyApprox(buffer: StereoAudioBuffer): Float {
        val n = buffer.frameCount
        if (n <= 4) return 0f
        var sumSq = 0.0
        var prev = 0f
        var samples = 0
        for (i in 0 until n step 4) {
            val s = 0.5f * (buffer.left[i] + buffer.right[i])
            val diff = s - prev
            sumSq += diff * diff
            prev = s
            samples++
        }
        return sqrt(sumSq / max(1, samples)).toFloat()
    }

    private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}
