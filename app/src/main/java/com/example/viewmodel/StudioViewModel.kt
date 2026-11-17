package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiTextState
import com.example.ai.StudioAiRepository
import com.example.audio.dsp.DspPipeline
import com.example.audio.dsp.FormantVoiceTransformer
import com.example.audio.dsp.SmartAutoMasterEngine
import com.example.audio.dsp.SmartMasterAnalysis
import com.example.audio.dsp.VoicePurifierEngine
import com.example.audio.dsp.VoicePurifyPreset
import com.example.audio.export.ExportAudioFormat
import com.example.audio.export.Mp3ExportCoordinator
import com.example.audio.export.Mp3ExportRequest
import com.example.audio.export.Mp3ExportState
import com.example.audio.io.AudioEditorOps
import com.example.audio.io.AudioPlayerEngine
import com.example.audio.io.AudioRecorderEngine
import com.example.audio.io.RecordingState
import com.example.audio.io.StereoAudioBuffer
import com.example.audio.io.WavAudioCodec
import com.example.audio.tts.StudioTtsEngine
import com.example.audio.tts.TtsDialect
import com.example.data.AudioRecordingEntity
import com.example.data.StudioDatabase
import com.example.data.StudioProject
import com.example.data.StudioRepository
import com.example.model.DspConfig
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.NoiseReductionParams
import com.example.model.StudioPreset
import com.example.ui.AppLanguage
import com.example.ui.LocaleHelper
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class EchoPadAxisMode(val label: String, val xLabel: String, val yLabel: String) {
    DEPTH_AND_DIFFUSION("العمق × الانتشار", "العمق والحجم", "الشدة والانتشار"),
    REFLECTIONS_BALANCE("المبكرة × المتأخرة", "الانعكاسات المبكرة", "الانعكاسات المتأخرة"),
    DISTANCE_AND_DAMPING("المسافة × التخميد", "التأخير المسبق", "التخميد والنعومة"),
    ECHO_FEEDBACK("التأخير × التكرار", "زمن الصدى", "التغذية الراجعة")
}

data class TtsStudioUiState(
    val isPreviewing: Boolean = false,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

data class StudioUiState(
    val navStack: List<WorkspaceDestination> = listOf(WorkspaceDestination.Home),
    val appLanguage: AppLanguage = AppLanguage.ARABIC,
    val projectId: Long = 0L,
    val projectName: String = "مشروع صوتي جديد",
    val dspConfig: DspConfig = DspConfig(),
    val activePresetId: Long? = null,
    val isProEchoMode: Boolean = false,
    val echoPadAxisMode: EchoPadAxisMode = EchoPadAxisMode.DEPTH_AND_DIFFUSION,
    val hasAudio: Boolean = false,
    val durationMs: Long = 0L,
    val waveformPeaks: List<Float> = emptyList(),
    val trimStart: Float = 0f,
    val trimEnd: Float = 1f,
    val loopSelection: Boolean = false,
    val isAutoLoopEnabled: Boolean = true,
    val canUndoEdit: Boolean = false,
    val canRedoEdit: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isBusy: Boolean = false,
    val exportProgress: Float? = null,
    val purifyProgress: Float? = null,
    val importProgress: Float? = null,
    val importStatusText: String = "",
    val normalizeBeforeExport: Boolean = true,
    val isHoldBypassing: Boolean = false,
    val mixBackgroundName: String? = null,
    val mixBackgroundDurationMs: Long = 0L,
    val statusBanner: String? = null
) {
    val currentDestination: WorkspaceDestination
        get() = navStack.lastOrNull() ?: WorkspaceDestination.Home

    val effectiveBypassAll: Boolean
        get() = dspConfig.bypassAll || isHoldBypassing
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StudioDatabase.getInstance(application)
    private val repository = StudioRepository(application, database.studioDao())
    private val aiRepository by lazy { StudioAiRepository(getApplication()) }

    private val recorder = AudioRecorderEngine()
    private val player = AudioPlayerEngine()
    private val ttsEngine by lazy { StudioTtsEngine(getApplication()) }

    private val _uiState = MutableStateFlow(
        StudioUiState(appLanguage = LocaleHelper.getSavedLanguage(application))
    )
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _ttsUiState = MutableStateFlow(TtsStudioUiState())
    val ttsUiState: StateFlow<TtsStudioUiState> = _ttsUiState.asStateFlow()

    private val _transcriptionState = MutableStateFlow<AiTextState>(AiTextState.Idle)
    val transcriptionState: StateFlow<AiTextState> = _transcriptionState.asStateFlow()

    private val _smartMasterState = MutableStateFlow<SmartMasterAnalysis?>(null)
    val smartMasterState: StateFlow<SmartMasterAnalysis?> = _smartMasterState.asStateFlow()

    private val _searchAssistantState = MutableStateFlow<AiTextState>(AiTextState.Idle)
    val searchAssistantState: StateFlow<AiTextState> = _searchAssistantState.asStateFlow()

    private var transcribeJob: Job? = null
    private var searchJob: Job? = null

    val recordingState: StateFlow<RecordingState> = recorder.state
    val recordingLevel: StateFlow<Float> = recorder.inputLevel
    val recordingDurationMs: StateFlow<Long> = recorder.durationMs
    val recordingWaveform: StateFlow<List<Float>> = recorder.liveWaveform
    val recordingLiveSamples: StateFlow<FloatArray> = recorder.liveWaveformSamples

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<Float> = player.progress
    val outputLevel: StateFlow<Float> = player.outputLevel
    val gainReductionDb: StateFlow<Float> = player.gainReductionDb
    val noiseSuppressionDb: StateFlow<Float> = player.noiseSuppressionDb
    val playbackLiveSamples: StateFlow<FloatArray> = player.liveOutputSamples

    val mp3ExportState: StateFlow<Mp3ExportState> = Mp3ExportCoordinator.state

    val savedProjects: StateFlow<List<StudioProject>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedRecordings: StateFlow<List<AudioRecordingEntity>> = repository.recordings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<StudioPreset>> = repository.allPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var activeBuffer: StereoAudioBuffer? = null
    private var pendingMixBackgroundBuffer: StereoAudioBuffer? = null
    private val undoStack = mutableListOf<StereoAudioBuffer>()
    private val redoStack = mutableListOf<StereoAudioBuffer>()

    init {
        player.setAutoLoop(true)
        viewModelScope.launch {
            LocaleHelper.observeLanguage(getApplication()).collect { savedLang ->
                _uiState.update { it.copy(appLanguage = savedLang) }
            }
        }
    }

    // --- Focus Workspace Navigation ---

    fun navigateToSection(section: WorkspaceDestination) {
        _uiState.update { state ->
            state.copy(navStack = listOf(WorkspaceDestination.Home, section))
        }
    }

    fun navigateToDetail(detail: WorkspaceDestination) {
        _uiState.update { state ->
            state.copy(navStack = state.navStack + detail)
        }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value.navStack
        if (current.size <= 1) return false
        _uiState.update { it.copy(navStack = current.dropLast(1)) }
        return true
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    fun setAppLanguage(language: AppLanguage) {
        LocaleHelper.saveLanguage(getApplication(), language)
        _uiState.update { it.copy(appLanguage = language) }
        viewModelScope.launch {
            LocaleHelper.saveLanguageToDataStore(getApplication(), language)
        }
    }

    // --- Recording Controls ---

    fun startOrResumeRecording(): Boolean {
        if (player.isPlaying.value) player.pause()
        val ok = recorder.startOrResume()
        if (!ok) {
            postBanner("تعذر فتح الميكروفون. يرجى التحقق من الصلاحية.")
        }
        return ok
    }

    fun pauseRecording() {
        recorder.pause()
    }

    fun finishRecording() {
        val recorded = recorder.stopAndBuildBuffer() ?: return
        setActiveBuffer(recorded, recordUndo = false)
        val title = formatRecordingTitle("تسجيل")
        viewModelScope.launch {
            runCatching {
                repository.indexAudioRecording(
                    title = title,
                    buffer = recorded,
                    sourceType = "RECORDING"
                )
            }
        }
        postBanner("تم التقاط التسجيل الصوتي وفهرسته بنجاح")
    }

    fun discardRecording() {
        recorder.discard()
    }

    // --- Playback & Transport ---

    fun togglePlayback() {
        if (player.isPlaying.value) {
            player.pause()
        } else {
            val s = _uiState.value
            player.setLoopRange(s.loopSelection, s.trimStart, s.trimEnd)
            player.setAutoLoop(s.isAutoLoopEnabled)
            player.updateDspConfig(s.dspConfig)
            player.play()
        }
    }

    fun stopPlayback() {
        player.stop()
    }

    fun seekPlayback(fraction: Float) {
        player.seekTo(fraction)
    }

    fun toggleLoopSelection() {
        _uiState.update { s ->
            val next = !s.loopSelection
            player.setLoopRange(next, s.trimStart, s.trimEnd)
            s.copy(loopSelection = next)
        }
    }

    fun toggleAutoLoop() {
        _uiState.update { state ->
            val next = !state.isAutoLoopEnabled
            player.setAutoLoop(next)
            state.copy(isAutoLoopEnabled = next)
        }
    }

    fun toggleBypassAll() {
        _uiState.update { state ->
            val nextCfg = state.dspConfig.copy(bypassAll = !state.dspConfig.bypassAll)
            player.updateDspConfig(nextCfg)
            state.copy(
                dspConfig = nextCfg,
                isHoldBypassing = false,
                activePresetId = null,
                hasUnsavedChanges = state.hasUnsavedChanges || state.hasAudio || state.projectId > 0L
            )
        }
    }

    fun setHoldBypass(holding: Boolean) {
        _uiState.update { state ->
            val effectiveCfg = if (holding) {
                state.dspConfig.copy(bypassAll = true)
            } else {
                state.dspConfig
            }
            player.updateDspConfig(effectiveCfg)
            state.copy(isHoldBypassing = holding)
        }
    }

    fun toggleNormalizeBeforeExport() {
        _uiState.update { it.copy(normalizeBeforeExport = !it.normalizeBeforeExport) }
    }

    // --- DSP Configuration & Chain ---

    fun updateDspConfig(transform: (DspConfig) -> DspConfig) {
        _uiState.update { state ->
            val next = transform(state.dspConfig)
            val playerCfg = if (state.isHoldBypassing) next.copy(bypassAll = true) else next
            player.updateDspConfig(playerCfg)
            state.copy(
                dspConfig = next,
                activePresetId = null,
                hasUnsavedChanges = state.hasUnsavedChanges || state.hasAudio || state.projectId > 0L
            )
        }
    }

    fun updateEchoReverb(transform: (EchoReverbParams) -> EchoReverbParams) {
        updateDspConfig { cfg ->
            val updatedChain = cfg.chain.map {
                if (it.type == EffectType.ECHO_REVERB) it.copy(enabled = true) else it
            }
            cfg.copy(
                bypassAll = false,
                chain = updatedChain,
                echoReverb = transform(cfg.echoReverb)
            )
        }
    }

    fun toggleEchoMode(isPro: Boolean) {
        _uiState.update { it.copy(isProEchoMode = isPro) }
    }

    fun setEchoPadAxisMode(mode: EchoPadAxisMode) {
        _uiState.update { it.copy(echoPadAxisMode = mode) }
    }

    fun applyEchoSpatialTouch(normX: Float, normY: Float) {
        val x = normX.coerceIn(0f, 1f)
        val y = normY.coerceIn(0f, 1f)
        val state = _uiState.value

        updateEchoReverb { cur ->
            if (!state.isProEchoMode) {
                // Simple intuitive mode: X = Acoustic Distance/Size, Y = Echo Intensity & Diffusion
                cur.copy(
                    reverbSize = (0.18f + x * 0.80f).coerceIn(0.1f, 0.98f),
                    preDelayMs = (x * 65f).coerceIn(0f, 150f),
                    reverbDecay = (0.25f + x * 0.62f).coerceIn(0.1f, 0.92f),
                    echoStrength = (0.08f + y * 0.68f).coerceIn(0f, 1f),
                    echoDepth = (0.15f + y * 0.75f).coerceIn(0f, 1f),
                    diffusion = (0.30f + y * 0.65f).coerceIn(0f, 1f),
                    wetDryMix = (0.12f + (x * 0.35f + y * 0.45f)).coerceIn(0.08f, 0.88f),
                    earlyReflections = (0.25f + (1f - x) * 0.45f + y * 0.2f).coerceIn(0f, 1f),
                    lateReflections = (0.20f + x * 0.65f).coerceIn(0f, 1f)
                )
            } else {
                when (state.echoPadAxisMode) {
                    EchoPadAxisMode.DEPTH_AND_DIFFUSION -> cur.copy(
                        echoDepth = x,
                        reverbSize = (0.15f + x * 0.83f).coerceIn(0f, 1f),
                        diffusion = y,
                        echoStrength = (0.10f + y * 0.78f).coerceIn(0f, 1f),
                        wetDryMix = (0.15f + y * 0.65f).coerceIn(0f, 0.95f)
                    )
                    EchoPadAxisMode.REFLECTIONS_BALANCE -> cur.copy(
                        earlyReflections = x,
                        lateReflections = y
                    )
                    EchoPadAxisMode.DISTANCE_AND_DAMPING -> cur.copy(
                        preDelayMs = x * 140f,
                        stereoWidth = 0.5f + x * 1.4f,
                        damping = y
                    )
                    EchoPadAxisMode.ECHO_FEEDBACK -> cur.copy(
                        delayTimeMs = 40f + x * 1000f,
                        feedback = (y * 0.85f).coerceIn(0f, 0.90f)
                    )
                }
            }
        }
    }

    fun toggleEffectEnabled(type: EffectType) {
        updateDspConfig { cfg ->
            cfg.copy(
                chain = cfg.chain.map { slot ->
                    if (slot.type == type) slot.copy(enabled = !slot.enabled) else slot
                }
            )
        }
    }

    fun moveEffectInChain(type: EffectType, moveUp: Boolean) {
        updateDspConfig { cfg ->
            val list = cfg.chain.toMutableList()
            val idx = list.indexOfFirst { it.type == type }
            if (idx < 0) return@updateDspConfig cfg
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in list.indices) return@updateDspConfig cfg
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            cfg.copy(chain = list)
        }
    }

    // --- Voice Purification & Noise Cleaning ---

    fun updateNoiseReduction(transform: (NoiseReductionParams) -> NoiseReductionParams) {
        updateDspConfig { cfg ->
            val updatedChain = cfg.chain.map {
                if (it.type == EffectType.NOISE_REDUCTION) it.copy(enabled = true) else it
            }
            cfg.copy(
                bypassAll = false,
                chain = updatedChain,
                noiseReduction = transform(cfg.noiseReduction)
            )
        }
    }

    fun applyVoicePurifyPreset(preset: VoicePurifyPreset) {
        updateDspConfig { cfg ->
            val updatedChain = cfg.chain.map {
                if (it.type == EffectType.NOISE_REDUCTION) it.copy(enabled = true) else it
            }
            cfg.copy(
                bypassAll = false,
                chain = updatedChain,
                noiseReduction = preset.params
            )
        }
        postBanner("تم تفعيل وضع التصفية: ${preset.title}")
    }

    fun runDeepVoicePurificationOffline() {
        val source = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتصفيته")
            return
        }
        val params = _uiState.value.dspConfig.noiseReduction
        viewModelScope.launch {
            _uiState.update { it.copy(purifyProgress = 0.02f) }
            val purified = withContext(Dispatchers.Default) {
                VoicePurifierEngine.purifyBufferOffline(source, params) { p ->
                    _uiState.update { it.copy(purifyProgress = p) }
                }
            }
            // Disable live double-gating if user already baked deep clean into buffer?
            // Keep live chain state intact OR let user listen immediately without over-gating:
            // Actually disabling redundant NOISE_REDUCTION slot avoids double-filtering the already-cleaned clip,
            // while keeping undoStack so user can A/B compare or undo anytime!
            setActiveBuffer(purified, recordUndo = true)
            updateDspConfig { cfg ->
                cfg.copy(
                    chain = cfg.chain.map { slot ->
                        if (slot.type == EffectType.NOISE_REDUCTION) slot.copy(enabled = false) else slot
                    }
                )
            }
            _uiState.update { it.copy(purifyProgress = null) }
            postBanner("تمت تصفية الصوت في الذاكرة — احفظ المشروع لتثبيت التعديلات")
        }
    }

    // --- Audio Importing & Editing ---

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            val importActive = AtomicBoolean(true)
            _uiState.update {
                it.copy(
                    isBusy = true,
                    importProgress = 0f,
                    importStatusText = "جاري نسخ الملف الصوتي..."
                )
            }
            try {
                val decoded = repository.importAudioFromUri(uri) { progress, status ->
                    if (importActive.get()) {
                        _uiState.update {
                            it.copy(
                                importProgress = progress.coerceIn(0f, 1f),
                                importStatusText = status
                            )
                        }
                    }
                }
                if (decoded != null) {
                    setActiveBuffer(decoded, recordUndo = false)
                    val displayName = repository.queryUriDisplayName(uri)
                        ?: formatRecordingTitle("ملف مستورد")
                    runCatching {
                        repository.indexAudioRecording(
                            title = displayName,
                            buffer = decoded,
                            sourceType = "IMPORTED"
                        )
                    }
                    postBanner("تم استيراد الملف الصوتي وفهرسته بنجاح")
                } else {
                    postBanner("تعذر فك تشفير الملف الصوتي المحدد")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                postBanner("تعذر فك تشفير الملف الصوتي المحدد")
            } finally {
                importActive.set(false)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        importProgress = null,
                        importStatusText = ""
                    )
                }
            }
        }
    }

    fun updateTrimRange(start: Float, end: Float) {
        val clampedStart = start.coerceIn(0f, 0.98f)
        val clampedEnd = end.coerceIn(clampedStart + 0.02f, 1f)
        _uiState.update { state ->
            player.setLoopRange(state.loopSelection, clampedStart, clampedEnd)
            state.copy(trimStart = clampedStart, trimEnd = clampedEnd)
        }
    }

    fun resetTrimSelection() {
        updateTrimRange(0f, 1f)
    }

    fun trimKeepSelection() {
        val current = activeBuffer ?: return
        val s = _uiState.value
        val trimmed = AudioEditorOps.trimToSelection(current, s.trimStart, s.trimEnd)
        setActiveBuffer(trimmed, recordUndo = true)
        postBanner("تم اقتصاص المقطع المحدد")
    }

    fun cutSelectedRegion() {
        val current = activeBuffer ?: return
        val s = _uiState.value
        val cut = AudioEditorOps.cutSelection(current, s.trimStart, s.trimEnd)
        setActiveBuffer(cut, recordUndo = true)
        postBanner("تم حذف الجزء المحدد بسلاسة")
    }

    fun normalizeAudio() {
        val current = activeBuffer ?: return
        val norm = AudioEditorOps.normalize(current)
        setActiveBuffer(norm, recordUndo = true)
        postBanner("تمت موازنة مستوى الصوت")
    }

    fun bakeClipGain(gainDb: Float) {
        val current = activeBuffer ?: return
        val gained = AudioEditorOps.applyGainDb(current, gainDb)
        setActiveBuffer(gained, recordUndo = true)
        postBanner("تم تطبيق تعديل الشدة على المقطع")
    }

    fun applyFadeInEffect(durationSec: Float) {
        val current = activeBuffer ?: return
        viewModelScope.launch {
            val faded = withContext(Dispatchers.Default) {
                AudioEditorOps.applyFadeIn(current, durationSec)
            }
            setActiveBuffer(faded, recordUndo = true)
            postBanner("تم تطبيق الظهور التدريجي (${String.format(java.util.Locale.US, "%.1f", durationSec)} ث)")
        }
    }

    fun applyFadeOutEffect(durationSec: Float) {
        val current = activeBuffer ?: return
        viewModelScope.launch {
            val faded = withContext(Dispatchers.Default) {
                AudioEditorOps.applyFadeOut(current, durationSec)
            }
            setActiveBuffer(faded, recordUndo = true)
            postBanner("تم تطبيق الاختفاء التدريجي (${String.format(java.util.Locale.US, "%.1f", durationSec)} ث)")
        }
    }

    fun importMixBackgroundUri(uri: Uri) {
        viewModelScope.launch {
            val importActive = AtomicBoolean(true)
            _uiState.update {
                it.copy(
                    isBusy = true,
                    importProgress = 0f,
                    importStatusText = "جاري نسخ مسار الخلفية للمزج..."
                )
            }
            try {
                val decoded = repository.importAudioFromUri(uri) { progress, status ->
                    if (importActive.get()) {
                        _uiState.update {
                            it.copy(
                                importProgress = progress.coerceIn(0f, 1f),
                                importStatusText = status
                            )
                        }
                    }
                }
                if (decoded != null) {
                    pendingMixBackgroundBuffer = decoded
                    val bgName = repository.queryUriDisplayName(uri) ?: "مسار الخلفية"
                    _uiState.update {
                        it.copy(
                            mixBackgroundName = bgName,
                            mixBackgroundDurationMs = decoded.durationMs
                        )
                    }
                    postBanner("تم تجهيز \"$bgName\" — اضبط مستوى الصوت ثم اضغط تطبيق المزج")
                } else {
                    postBanner("تعذر استيراد مسار الخلفية للمزج")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                postBanner("تعذر استيراد مسار الخلفية للمزج")
            } finally {
                importActive.set(false)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        importProgress = null,
                        importStatusText = ""
                    )
                }
            }
        }
    }

    fun clearMixBackgroundTrack() {
        pendingMixBackgroundBuffer = null
        _uiState.update {
            it.copy(
                mixBackgroundName = null,
                mixBackgroundDurationMs = 0L
            )
        }
    }

    fun applyDualTrackMix(mainGain: Float, backgroundGain: Float) {
        val current = activeBuffer ?: run {
            postBanner("لا يوجد صوت رئيسي حالياً")
            return
        }
        val bg = pendingMixBackgroundBuffer ?: run {
            postBanner("استورد مسار خلفية أولاً للمزج الصوتي")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val mixed = withContext(Dispatchers.Default) {
                AudioEditorOps.mixTracks(
                    mainBuffer = current,
                    backgroundBuffer = bg,
                    mainGain = mainGain,
                    backgroundGain = backgroundGain
                )
            }
            setActiveBuffer(mixed, recordUndo = true)
            pendingMixBackgroundBuffer = null
            _uiState.update {
                it.copy(
                    isBusy = false,
                    mixBackgroundName = null,
                    mixBackgroundDurationMs = 0L
                )
            }
            postBanner("تم مزج المسارين بنجاح — يمكنك التراجع أو التصدير الآن")
        }
    }

    fun mergeExternalAudio(uri: Uri) {
        val current = activeBuffer ?: run {
            importAudioUri(uri)
            return
        }
        viewModelScope.launch {
            val importActive = AtomicBoolean(true)
            _uiState.update {
                it.copy(
                    isBusy = true,
                    importProgress = 0f,
                    importStatusText = "جاري نسخ الملف الصوتي..."
                )
            }
            try {
                val second = repository.importAudioFromUri(uri) { progress, status ->
                    if (importActive.get()) {
                        _uiState.update {
                            it.copy(
                                importProgress = progress.coerceIn(0f, 1f),
                                importStatusText = status
                            )
                        }
                    }
                }
                if (second != null) {
                    val merged = withContext(Dispatchers.Default) {
                        AudioEditorOps.mergeClips(current, second)
                    }
                    setActiveBuffer(merged, recordUndo = true)
                    postBanner("تم دمج المقطعين بسلاسة")
                } else {
                    postBanner("تعذر استيراد المقطع المراد دمجه")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                postBanner("تعذر استيراد المقطع المراد دمجه")
            } finally {
                importActive.set(false)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        importProgress = null,
                        importStatusText = ""
                    )
                }
            }
        }
    }

    fun mergeSavedProject(project: StudioProject) {
        val current = activeBuffer
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val second = repository.loadProjectAudio(project)
            _uiState.update { it.copy(isBusy = false) }
            if (second == null) {
                postBanner("هذا المشروع لا يحتوي على ملف صوتي")
                return@launch
            }
            if (current == null) {
                setActiveBuffer(second, recordUndo = false)
            } else {
                val merged = withContext(Dispatchers.Default) {
                    AudioEditorOps.mergeClips(current, second)
                }
                setActiveBuffer(merged, recordUndo = true)
            }
            postBanner("تم دمج مشروع \"${project.name}\"")
        }
    }

    fun undoLastEdit() {
        val prev = undoStack.removeLastOrNull() ?: return
        activeBuffer?.let { cur ->
            if (redoStack.size >= 12) redoStack.removeAt(0)
            redoStack.add(cur)
        }
        activeBuffer = prev
        player.setBuffer(prev, resetPosition = true)
        _uiState.update {
            it.copy(
                hasAudio = true,
                durationMs = prev.durationMs,
                waveformPeaks = prev.computeWaveformPeaks(120),
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true
            )
        }
        postBanner("تم التراجع عن التعديل الأخير")
    }

    fun redoLastEdit() {
        val next = redoStack.removeLastOrNull() ?: return
        activeBuffer?.let { cur ->
            if (undoStack.size >= 12) undoStack.removeAt(0)
            undoStack.add(cur)
        }
        activeBuffer = next
        player.setBuffer(next, resetPosition = true)
        _uiState.update {
            it.copy(
                hasAudio = true,
                durationMs = next.durationMs,
                waveformPeaks = next.computeWaveformPeaks(120),
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true
            )
        }
        postBanner("تمت إعادة التعديل")
    }

    fun discardUnsavedChanges() {
        _uiState.update { it.copy(hasUnsavedChanges = false) }
    }

    // --- Presets ---

    fun applyPreset(preset: StudioPreset) {
        _uiState.update { state ->
            player.updateDspConfig(preset.config)
            state.copy(
                dspConfig = preset.config,
                activePresetId = preset.id,
                hasUnsavedChanges = state.hasUnsavedChanges || state.hasAudio || state.projectId > 0L
            )
        }
        postBanner("تم تفعيل قالب: ${preset.name}")
    }

    fun saveCurrentAsPreset(name: String, description: String) {
        if (name.isBlank()) return
        val cfg = _uiState.value.dspConfig
        viewModelScope.launch {
            repository.saveCustomPreset(name, description, cfg)
            postBanner("تم حفظ القالب \"$name\"")
        }
    }

    fun deleteCustomPreset(preset: StudioPreset) {
        if (preset.isBuiltIn) return
        viewModelScope.launch {
            repository.deleteCustomPreset(preset.id)
            postBanner("تم حذف القالب")
        }
    }

    // --- Projects & Export ---

    fun renameCurrentProject(name: String) {
        _uiState.update { it.copy(projectName = name) }
    }

    fun saveCurrentProject(customName: String? = null, onSaved: (() -> Unit)? = null) {
        val s = _uiState.value
        val finalName = (customName ?: s.projectName).ifBlank { "مشروع صوتي" }
        val bufferSnapshot = activeBuffer
        val configSnapshot = s.dspConfig
        val existingIdSnapshot = s.projectId
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val saved = repository.saveProject(
                existingId = existingIdSnapshot,
                name = finalName,
                buffer = bufferSnapshot,
                config = configSnapshot
            )
            _uiState.update {
                it.copy(
                    projectId = saved.id,
                    projectName = saved.name,
                    hasUnsavedChanges = false,
                    isBusy = false
                )
            }
            postBanner("تم حفظ المشروع \"${saved.name}\"")
            onSaved?.invoke()
        }
    }

    fun createNewProject() {
        player.stop()
        recorder.discard()
        activeBuffer = null
        undoStack.clear()
        redoStack.clear()
        val fresh = DspConfig()
        player.setBuffer(null)
        player.updateDspConfig(fresh)
        _uiState.update {
            it.copy(
                projectId = 0L,
                projectName = "مشروع صوتي جديد",
                dspConfig = fresh,
                activePresetId = null,
                hasAudio = false,
                durationMs = 0L,
                waveformPeaks = emptyList(),
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = false,
                canRedoEdit = false,
                hasUnsavedChanges = false
            )
        }
        postBanner("تم فتح مساحة مشروع جديد")
    }

    fun loadProject(project: StudioProject) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val loadedAudio = repository.loadProjectAudio(project)
            undoStack.clear()
            redoStack.clear()
            activeBuffer = loadedAudio
            player.setBuffer(loadedAudio, resetPosition = true)
            player.updateDspConfig(project.config)
            _uiState.update {
                it.copy(
                    projectId = project.id,
                    projectName = project.name,
                    dspConfig = project.config,
                    activePresetId = null,
                    hasAudio = loadedAudio != null,
                    durationMs = loadedAudio?.durationMs ?: 0L,
                    waveformPeaks = loadedAudio?.computeWaveformPeaks(120) ?: emptyList(),
                    trimStart = 0f,
                    trimEnd = 1f,
                    canUndoEdit = false,
                    canRedoEdit = false,
                    hasUnsavedChanges = false,
                    isBusy = false
                )
            }
            postBanner("تم فتح مشروع \"${project.name}\"")
        }
    }

    fun deleteProject(project: StudioProject) {
        viewModelScope.launch {
            repository.deleteProject(project)
            if (_uiState.value.projectId == project.id) {
                _uiState.update { it.copy(projectId = 0L) }
            }
            postBanner("تم حذف المشروع")
        }
    }

    fun loadIndexedRecording(recording: AudioRecordingEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val loaded = repository.loadRecordingAudio(recording)
            _uiState.update { it.copy(isBusy = false) }
            if (loaded == null) {
                postBanner("تعذر قراءة الملف الصوتي من الذاكرة")
                return@launch
            }
            setActiveBuffer(loaded, recordUndo = false)
            _uiState.update { it.copy(projectName = recording.title) }
            postBanner("تم تحميل \"${recording.title}\"")
        }
    }

    fun deleteIndexedRecording(recording: AudioRecordingEntity) {
        viewModelScope.launch {
            repository.deleteRecording(recording)
            postBanner("تم حذف التسجيل المفهرس")
        }
    }

    fun exportProcessedWav(destinationUri: Uri) {
        exportAudioWithFormat(destinationUri, ExportAudioFormat.WAV, _uiState.value.projectName)
    }

    fun exportAudioWithFormat(
        destinationUri: Uri,
        format: ExportAudioFormat,
        customFileName: String
    ) {
        val source = activeBuffer ?: run {
            postBanner("لا يوجد صوت لتصديره")
            return
        }
        val state = _uiState.value
        val config = state.dspConfig
        val normalize = state.normalizeBeforeExport
        val cleanName = customFileName.trim().ifBlank { state.projectName.ifBlank { "VoicePro_Export" } }
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = 0.01f, projectName = cleanName) }
            val rendered = withContext(Dispatchers.Default) {
                val processed = DspPipeline(source.sampleRate).renderOffline(source, config) { p ->
                    _uiState.update { it.copy(exportProgress = (p * 0.55f).coerceIn(0.01f, 0.55f)) }
                }
                if (normalize) AudioEditorOps.normalize(processed, targetPeak = 0.944f) else processed
            }
            val success = repository.exportFormattedToUri(
                uri = destinationUri,
                rendered = rendered,
                format = format
            ) { encFrac ->
                _uiState.update {
                    it.copy(exportProgress = (0.55f + encFrac * 0.45f).coerceIn(0.55f, 1f))
                }
            }
            _uiState.update { it.copy(exportProgress = null) }
            postBanner(
                if (success) {
                    "تم حفظ وتصدير الملف بصيغة ${format.displayName} بنجاح"
                } else {
                    "فشل تصدير الملف بصيغة ${format.displayName}"
                }
            )
        }
    }

    fun prepareShareWav(onUriReady: (Uri) -> Unit) {
        val source = activeBuffer ?: run {
            postBanner("لا يوجد صوت لمشاركته")
            return
        }
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = 0.01f) }
            val rendered = withContext(Dispatchers.Default) {
                val processed = DspPipeline(source.sampleRate).renderOffline(source, state.dspConfig) { p ->
                    _uiState.update { it.copy(exportProgress = p) }
                }
                if (state.normalizeBeforeExport) {
                    AudioEditorOps.normalize(processed, targetPeak = 0.944f)
                } else {
                    processed
                }
            }
            val uri = repository.createShareWavUri(state.projectName, rendered)
            _uiState.update { it.copy(exportProgress = null) }
            onUriReady(uri)
        }
    }

    fun exportProcessedMp3(destinationUri: Uri) {
        val source = activeBuffer ?: run {
            postBanner("لا يوجد صوت لتصديره")
            return
        }
        val state = _uiState.value
        Mp3ExportCoordinator.submitAndStart(
            context = getApplication(),
            request = Mp3ExportRequest(
                sourceBuffer = source,
                dspConfig = state.dspConfig,
                destinationUri = destinationUri,
                fileName = state.projectName.ifBlank { "VoicePro_Export" },
                isShare = false,
                normalizeBeforeExport = state.normalizeBeforeExport
            )
        )
    }

    fun prepareShareMp3() {
        val source = activeBuffer ?: run {
            postBanner("لا يوجد صوت لمشاركته")
            return
        }
        val state = _uiState.value
        Mp3ExportCoordinator.submitAndStart(
            context = getApplication(),
            request = Mp3ExportRequest(
                sourceBuffer = source,
                dspConfig = state.dspConfig,
                destinationUri = null,
                fileName = state.projectName.ifBlank { "VoicePro_Export" },
                isShare = true,
                normalizeBeforeExport = state.normalizeBeforeExport
            )
        )
    }

    fun acknowledgeMp3ExportState(bannerMessage: String? = null) {
        Mp3ExportCoordinator.acknowledgeTerminalState()
        if (!bannerMessage.isNullOrBlank()) {
            postBanner(bannerMessage)
        }
    }

    // --- Firebase AI Logic Features ---

    fun transcribeActiveAudio(languageHint: String) {
        val buf = activeBuffer ?: run {
            _transcriptionState.value = AiTextState.Error("لا يوجد تسجيل أو مقطع صوتي حالياً لتفريغه.")
            return
        }
        runTranscription(buf, languageHint)
    }

    fun stopMicAndTranscribe(languageHint: String) {
        val recorded = recorder.stopAndBuildBuffer() ?: run {
            _transcriptionState.value = AiTextState.Error("لم يتم التقاط صوت كافٍ للتفريغ.")
            return
        }
        setActiveBuffer(recorded, recordUndo = false)
        val title = formatRecordingTitle("تفريغ صوتي")
        viewModelScope.launch {
            runCatching {
                repository.indexAudioRecording(
                    title = title,
                    buffer = recorded,
                    sourceType = "RECORDING"
                )
            }
        }
        runTranscription(recorded, languageHint)
    }

    private fun runTranscription(buffer: StereoAudioBuffer, languageHint: String) {
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            aiRepository.transcribeAudioStream(buffer, languageHint).collect { st ->
                _transcriptionState.value = st
            }
        }
    }

    fun runSmartAutoMaster() {
        val source = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتحليله")
            return
        }
        val currentCfg = _uiState.value.dspConfig
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val analysis = withContext(Dispatchers.Default) {
                SmartAutoMasterEngine.analyzeAndOptimize(source, currentCfg)
            }
            _smartMasterState.value = analysis
            _uiState.update { state ->
                val playerCfg = if (state.isHoldBypassing) {
                    analysis.optimizedConfig.copy(bypassAll = true)
                } else {
                    analysis.optimizedConfig
                }
                player.updateDspConfig(playerCfg)
                state.copy(
                    dspConfig = analysis.optimizedConfig,
                    activePresetId = null,
                    hasUnsavedChanges = true,
                    isBusy = false
                )
            }
            postBanner("تم تحليل الصوت وتطبيق الماسترينغ الذكي تلقائياً")
        }
    }

    fun clearSmartMasterAnalysis() {
        _smartMasterState.value = null
    }

    fun askSearchAssistant(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            aiRepository.askWithGoogleSearch(query).collect { st ->
                _searchAssistantState.value = st
            }
        }
    }

    // --- Free Text-to-Speech Studio (10 Dialects) ---

    fun previewTtsSpeech(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float
    ) {
        if (text.isBlank()) {
            _ttsUiState.update { it.copy(errorMessage = "يرجى إدخال النص أولاً.") }
            return
        }
        viewModelScope.launch {
            _ttsUiState.update { it.copy(isPreviewing = true, errorMessage = null) }
            ttsEngine.speakPreview(text, dialect, speechRate, pitch) { _, err ->
                _ttsUiState.update { it.copy(isPreviewing = false, errorMessage = err) }
            }
        }
    }

    fun stopTtsPreview() {
        ttsEngine.stopPreview()
        _ttsUiState.update { it.copy(isPreviewing = false) }
    }

    fun generateTtsToStudio(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        appendToExisting: Boolean
    ) {
        if (text.isBlank()) {
            _ttsUiState.update { it.copy(errorMessage = "يرجى إدخال نص لتوليد الصوت.") }
            return
        }
        viewModelScope.launch {
            _ttsUiState.update { it.copy(isGenerating = true, errorMessage = null) }
            _uiState.update { it.copy(isBusy = true) }
            val app = getApplication<Application>()
            val outFile = File(app.cacheDir, "tts_synth_${System.currentTimeMillis()}.wav")
            try {
                val synthResult = ttsEngine.synthesizeToWavFile(
                    text = text,
                    dialect = dialect,
                    speechRate = speechRate,
                    pitch = pitch,
                    outFile = outFile
                )
                val synthesizedFile = synthResult.getOrElse { err ->
                    _ttsUiState.update {
                        it.copy(isGenerating = false, errorMessage = err.message ?: "تعذر توليد الصوت")
                    }
                    _uiState.update { it.copy(isBusy = false) }
                    return@launch
                }

                val decoded = withContext(Dispatchers.IO) {
                    WavAudioCodec.decodeAudioUri(app, Uri.fromFile(synthesizedFile))
                }
                if (decoded == null || decoded.frameCount == 0) {
                    _ttsUiState.update {
                        it.copy(isGenerating = false, errorMessage = "فشل قراءة ملف الصوت المولّد.")
                    }
                    _uiState.update { it.copy(isBusy = false) }
                    return@launch
                }

                val current = activeBuffer
                val nextBuffer = if (appendToExisting && current != null) {
                    withContext(Dispatchers.Default) {
                        AudioEditorOps.mergeClips(current, decoded)
                    }
                } else {
                    decoded
                }
                setActiveBuffer(nextBuffer, recordUndo = current != null)
                val title = formatRecordingTitle("TTS")
                runCatching {
                    repository.indexAudioRecording(
                        title = title,
                        buffer = decoded,
                        sourceType = "TTS"
                    )
                }
                _ttsUiState.update { it.copy(isGenerating = false, errorMessage = null) }
                _uiState.update { it.copy(isBusy = false) }
                postBanner("تم توليد الصوت وإدراجه في الاستوديو بنجاح")
            } finally {
                runCatching { if (outFile.exists()) outFile.delete() }
            }
        }
    }

    // --- Offline Formant-Preserving Voice Transformer ---

    fun transformVoiceOffline(
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        mix: Float,
        characterLabel: String
    ) {
        val source = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتحويله")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, purifyProgress = 0.02f) }
            val transformed = withContext(Dispatchers.Default) {
                FormantVoiceTransformer.transformOffline(
                    source = source,
                    pitchSemitones = pitchSemitones,
                    formantShift = formantShift,
                    warmthDb = warmthDb,
                    presenceDb = presenceDb,
                    mix = mix
                ) { p ->
                    _uiState.update { it.copy(purifyProgress = p) }
                }
            }
            setActiveBuffer(transformed, recordUndo = true)
            _uiState.update { it.copy(isBusy = false, purifyProgress = null) }
            postBanner("تم تحويل الشخصية الصوتية ($characterLabel) بنجاح")
        }
    }

    private fun setActiveBuffer(buffer: StereoAudioBuffer, recordUndo: Boolean) {
        if (recordUndo) {
            activeBuffer?.let { prev ->
                if (undoStack.size >= 12) undoStack.removeAt(0)
                undoStack.add(prev)
            }
            redoStack.clear()
        } else {
            undoStack.clear()
            redoStack.clear()
        }
        activeBuffer = buffer
        player.setBuffer(buffer, resetPosition = true)
        player.updateDspConfig(_uiState.value.dspConfig)
        _uiState.update {
            it.copy(
                hasAudio = true,
                durationMs = buffer.durationMs,
                waveformPeaks = buffer.computeWaveformPeaks(120),
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = recordUndo
            )
        }
    }

    private fun postBanner(message: String) {
        _uiState.update { it.copy(statusBanner = message) }
    }

    private fun formatRecordingTitle(prefix: String): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return "$prefix ${fmt.format(java.util.Date())}"
    }

    override fun onCleared() {
        super.onCleared()
        transcribeJob?.cancel()
        searchJob?.cancel()
        ttsEngine.release()
        player.stop()
        recorder.discard()
    }
}
