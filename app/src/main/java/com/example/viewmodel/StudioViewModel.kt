package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.FileProvider
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AndroidTtsEngine
import com.example.audio.AudioDecoder
import com.example.audio.ExportedAudioResult
import com.example.audio.StudioAudioPlayer
import com.example.audio.StudioAudioRecorder
import com.example.audio.analyzeAndOptimizeAudio
import com.example.audio.applyFadeIn
import com.example.audio.applyFadeOut
import com.example.audio.applyGainDb
import com.example.audio.clamp
import com.example.audio.computeWaveformPeaks
import com.example.audio.cutSelection
import com.example.audio.exportRenderedAudioToFile
import com.example.audio.mergeClips
import com.example.audio.mixTracks
import com.example.audio.normalizeAudio
import com.example.audio.purifyBufferOffline
import com.example.audio.renderOfflineDsp
import com.example.audio.transformVoiceOffline as runTransformVoiceOffline
import com.example.audio.trimToSelection
import com.example.data.AuthResult
import com.example.data.StudioRepository
import com.example.data.ai.AiRepository
import com.example.data.ai.AiResponse
import com.example.data.ai.FirebaseUnprovisionedAiRepository
import com.example.i18n.t
import com.example.model.AiStreamState
import com.example.model.AppLanguage
import com.example.model.AudioRecordingItem
import com.example.model.AuthSession
import com.example.model.BUILT_IN_PRESETS
import com.example.model.DspConfig
import com.example.model.EchoPadAxisMode
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.ExportAudioFormat
import com.example.model.PlaybackTelemetry
import com.example.model.RecorderTelemetry
import com.example.model.RecordingSourceType
import com.example.model.SmartMasterAnalysis
import com.example.model.StereoAudioBuffer
import com.example.model.StudioPreset
import com.example.model.StudioProject
import com.example.model.TtsDialect
import com.example.model.TtsStudioUiState
import com.example.model.VoicePurifyPreset
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

sealed interface StudioSideEffect {
    data class PromptSaveDocument(val result: ExportedAudioResult, val suggestedName: String) : StudioSideEffect
    data class LaunchShareIntent(val uri: Uri, val mimeType: String, val title: String) : StudioSideEffect
}

data class StudioUiState(
    val language: AppLanguage = AppLanguage.AR,
    val authSession: AuthSession = AuthSession.SignedOut,
    val hasAudio: Boolean = false,
    val durationMs: Long = 0L,
    val waveformPeaks: List<Float> = emptyList(),
    val dspConfig: DspConfig = DspConfig(),
    val activePresetId: Long? = null,
    val isSimpleEchoMode: Boolean = true,
    val echoPadAxisMode: EchoPadAxisMode = EchoPadAxisMode.SPACE,
    val isHoldBypassing: Boolean = false,
    val playback: PlaybackTelemetry = PlaybackTelemetry(),
    val recorder: RecorderTelemetry = RecorderTelemetry(),
    val micPermissionError: String? = null,
    val trimStart: Float = 0f,
    val trimEnd: Float = 1f,
    val clipGainDb: Float = 0f,
    val loopSelection: Boolean = false,
    val autoLoopOnPlayback: Boolean = false,
    val canUndoEdit: Boolean = false,
    val canRedoEdit: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val projects: List<StudioProject> = emptyList(),
    val recordings: List<AudioRecordingItem> = emptyList(),
    val customPresets: List<StudioPreset> = emptyList(),
    val activeProjectId: Long = 0L,
    val activeProjectName: String = "مشروع صوتي جديد",
    val mixBgName: String? = null,
    val mixBgDurationMs: Long = 0L,
    val transcriptionState: AiStreamState = AiStreamState(),
    val smartMasterState: SmartMasterAnalysis? = null,
    val searchAssistantState: AiStreamState = AiStreamState(),
    val ttsUiState: TtsStudioUiState = TtsStudioUiState(),
    val statusBanner: String? = null,
    val isBusy: Boolean = false,
    val exportProgress: Float? = null,
    val purifyProgress: Float? = null,
    val homeScrollIndex: Int = 0,
    val homeScrollOffset: Int = 0,
) {
    val allPresets: List<StudioPreset> get() = customPresets + BUILT_IN_PRESETS
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StudioRepository(application)
    private val aiRepository: AiRepository = FirebaseUnprovisionedAiRepository()
    private val ttsEngine = AndroidTtsEngine(application)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _sideEffects = MutableSharedFlow<StudioSideEffect>(extraBufferCapacity = 8)
    val sideEffects = _sideEffects.asSharedFlow()

    val homeListState: LazyListState = LazyListState()

    fun updateHomeScrollPosition(index: Int, offset: Int) {
        val cur = _uiState.value
        if (cur.homeScrollIndex != index || cur.homeScrollOffset != offset) {
            _uiState.update { it.copy(homeScrollIndex = index, homeScrollOffset = offset) }
        }
    }

    private var activeBuffer: StereoAudioBuffer? = null
    private var mixBgBuffer: StereoAudioBuffer? = null
    private val undoStack = ArrayDeque<StereoAudioBuffer>()
    private val redoStack = ArrayDeque<StereoAudioBuffer>()

    private var bannerJob: Job? = null
    private var pendingExportResult: ExportedAudioResult? = null

    private val player = StudioAudioPlayer(_uiState.value.dspConfig) { telemetry ->
        _uiState.update { it.copy(playback = telemetry) }
    }

    private val recorder = StudioAudioRecorder(application) { telemetry ->
        _uiState.update { it.copy(recorder = telemetry) }
    }

    init {
        viewModelScope.launch {
            combine(
                repository.languageFlow,
                repository.authSessionFlow,
                repository.autoLoopFlow,
                repository.projectsFlow,
                repository.recordingsFlow,
                repository.customPresetsFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                PrefAndDbSnapshot(
                    language = values[0] as AppLanguage,
                    authSession = values[1] as AuthSession,
                    autoLoop = values[2] as Boolean,
                    projects = values[3] as List<StudioProject>,
                    recordings = values[4] as List<AudioRecordingItem>,
                    customPresets = values[5] as List<StudioPreset>,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null).collect { snap ->
                snap ?: return@collect
                _uiState.update { cur ->
                    val defaultUntitledAr = "مشروع صوتي جديد"
                    val updatedProjectName = if (cur.activeProjectId == 0L && cur.activeProjectName == defaultUntitledAr) {
                        t(snap.language, "untitled_project")
                    } else {
                        cur.activeProjectName
                    }
                    cur.copy(
                        language = snap.language,
                        authSession = snap.authSession,
                        autoLoopOnPlayback = snap.autoLoop,
                        projects = snap.projects,
                        recordings = snap.recordings,
                        customPresets = snap.customPresets,
                        activeProjectName = updatedProjectName,
                    )
                }
                val s = _uiState.value
                player.setLoop(s.loopSelection || s.autoLoopOnPlayback, s.trimStart, s.trimEnd)
            }
        }
    }

    private data class PrefAndDbSnapshot(
        val language: AppLanguage,
        val authSession: AuthSession,
        val autoLoop: Boolean,
        val projects: List<StudioProject>,
        val recordings: List<AudioRecordingItem>,
        val customPresets: List<StudioPreset>,
    )

    private fun postBanner(msg: String) {
        bannerJob?.cancel()
        _uiState.update { it.copy(statusBanner = msg) }
        bannerJob = viewModelScope.launch {
            delay(4200L)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    fun clearBanner() {
        bannerJob?.cancel()
        _uiState.update { it.copy(statusBanner = null) }
    }

    // Auth & Language
    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch {
            repository.saveLanguage(lang)
        }
    }

    fun continueAsGuest() {
        viewModelScope.launch {
            repository.saveAuthSession(AuthSession.Guest)
        }
    }

    fun requestSignIn() {
        viewModelScope.launch {
            repository.saveAuthSession(AuthSession.SignedOut)
        }
    }

    suspend fun completeGoogleSignIn(uid: String, displayName: String, email: String) {
        val cleanName = displayName.trim().ifEmpty {
            email.substringBefore('@').ifEmpty { "Google User" }
        }
        repository.saveAuthSession(
            AuthSession.SignedIn(
                uid = uid,
                displayName = cleanName,
                email = email.trim(),
            ),
        )
    }

    fun signOut() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching {
                    CredentialManager.create(getApplication()).clearCredentialState(ClearCredentialStateRequest())
                }.onFailure { err ->
                    Log.w("Auth", "Failed to clear credential state: ${err.message}")
                }
                runCatching {
                    Firebase.auth.signOut()
                }.onFailure { err ->
                    Log.w("Auth", "Firebase signOut warning: ${err.message}")
                }
                repository.saveAuthSession(AuthSession.SignedOut)
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): String? {
        return when (val res = repository.signInLocalEmail(email, password)) {
            is AuthResult.Success -> null
            is AuthResult.Error -> res.message
        }
    }

    suspend fun createAccountWithEmail(name: String, email: String, password: String): String? {
        return when (val res = repository.registerLocalEmail(name, email, password)) {
            is AuthResult.Success -> null
            is AuthResult.Error -> res.message
        }
    }

    // Audio Buffer & Undo/Redo Management
    private fun setActiveBuffer(buf: StereoAudioBuffer, recordUndo: Boolean) {
        if (recordUndo && activeBuffer != null) {
            if (undoStack.size >= 12) undoStack.removeFirst()
            undoStack.addLast(activeBuffer!!)
            redoStack.clear()
        } else if (!recordUndo) {
            undoStack.clear()
            redoStack.clear()
        }
        activeBuffer = buf
        player.setBuffer(buf, resetPosition = true)
        val peaks = computeWaveformPeaks(buf, 120)
        _uiState.update {
            it.copy(
                hasAudio = true,
                durationMs = buf.durationMs,
                waveformPeaks = peaks,
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = recordUndo,
            )
        }
    }

    private fun syncPlayerConfig(cfg: DspConfig, holdBypass: Boolean = _uiState.value.isHoldBypassing) {
        val effective = if (holdBypass) cfg.copy(bypassAll = true) else cfg
        player.updateConfig(effective)
    }

    // DSP Controls
    fun setSimpleEchoMode(simple: Boolean) {
        _uiState.update { it.copy(isSimpleEchoMode = simple) }
    }

    fun setEchoPadAxisMode(mode: EchoPadAxisMode) {
        _uiState.update { it.copy(echoPadAxisMode = mode) }
    }

    fun setHoldBypass(holding: Boolean) {
        _uiState.update { it.copy(isHoldBypassing = holding) }
        syncPlayerConfig(_uiState.value.dspConfig, holding)
    }

    fun toggleBypass() {
        val next = _uiState.value.dspConfig.let { it.copy(bypassAll = !it.bypassAll) }
        _uiState.update { it.copy(dspConfig = next) }
        syncPlayerConfig(next)
    }

    fun updateDspConfig(updater: (DspConfig) -> DspConfig) {
        val next = updater(_uiState.value.dspConfig).normalized()
        _uiState.update {
            it.copy(
                dspConfig = next,
                activePresetId = null,
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(next)
    }

    fun resetAllEffects() {
        val def = DspConfig().let { d ->
            d.copy(chain = d.chain.map { it.copy(enabled = false) })
        }
        _uiState.update {
            it.copy(
                dspConfig = def,
                activePresetId = null,
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(def)
        postBanner("تم إيقاف كافة المؤثرات وإرجاعها للإعداد الافتراضي")
    }

    fun toggleEffectEnabled(type: EffectType, enabled: Boolean) {
        updateDspConfig { prev ->
            prev.copy(bypassAll = false).withSlotEnabled(type, enabled)
        }
    }

    fun moveEffectInChain(fromIdx: Int, toIdx: Int) {
        val cur = _uiState.value.dspConfig.chain
        if (fromIdx !in cur.indices || toIdx !in cur.indices || fromIdx == toIdx) return
        val mutable = cur.toMutableList()
        val moved = mutable.removeAt(fromIdx)
        mutable.add(toIdx, moved)
        updateDspConfig { prev -> prev.copy(chain = mutable) }
    }

    fun updateEchoReverbParams(updater: (EchoReverbParams) -> EchoReverbParams) {
        updateDspConfig { prev ->
            prev.copy(bypassAll = false, echoReverb = updater(prev.echoReverb))
                .withSlotEnabled(EffectType.ECHO_REVERB, true)
        }
    }

    private fun mapNormalized(tVal: Float, minV: Float, maxV: Float): Float =
        clamp(minV + clamp(tVal, 0f, 1f) * (maxV - minV), minOf(minV, maxV), maxOf(minV, maxV))

    private fun unmapNormalized(v: Float, minV: Float, maxV: Float): Float =
        clamp((v - minV) / (maxV - minV), 0f, 1f)

    fun updateEchoReverbFromPad(rawX: Float, rawY: Float) {
        val nx = clamp(rawX, 0f, 1f)
        val ny = clamp(rawY, 0f, 1f)
        val mode = _uiState.value.echoPadAxisMode
        updateEchoReverbParams { prev ->
            when (mode) {
                EchoPadAxisMode.SPACE -> prev.copy(
                    echoStrength = nx,
                    echoDepth = ny,
                    reverbSize = mapNormalized(ny, 0.2f, 0.96f),
                    reverbDecay = mapNormalized(ny, 0.15f, 0.92f),
                    wetDryMix = mapNormalized((nx + ny) * 0.5f, 0.08f, 0.85f),
                )
                EchoPadAxisMode.DELAY -> prev.copy(
                    delayTimeMs = mapNormalized(nx, 40f, 1000f),
                    feedback = mapNormalized(ny, 0.05f, 0.85f),
                )
                EchoPadAxisMode.WIDTH -> prev.copy(
                    stereoWidth = nx,
                    diffusion = mapNormalized(ny, 0.15f, 0.96f),
                    earlyReflections = mapNormalized(ny, 0.15f, 0.9f),
                )
                EchoPadAxisMode.DAMPING -> prev.copy(
                    damping = nx,
                    preDelayMs = mapNormalized(ny, 0f, 140f),
                    lateReflections = mapNormalized(1f - nx * 0.35f, 0.25f, 0.9f),
                )
            }
        }
    }

    fun getEchoPadAxes(): Pair<Float, Float> {
        val e = _uiState.value.dspConfig.echoReverb
        return when (_uiState.value.echoPadAxisMode) {
            EchoPadAxisMode.SPACE -> clamp(e.echoStrength, 0f, 1f) to clamp(e.echoDepth, 0f, 1f)
            EchoPadAxisMode.DELAY -> unmapNormalized(e.delayTimeMs, 40f, 1000f) to unmapNormalized(e.feedback, 0.05f, 0.85f)
            EchoPadAxisMode.WIDTH -> clamp(e.stereoWidth, 0f, 1f) to unmapNormalized(e.diffusion, 0.15f, 0.96f)
            EchoPadAxisMode.DAMPING -> clamp(e.damping, 0f, 1f) to unmapNormalized(e.preDelayMs, 0f, 140f)
        }
    }

    // Transport
    fun togglePlayPause() {
        if (activeBuffer == null) {
            postBanner("سجّل أو استورد مقطعاً صوتياً أولاً")
            return
        }
        if (_uiState.value.playback.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun stopPlayback() {
        player.stop()
    }

    fun seekToFraction(frac: Float) {
        player.seekToFraction(frac)
    }

    fun toggleLoopSelection() {
        val next = !_uiState.value.loopSelection
        _uiState.update { it.copy(loopSelection = next) }
        val s = _uiState.value
        player.setLoop(next || s.autoLoopOnPlayback, s.trimStart, s.trimEnd)
    }

    fun setAutoLoopOnPlayback(enabled: Boolean) {
        viewModelScope.launch {
            repository.saveAutoLoop(enabled)
        }
        val s = _uiState.value
        player.setLoop(s.loopSelection || enabled, s.trimStart, s.trimEnd)
    }

    // Recording & Import
    fun hasMicPermission(): Boolean = recorder.hasMicPermission()

    fun setMicPermissionDeniedError() {
        _uiState.update {
            it.copy(micPermissionError = t(it.language, "mic_permission_desc"))
        }
    }

    fun startRecording() {
        _uiState.update { it.copy(micPermissionError = null) }
        player.stop()
        val ok = recorder.start()
        if (!ok) {
            setMicPermissionDeniedError()
        }
    }

    fun pauseRecording() = recorder.pause()
    fun resumeRecording() = recorder.resume()
    fun discardRecording() = recorder.discard()

    fun finishRecording() {
        viewModelScope.launch {
            val buf = recorder.stopAndBuildBuffer()
            if (buf == null || buf.frameCount == 0) {
                postBanner("التسجيل قصير جداً أو لم يتم التقاط صوت")
                return@launch
            }
            setActiveBuffer(buf, recordUndo = false)
            val nowStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            repository.indexAudioRecording("تسجيل $nowStr", buf, RecordingSourceType.RECORDING)
            postBanner("تم حفظ التسجيل في الاستوديو بنجاح")
        }
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                player.stop()
                val buf = AudioDecoder.decodeUri(getApplication(), uri)
                if (buf == null || buf.frameCount == 0) {
                    postBanner("تعذر فك ترميز الملف الصوتي المحدد")
                    return@launch
                }
                setActiveBuffer(buf, recordUndo = false)
                val title = resolveFileName(uri)?.substringBeforeLast('.')?.ifEmpty { "ملف مستورد" } ?: "ملف مستورد"
                if (_uiState.value.activeProjectId == 0L) {
                    _uiState.update { it.copy(activeProjectName = title) }
                }
                repository.indexAudioRecording(title, buf, RecordingSourceType.IMPORTED)
                postBanner("تم استيراد الملف الصوتي وتحميل الموجات بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    // Purifier
    fun applyVoicePurifyPreset(preset: VoicePurifyPreset) {
        updateDspConfig { prev ->
            prev.copy(
                bypassAll = false,
                noiseReduction = prev.noiseReduction.copy(
                    thresholdDb = preset.thresholdDb,
                    reductionDb = preset.reductionDb,
                    highPassHz = preset.highPassHz,
                    spectralStrength = preset.spectralStrength,
                    deEssAmount = preset.deEssAmount,
                    clarityBoostDb = preset.clarityBoostDb,
                    spectralCleanerEnabled = preset.spectralCleanerEnabled,
                ),
            ).withSlotEnabled(EffectType.NOISE_REDUCTION, true)
        }
        val title = if (_uiState.value.language == AppLanguage.EN) preset.titleEn else preset.titleAr
        postBanner("تم تطبيق وضع التنقية: $title")
    }

    fun purifyAudioOffline() {
        val src = activeBuffer ?: run {
            postBanner("سجّل أو استورد مقطعاً صوتياً أولاً لتنقيته")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, purifyProgress = 0.02f) }
            try {
                val cleaned = purifyBufferOffline(src, _uiState.value.dspConfig.noiseReduction) { p ->
                    _uiState.update { it.copy(purifyProgress = p) }
                }
                setActiveBuffer(cleaned, recordUndo = true)
                postBanner("تمت تصفية وعزل الضوضاء من المقطع وحفظ النسخة في التراجع")
            } finally {
                _uiState.update { it.copy(isBusy = false, purifyProgress = null) }
            }
        }
    }

    // Editor Ops
    fun updateTrimRange(s: Float, e: Float) {
        val cs = clamp(s, 0f, 0.98f)
        val ce = clamp(e, cs + 0.02f, 1f)
        _uiState.update { it.copy(trimStart = cs, trimEnd = ce) }
        val st = _uiState.value
        player.setLoop(st.loopSelection || st.autoLoopOnPlayback, cs, ce)
    }

    fun resetSelection() {
        _uiState.update { it.copy(trimStart = 0f, trimEnd = 1f) }
        val st = _uiState.value
        player.setLoop(st.loopSelection || st.autoLoopOnPlayback, 0f, 1f)
        postBanner("تم تحديد المقطع بالكامل")
    }

    fun setClipGainDb(db: Float) {
        _uiState.update { it.copy(clipGainDb = db) }
    }

    fun bakeClipGainDb(db: Float) {
        val src = activeBuffer ?: return
        val gained = applyGainDb(src, db)
        setActiveBuffer(gained, recordUndo = true)
        _uiState.update { it.copy(clipGainDb = 0f) }
        postBanner("تم تعديل شدة الصوت في المقطع بنجاح")
    }

    fun trimKeepSelection() {
        val src = activeBuffer ?: return
        val st = _uiState.value
        val trimmed = trimToSelection(src, st.trimStart, st.trimEnd)
        setActiveBuffer(trimmed, recordUndo = true)
        postBanner("تم اقتصاص المقطع والإبقاء على الجزء المحدد")
    }

    fun cutSelectedRange() {
        val src = activeBuffer ?: return
        val st = _uiState.value
        val cut = cutSelection(src, st.trimStart, st.trimEnd)
        setActiveBuffer(cut, recordUndo = true)
        postBanner("تم حذف الجزء المحدد من المقطع")
    }

    fun normalizeAudioBuffer() {
        val src = activeBuffer ?: return
        val normed = normalizeAudio(src, -1f)
        setActiveBuffer(normed, recordUndo = true)
        postBanner("تمت موازنة مستوى الصوت إلى -1 dBFS")
    }

    fun applyFadeInSeconds(seconds: Float) {
        val src = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد صوت أولاً لتطبيق التدرج")
            return
        }
        setActiveBuffer(applyFadeIn(src, seconds), recordUndo = true)
        postBanner("تم تطبيق الظهور التدريجي (${"%.1f".format(seconds)} ثانية)")
    }

    fun applyFadeOutSeconds(seconds: Float) {
        val src = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد صوت أولاً لتطبيق التدرج")
            return
        }
        setActiveBuffer(applyFadeOut(src, seconds), recordUndo = true)
        postBanner("تم تطبيق الاختفاء التدريجي (${"%.1f".format(seconds)} ثانية)")
    }

    fun loadMixBackgroundUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val bg = AudioDecoder.decodeUri(getApplication(), uri)
                if (bg == null || bg.frameCount == 0) {
                    postBanner("تعذر قراءة ملف الخلفية الصوتية")
                    return@launch
                }
                mixBgBuffer = bg
                val name = resolveFileName(uri) ?: "Background Track"
                _uiState.update {
                    it.copy(
                        mixBgName = name,
                        mixBgDurationMs = bg.durationMs,
                    )
                }
                postBanner("تم تحميل مسار الخلفية بنجاح، يمكنك الآن ضبط المستويات والمزج")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun clearMixBackgroundTrack() {
        mixBgBuffer = null
        _uiState.update { it.copy(mixBgName = null, mixBgDurationMs = 0L) }
    }

    fun mixWithBackgroundTrack(mainGain: Float, bgGain: Float) {
        val main = activeBuffer
        val bg = mixBgBuffer
        if (main == null || bg == null) {
            postBanner("يرجى التأكد من وجود الصوت الرئيسي واستيراد مسار الخلفية أولاً")
            return
        }
        val mixed = mixTracks(main, bg, mainGain, bgGain)
        setActiveBuffer(mixed, recordUndo = true)
        postBanner("تم مزج المسارين معاً في مسار استوديو واحد بنجاح")
    }

    fun appendAudioUriToCurrent(uri: Uri) {
        val src = activeBuffer
        if (src == null) {
            importAudioUri(uri)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val extra = AudioDecoder.decodeUri(getApplication(), uri)
                if (extra == null || extra.frameCount == 0) {
                    postBanner("تعذر فك ترميز المقطع الإضافي للدمج")
                    return@launch
                }
                setActiveBuffer(mergeClips(src, extra), recordUndo = true)
                postBanner("تم دمج المقطع الصوتي في نهاية الصوت بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun appendSavedProjectToCurrent(project: StudioProject) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val extra = repository.loadProjectAudioBuffer(project.id)
                if (extra == null) {
                    postBanner("هذا المشروع لا يحتوي على ملف صوتي للدمج")
                    return@launch
                }
                val cur = activeBuffer
                if (cur == null) {
                    setActiveBuffer(extra, recordUndo = false)
                } else {
                    setActiveBuffer(mergeClips(cur, extra), recordUndo = true)
                }
                postBanner("تم دمج \"${project.name}\" في نهاية المقطع بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun undoLastEdit() {
        val prev = undoStack.removeLastOrNull() ?: return
        activeBuffer?.let { cur ->
            if (redoStack.size >= 12) redoStack.removeFirst()
            redoStack.addLast(cur)
        }
        activeBuffer = prev
        player.setBuffer(prev, resetPosition = true)
        val peaks = computeWaveformPeaks(prev, 120)
        _uiState.update {
            it.copy(
                durationMs = prev.durationMs,
                waveformPeaks = peaks,
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
            )
        }
        postBanner("تم التراجع عن آخر تعديل")
    }

    fun redoLastEdit() {
        val next = redoStack.removeLastOrNull() ?: return
        activeBuffer?.let { cur ->
            if (undoStack.size >= 12) undoStack.removeFirst()
            undoStack.addLast(cur)
        }
        activeBuffer = next
        player.setBuffer(next, resetPosition = true)
        val peaks = computeWaveformPeaks(next, 120)
        _uiState.update {
            it.copy(
                durationMs = next.durationMs,
                waveformPeaks = peaks,
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
            )
        }
        postBanner("تمت إعادة التعديل")
    }

    // Projects, Presets & Export
    fun setActiveProjectName(name: String) {
        _uiState.update { it.copy(activeProjectName = name) }
    }

    fun saveCurrentProject() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val st = _uiState.value
                val saved = repository.saveProject(
                    existingId = st.activeProjectId,
                    name = st.activeProjectName,
                    buffer = activeBuffer,
                    config = st.dspConfig,
                )
                _uiState.update {
                    it.copy(
                        activeProjectId = saved.id,
                        activeProjectName = saved.name,
                        hasUnsavedChanges = false,
                    )
                }
                postBanner("تم حفظ المشروع بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun saveEditsPermanent() {
        undoStack.clear()
        redoStack.clear()
        _uiState.update { it.copy(canUndoEdit = false, canRedoEdit = false) }
        saveCurrentProject()
    }

    fun newProject() {
        player.stop()
        activeBuffer = null
        undoStack.clear()
        redoStack.clear()
        val def = DspConfig()
        player.setBuffer(null, resetPosition = true)
        syncPlayerConfig(def, holdBypass = false)
        _uiState.update {
            it.copy(
                activeProjectId = 0L,
                activeProjectName = t(it.language, "untitled_project"),
                hasAudio = false,
                durationMs = 0L,
                waveformPeaks = emptyList(),
                dspConfig = def,
                activePresetId = null,
                hasUnsavedChanges = false,
                canUndoEdit = false,
                canRedoEdit = false,
                isHoldBypassing = false,
            )
        }
        postBanner("تم إنشاء مشروع صوتي جديد")
    }

    fun loadProject(project: StudioProject) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                player.stop()
                val buf = repository.loadProjectAudioBuffer(project.id)
                _uiState.update {
                    it.copy(
                        activeProjectId = project.id,
                        activeProjectName = project.name,
                        dspConfig = project.config,
                        activePresetId = null,
                    )
                }
                syncPlayerConfig(project.config)
                if (buf != null) {
                    setActiveBuffer(buf, recordUndo = false)
                } else {
                    activeBuffer = null
                    player.setBuffer(null, resetPosition = true)
                    _uiState.update {
                        it.copy(
                            hasAudio = false,
                            durationMs = 0L,
                            waveformPeaks = emptyList(),
                        )
                    }
                }
                _uiState.update { it.copy(hasUnsavedChanges = false) }
                postBanner("تم فتح المشروع \"${project.name}\"")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun deleteProject(project: StudioProject) {
        viewModelScope.launch {
            repository.deleteProject(project.id)
            if (_uiState.value.activeProjectId == project.id) {
                _uiState.update { it.copy(activeProjectId = 0L) }
            }
            postBanner("تم حذف المشروع \"${project.name}\"")
        }
    }

    fun loadRecordingIntoStudio(rec: AudioRecordingItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val buf = repository.loadRecordingBuffer(rec.id)
                if (buf == null) {
                    postBanner("تعذر تحميل التسجيل المحفوظ")
                    return@launch
                }
                setActiveBuffer(buf, recordUndo = false)
                postBanner("تم فتح \"${rec.title}\" في الاستوديو بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun deleteRecording(rec: AudioRecordingItem) {
        viewModelScope.launch {
            repository.deleteRecording(rec.id)
            postBanner("تم حذف التسجيل \"${rec.title}\"")
        }
    }

    fun applyPreset(preset: StudioPreset) {
        val next = preset.config.copy(bypassAll = false).normalized()
        _uiState.update {
            it.copy(
                dspConfig = next,
                activePresetId = preset.id,
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(next)
        postBanner("تم تطبيق قالب \"${preset.name}\"")
    }

    fun saveCustomPreset(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.saveCustomPreset(name, description, _uiState.value.dspConfig)
            postBanner("تم حفظ القالب \"$name\"")
        }
    }

    fun deleteCustomPreset(presetId: Long) {
        viewModelScope.launch {
            repository.deleteCustomPreset(presetId)
            postBanner("تم حذف القالب المخصص")
        }
    }

    fun exportRenderedAudio(
        format: ExportAudioFormat,
        fileName: String,
        shareAfter: Boolean = false,
    ) {
        val src = activeBuffer ?: run {
            postBanner("لا يوجد صوت لتصديره حالياً")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, exportProgress = 0.05f) }
            try {
                val cfg = _uiState.value.dspConfig
                val rendered = withContext(Dispatchers.Default) {
                    renderOfflineDsp(src, cfg)
                }
                _uiState.update { it.copy(exportProgress = 0.45f) }
                val res = exportRenderedAudioToFile(
                    context = getApplication(),
                    buffer = rendered,
                    format = format,
                    baseFileName = fileName,
                ) { p ->
                    _uiState.update { it.copy(exportProgress = 0.45f + p * 0.55f) }
                }
                pendingExportResult = res

                if (shareAfter) {
                    val app = getApplication<Application>()
                    val uri = FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        res.file,
                    )
                    _sideEffects.tryEmit(
                        StudioSideEffect.LaunchShareIntent(
                            uri = uri,
                            mimeType = res.mimeType,
                            title = res.file.name,
                        ),
                    )
                } else {
                    val cleanName = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "VoicePro_Export" }
                    _sideEffects.tryEmit(
                        StudioSideEffect.PromptSaveDocument(
                            result = res,
                            suggestedName = "$cleanName.${res.extension}",
                        ),
                    )
                }
            } catch (_: Throwable) {
                postBanner("تعذر تصدير الملف الصوتي")
            } finally {
                _uiState.update { it.copy(isBusy = false, exportProgress = null) }
            }
        }
    }

    fun completeSaveExportedDocument(destinationUri: Uri?) {
        val result = pendingExportResult ?: return
        pendingExportResult = null
        if (destinationUri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destinationUri)?.use { out ->
                    result.file.inputStream().use { inp -> inp.copyTo(out) }
                }
                postBanner("تم حفظ الملف بصيغة ${result.extension.uppercase()} بنجاح")
            }.onFailure {
                postBanner("تعذر حفظ الملف في الموقع المحدد")
            }
        }
    }

    // AI Studio
    fun transcribeActiveAudio() {
        val src = activeBuffer
        if (src == null) {
            _uiState.update {
                it.copy(
                    transcriptionState = AiStreamState(
                        isLoading = false,
                        errorMessage = "لا يوجد مقطع صوتي حالياً لتفريغه. سجّل أو استورد صوتاً أولاً.",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(transcriptionState = AiStreamState(isLoading = true))
            }
            when (val res = aiRepository.transcribeAudio(src, _uiState.value.language)) {
                is AiResponse.Success -> _uiState.update {
                    it.copy(
                        transcriptionState = AiStreamState(
                            isLoading = false,
                            outputText = res.text,
                        ),
                    )
                }
                is AiResponse.NeedsCloudProvisioning -> _uiState.update {
                    it.copy(
                        transcriptionState = AiStreamState(
                            isLoading = false,
                            errorMessage = res.message,
                        ),
                    )
                }
            }
        }
    }

    fun clearTranscription() {
        _uiState.update { it.copy(transcriptionState = AiStreamState()) }
    }

    fun runSmartAutoMaster() {
        val src = activeBuffer ?: run {
            postBanner("قم بتسجيل أو استيراد مقطع صوتي أولاً لتحليله")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val analysis = withContext(Dispatchers.Default) {
                    analyzeAndOptimizeAudio(src, _uiState.value.dspConfig)
                }
                _uiState.update {
                    it.copy(
                        smartMasterState = analysis,
                        dspConfig = analysis.optimizedConfig,
                        activePresetId = null,
                        hasUnsavedChanges = true,
                    )
                }
                syncPlayerConfig(analysis.optimizedConfig)
                postBanner("تم تحليل الصوت وتطبيق الماسترينغ الذكي تلقائياً")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun clearSmartMasterAnalysis() {
        _uiState.update { it.copy(smartMasterState = null) }
    }

    fun askSearchAssistant(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(searchAssistantState = AiStreamState(isLoading = true))
            }
            when (val res = aiRepository.askSearchAssistant(query.trim(), _uiState.value.language)) {
                is AiResponse.Success -> _uiState.update {
                    it.copy(
                        searchAssistantState = AiStreamState(
                            isLoading = false,
                            outputText = res.text,
                            searchQueries = res.searchQueries,
                            sources = res.sources,
                        ),
                    )
                }
                is AiResponse.NeedsCloudProvisioning -> _uiState.update {
                    it.copy(
                        searchAssistantState = AiStreamState(
                            isLoading = false,
                            errorMessage = res.message,
                        ),
                    )
                }
            }
        }
    }

    // TTS Studio (Android native TextToSpeech Preview & File Synthesis)
    fun previewTtsSpeech(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
    ) {
        if (text.isBlank()) {
            _uiState.update { it.copy(ttsUiState = it.ttsUiState.copy(errorMessage = "يرجى إدخال النص أولاً.")) }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(ttsUiState = TtsStudioUiState(isPreviewing = true, isGenerating = false, errorMessage = null))
            }
            val started = ttsEngine.preview(text, dialect, speechRate, pitch) {
                _uiState.update {
                    it.copy(ttsUiState = it.ttsUiState.copy(isPreviewing = false))
                }
            }
            if (!started) {
                _uiState.update {
                    it.copy(
                        ttsUiState = TtsStudioUiState(
                            isPreviewing = false,
                            errorMessage = "تعذر تشغيل محرك النطق على هذا الجهاز.",
                        ),
                    )
                }
            }
        }
    }

    fun stopTtsPreview() {
        ttsEngine.stopPreview()
        _uiState.update { it.copy(ttsUiState = it.ttsUiState.copy(isPreviewing = false)) }
    }

    fun generateTtsToStudio(
        text: String,
        dialect: TtsDialect,
        speechRate: Float,
        pitch: Float,
        appendToExisting: Boolean,
    ) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    ttsUiState = TtsStudioUiState(isPreviewing = false, isGenerating = true, errorMessage = null),
                )
            }
            try {
                ttsEngine.stopPreview()
                val rawBuf = ttsEngine.synthesizeToBuffer(text.trim(), dialect, speechRate, pitch)
                if (rawBuf == null || rawBuf.frameCount == 0) {
                    _uiState.update {
                        it.copy(
                            ttsUiState = TtsStudioUiState(
                                isGenerating = false,
                                errorMessage = "تعذر توليد الصوت عبر محرك النطق بالنظام.",
                            ),
                        )
                    }
                    return@launch
                }

                val existing = activeBuffer
                val finalBuf = if (appendToExisting && existing != null) {
                    mergeClips(existing, rawBuf)
                } else {
                    rawBuf
                }
                setActiveBuffer(finalBuf, recordUndo = appendToExisting && existing != null)
                val dialectLabel = t(_uiState.value.language, dialect.titleKey)
                repository.indexAudioRecording("TTS • $dialectLabel", rawBuf, RecordingSourceType.TTS)
                postBanner("تم توليد وإدراج المقطع الصوتي ($dialectLabel) في الاستوديو بنجاح")
            } finally {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        ttsUiState = it.ttsUiState.copy(isGenerating = false),
                    )
                }
            }
        }
    }

    // Offline Formant-Preserving Voice Character Transformer
    fun transformVoiceOffline(
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        mix: Float,
        characterLabel: String,
    ) {
        val src = activeBuffer ?: run {
            postBanner("سجّل أو استورد مقطعاً صوتياً أولاً لتغيير الشخصية الصوتية")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, purifyProgress = 0.05f) }
            try {
                val transformed = runTransformVoiceOffline(
                    source = src,
                    pitchSemitones = pitchSemitones,
                    formantShift = formantShift,
                    warmthDb = warmthDb,
                    presenceDb = presenceDb,
                    mix = mix,
                ) { p ->
                    _uiState.update { it.copy(purifyProgress = p) }
                }
                setActiveBuffer(transformed, recordUndo = true)
                postBanner("تم تحويل الصوت إلى شخصية \"$characterLabel\" بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false, purifyProgress = null) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.dispose()
        recorder.discard()
        ttsEngine.shutdown()
    }
}
