package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioDecoder
import com.example.audio.ExportedAudioResult
import com.example.audio.NativeSpeechState
import com.example.audio.NetworkUtils
import com.example.audio.ai.OnlineSoundItem
import com.example.audio.ai.SmartAssistantAndSearch
import com.example.audio.ai.ToolGuideResult
import com.example.audio.fx.VoiceEffect
import com.example.audio.fx.VoiceProcessor
import com.example.audio.tts.ElevenLabsClient
import com.example.audio.tts.SmartVoiceRouter
import com.example.audio.tts.VoiceRouteOutcome
import com.example.audio.unity.UnityExporter
import com.example.audio.video.AudioToVideoCreator
import com.example.audio.video.CinematicRenderParams
import com.example.audio.video.CinematicVideoRenderer
import com.example.audio.web.WebArticleExtractor
import com.example.audio.RingtoneSaveResult
import com.example.audio.SonixMediaPlaybackService
import com.example.audio.StudioAudioPlayer
import com.example.audio.StudioAudioRecorder
import com.example.audio.StudioPlaybackBridge
import com.example.audio.StudioSpeechTranscriber
import com.example.audio.analyzeAndOptimizeAudio
import com.example.audio.applyFadeIn
import com.example.audio.applyFadeOut
import com.example.audio.applyGainDb
import com.example.audio.applyPitchAndSpeedOffline
import com.example.audio.clamp
import com.example.audio.computeWaveformPeaks
import com.example.audio.convertToSurroundStereo
import com.example.audio.cutSelection
import com.example.audio.exportRenderedAudioToFile
import com.example.audio.formatDurationMs
import com.example.audio.generateSilence
import com.example.audio.mergeClips
import com.example.audio.mixTracks
import com.example.audio.mixWithSidechainAutoDucking
import com.example.audio.normalizeAudio
import com.example.audio.purifyBufferOffline
import com.example.audio.renderOfflineDsp
import com.example.audio.replaceOrMixVideoAudio
import com.example.audio.reverseAudio
import com.example.audio.saveAndSetRingtone
import com.example.audio.splitAudioByTime
import com.example.audio.splitStereoChannels
import com.example.audio.transformVoiceOffline as runTransformVoiceOffline
import com.example.audio.trimToSelection
import com.example.audio.writeStereoWavFile
import com.example.data.AuthResult
import com.example.data.MediaStoreAudioRepository
import com.example.data.StudioRepository
import com.example.data.ai.AiRepository
import com.example.data.ai.AiResponse
import com.example.data.ai.FirebaseUnprovisionedAiRepository
import com.example.data.remote.FirestoreStudioRepository
import com.example.i18n.t
import com.example.model.AiStreamState
import com.example.model.AppLanguage
import com.example.model.AudioRecordingItem
import com.example.model.AuthSession
import com.example.model.BUILT_IN_PRESETS
import com.example.model.CloudDraft
import com.example.model.DeviceAudioItem
import com.example.model.DspConfig
import com.example.model.EchoPadAxisMode
import com.example.model.EchoReverbParams
import com.example.model.EffectType
import com.example.model.ExportAudioFormat
import com.example.model.GlobalProPreset
import com.example.model.PlaybackTelemetry
import com.example.model.RecorderTelemetry
import com.example.model.RecordingSourceType
import com.example.model.SleepTimerOption
import com.example.model.SmartMasterAnalysis
import com.example.model.StereoAudioBuffer
import com.example.model.StudioPreset
import com.example.model.StudioProject
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsDialect
import com.example.model.TtsGender
import com.example.model.TtsStudioUiState
import com.example.model.VoicePurifyPreset
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
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
    data class LaunchShareIntent(
        val uri: Uri,
        val mimeType: String,
        val title: String,
        val targetPackage: String? = null,
    ) : StudioSideEffect
    data class LaunchMultiShareIntent(
        val uris: List<Uri>,
        val mimeType: String,
        val title: String,
    ) : StudioSideEffect
}

@Immutable
data class StudioUiState(
    val language: AppLanguage = AppLanguage.AR,
    val authSession: AuthSession = AuthSession.SignedOut,
    val hasAudio: Boolean = false,
    val durationMs: Long = 0L,
    val waveformPeaks: List<Float> = emptyList(),
    val dspConfig: DspConfig = DspConfig(),
    val activePresetId: Long? = null,
    val selectedPresetLabel: String = "",
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
    val cloudDrafts: List<CloudDraft> = emptyList(),
    val activeCloudDraftId: String? = null,
    val isCloudSynced: Boolean = false,
    val previewWavPath: String? = null,
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
    val deviceAudios: List<DeviceAudioItem> = emptyList(),
    val isScanningDeviceAudio: Boolean = false,
    val statusBanner: String? = null,
    val isBusy: Boolean = false,
    val importProgress: Float? = null,
    val importLabel: String? = null,
    val exportProgress: Float? = null,
    val purifyProgress: Float? = null,
    val elevenLabsApiKey: String = "",
    val elevenLabsStability: Float = 0.45f,
    val elevenLabsSimilarity: Float = 0.78f,
    val homeScrollIndex: Int = 0,
    val homeScrollOffset: Int = 0,
    val speechState: NativeSpeechState = NativeSpeechState(),
    val sleepTimerOption: SleepTimerOption = SleepTimerOption.OFF,
    val sleepTimerRemainingSec: Int = 0,
    val smartMixBgVolume: Float = 0.25f,
    val isSmartMixPreviewActive: Boolean = false,
    val isLiveVoiceChangerActive: Boolean = false,
    val liveVoiceEffect: VoiceEffect = VoiceEffect.CHILD_NATURAL,
    val isExtractingArticle: Boolean = false,
    val isLoadingAiGuide: Boolean = false,
    val assistantAiSummary: String? = null,
    val assistantGuideOfflineError: String? = null,
    val assistantGuideResults: List<ToolGuideResult> = emptyList(),
    val soundSearchOfflineError: String? = null,
    val onlineSoundCategories: Map<String, List<OnlineSoundItem>> = emptyMap(),
    val isSearchingOnlineSounds: Boolean = false,
    val previewingOnlineUrl: String? = null,
    val loadingOnlineSoundId: String? = null,
    val videoRenderProgress: Float? = null,
    val lastRenderedVideoPath: String? = null,
) {
    val allPresets: List<StudioPreset> get() = customPresets + BUILT_IN_PRESETS
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StudioRepository(application)
    private val firestoreRepo = FirestoreStudioRepository(application)
    private val aiRepository: AiRepository = FirebaseUnprovisionedAiRepository()
    private val smartVoiceRouter = SmartVoiceRouter()
    private val voiceProcessor = VoiceProcessor()
    private var ttsPreviewPlayer: MediaPlayer? = null
    private var ttsPreviewTempFile: File? = null
    private var mixPreviewPlayer: MediaPlayer? = null
    private var onlinePreviewPlayer: MediaPlayer? = null
    private val speechTranscriber = StudioSpeechTranscriber(application)

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

    private data class EditorSnapshot(
        val buffer: StereoAudioBuffer?,
        val dspConfig: DspConfig,
        val activePresetId: Long?,
        val selectedPresetLabel: String,
    )

    private var activeBuffer: StereoAudioBuffer? = null
    private var mixBgBuffer: StereoAudioBuffer? = null
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var lastDspSnapshotTimeMs: Long = 0L

    private var bannerJob: Job? = null
    private var tempPreviewJob: Job? = null
    private var smartMixPreviewJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var cloudPresetsJob: Job? = null
    private var cloudDraftsJob: Job? = null
    private var pendingExportResult: ExportedAudioResult? = null
    @Volatile private var lastNotifSyncMs: Long = 0L
    @Volatile private var lastNotifPlaying: Boolean = false

    private val cloudPresetsFlow = MutableStateFlow<List<StudioPreset>>(emptyList())
    private val cloudDraftsFlow = MutableStateFlow<List<CloudDraft>>(emptyList())
    private val isCloudSyncedFlow = MutableStateFlow(false)

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        attachCloudListeners(user?.uid)
        if (user != null) {
            viewModelScope.launch {
                val currentSession = repository.authSessionFlow.first()
                if (currentSession !is AuthSession.SignedIn || currentSession.uid != user.uid) {
                    completeGoogleSignIn(
                        uid = user.uid,
                        displayName = user.displayName.orEmpty(),
                        email = user.email.orEmpty(),
                    )
                }
            }
        }
    }

    private val player = StudioAudioPlayer(_uiState.value.dspConfig) { telemetry ->
        _uiState.update { it.copy(playback = telemetry) }
        val now = System.currentTimeMillis()
        val stateFlipped = telemetry.isPlaying != lastNotifPlaying
        if (stateFlipped || (telemetry.isPlaying && now - lastNotifSyncMs >= 1500L)) {
            lastNotifPlaying = telemetry.isPlaying
            lastNotifSyncMs = now
            val cur = _uiState.value
            val title = cur.activeProjectName.ifBlank { "Sonix ✦" }
            val sub = "${formatDurationMs(telemetry.positionMs)} / ${formatDurationMs(telemetry.durationMs)}"
            SonixMediaPlaybackService.syncPlaybackState(
                context = getApplication(),
                isPlaying = telemetry.isPlaying,
                title = title,
                subtitle = sub,
            )
        }
        if (!telemetry.isPlaying && speechTranscriber.state.value.isRoutingClip) {
            speechTranscriber.stop()
        }
    }

    private val recorder = StudioAudioRecorder(application) { telemetry ->
        _uiState.update { it.copy(recorder = telemetry) }
    }

    init {
        StudioPlaybackBridge.onTogglePlayPause = { togglePlayPause() }
        StudioPlaybackBridge.onStopPlayback = { stopPlayback() }

        val appCtx = getApplication<Application>()
        _uiState.update {
            it.copy(
                elevenLabsApiKey = ElevenLabsClient.getSavedApiKey(appCtx),
                elevenLabsStability = ElevenLabsClient.getSavedStability(appCtx),
                elevenLabsSimilarity = ElevenLabsClient.getSavedSimilarity(appCtx),
            )
        }

        viewModelScope.launch {
            speechTranscriber.state.collect { st ->
                _uiState.update { it.copy(speechState = st) }
            }
        }

        val firebaseAuth = runCatching { Firebase.auth }.getOrNull()
        firebaseAuth?.addAuthStateListener(authStateListener)
        attachCloudListeners(firebaseAuth?.currentUser?.uid)

        viewModelScope.launch {
            val baseFlow = combine(
                repository.languageFlow,
                repository.authSessionFlow,
                repository.autoLoopFlow,
                repository.projectsFlow,
                repository.recordingsFlow,
                repository.customPresetsFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                LocalDbSnapshot(
                    language = values[0] as AppLanguage,
                    authSession = values[1] as AuthSession,
                    autoLoop = values[2] as Boolean,
                    projects = values[3] as List<StudioProject>,
                    recordings = values[4] as List<AudioRecordingItem>,
                    localCustomPresets = values[5] as List<StudioPreset>,
                )
            }
            combine(
                baseFlow,
                cloudPresetsFlow,
                cloudDraftsFlow,
                isCloudSyncedFlow,
            ) { local, cloudPresets, cloudDrafts, isSynced ->
                PrefAndDbSnapshot(
                    language = local.language,
                    authSession = local.authSession,
                    autoLoop = local.autoLoop,
                    projects = local.projects,
                    recordings = local.recordings,
                    customPresets = if (isSynced) cloudPresets else local.localCustomPresets,
                    cloudDrafts = cloudDrafts,
                    isCloudSynced = isSynced,
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
                        cloudDrafts = snap.cloudDrafts,
                        isCloudSynced = snap.isCloudSynced,
                        activeProjectName = updatedProjectName,
                    )
                }
                val s = _uiState.value
                player.setLoop(s.loopSelection || s.autoLoopOnPlayback, s.trimStart, s.trimEnd)
            }
        }
    }

    private fun attachCloudListeners(uid: String?) {
        cloudPresetsJob?.cancel()
        cloudPresetsJob = null
        cloudDraftsJob?.cancel()
        cloudDraftsJob = null

        if (uid.isNullOrBlank()) {
            isCloudSyncedFlow.value = false
            cloudPresetsFlow.value = emptyList()
            cloudDraftsFlow.value = emptyList()
            return
        }

        isCloudSyncedFlow.value = true
        cloudPresetsJob = viewModelScope.launch {
            firestoreRepo.observeCustomPresets(uid).collect { list ->
                cloudPresetsFlow.value = list
            }
        }
        cloudDraftsJob = viewModelScope.launch {
            firestoreRepo.observeDrafts(uid).collect { list ->
                cloudDraftsFlow.value = list
            }
        }
    }

    private data class LocalDbSnapshot(
        val language: AppLanguage,
        val authSession: AuthSession,
        val autoLoop: Boolean,
        val projects: List<StudioProject>,
        val recordings: List<AudioRecordingItem>,
        val localCustomPresets: List<StudioPreset>,
    )

    private data class PrefAndDbSnapshot(
        val language: AppLanguage,
        val authSession: AuthSession,
        val autoLoop: Boolean,
        val projects: List<StudioProject>,
        val recordings: List<AudioRecordingItem>,
        val customPresets: List<StudioPreset>,
        val cloudDrafts: List<CloudDraft>,
        val isCloudSynced: Boolean,
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

    fun showStatusBanner(msg: String) {
        postBanner(msg)
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
                // Stop active Firestore snapshot collectors BEFORE Firebase.auth.signOut()
                // to avoid spurious PERMISSION_DENIED race conditions.
                attachCloudListeners(null)
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

    private fun currentSnapshot(): EditorSnapshot {
        val st = _uiState.value
        return EditorSnapshot(
            buffer = activeBuffer,
            dspConfig = st.dspConfig,
            activePresetId = st.activePresetId,
            selectedPresetLabel = st.selectedPresetLabel,
        )
    }

    private fun pushUndoSnapshot(debounced: Boolean) {
        val now = System.currentTimeMillis()
        if (debounced && now - lastDspSnapshotTimeMs < 550L) {
            return
        }
        lastDspSnapshotTimeMs = if (debounced) now else 0L
        if (undoStack.size >= 20) undoStack.removeFirst()
        undoStack.addLast(currentSnapshot())
        redoStack.clear()
    }

    private fun previewTempFile(): File = File(getApplication<Application>().cacheDir, "temp_preview.wav")

    private fun deleteTempPreviewFile() {
        tempPreviewJob?.cancel()
        tempPreviewJob = null
        runCatching { previewTempFile().delete() }
        runCatching { File(getApplication<Application>().cacheDir, "temp_preview.wav.tmp").delete() }
        _uiState.update { it.copy(previewWavPath = null) }
    }

    private fun scheduleTempPreviewRender() {
        val src = activeBuffer
        if (src == null) {
            deleteTempPreviewFile()
            return
        }
        val cfg = _uiState.value.dspConfig
        tempPreviewJob?.cancel()
        tempPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            delay(220L)
            val rendered = renderOfflineDsp(src, cfg)
            val tmpFile = File(getApplication<Application>().cacheDir, "temp_preview.wav.tmp")
            val finalFile = previewTempFile()
            writeStereoWavFile(tmpFile, rendered)
            if (!tmpFile.renameTo(finalFile)) {
                writeStereoWavFile(finalFile, rendered)
                tmpFile.delete()
            }
            _uiState.update { it.copy(previewWavPath = finalFile.absolutePath) }
        }
    }

    // Audio Buffer & Unified Undo/Redo Management
    private fun setActiveBuffer(
        buf: StereoAudioBuffer,
        recordUndo: Boolean,
        resetDspOnNewEntry: Boolean = false,
    ) {
        if (recordUndo && activeBuffer != null) {
            pushUndoSnapshot(debounced = false)
        } else if (!recordUndo) {
            undoStack.clear()
            redoStack.clear()
            lastDspSnapshotTimeMs = 0L
        }
        activeBuffer = buf
        player.setBuffer(buf, resetPosition = true)
        val peaks = computeWaveformPeaks(buf, 120)
        val cleanCfg = DspConfig()
        if (resetDspOnNewEntry) {
            syncPlayerConfig(cleanCfg, holdBypass = false)
        }
        _uiState.update { cur ->
            val updatedCfg = if (resetDspOnNewEntry) cleanCfg else cur.dspConfig
            cur.copy(
                hasAudio = true,
                durationMs = buf.durationMs,
                waveformPeaks = peaks,
                dspConfig = updatedCfg,
                activePresetId = if (resetDspOnNewEntry) null else cur.activePresetId,
                selectedPresetLabel = if (resetDspOnNewEntry) "" else cur.selectedPresetLabel,
                activeCloudDraftId = if (resetDspOnNewEntry) null else cur.activeCloudDraftId,
                isHoldBypassing = if (resetDspOnNewEntry) false else cur.isHoldBypassing,
                trimStart = 0f,
                trimEnd = 1f,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = recordUndo,
            )
        }
        scheduleTempPreviewRender()
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

    fun updateDspConfig(debouncedUndo: Boolean = true, updater: (DspConfig) -> DspConfig) {
        pushUndoSnapshot(debounced = debouncedUndo)
        val next = updater(_uiState.value.dspConfig).normalized()
        _uiState.update {
            it.copy(
                dspConfig = next,
                activePresetId = null,
                selectedPresetLabel = "",
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(next)
        scheduleTempPreviewRender()
    }

    fun resetAllEffects() {
        pushUndoSnapshot(debounced = false)
        val def = DspConfig().let { d ->
            d.copy(chain = d.chain.map { it.copy(enabled = false) })
        }
        _uiState.update {
            it.copy(
                dspConfig = def,
                activePresetId = null,
                selectedPresetLabel = "",
                isHoldBypassing = false,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(def, holdBypass = false)
        scheduleTempPreviewRender()
        postBanner("تم إيقاف كافة المؤثرات وإرجاعها إلى 0%")
    }

    fun toggleEffectEnabled(type: EffectType, enabled: Boolean) {
        updateDspConfig(debouncedUndo = false) { prev ->
            prev.copy(bypassAll = false).withSlotEnabled(type, enabled)
        }
    }

    fun moveEffectInChain(fromIdx: Int, toIdx: Int) {
        val cur = _uiState.value.dspConfig.chain
        if (fromIdx !in cur.indices || toIdx !in cur.indices || fromIdx == toIdx) return
        val mutable = cur.toMutableList()
        val moved = mutable.removeAt(fromIdx)
        mutable.add(toIdx, moved)
        updateDspConfig(debouncedUndo = false) { prev -> prev.copy(chain = mutable) }
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
        stopMixPreviewPlayer()
        SonixMediaPlaybackService.stopService(getApplication())
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        val mins = option.minutes
        if (mins == null || mins <= 0) {
            _uiState.update { it.copy(sleepTimerOption = SleepTimerOption.OFF, sleepTimerRemainingSec = 0) }
            return
        }
        val totalSec = mins * 60
        _uiState.update { it.copy(sleepTimerOption = option, sleepTimerRemainingSec = totalSec) }
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSec
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1
                _uiState.update { it.copy(sleepTimerRemainingSec = remaining) }
            }
            if (_uiState.value.playback.isPlaying) {
                player.pause()
            }
            stopMixPreviewPlayer()
            _uiState.update { it.copy(sleepTimerOption = SleepTimerOption.OFF, sleepTimerRemainingSec = 0) }
            postBanner(t(_uiState.value.language, "sleep_timer_triggered"))
        }
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
            setActiveBuffer(buf, recordUndo = false, resetDspOnNewEntry = true)
            val nowStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            repository.indexAudioRecording("تسجيل $nowStr", buf, RecordingSourceType.RECORDING)
            postBanner("تم تحميل التسجيل بالصوت الأصلي النظيف (0%) في الاستوديو")
        }
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            val resolvedName = resolveFileName(uri) ?: "ملف مستورد"
            _uiState.update {
                it.copy(
                    isBusy = true,
                    importProgress = 0.02f,
                    importLabel = resolvedName,
                )
            }
            try {
                player.stop()
                val buf = AudioDecoder.decodeUriWithProgress(getApplication(), uri) { p ->
                    _uiState.update { it.copy(importProgress = p) }
                }
                if (buf == null || buf.frameCount == 0) {
                    postBanner("تعذر فك ترميز الملف الصوتي المحدد — تأكد من سلامة الصيغة أو الصلاحيات")
                    return@launch
                }
                setActiveBuffer(buf, recordUndo = false, resetDspOnNewEntry = true)
                val title = resolvedName.substringBeforeLast('.').ifEmpty { "ملف مستورد" }
                if (_uiState.value.activeProjectId == 0L) {
                    _uiState.update { it.copy(activeProjectName = title) }
                }
                repository.indexAudioRecording(title, buf, RecordingSourceType.IMPORTED)
                postBanner("تم استيراد الملف ($title) بالصوت الأصلي النقي (0%) جاهزاً للمعالجة")
            } finally {
                _uiState.update { it.copy(isBusy = false, importProgress = null, importLabel = null) }
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
            val name = resolveFileName(uri) ?: "Background Track"
            _uiState.update { it.copy(isBusy = true, importProgress = 0.02f, importLabel = name) }
            try {
                val bg = AudioDecoder.decodeUriWithProgress(getApplication(), uri) { p ->
                    _uiState.update { it.copy(importProgress = p) }
                }
                if (bg == null || bg.frameCount == 0) {
                    postBanner("تعذر قراءة ملف الخلفية الصوتية")
                    return@launch
                }
                mixBgBuffer = bg
                _uiState.update {
                    it.copy(
                        mixBgName = name,
                        mixBgDurationMs = bg.durationMs,
                    )
                }
                scheduleSmartDuckingPreviewRender()
                postBanner("تم تحميل مسار الخلفية بنجاح، يمكنك الآن ضبط المستويات والمعاينة التلقائية")
            } finally {
                _uiState.update { it.copy(isBusy = false, importProgress = null, importLabel = null) }
            }
        }
    }

    fun clearMixBackgroundTrack() {
        mixBgBuffer = null
        stopMixPreviewPlayer()
        _uiState.update { it.copy(mixBgName = null, mixBgDurationMs = 0L, isSmartMixPreviewActive = false) }
    }

    fun setSmartMixBgVolume(volume: Float) {
        val clamped = clamp(volume, 0f, 1.0f)
        _uiState.update { it.copy(smartMixBgVolume = clamped) }
        scheduleSmartDuckingPreviewRender()
    }

    private fun scheduleSmartDuckingPreviewRender() {
        val voice = activeBuffer ?: return
        val music = mixBgBuffer ?: return
        val bgVol = _uiState.value.smartMixBgVolume
        smartMixPreviewJob?.cancel()
        smartMixPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            delay(260L)
            val mixed = mixWithSidechainAutoDucking(
                voice = voice,
                music = music,
                musicBaseVolume = bgVol,
            )
            val tmpFile = File(getApplication<Application>().cacheDir, "temp_preview.wav.tmp")
            val finalFile = previewTempFile()
            writeStereoWavFile(tmpFile, mixed)
            if (!tmpFile.renameTo(finalFile)) {
                writeStereoWavFile(finalFile, mixed)
                tmpFile.delete()
            }
            _uiState.update {
                it.copy(
                    previewWavPath = finalFile.absolutePath,
                    isSmartMixPreviewActive = true,
                )
            }
        }
    }

    fun toggleSmartDuckingPreviewPlayback() {
        if (_uiState.value.isSmartMixPreviewActive && mixPreviewPlayer?.isPlaying == true) {
            stopMixPreviewPlayer()
            return
        }
        val voice = activeBuffer
        val music = mixBgBuffer
        if (voice == null || music == null) {
            postBanner("يرجى اختيار مسار الصوت وموسيقى الخلفية أولاً")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                player.pause()
                val mixed = withContext(Dispatchers.Default) {
                    mixWithSidechainAutoDucking(
                        voice = voice,
                        music = music,
                        musicBaseVolume = _uiState.value.smartMixBgVolume,
                    )
                }
                val finalFile = previewTempFile()
                withContext(Dispatchers.IO) {
                    writeStereoWavFile(finalFile, mixed)
                }
                _uiState.update { it.copy(previewWavPath = finalFile.absolutePath, isSmartMixPreviewActive = true) }
                stopMixPreviewPlayer()
                val mp = MediaPlayer().apply {
                    setDataSource(finalFile.absolutePath)
                    setOnCompletionListener {
                        stopMixPreviewPlayer()
                    }
                    prepare()
                    start()
                }
                mixPreviewPlayer = mp
            } catch (_: Throwable) {
                postBanner("تعذر تشغيل معاينة الدمج التلقائي")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun stopMixPreviewPlayer() {
        runCatching { mixPreviewPlayer?.stop() }
        runCatching { mixPreviewPlayer?.release() }
        mixPreviewPlayer = null
    }

    fun applySmartDuckingMixToStudio() {
        val voice = activeBuffer
        val music = mixBgBuffer
        if (voice == null || music == null) {
            postBanner("يرجى اختيار مسار الصوت وموسيقى الخلفية أولاً")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                stopMixPreviewPlayer()
                val mixed = withContext(Dispatchers.Default) {
                    mixWithSidechainAutoDucking(
                        voice = voice,
                        music = music,
                        musicBaseVolume = _uiState.value.smartMixBgVolume,
                    )
                }
                setActiveBuffer(mixed, recordUndo = true)
                postBanner("تم اعتماد الدمج الذكي (Auto-Ducking) وحفظ النسخة في التراجع")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun exportSmartDuckingMix(
        format: ExportAudioFormat,
        fileName: String,
        shareAfter: Boolean = false,
        targetPackage: String? = null,
    ) {
        val voice = activeBuffer ?: run {
            postBanner(t(_uiState.value.language, "no_audio_loaded"))
            return
        }
        val music = mixBgBuffer
        if (music == null) {
            exportRenderedAudio(
                format = format,
                fileName = fileName,
                shareAfter = shareAfter,
                targetPackage = targetPackage,
            )
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, exportProgress = 0.08f) }
            try {
                val bgVol = _uiState.value.smartMixBgVolume
                val mixed = withContext(Dispatchers.Default) {
                    mixWithSidechainAutoDucking(
                        voice = voice,
                        music = music,
                        musicBaseVolume = bgVol,
                    )
                }
                _uiState.update { it.copy(exportProgress = 0.45f) }
                val res = exportRenderedAudioToFile(
                    context = getApplication(),
                    buffer = mixed,
                    format = format,
                    baseFileName = fileName.ifBlank { "Sonix_Smart_Mix" },
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
                            targetPackage = targetPackage,
                        ),
                    )
                } else {
                    val cleanName = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Smart_Mix" }
                    _sideEffects.tryEmit(
                        StudioSideEffect.PromptSaveDocument(
                            result = res,
                            suggestedName = "$cleanName.${res.extension}",
                        ),
                    )
                }
            } catch (_: Throwable) {
                postBanner("تعذر تصدير الدمج الذكي")
            } finally {
                _uiState.update { it.copy(isBusy = false, exportProgress = null) }
            }
        }
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
            val name = resolveFileName(uri) ?: "Extra Clip"
            _uiState.update { it.copy(isBusy = true, importProgress = 0.02f, importLabel = name) }
            try {
                val extra = AudioDecoder.decodeUriWithProgress(getApplication(), uri) { p ->
                    _uiState.update { it.copy(importProgress = p) }
                }
                if (extra == null || extra.frameCount == 0) {
                    postBanner("تعذر فك ترميز المقطع الإضافي للدمج")
                    return@launch
                }
                setActiveBuffer(mergeClips(src, extra), recordUndo = true)
                postBanner("تم دمج المقطع الصوتي في نهاية الصوت بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false, importProgress = null, importLabel = null) }
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
        lastDspSnapshotTimeMs = 0L
        if (redoStack.size >= 20) redoStack.removeFirst()
        redoStack.addLast(currentSnapshot())

        val bufferChanged = prev.buffer != null && prev.buffer !== activeBuffer
        if (bufferChanged) {
            activeBuffer = prev.buffer
            player.setBuffer(prev.buffer, resetPosition = true)
        }
        val restoredCfg = prev.dspConfig.normalized()
        syncPlayerConfig(restoredCfg)
        _uiState.update { cur ->
            val targetBuf = prev.buffer
            val newPeaks = if (bufferChanged && targetBuf != null) computeWaveformPeaks(targetBuf, 120) else cur.waveformPeaks
            cur.copy(
                hasAudio = targetBuf != null || cur.hasAudio,
                durationMs = if (bufferChanged && targetBuf != null) targetBuf.durationMs else cur.durationMs,
                waveformPeaks = newPeaks,
                trimStart = if (bufferChanged) 0f else cur.trimStart,
                trimEnd = if (bufferChanged) 1f else cur.trimEnd,
                dspConfig = restoredCfg,
                activePresetId = prev.activePresetId,
                selectedPresetLabel = prev.selectedPresetLabel,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        scheduleTempPreviewRender()
        postBanner("تم التراجع عن آخر تعديل")
    }

    fun redoLastEdit() {
        val next = redoStack.removeLastOrNull() ?: return
        lastDspSnapshotTimeMs = 0L
        if (undoStack.size >= 20) undoStack.removeFirst()
        undoStack.addLast(currentSnapshot())

        val bufferChanged = next.buffer != null && next.buffer !== activeBuffer
        if (bufferChanged) {
            activeBuffer = next.buffer
            player.setBuffer(next.buffer, resetPosition = true)
        }
        val restoredCfg = next.dspConfig.normalized()
        syncPlayerConfig(restoredCfg)
        _uiState.update { cur ->
            val targetBuf = next.buffer
            val newPeaks = if (bufferChanged && targetBuf != null) computeWaveformPeaks(targetBuf, 120) else cur.waveformPeaks
            cur.copy(
                hasAudio = targetBuf != null || cur.hasAudio,
                durationMs = if (bufferChanged && targetBuf != null) targetBuf.durationMs else cur.durationMs,
                waveformPeaks = newPeaks,
                trimStart = if (bufferChanged) 0f else cur.trimStart,
                trimEnd = if (bufferChanged) 1f else cur.trimEnd,
                dspConfig = restoredCfg,
                activePresetId = next.activePresetId,
                selectedPresetLabel = next.selectedPresetLabel,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        scheduleTempPreviewRender()
        postBanner("تمت إعادة التعديل")
    }

    // Projects, Presets, Cloud Drafts & Export
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
        lastDspSnapshotTimeMs = 0L
        _uiState.update { it.copy(canUndoEdit = false, canRedoEdit = false) }
        saveCurrentProject()
    }

    fun newProject() {
        player.stop()
        activeBuffer = null
        undoStack.clear()
        redoStack.clear()
        lastDspSnapshotTimeMs = 0L
        deleteTempPreviewFile()
        val def = DspConfig()
        player.setBuffer(null, resetPosition = true)
        syncPlayerConfig(def, holdBypass = false)
        _uiState.update {
            it.copy(
                activeProjectId = 0L,
                activeCloudDraftId = null,
                activeProjectName = t(it.language, "untitled_project"),
                hasAudio = false,
                durationMs = 0L,
                waveformPeaks = emptyList(),
                dspConfig = def,
                activePresetId = null,
                selectedPresetLabel = "",
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
                        activeCloudDraftId = null,
                        activeProjectName = project.name,
                        dspConfig = project.config,
                        activePresetId = null,
                        selectedPresetLabel = "",
                    )
                }
                syncPlayerConfig(project.config)
                if (buf != null) {
                    setActiveBuffer(buf, recordUndo = false, resetDspOnNewEntry = false)
                } else {
                    activeBuffer = null
                    player.setBuffer(null, resetPosition = true)
                    deleteTempPreviewFile()
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
                setActiveBuffer(buf, recordUndo = false, resetDspOnNewEntry = true)
                postBanner("تم فتح \"${rec.title}\" بالصوت الأصلي النظيف في الاستوديو")
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
        pushUndoSnapshot(debounced = false)
        val next = preset.config.copy(bypassAll = false).normalized()
        _uiState.update {
            it.copy(
                dspConfig = next,
                activePresetId = preset.id,
                selectedPresetLabel = preset.name,
                isHoldBypassing = false,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(next, holdBypass = false)
        scheduleTempPreviewRender()
        postBanner("تم تطبيق قالب \"${preset.name}\" للمعاينة الفورية")
    }

    fun saveCustomPreset(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
            if (!uid.isNullOrBlank()) {
                val res = firestoreRepo.saveCustomPreset(uid, name, description, _uiState.value.dspConfig)
                if (res.isSuccess) {
                    postBanner("تم حفظ القالب \"$name\" في السحابة بنجاح")
                } else {
                    val errMsg = res.exceptionOrNull()?.localizedMessage ?: "تعذر حفظ القالب في السحابة"
                    postBanner(errMsg)
                }
            } else {
                repository.saveCustomPreset(name, description, _uiState.value.dspConfig)
                postBanner("تم حفظ القالب \"$name\"")
            }
        }
    }

    fun deleteCustomPreset(presetId: Long) {
        viewModelScope.launch {
            val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
            val match = _uiState.value.customPresets.firstOrNull { it.id == presetId }
            val cloudDocId = match?.cloudDocId
            if (!uid.isNullOrBlank() && !cloudDocId.isNullOrBlank()) {
                val res = firestoreRepo.deleteCustomPreset(uid, cloudDocId)
                if (res.isSuccess) {
                    postBanner("تم حذف القالب المخصص من السحابة")
                } else {
                    postBanner(res.exceptionOrNull()?.localizedMessage ?: "تعذر حذف القالب المخصص")
                }
            } else {
                repository.deleteCustomPreset(presetId)
                postBanner("تم حذف القالب المخصص")
            }
        }
    }

    fun saveCloudDraft(draftName: String) {
        val cleanName = draftName.trim()
        if (cleanName.isBlank()) return
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        val lang = _uiState.value.language
        if (uid.isNullOrBlank()) {
            postBanner(t(lang, "cloud_drafts_signin_hint"))
            return
        }
        val st = _uiState.value
        val presetName = st.selectedPresetLabel.ifBlank { st.activeProjectName }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val res = firestoreRepo.saveDraft(
                    userId = uid,
                    existingDraftId = st.activeCloudDraftId,
                    name = cleanName,
                    selectedPresetName = presetName,
                    durationMs = st.durationMs,
                    sampleRate = activeBuffer?.sampleRate ?: 44100,
                    config = st.dspConfig,
                )
                if (res.isSuccess) {
                    val newDocId = res.getOrNull()
                    _uiState.update {
                        it.copy(
                            activeCloudDraftId = newDocId ?: it.activeCloudDraftId,
                            activeProjectName = cleanName,
                            hasUnsavedChanges = false,
                        )
                    }
                    postBanner(t(lang, "draft_saved_success"))
                } else {
                    postBanner(res.exceptionOrNull()?.localizedMessage ?: "تعذر حفظ المسودة السحابية")
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun loadCloudDraft(draft: CloudDraft) {
        pushUndoSnapshot(debounced = false)
        val restored = draft.config.copy(bypassAll = false).normalized()
        syncPlayerConfig(restored, holdBypass = false)
        _uiState.update {
            it.copy(
                activeCloudDraftId = draft.docId,
                activeProjectName = draft.name,
                dspConfig = restored,
                activePresetId = null,
                selectedPresetLabel = draft.selectedPresetName,
                isHoldBypassing = false,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = false,
            )
        }
        scheduleTempPreviewRender()
        postBanner("تم تحميل المسودة \"${draft.name}\" للمعاينة")
    }

    fun deleteCloudDraft(draftId: String) {
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull() ?: return
        viewModelScope.launch {
            val res = firestoreRepo.deleteDraft(uid, draftId)
            if (res.isSuccess) {
                if (_uiState.value.activeCloudDraftId == draftId) {
                    _uiState.update { it.copy(activeCloudDraftId = null) }
                }
                postBanner("تم حذف المسودة السحابية")
            } else {
                postBanner(res.exceptionOrNull()?.localizedMessage ?: "تعذر حذف المسودة")
            }
        }
    }

    fun exportRenderedAudio(
        format: ExportAudioFormat,
        fileName: String,
        shareAfter: Boolean = false,
        targetPackage: String? = null,
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
                            targetPackage = targetPackage,
                        ),
                    )
                } else {
                    val cleanName = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Export" }
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

    fun exportAudioForUnity(
        assetName: String,
        shareAfter: Boolean = false,
        useSmartMixIfPresent: Boolean = false,
    ) {
        val src = activeBuffer ?: run {
            postBanner("لا يوجد صوت لتصديره لمحرك Unity حالياً")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, exportProgress = 0.08f) }
            try {
                val cfg = _uiState.value.dspConfig
                val bg = mixBgBuffer
                val bgVol = _uiState.value.smartMixBgVolume
                val rendered = withContext(Dispatchers.Default) {
                    if (useSmartMixIfPresent && bg != null) {
                        mixWithSidechainAutoDucking(
                            voice = src,
                            music = bg,
                            musicBaseVolume = bgVol,
                        )
                    } else {
                        renderOfflineDsp(src, cfg)
                    }
                }
                _uiState.update { it.copy(exportProgress = 0.50f) }

                val app = getApplication<Application>()
                val unityDir = File(app.cacheDir, "exports/unity").apply { mkdirs() }
                val bundle = UnityExporter.exportBufferForUnity(
                    buffer = rendered,
                    outputDir = unityDir,
                    assetName = assetName.ifBlank { "SonixGameAudio" },
                )
                if (bundle == null) {
                    postBanner("تعذر توليد حزمة الصوت لمحرك Unity")
                    return@launch
                }
                _uiState.update { it.copy(exportProgress = 1.0f) }

                val fileToSave = bundle.zipBundleFile ?: bundle.wavFile
                val ext = if (bundle.zipBundleFile != null) "zip" else "wav"
                val mime = if (bundle.zipBundleFile != null) "application/zip" else "audio/wav"
                val res = ExportedAudioResult(fileToSave, ext, mime)
                pendingExportResult = res

                if (shareAfter) {
                    val wavUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", bundle.wavFile)
                    val csUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", bundle.csharpScriptFile)
                    _sideEffects.tryEmit(
                        StudioSideEffect.LaunchMultiShareIntent(
                            uris = listOf(wavUri, csUri),
                            mimeType = "*/*",
                            title = "${bundle.wavFile.nameWithoutExtension} • Unity Assets",
                        ),
                    )
                    postBanner("تم تجهيز ملف الصوت (${bundle.wavFile.name}) وسكربت (${bundle.csharpScriptFile.name}) لمحرك Unity")
                } else {
                    _sideEffects.tryEmit(
                        StudioSideEffect.PromptSaveDocument(
                            result = res,
                            suggestedName = fileToSave.name,
                        ),
                    )
                    postBanner("تم تجهيز حزمة Unity 48kHz (${bundle.wavFile.name} + ${bundle.csharpScriptFile.name})")
                }
            } catch (_: Throwable) {
                postBanner("تعذر تصدير حزمة محرك الألعاب Unity")
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
                pushUndoSnapshot(debounced = false)
                val analysis = withContext(Dispatchers.Default) {
                    analyzeAndOptimizeAudio(src, _uiState.value.dspConfig)
                }
                _uiState.update {
                    it.copy(
                        smartMasterState = analysis,
                        dspConfig = analysis.optimizedConfig,
                        activePresetId = null,
                        selectedPresetLabel = "Smart Auto-Master",
                        canUndoEdit = undoStack.isNotEmpty(),
                        canRedoEdit = redoStack.isNotEmpty(),
                        hasUnsavedChanges = true,
                    )
                }
                syncPlayerConfig(analysis.optimizedConfig)
                scheduleTempPreviewRender()
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

    // In-App Audio Library Scanner & Actions
    fun refreshDeviceAudios() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningDeviceAudio = true) }
            try {
                val items = MediaStoreAudioRepository.queryDeviceAudioFiles(getApplication())
                _uiState.update { it.copy(deviceAudios = items) }
            } finally {
                _uiState.update { it.copy(isScanningDeviceAudio = false) }
            }
        }
    }

    fun importDeviceAudioItem(item: DeviceAudioItem) {
        importAudioUri(Uri.parse(item.uriString))
    }

    fun appendDeviceAudioItem(item: DeviceAudioItem) {
        appendAudioUriToCurrent(Uri.parse(item.uriString))
    }

    fun loadMixBackgroundDeviceItem(item: DeviceAudioItem) {
        loadMixBackgroundUri(Uri.parse(item.uriString))
    }

    // Global Pro Presets & 18-Tool Operations (100% Non-Destructive Live Preview)
    fun applyGlobalProPreset(preset: GlobalProPreset, bakeOffline: Boolean = false) {
        pushUndoSnapshot(debounced = false)
        val nextCfg = preset.dspConfig.copy(bypassAll = false).normalized()
        val title = t(_uiState.value.language, preset.titleKey)
        _uiState.update {
            it.copy(
                dspConfig = nextCfg,
                activePresetId = null,
                selectedPresetLabel = title,
                isHoldBypassing = false,
                canUndoEdit = undoStack.isNotEmpty(),
                canRedoEdit = redoStack.isNotEmpty(),
                hasUnsavedChanges = true,
            )
        }
        syncPlayerConfig(nextCfg, holdBypass = false)
        scheduleTempPreviewRender()
        postBanner("تم تفعيل القالب العالمي \"$title\" للمعاينة الحية بدون المساس بالأصل")
    }

    fun applyReverseAudio() {
        val src = activeBuffer ?: run {
            postBanner("يرجى تسجيل أو اختيار ملف صوتي أولاً")
            return
        }
        val reversed = reverseAudio(src)
        setActiveBuffer(reversed, recordUndo = true)
        postBanner("تم عكس مسار الصوت بنجاح (Reverse)")
    }

    fun insertGeneratedSilence(durationSeconds: Float, appendToCurrent: Boolean) {
        val silence = generateSilence(durationSeconds)
        val cur = activeBuffer
        val isAppend = appendToCurrent && cur != null
        val result = if (isAppend) {
            mergeClips(cur!!, silence, crossfadeMs = 5)
        } else {
            silence
        }
        setActiveBuffer(result, recordUndo = isAppend, resetDspOnNewEntry = !isAppend)
        postBanner("تم إنشاء مسار صمت (${"%.1f".format(durationSeconds)} ثانية) وإدراجه بالاستوديو")
    }

    fun applySurroundStereo(midLevel: Float, sideLevel: Float, stereoWidth: Float) {
        val src = activeBuffer ?: run {
            postBanner("يرجى اختيار ملف صوتي أولاً للتحويل إلى ستيريو")
            return
        }
        val widened = convertToSurroundStereo(src, midLevel, sideLevel, stereoWidth)
        setActiveBuffer(widened, recordUndo = true)
        postBanner("تم تحويل المقطع إلى ستيريو محيطي متسع (M/S Surround)")
    }

    fun applyPitchAndSpeed(semitones: Float, tempoRatio: Float) {
        val src = activeBuffer ?: run {
            postBanner("يرجى اختيار ملف صوتي أولاً لتعديل الطبقة والسرعة")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, purifyProgress = 0.2f) }
            try {
                val processed = applyPitchAndSpeedOffline(src, semitones, tempoRatio)
                setActiveBuffer(processed, recordUndo = true)
                postBanner("تم تعديل الطبقة والسرعة وحفظ النسخة في التراجع")
            } finally {
                _uiState.update { it.copy(isBusy = false, purifyProgress = null) }
            }
        }
    }

    fun splitAudioAtFraction(fraction: Float, loadPart: Int, saveBothPartsToLibrary: Boolean) {
        val src = activeBuffer ?: run {
            postBanner("يرجى اختيار ملف صوتي أولاً لتقسيمه")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val (part1, part2) = splitAudioByTime(src, fraction)
                if (saveBothPartsToLibrary) {
                    val base = _uiState.value.activeProjectName.ifBlank { "Clip" }
                    repository.indexAudioRecording("$base • الجزء 1", part1, RecordingSourceType.RECORDING)
                    repository.indexAudioRecording("$base • الجزء 2", part2, RecordingSourceType.RECORDING)
                }
                setActiveBuffer(if (loadPart == 2) part2 else part1, recordUndo = true)
                postBanner("تم تقسيم المسار الصوتي وتحميل الجزء المختار في الاستوديو")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun splitStereoChannel(keepLeft: Boolean) {
        val src = activeBuffer ?: run {
            postBanner("يرجى اختيار ملف صوتي أولاً لفصل القنوات")
            return
        }
        val (leftBuf, rightBuf) = splitStereoChannels(src)
        setActiveBuffer(if (keepLeft) leftBuf else rightBuf, recordUndo = true)
        postBanner(if (keepLeft) "تم عزل القناة اليسرى (Left Channel)" else "تم عزل القناة اليمنى (Right Channel)")
    }

    fun compressAndExportAudio(bitrateKbps: Int, fileName: String, shareAfter: Boolean) {
        val src = activeBuffer ?: run {
            postBanner("لا يوجد صوت لضغطه حالياً")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, exportProgress = 0.1f) }
            try {
                val rendered = withContext(Dispatchers.Default) {
                    renderOfflineDsp(src, _uiState.value.dspConfig)
                }
                _uiState.update { it.copy(exportProgress = 0.45f) }
                val res = exportRenderedAudioToFile(
                    context = getApplication(),
                    buffer = rendered,
                    format = ExportAudioFormat.M4A,
                    baseFileName = fileName.ifBlank { "Sonix_${bitrateKbps}kbps" },
                    bitrateKbps = bitrateKbps,
                ) { p ->
                    _uiState.update { it.copy(exportProgress = 0.45f + p * 0.55f) }
                }
                pendingExportResult = res
                if (shareAfter) {
                    val app = getApplication<Application>()
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", res.file)
                    _sideEffects.tryEmit(StudioSideEffect.LaunchShareIntent(uri, res.mimeType, res.file.name))
                } else {
                    val cleanName = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_${bitrateKbps}k" }
                    _sideEffects.tryEmit(StudioSideEffect.PromptSaveDocument(res, "$cleanName.${res.extension}"))
                }
            } catch (_: Throwable) {
                postBanner("تعذر ضغط وتصدير الملف الصوتي")
            } finally {
                _uiState.update { it.copy(isBusy = false, exportProgress = null) }
            }
        }
    }

    fun extractAudioFromVideoUri(videoUri: Uri) {
        importAudioUri(videoUri)
    }

    fun exportVideoWithEditedAudio(
        videoUri: Uri,
        mixWithOriginal: Boolean,
        originalGain: Float,
        newGain: Float,
        outputName: String,
        shareAfter: Boolean,
    ) {
        val studioAudio = activeBuffer ?: run {
            postBanner("استورد أو سجّل الصوت الجديد أولاً ثم امزجه مع الفيديو")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, exportProgress = 0.05f) }
            try {
                val app = getApplication<Application>()
                val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
                val cleanBase = outputName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Video_Mix" }
                val outMp4 = File(exportDir, "$cleanBase.mp4")
                val ok = replaceOrMixVideoAudio(
                    context = app,
                    videoUri = videoUri,
                    newAudio = studioAudio,
                    mixWithOriginal = mixWithOriginal,
                    originalGain = originalGain,
                    newGain = newGain,
                    outputMp4File = outMp4,
                ) { p ->
                    _uiState.update { it.copy(exportProgress = p) }
                }
                if (!ok || !outMp4.exists()) {
                    postBanner("تعذر دمج الصوت مع ملف الفيديو المحدد")
                    return@launch
                }
                val res = ExportedAudioResult(outMp4, "mp4", "video/mp4")
                pendingExportResult = res
                if (shareAfter) {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", outMp4)
                    _sideEffects.tryEmit(StudioSideEffect.LaunchShareIntent(uri, "video/mp4", outMp4.name))
                } else {
                    _sideEffects.tryEmit(StudioSideEffect.PromptSaveDocument(res, "$cleanBase.mp4"))
                }
            } finally {
                _uiState.update { it.copy(isBusy = false, exportProgress = null) }
            }
        }
    }

    fun createRingtoneFromSelection(
        startFrac: Float,
        endFrac: Float,
        title: String,
        setAsDefault: Boolean,
        onNeedsWriteSettings: () -> Unit,
    ) {
        val src = activeBuffer ?: run {
            postBanner("يرجى اختيار أو تسجيل مقطع صوتي لصناعة النغمة")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val clipped = trimToSelection(src, clamp(startFrac, 0f, 0.98f), clamp(endFrac, 0.02f, 1f))
                val rendered = withContext(Dispatchers.Default) {
                    renderOfflineDsp(clipped, _uiState.value.dspConfig)
                }
                when (val res = saveAndSetRingtone(getApplication(), rendered, title, setAsDefault)) {
                    is RingtoneSaveResult.Saved -> {
                        if (res.needsWriteSettings) {
                            postBanner("تم حفظ النغمة بمجلد الرنين — اسمح بتعديل إعدادات النظام لتعيينها نغمة افتراضية")
                            onNeedsWriteSettings()
                        } else if (res.appliedAsDefault) {
                            postBanner("تم حفظ النغمة وتعيينها كنغمة رنين افتراضية بنجاح")
                        } else {
                            postBanner("تم حفظ النغمة في مجلد نغمات الرنين بنجاح")
                        }
                    }
                    is RingtoneSaveResult.Error -> postBanner(res.message)
                }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    // TTS Studio (ElevenLabs eleven_multilingual_v2 Emotional Voice — zero robotic offline fallback)
    fun saveElevenLabsApiKey(rawKey: String) {
        val clean = rawKey.trim()
        ElevenLabsClient.saveApiKey(getApplication(), clean)
        _uiState.update {
            it.copy(
                elevenLabsApiKey = clean,
                ttsUiState = it.ttsUiState.copy(
                    missingApiKey = clean.isBlank(),
                    errorMessage = if (clean.isNotBlank()) null else it.ttsUiState.errorMessage,
                ),
            )
        }
        if (clean.isNotBlank()) {
            postBanner(t(_uiState.value.language, "elevenlabs_key_saved"))
        }
    }

    fun updateElevenLabsStability(stability: Float) {
        val clamped = clamp(stability, 0.05f, 0.98f)
        ElevenLabsClient.saveStability(getApplication(), clamped)
        _uiState.update { it.copy(elevenLabsStability = clamped) }
    }

    fun updateElevenLabsSimilarity(similarity: Float) {
        val clamped = clamp(similarity, 0.05f, 1.0f)
        ElevenLabsClient.saveSimilarity(getApplication(), clamped)
        _uiState.update { it.copy(elevenLabsSimilarity = clamped) }
    }

    private fun releaseTtsPreviewPlayer() {
        runCatching { ttsPreviewPlayer?.stop() }
        runCatching { ttsPreviewPlayer?.release() }
        ttsPreviewPlayer = null
        ttsPreviewTempFile?.delete()
        ttsPreviewTempFile = null
    }

    fun previewTtsSpeech(
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
    ) {
        if (text.isBlank()) {
            _uiState.update { it.copy(ttsUiState = it.ttsUiState.copy(errorMessage = "يرجى إدخال النص أولاً.")) }
            return
        }
        val lang = _uiState.value.language
        val savedKey = ElevenLabsClient.getSavedApiKey(getApplication())
        if (savedKey.isBlank()) {
            val keyMsg = t(lang, "elevenlabs_missing_key_banner")
            stopTtsPreview()
            _uiState.update {
                it.copy(
                    ttsUiState = TtsStudioUiState(
                        missingApiKey = true,
                        errorMessage = keyMsg,
                    ),
                )
            }
            postBanner(keyMsg)
            return
        }
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(lang, "elevenlabs_offline_banner")
            stopTtsPreview()
            _uiState.update {
                it.copy(
                    ttsUiState = TtsStudioUiState(
                        isOffline = true,
                        errorMessage = offlineMsg,
                    ),
                )
            }
            postBanner(offlineMsg)
            return
        }
        viewModelScope.launch {
            stopTtsPreview()
            _uiState.update {
                it.copy(
                    ttsUiState = TtsStudioUiState(
                        isSynthesizingPreview = true,
                        isPreviewing = false,
                        isGenerating = false,
                        usedFallbackEngine = false,
                        missingApiKey = false,
                        isOffline = false,
                        errorMessage = null,
                    ),
                )
            }
            val cur = _uiState.value
            val outcome = smartVoiceRouter.synthesizeStudioVoiceWithStatus(
                context = getApplication(),
                text = text.trim(),
                dialect = dialect,
                gender = gender,
                style = style,
                speechRate = speechRate,
                pitch = pitch,
                stability = cur.elevenLabsStability,
                similarity = cur.elevenLabsSimilarity,
            )
            when (outcome) {
                is VoiceRouteOutcome.Success -> {
                    val synthRes = outcome.result
                    ttsPreviewTempFile = synthRes.mp3File
                    val mp = MediaPlayer()
                    val prepared = runCatching {
                        mp.setDataSource(synthRes.mp3File.absolutePath)
                        mp.prepare()
                        mp.setOnCompletionListener {
                            releaseTtsPreviewPlayer()
                            _uiState.update { it.copy(ttsUiState = it.ttsUiState.copy(isPreviewing = false)) }
                        }
                        mp.start()
                        ttsPreviewPlayer = mp
                        true
                    }.getOrDefault(false)
                    if (prepared) {
                        _uiState.update {
                            it.copy(
                                ttsUiState = TtsStudioUiState(
                                    isSynthesizingPreview = false,
                                    isPreviewing = true,
                                    errorMessage = null,
                                ),
                            )
                        }
                    } else {
                        runCatching { mp.release() }
                        synthRes.mp3File.delete()
                        ttsPreviewTempFile = null
                        _uiState.update {
                            it.copy(
                                ttsUiState = TtsStudioUiState(
                                    errorMessage = t(lang, "tts_cloud_required_error"),
                                ),
                            )
                        }
                    }
                }
                VoiceRouteOutcome.MissingApiKey -> {
                    val keyMsg = t(lang, "elevenlabs_missing_key_banner")
                    _uiState.update {
                        it.copy(ttsUiState = TtsStudioUiState(missingApiKey = true, errorMessage = keyMsg))
                    }
                    postBanner(keyMsg)
                }
                VoiceRouteOutcome.Offline -> {
                    val offlineMsg = t(lang, "elevenlabs_offline_banner")
                    _uiState.update {
                        it.copy(ttsUiState = TtsStudioUiState(isOffline = true, errorMessage = offlineMsg))
                    }
                    postBanner(offlineMsg)
                }
                is VoiceRouteOutcome.ProviderError -> {
                    _uiState.update {
                        it.copy(ttsUiState = TtsStudioUiState(errorMessage = outcome.message))
                    }
                }
            }
        }
    }

    fun stopTtsPreview() {
        releaseTtsPreviewPlayer()
        _uiState.update {
            it.copy(
                ttsUiState = it.ttsUiState.copy(
                    isSynthesizingPreview = false,
                    isPreviewing = false,
                ),
            )
        }
    }

    fun generateTtsToStudio(
        text: String,
        dialect: TtsDialect,
        gender: TtsGender,
        style: TtsDeliveryStyle,
        speechRate: Float,
        pitch: Float,
        appendToExisting: Boolean,
    ) {
        if (text.isBlank()) return
        val lang = _uiState.value.language
        val savedKey = ElevenLabsClient.getSavedApiKey(getApplication())
        if (savedKey.isBlank()) {
            val keyMsg = t(lang, "elevenlabs_missing_key_banner")
            stopTtsPreview()
            _uiState.update {
                it.copy(ttsUiState = TtsStudioUiState(missingApiKey = true, errorMessage = keyMsg))
            }
            postBanner(keyMsg)
            return
        }
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(lang, "elevenlabs_offline_banner")
            stopTtsPreview()
            _uiState.update {
                it.copy(ttsUiState = TtsStudioUiState(isOffline = true, errorMessage = offlineMsg))
            }
            postBanner(offlineMsg)
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    ttsUiState = TtsStudioUiState(
                        isGenerating = true,
                        errorMessage = null,
                    ),
                )
            }
            try {
                stopTtsPreview()
                val cur = _uiState.value
                val outcome = smartVoiceRouter.synthesizeStudioVoiceWithStatus(
                    context = getApplication(),
                    text = text.trim(),
                    dialect = dialect,
                    gender = gender,
                    style = style,
                    speechRate = speechRate,
                    pitch = pitch,
                    stability = cur.elevenLabsStability,
                    similarity = cur.elevenLabsSimilarity,
                )
                when (outcome) {
                    is VoiceRouteOutcome.Success -> {
                        val synthRes = outcome.result
                        synthRes.mp3File.delete()
                        val rawBuf = synthRes.buffer
                        if (rawBuf.frameCount == 0) {
                            _uiState.update {
                                it.copy(ttsUiState = TtsStudioUiState(errorMessage = t(lang, "tts_cloud_required_error")))
                            }
                            return@launch
                        }
                        val existing = activeBuffer
                        val isAppend = appendToExisting && existing != null
                        val finalBuf = if (isAppend) {
                            mergeClips(existing!!, rawBuf)
                        } else {
                            rawBuf
                        }
                        setActiveBuffer(finalBuf, recordUndo = isAppend, resetDspOnNewEntry = !isAppend)
                        val dialectLabel = t(lang, dialect.titleKey)
                        repository.indexAudioRecording("ElevenLabs • $dialectLabel", rawBuf, RecordingSourceType.TTS)
                        _uiState.update {
                            it.copy(ttsUiState = TtsStudioUiState(errorMessage = null))
                        }
                        postBanner("تم توليد وإدراج الصوت البشري ذو المشاعر ($dialectLabel) بنجاح")
                    }
                    VoiceRouteOutcome.MissingApiKey -> {
                        val keyMsg = t(lang, "elevenlabs_missing_key_banner")
                        _uiState.update {
                            it.copy(ttsUiState = TtsStudioUiState(missingApiKey = true, errorMessage = keyMsg))
                        }
                        postBanner(keyMsg)
                    }
                    VoiceRouteOutcome.Offline -> {
                        val offlineMsg = t(lang, "elevenlabs_offline_banner")
                        _uiState.update {
                            it.copy(ttsUiState = TtsStudioUiState(isOffline = true, errorMessage = offlineMsg))
                        }
                        postBanner(offlineMsg)
                    }
                    is VoiceRouteOutcome.ProviderError -> {
                        _uiState.update {
                            it.copy(ttsUiState = TtsStudioUiState(errorMessage = outcome.message))
                        }
                    }
                }
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

    // Studio-Quality Formant & AudioSkillsPresets Voice Character Transformer
    fun transformVoiceOffline(
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        mix: Float,
        characterLabel: String,
        bassBoostDb: Float = 0f,
        bassFreqHz: Float = 75f,
        vibratoHz: Float = 0f,
        vibratoDepth: Float = 0f,
        echoDelayMs: Float = 0f,
        echoDecay: Float = 0f,
        dualLayerPitchSemitones: Float = 0f,
        dualLayerFormantShift: Float = 1.0f,
        dualLayerMix: Float = 0f,
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
                    bassBoostDb = bassBoostDb,
                    bassFreqHz = bassFreqHz,
                    vibratoHz = vibratoHz,
                    vibratoDepth = vibratoDepth,
                    echoDelayMs = echoDelayMs,
                    echoDecay = echoDecay,
                    dualLayerPitchSemitones = dualLayerPitchSemitones,
                    dualLayerFormantShift = dualLayerFormantShift,
                    dualLayerMix = dualLayerMix,
                    studioMasteringPolish = true,
                ) { p ->
                    _uiState.update { it.copy(purifyProgress = p) }
                }
                setActiveBuffer(transformed, recordUndo = true)
                postBanner("تم تحويل الصوت إلى شخصية \"$characterLabel\" بجودة الاستوديو بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false, purifyProgress = null) }
            }
        }
    }

    // Native Android SpeechRecognizer Studio
    fun isSpeechRecognizerAvailable(): Boolean = speechTranscriber.isAvailable()

    fun setSpeechLocale(localeTag: String) {
        speechTranscriber.setLocale(localeTag)
    }

    fun updateSpeechTranscript(text: String) {
        speechTranscriber.setEditedText(text)
    }

    fun startLiveSpeechDictation() {
        player.pause()
        stopMixPreviewPlayer()
        val loc = _uiState.value.speechState.localeTag
        speechTranscriber.startListening(localeTag = loc, isClipRouting = false)
    }

    fun startStudioClipSpeechTranscription() {
        if (activeBuffer == null) {
            postBanner(t(_uiState.value.language, "no_audio_loaded"))
            return
        }
        stopMixPreviewPlayer()
        val loc = _uiState.value.speechState.localeTag
        player.seekToFraction(0f)
        player.play()
        speechTranscriber.startListening(localeTag = loc, isClipRouting = true)
    }

    fun stopSpeechTranscription() {
        val wasClipRouting = speechTranscriber.state.value.isRoutingClip
        speechTranscriber.stop()
        if (wasClipRouting && _uiState.value.playback.isPlaying) {
            player.pause()
        }
    }

    fun clearSpeechTranscription() {
        speechTranscriber.clear()
    }

    // Live Zero-Latency Microphone VoiceProcessor
    fun startLiveVoiceProcessor(effect: VoiceEffect) {
        if (!recorder.hasMicPermission()) {
            setMicPermissionDeniedError()
            return
        }
        player.pause()
        stopMixPreviewPlayer()
        stopOnlineSoundPreview()
        voiceProcessor.effect = effect
        voiceProcessor.start(viewModelScope)
        _uiState.update {
            it.copy(
                micPermissionError = null,
                isLiveVoiceChangerActive = true,
                liveVoiceEffect = effect,
            )
        }
    }

    fun setLiveVoiceEffect(effect: VoiceEffect) {
        voiceProcessor.effect = effect
        _uiState.update { it.copy(liveVoiceEffect = effect) }
    }

    fun stopLiveVoiceProcessor() {
        voiceProcessor.stop()
        _uiState.update { it.copy(isLiveVoiceChangerActive = false) }
    }

    // Web Article Extractor for TTS Podcast (Online only)
    fun extractArticleTextForTts(url: String, onExtracted: (String) -> Unit) {
        if (url.isBlank()) return
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            postBanner(t(_uiState.value.language, "tts_offline_required_error"))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExtractingArticle = true) }
            try {
                val extracted = WebArticleExtractor.extractCleanTextFromUrl(url)
                if (extracted.isBlank()) {
                    postBanner(t(_uiState.value.language, "tts_web_article_empty"))
                } else {
                    onExtracted(extracted)
                    postBanner("تم استخراج نص المقال النظيف بنجاح (${extracted.length} حرف)")
                }
            } finally {
                _uiState.update { it.copy(isExtractingArticle = false) }
            }
        }
    }

    // Smart Tool Guide (Pollinations AI Online) & Archive.org Online Sound Search
    fun searchToolGuide(query: String) {
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    assistantGuideResults = emptyList(),
                    assistantAiSummary = null,
                    assistantGuideOfflineError = null,
                )
            }
            return
        }
        val quickLocalMatches = SmartAssistantAndSearch.findBestToolWithTypoTolerance(query)
        _uiState.update {
            it.copy(
                assistantGuideResults = quickLocalMatches,
                assistantGuideOfflineError = null,
            )
        }
    }

    fun askAiToolGuide(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val lang = _uiState.value.language
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(lang, "online_ai_offline_notice")
            _uiState.update {
                it.copy(
                    isLoadingAiGuide = false,
                    assistantAiSummary = null,
                    assistantGuideOfflineError = offlineMsg,
                )
            }
            postBanner(offlineMsg)
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingAiGuide = true,
                    assistantGuideOfflineError = null,
                )
            }
            try {
                val response = SmartAssistantAndSearch.askPollinationsToolGuide(clean, lang)
                _uiState.update {
                    it.copy(
                        assistantAiSummary = response.aiSummary,
                        assistantGuideResults = response.tools,
                        assistantGuideOfflineError = null,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoadingAiGuide = false) }
            }
        }
    }

    fun searchInternetSounds(query: String) {
        val lang = _uiState.value.language
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(lang, "online_sfx_offline_notice")
            _uiState.update {
                it.copy(
                    isSearchingOnlineSounds = false,
                    soundSearchOfflineError = offlineMsg,
                )
            }
            postBanner(offlineMsg)
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearchingOnlineSounds = true,
                    soundSearchOfflineError = null,
                )
            }
            try {
                val grouped = SmartAssistantAndSearch.searchInternetSoundsOrganized(query)
                _uiState.update { it.copy(onlineSoundCategories = grouped) }
                if (grouped.isEmpty()) {
                    postBanner("لم يتم العثور على نتائج حالياً — جرب كلمة بحث أخرى")
                }
            } finally {
                _uiState.update { it.copy(isSearchingOnlineSounds = false) }
            }
        }
    }

    fun toggleOnlineSoundPreview(item: OnlineSoundItem) {
        if (_uiState.value.previewingOnlineUrl == item.streamUrl) {
            stopOnlineSoundPreview()
            return
        }
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(_uiState.value.language, "online_sfx_offline_notice")
            _uiState.update { it.copy(soundSearchOfflineError = offlineMsg) }
            postBanner(offlineMsg)
            return
        }
        stopOnlineSoundPreview()
        player.pause()
        stopMixPreviewPlayer()
        _uiState.update { it.copy(previewingOnlineUrl = item.streamUrl, soundSearchOfflineError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val mp = MediaPlayer()
            val ok = runCatching {
                mp.setDataSource(item.streamUrl)
                mp.setOnPreparedListener { startedMp ->
                    if (_uiState.value.previewingOnlineUrl == item.streamUrl) {
                        startedMp.start()
                    } else {
                        runCatching { startedMp.release() }
                    }
                }
                mp.setOnCompletionListener {
                    stopOnlineSoundPreview()
                }
                mp.setOnErrorListener { _, _, _ ->
                    stopOnlineSoundPreview()
                    postBanner("تعذر تشغيل المعاينة من الإنترنت")
                    true
                }
                onlinePreviewPlayer = mp
                mp.prepareAsync()
                true
            }.getOrDefault(false)
            if (!ok) {
                runCatching { mp.release() }
                stopOnlineSoundPreview()
            }
        }
    }

    fun stopOnlineSoundPreview() {
        runCatching { onlinePreviewPlayer?.stop() }
        runCatching { onlinePreviewPlayer?.release() }
        onlinePreviewPlayer = null
        _uiState.update { it.copy(previewingOnlineUrl = null) }
    }

    fun importOnlineSoundToStudio(item: OnlineSoundItem) {
        if (!NetworkUtils.isInternetAvailable(getApplication())) {
            val offlineMsg = t(_uiState.value.language, "online_sfx_offline_notice")
            _uiState.update { it.copy(soundSearchOfflineError = offlineMsg) }
            postBanner(offlineMsg)
            return
        }
        viewModelScope.launch {
            stopOnlineSoundPreview()
            _uiState.update { it.copy(isBusy = true, loadingOnlineSoundId = item.identifier) }
            try {
                val buf = SmartAssistantAndSearch.downloadSoundToStudioBuffer(getApplication(), item)
                if (buf == null || buf.frameCount == 0) {
                    postBanner("تعذر تحميل الصوت من Archive.org — حاول مقطعاً آخر")
                    return@launch
                }
                setActiveBuffer(buf, recordUndo = false, resetDspOnNewEntry = true)
                _uiState.update { it.copy(activeProjectName = item.title.take(40)) }
                repository.indexAudioRecording(item.title.take(40), buf, RecordingSourceType.IMPORTED)
                postBanner("تمت إضافة \"${item.title.take(28)}\" بالصوت النقي إلى الاستوديو")
            } finally {
                _uiState.update { it.copy(isBusy = false, loadingOnlineSoundId = null) }
            }
        }
    }

    // Reels Audiogram (1080x1920) & Cinematic ReShade Video Renderer (60/90/120 FPS)
    fun createReelsAudiogramFromStudio(
        waveColorHex: String,
        bgColorHex: String,
        titleText: String,
        shareAfter: Boolean,
    ) {
        val src = activeBuffer ?: run {
            postBanner(t(_uiState.value.language, "no_audio_loaded"))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, videoRenderProgress = 0.02f) }
            try {
                val app = getApplication<Application>()
                val renderedAudio = withContext(Dispatchers.Default) {
                    renderOfflineDsp(src, _uiState.value.dspConfig)
                }
                val cleanBase = titleText.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Reels" }
                val outMp4 = File(File(app.cacheDir, "exports").apply { mkdirs() }, "${cleanBase}_Reels.mp4")
                val ok = AudioToVideoCreator.createAudiogramVideoFromBuffer(
                    buffer = renderedAudio,
                    outputFile = outMp4,
                    waveColorHex = waveColorHex,
                    bgColorHex = bgColorHex,
                    titleText = titleText.ifBlank { "Sonix ✦ Studio" },
                ) { p ->
                    _uiState.update { it.copy(videoRenderProgress = p) }
                }
                if (!ok || !outMp4.exists()) {
                    postBanner("تعذر إنشاء فيديو الريلز — تحقق من طول المقطع أو مساحة الجهاز")
                    return@launch
                }
                _uiState.update { it.copy(lastRenderedVideoPath = outMp4.absolutePath) }
                emitVideoOutputSideEffect(outMp4, "${cleanBase}_Reels", shareAfter)
                postBanner("تم إنشاء فيديو الريلز 1080x1920 @ 60 FPS بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false, videoRenderProgress = null) }
            }
        }
    }

    fun renderCinematicReshadeVideo(
        sourceUri: Uri,
        params: CinematicRenderParams,
        outputName: String,
        shareAfter: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, videoRenderProgress = 0.02f) }
            try {
                val app = getApplication<Application>()
                val cleanBase = outputName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Sonix_Cinema" }
                val outMp4 = File(File(app.cacheDir, "exports").apply { mkdirs() }, "${cleanBase}_Cinema.mp4")
                val ok = CinematicVideoRenderer.renderCinematicVideo(
                    context = app,
                    sourceUri = sourceUri,
                    outputFile = outMp4,
                    params = params,
                ) { p ->
                    _uiState.update { it.copy(videoRenderProgress = p) }
                }
                if (!ok || !outMp4.exists()) {
                    postBanner("تعذر رندر الفيديو السينمائي — تأكد من اختيار ملف فيديو MP4 صالح")
                    return@launch
                }
                _uiState.update { it.copy(lastRenderedVideoPath = outMp4.absolutePath) }
                emitVideoOutputSideEffect(outMp4, "${cleanBase}_Cinema", shareAfter)
                postBanner("تم رندر الفيديو السينمائي مع الحفاظ على الفريمات الأصلية بنجاح")
            } finally {
                _uiState.update { it.copy(isBusy = false, videoRenderProgress = null) }
            }
        }
    }

    fun exportLastRenderedVideo(shareAfter: Boolean) {
        val path = _uiState.value.lastRenderedVideoPath ?: return
        val file = File(path)
        if (!file.exists()) {
            postBanner("لم يتم العثور على الفيديو المصدّر")
            return
        }
        emitVideoOutputSideEffect(file, file.nameWithoutExtension, shareAfter)
    }

    private fun emitVideoOutputSideEffect(outMp4: File, cleanBase: String, shareAfter: Boolean) {
        val app = getApplication<Application>()
        val res = ExportedAudioResult(outMp4, "mp4", "video/mp4")
        pendingExportResult = res
        if (shareAfter) {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", outMp4)
            _sideEffects.tryEmit(StudioSideEffect.LaunchShareIntent(uri, "video/mp4", outMp4.name))
        } else {
            _sideEffects.tryEmit(StudioSideEffect.PromptSaveDocument(res, "$cleanBase.mp4"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { Firebase.auth.removeAuthStateListener(authStateListener) }
        cloudPresetsJob?.cancel()
        cloudDraftsJob?.cancel()
        sleepTimerJob?.cancel()
        smartMixPreviewJob?.cancel()
        deleteTempPreviewFile()
        voiceProcessor.stop()
        player.dispose()
        recorder.discard()
        stopMixPreviewPlayer()
        stopOnlineSoundPreview()
        releaseTtsPreviewPlayer()
        speechTranscriber.destroy()
        SonixMediaPlaybackService.stopService(getApplication())
    }
}
