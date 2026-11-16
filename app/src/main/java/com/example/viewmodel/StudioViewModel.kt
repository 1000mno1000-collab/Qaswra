package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiTextState
import com.example.ai.LyriaTrackLength
import com.example.ai.MusicGenState
import com.example.ai.StudioAiRepository
import com.example.audio.dsp.DspPipeline
import com.example.audio.io.AudioEditorOps
import com.example.audio.io.AudioPlayerEngine
import com.example.audio.io.AudioRecorderEngine
import com.example.audio.io.RecordingState
import com.example.audio.io.StereoAudioBuffer
import com.example.data.StudioDatabase
import com.example.data.StudioProject
import com.example.data.StudioRepository
import com.example.model.DspConfig
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.StudioPreset
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

enum class EchoPadAxisMode(val label: String, val xLabel: String, val yLabel: String) {
    DEPTH_AND_DIFFUSION("العمق × الانتشار", "العمق والحجم", "الشدة والانتشار"),
    REFLECTIONS_BALANCE("المبكرة × المتأخرة", "الانعكاسات المبكرة", "الانعكاسات المتأخرة"),
    DISTANCE_AND_DAMPING("المسافة × التخميد", "التأخير المسبق", "التخميد والنعومة"),
    ECHO_FEEDBACK("التأخير × التكرار", "زمن الصدى", "التغذية الراجعة")
}

data class StudioUiState(
    val navStack: List<WorkspaceDestination> = listOf(WorkspaceDestination.Home),
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
    val canUndoEdit: Boolean = false,
    val isBusy: Boolean = false,
    val exportProgress: Float? = null,
    val statusBanner: String? = null
) {
    val currentDestination: WorkspaceDestination
        get() = navStack.lastOrNull() ?: WorkspaceDestination.Home
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StudioDatabase.getInstance(application)
    private val repository = StudioRepository(application, database.studioDao())
    private val aiRepository by lazy { StudioAiRepository(getApplication()) }

    private val recorder = AudioRecorderEngine()
    private val player = AudioPlayerEngine()

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _transcriptionState = MutableStateFlow<AiTextState>(AiTextState.Idle)
    val transcriptionState: StateFlow<AiTextState> = _transcriptionState.asStateFlow()

    private val _musicGenState = MutableStateFlow<MusicGenState>(MusicGenState.Idle)
    val musicGenState: StateFlow<MusicGenState> = _musicGenState.asStateFlow()

    private val _searchAssistantState = MutableStateFlow<AiTextState>(AiTextState.Idle)
    val searchAssistantState: StateFlow<AiTextState> = _searchAssistantState.asStateFlow()

    private var transcribeJob: Job? = null
    private var musicGenJob: Job? = null
    private var searchJob: Job? = null

    val recordingState: StateFlow<RecordingState> = recorder.state
    val recordingLevel: StateFlow<Float> = recorder.inputLevel
    val recordingDurationMs: StateFlow<Long> = recorder.durationMs
    val recordingWaveform: StateFlow<List<Float>> = recorder.liveWaveform

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<Float> = player.progress
    val outputLevel: StateFlow<Float> = player.outputLevel
    val gainReductionDb: StateFlow<Float> = player.gainReductionDb

    val savedProjects: StateFlow<List<StudioProject>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<StudioPreset>> = repository.allPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var activeBuffer: StereoAudioBuffer? = null
    private val undoStack = mutableListOf<StereoAudioBuffer>()

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
        postBanner("تم التقاط التسجيل الصوتي بنجاح")
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

    fun toggleBypassAll() {
        updateDspConfig { it.copy(bypassAll = !it.bypassAll) }
    }

    // --- DSP Configuration & Chain ---

    fun updateDspConfig(transform: (DspConfig) -> DspConfig) {
        _uiState.update { state ->
            val next = transform(state.dspConfig)
            player.updateDspConfig(next)
            state.copy(dspConfig = next, activePresetId = null)
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

    // --- Audio Importing & Editing ---

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val decoded = repository.importAudioFromUri(uri)
            _uiState.update { it.copy(isBusy = false) }
            if (decoded != null) {
                setActiveBuffer(decoded, recordUndo = false)
                postBanner("تم استيراد الملف الصوتي بنجاح")
            } else {
                postBanner("تعذر فك تشفير الملف الصوتي المحدد")
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

    fun mergeExternalAudio(uri: Uri) {
        val current = activeBuffer ?: run {
            importAudioUri(uri)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val second = repository.importAudioFromUri(uri)
            _uiState.update { it.copy(isBusy = false) }
            if (second != null) {
                val merged = withContext(Dispatchers.Default) {
                    AudioEditorOps.mergeClips(current, second)
                }
                setActiveBuffer(merged, recordUndo = true)
                postBanner("تم دمج المقطعين بسلاسة")
            } else {
                postBanner("تعذر استيراد المقطع المراد دمجه")
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
        activeBuffer = prev
        player.setBuffer(prev, resetPosition = true)
        _uiState.update {
            it.copy(
                hasAudio = true,
                durationMs = prev.durationMs,
                waveformPeaks = prev.computeWaveformPeaks(120),
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty()
            )
        }
        postBanner("تم التراجع عن التعديل الأخير")
    }

    // --- Presets ---

    fun applyPreset(preset: StudioPreset) {
        _uiState.update { state ->
            player.updateDspConfig(preset.config)
            state.copy(
                dspConfig = preset.config,
                activePresetId = preset.id
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

    fun saveCurrentProject(customName: String? = null) {
        val s = _uiState.value
        val finalName = (customName ?: s.projectName).ifBlank { "مشروع صوتي" }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val saved = repository.saveProject(
                existingId = s.projectId,
                name = finalName,
                buffer = activeBuffer,
                config = s.dspConfig
            )
            _uiState.update {
                it.copy(
                    projectId = saved.id,
                    projectName = saved.name,
                    isBusy = false
                )
            }
            postBanner("تم حفظ المشروع \"${saved.name}\"")
        }
    }

    fun createNewProject() {
        player.stop()
        recorder.discard()
        activeBuffer = null
        undoStack.clear()
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
                canUndoEdit = false
            )
        }
        postBanner("تم فتح مساحة مشروع جديد")
    }

    fun loadProject(project: StudioProject) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val loadedAudio = repository.loadProjectAudio(project)
            undoStack.clear()
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

    fun exportProcessedWav(destinationUri: Uri) {
        val source = activeBuffer ?: run {
            postBanner("لا يوجد صوت لتصديره")
            return
        }
        val config = _uiState.value.dspConfig
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = 0.01f) }
            val rendered = withContext(Dispatchers.Default) {
                DspPipeline(source.sampleRate).renderOffline(source, config) { p ->
                    _uiState.update { it.copy(exportProgress = p) }
                }
            }
            val success = repository.exportWavToUri(destinationUri, rendered)
            _uiState.update { it.copy(exportProgress = null) }
            postBanner(
                if (success) "تم تصدير الملف الصوتي بنجاح" else "فشل تصدير الملف الصوتي"
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
                DspPipeline(source.sampleRate).renderOffline(source, state.dspConfig) { p ->
                    _uiState.update { it.copy(exportProgress = p) }
                }
            }
            val uri = repository.createShareWavUri(state.projectName, rendered)
            _uiState.update { it.copy(exportProgress = null) }
            onUriReady(uri)
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

    fun generateMusicTrack(prompt: String, trackLength: LyriaTrackLength) {
        musicGenJob?.cancel()
        musicGenJob = viewModelScope.launch {
            _musicGenState.value = MusicGenState.Loading
            _musicGenState.value = aiRepository.generateMusic(prompt, trackLength)
        }
    }

    fun loadGeneratedMusicIntoStudio(replaceExisting: Boolean) {
        val success = _musicGenState.value as? MusicGenState.Success ?: return
        val generated = success.buffer
        val current = activeBuffer
        if (replaceExisting || current == null) {
            setActiveBuffer(generated, recordUndo = current != null)
            postBanner("تم تحميل المقطوعة المولدة داخل الاستوديو")
        } else {
            viewModelScope.launch {
                val merged = withContext(Dispatchers.Default) {
                    AudioEditorOps.mergeClips(current, generated)
                }
                setActiveBuffer(merged, recordUndo = true)
                postBanner("تم دمج المقطوعة المولدة مع مقطعك الحالي")
            }
        }
    }

    fun askSearchAssistant(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            aiRepository.askWithGoogleSearch(query).collect { st ->
                _searchAssistantState.value = st
            }
        }
    }

    private fun setActiveBuffer(buffer: StereoAudioBuffer, recordUndo: Boolean) {
        if (recordUndo) {
            activeBuffer?.let { prev ->
                if (undoStack.size >= 8) undoStack.removeAt(0)
                undoStack.add(prev)
            }
        } else {
            undoStack.clear()
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
                canUndoEdit = undoStack.isNotEmpty()
            )
        }
    }

    private fun postBanner(message: String) {
        _uiState.update { it.copy(statusBanner = message) }
    }

    override fun onCleared() {
        super.onCleared()
        transcribeJob?.cancel()
        musicGenJob?.cancel()
        searchJob?.cancel()
        player.stop()
        recorder.discard()
    }
}
