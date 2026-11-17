package com.example.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.audio.export.Mp3ExportState
import com.example.model.EffectType
import com.example.ui.components.PersistentTransportBar
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.screens.AiSearchAssistantScreen
import com.example.ui.screens.AiSmartMasterScreen
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.AiTranscribeScreen
import com.example.ui.screens.AudioEditorScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.EchoDetailScreen
import com.example.ui.screens.EchoReverbScreen
import com.example.ui.screens.EffectDetailScreen
import com.example.ui.screens.EffectsChainScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PresetsScreen
import com.example.ui.screens.ProjectsExportScreen
import com.example.ui.screens.RecordingStudioScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TextToSpeechStudioScreen
import com.example.ui.screens.VoicePurifierScreen
import com.example.ui.screens.VoiceTransformerScreen
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.AiToolType
import com.example.viewmodel.AuthSessionState
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.StudioViewModel
import com.example.viewmodel.WorkspaceDestination
import kotlinx.coroutines.launch

@Composable
fun VoiceProApp(
    viewModel: StudioViewModel,
    authViewModel: AuthViewModel = viewModel()
) {
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val localizedContext = remember(uiState.appLanguage, baseContext) {
        LocaleHelper.createLocalizedContext(baseContext, uiState.appLanguage)
    }
    val layoutDirection = if (uiState.appLanguage.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val currentRegistryOwner = LocalActivityResultRegistryOwner.current

    val providers = buildList {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedContext.resources.configuration)
        add(LocalLayoutDirection provides layoutDirection)
        if (currentRegistryOwner != null) {
            add(LocalActivityResultRegistryOwner provides currentRegistryOwner)
        }
    }.toTypedArray()

    CompositionLocalProvider(*providers) {
        val context = LocalContext.current

        if (authState.session is AuthSessionState.SignedOut) {
            AuthScreen(
                authState = authState,
                currentLanguage = uiState.appLanguage,
                onSelectLanguage = { viewModel.setAppLanguage(it) },
                onGoogleSignInStarted = { authViewModel.startGoogleSignInLoading() },
                onGoogleIdToken = { authViewModel.signInWithGoogleIdToken(it) },
                onGoogleCancelled = { authViewModel.onGoogleSignInCancelled() },
                onGoogleError = { authViewModel.onGoogleSignInFailure(it) },
                onEmailSignIn = { email, pwd -> authViewModel.signInWithEmail(email, pwd) },
                onEmailSignUp = { name, email, pwd -> authViewModel.createAccountWithEmail(name, email, pwd) },
                onPasswordReset = { email -> authViewModel.sendPasswordReset(email) },
                onContinueAsGuest = { authViewModel.continueAsGuest() }
            )
            return@CompositionLocalProvider
        }

        val ttsUiState by viewModel.ttsUiState.collectAsStateWithLifecycle()
        val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val recordingLevel by viewModel.recordingLevel.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingWaveform by viewModel.recordingWaveform.collectAsStateWithLifecycle()
    val recordingLiveSamples by viewModel.recordingLiveSamples.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val outputLevel by viewModel.outputLevel.collectAsStateWithLifecycle()
    val gainReductionDb by viewModel.gainReductionDb.collectAsStateWithLifecycle()
    val noiseSuppressionDb by viewModel.noiseSuppressionDb.collectAsStateWithLifecycle()
    val playbackLiveSamples by viewModel.playbackLiveSamples.collectAsStateWithLifecycle()
    val mp3ExportState by viewModel.mp3ExportState.collectAsStateWithLifecycle()
    val savedProjects by viewModel.savedProjects.collectAsStateWithLifecycle()
    val savedRecordings by viewModel.savedRecordings.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val smartMasterAnalysis by viewModel.smartMasterState.collectAsStateWithLifecycle()
    val searchAssistantState by viewModel.searchAssistantState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    LaunchedEffect(mp3ExportState) {
        when (val export = mp3ExportState) {
            is Mp3ExportState.Completed -> {
                if (export.isShare) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/mpeg"
                        putExtra(Intent.EXTRA_STREAM, export.outputUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة ملف MP3"))
                    viewModel.acknowledgeMp3ExportState()
                } else {
                    viewModel.acknowledgeMp3ExportState("تم تصدير ملف MP3 بنجاح")
                }
            }
            is Mp3ExportState.Error -> {
                viewModel.acknowledgeMp3ExportState(export.message)
            }
            else -> Unit
        }
    }

    val destination = uiState.currentDestination
    val isLoadingLocked = uiState.importProgress != null || uiState.isBusy

    BackHandler(enabled = uiState.navStack.size <= 1 && !isLoadingLocked) {
        showExitConfirmDialog = true
    }
    BackHandler(enabled = uiState.navStack.size > 1 && !isLoadingLocked) {
        viewModel.navigateBack()
    }
    BackHandler(enabled = isLoadingLocked) {
        // Locked during audio import / processing
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        StudioObsidian,
                        StudioMidnight,
                        VioletGlow.copy(alpha = 0.06f),
                        CyanGlow.copy(alpha = 0.05f)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (!uiState.hasAudio && destination == WorkspaceDestination.Home) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier
                    }
                )
            },
            topBar = {
                if (destination !is WorkspaceDestination.Home) {
                    val (title, subtitle) = when (destination) {
                        WorkspaceDestination.RecordingStudio ->
                            stringResource(R.string.section_record_title) to stringResource(R.string.section_record_subtitle)
                        WorkspaceDestination.VoicePurifier ->
                            stringResource(R.string.section_purifier_title) to stringResource(R.string.section_purifier_subtitle)
                        WorkspaceDestination.EchoReverbSpace ->
                            stringResource(R.string.section_echo_title) to stringResource(R.string.section_echo_subtitle)
                        WorkspaceDestination.EffectsChain ->
                            stringResource(R.string.section_chain_title) to stringResource(R.string.section_chain_subtitle)
                        WorkspaceDestination.AudioEditor ->
                            stringResource(R.string.section_editor_title) to stringResource(R.string.section_editor_subtitle)
                        WorkspaceDestination.Presets ->
                            stringResource(R.string.section_presets_title) to stringResource(R.string.section_presets_subtitle)
                        WorkspaceDestination.ProjectsExport ->
                            stringResource(R.string.section_projects_title) to stringResource(R.string.section_projects_subtitle)
                        WorkspaceDestination.AiStudio ->
                            stringResource(R.string.section_ai_title) to stringResource(R.string.section_ai_subtitle)
                        WorkspaceDestination.TextToSpeechStudio ->
                            stringResource(R.string.section_tts_title) to stringResource(R.string.section_tts_subtitle)
                        WorkspaceDestination.VoiceTransformer ->
                            stringResource(R.string.section_voice_transformer_title) to stringResource(R.string.section_voice_transformer_subtitle)
                        WorkspaceDestination.Settings ->
                            stringResource(R.string.section_settings_title) to stringResource(R.string.section_settings_subtitle)
                        is WorkspaceDestination.EchoDetail ->
                            destination.group.arabicTitle to destination.group.subtitle
                        is WorkspaceDestination.EffectDetail ->
                            stringResource(destination.effectType.titleRes) to destination.effectType.category.arabicLabel
                        is WorkspaceDestination.AiDetail ->
                            destination.tool.arabicTitle to destination.tool.subtitle
                        WorkspaceDestination.Home -> "" to null
                    }
                    StudioFocusTopBar(
                        title = title,
                        subtitle = subtitle,
                        onBack = { if (!isLoadingLocked) viewModel.navigateBack() },
                        showBackButton = !isLoadingLocked
                    )
                }
            },
            bottomBar = {
                if (uiState.hasAudio || destination != WorkspaceDestination.Home) {
                    PersistentTransportBar(
                        hasAudio = uiState.hasAudio,
                        isPlaying = isPlaying,
                        progress = playbackProgress,
                        durationMs = uiState.durationMs,
                        outputLevel = outputLevel,
                        bypassAll = uiState.effectiveBypassAll,
                        onTogglePlay = { viewModel.togglePlayback() },
                        onStop = { viewModel.stopPlayback() },
                        onToggleBypass = { viewModel.toggleBypassAll() },
                        onSeek = { viewModel.seekPlayback(it) },
                        onHoldBypassChange = { viewModel.setHoldBypass(it) },
                        isAutoLoopEnabled = uiState.isAutoLoopEnabled,
                        onToggleAutoLoop = { viewModel.toggleAutoLoop() }
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) { innerPadding ->
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    val forward = targetState !is WorkspaceDestination.Home
                    (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { if (forward) -it / 6 else it / 6 })
                        .togetherWith(
                            fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { if (forward) it / 8 else -it / 8 }
                        )
                },
                label = "focus_workspace_transition",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { dest ->
                when (dest) {
                    WorkspaceDestination.Home -> HomeScreen(
                        uiState = uiState,
                        authSession = authState.session,
                        currentLanguage = uiState.appLanguage,
                        onSelectLanguage = { viewModel.setAppLanguage(it) },
                        isPlaying = isPlaying,
                        outputLevel = outputLevel,
                        playbackProgress = playbackProgress,
                        playbackLiveSamples = playbackLiveSamples,
                        onOpenSection = { viewModel.navigateToSection(it) },
                        onSignOutOrSwitchAccount = {
                            if (authState.session is AuthSessionState.SignedIn) {
                                coroutineScope.launch {
                                    runCatching {
                                        CredentialManager.create(context)
                                            .clearCredentialState(ClearCredentialStateRequest())
                                    }
                                    authViewModel.signOut()
                                }
                            } else {
                                authViewModel.requestSignIn()
                            }
                        }
                    )

                    WorkspaceDestination.RecordingStudio -> RecordingStudioScreen(
                        uiState = uiState,
                        recordingState = recordingState,
                        recordingLevel = recordingLevel,
                        recordingDurationMs = recordingDurationMs,
                        recordingWaveform = recordingWaveform,
                        recordingLiveSamples = recordingLiveSamples,
                        isPlaying = isPlaying,
                        outputLevel = outputLevel,
                        playbackProgress = playbackProgress,
                        playbackLiveSamples = playbackLiveSamples,
                        onStartOrResumeRecording = { viewModel.startOrResumeRecording() },
                        onPauseRecording = { viewModel.pauseRecording() },
                        onFinishRecording = { viewModel.finishRecording() },
                        onDiscardRecording = { viewModel.discardRecording() },
                        onImportAudioUri = { viewModel.importAudioUri(it) },
                        savedRecordings = savedRecordings,
                        onLoadRecording = { viewModel.loadIndexedRecording(it) }
                    )

                    WorkspaceDestination.VoicePurifier -> VoicePurifierScreen(
                        uiState = uiState,
                        noiseSuppressionDb = noiseSuppressionDb,
                        onTogglePurifier = { viewModel.toggleEffectEnabled(EffectType.NOISE_REDUCTION) },
                        onApplyPreset = { viewModel.applyVoicePurifyPreset(it) },
                        onUpdateParams = { viewModel.updateNoiseReduction(it) },
                        onRunDeepCleanOffline = { viewModel.runDeepVoicePurificationOffline() },
                        onUndoEdit = { viewModel.undoLastEdit() }
                    )

                    WorkspaceDestination.EchoReverbSpace -> EchoReverbScreen(
                        uiState = uiState,
                        outputLevel = outputLevel,
                        onToggleProMode = { viewModel.toggleEchoMode(it) },
                        onSelectAxisMode = { viewModel.setEchoPadAxisMode(it) },
                        onSpatialTouch = { x, y -> viewModel.applyEchoSpatialTouch(x, y) },
                        onUpdateEchoReverb = { viewModel.updateEchoReverb(it) },
                        onOpenDetailGroup = { group ->
                            viewModel.navigateToDetail(WorkspaceDestination.EchoDetail(group))
                        }
                    )

                    is WorkspaceDestination.EchoDetail -> EchoDetailScreen(
                        group = dest.group,
                        params = uiState.dspConfig.echoReverb,
                        onUpdate = { viewModel.updateEchoReverb(it) }
                    )

                    WorkspaceDestination.EffectsChain -> EffectsChainScreen(
                        dspConfig = uiState.dspConfig,
                        onToggleEffect = { viewModel.toggleEffectEnabled(it) },
                        onMoveEffect = { type, up -> viewModel.moveEffectInChain(type, up) },
                        onOpenEffectDetail = { type ->
                            viewModel.navigateToDetail(WorkspaceDestination.EffectDetail(type))
                        },
                        onUpdateMasterGain = { db ->
                            viewModel.updateDspConfig { it.copy(masterGainDb = db) }
                        }
                    )

                    is WorkspaceDestination.EffectDetail -> EffectDetailScreen(
                        effectType = dest.effectType,
                        dspConfig = uiState.dspConfig,
                        gainReductionDb = gainReductionDb,
                        onToggleEffect = { viewModel.toggleEffectEnabled(it) },
                        onUpdateConfig = { viewModel.updateDspConfig(it) }
                    )

                    WorkspaceDestination.AudioEditor -> AudioEditorScreen(
                        uiState = uiState,
                        ttsState = ttsUiState,
                        playbackProgress = playbackProgress,
                        savedProjects = savedProjects,
                        onGoToRecording = { viewModel.navigateToSection(WorkspaceDestination.RecordingStudio) },
                        onRangeChange = { s, e -> viewModel.updateTrimRange(s, e) },
                        onResetSelection = { viewModel.resetTrimSelection() },
                        onToggleLoop = { viewModel.toggleLoopSelection() },
                        onTrimKeep = { viewModel.trimKeepSelection() },
                        onCutSelection = { viewModel.cutSelectedRegion() },
                        onNormalize = { viewModel.normalizeAudio() },
                        onBakeClipGain = { db -> viewModel.bakeClipGain(db) },
                        onApplyFadeIn = { sec -> viewModel.applyFadeInEffect(sec) },
                        onApplyFadeOut = { sec -> viewModel.applyFadeOutEffect(sec) },
                        onImportMixBackgroundUri = { uri -> viewModel.importMixBackgroundUri(uri) },
                        onClearMixBackgroundTrack = { viewModel.clearMixBackgroundTrack() },
                        onApplyDualTrackMix = { mainGain, bgGain ->
                            viewModel.applyDualTrackMix(mainGain, bgGain)
                        },
                        onMergeExternalUri = { viewModel.mergeExternalAudio(it) },
                        onMergeSavedProject = { viewModel.mergeSavedProject(it) },
                        onUndo = { viewModel.undoLastEdit() },
                        onRedo = { viewModel.redoLastEdit() },
                        onSaveEdits = { viewModel.saveCurrentProject() },
                        onExportWithFormat = { uri, fmt, name ->
                            viewModel.exportAudioWithFormat(uri, fmt, name)
                        },
                        onPreviewTtsSpeech = { text, dialect, rate, pitch ->
                            viewModel.previewTtsSpeech(text, dialect, rate, pitch)
                        },
                        onStopTtsPreview = { viewModel.stopTtsPreview() },
                        onGenerateTtsToStudio = { text, dialect, rate, pitch, append ->
                            viewModel.generateTtsToStudio(text, dialect, rate, pitch, append)
                        },
                        onApplyVoiceTransform = { pitchSt, formantShift, warmthDb, presenceDb, mix, label ->
                            viewModel.transformVoiceOffline(
                                pitchSemitones = pitchSt,
                                formantShift = formantShift,
                                warmthDb = warmthDb,
                                presenceDb = presenceDb,
                                mix = mix,
                                characterLabel = label
                            )
                        }
                    )

                    WorkspaceDestination.Presets -> PresetsScreen(
                        presets = presets,
                        activePresetId = uiState.activePresetId,
                        onApplyPreset = { viewModel.applyPreset(it) },
                        onSaveCustomPreset = { n, d -> viewModel.saveCurrentAsPreset(n, d) },
                        onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                    )

                    WorkspaceDestination.ProjectsExport -> ProjectsExportScreen(
                        uiState = uiState,
                        mp3ExportState = mp3ExportState,
                        savedProjects = savedProjects,
                        onRenameProject = { viewModel.renameCurrentProject(it) },
                        onSaveProject = { viewModel.saveCurrentProject() },
                        onNewProject = { viewModel.createNewProject() },
                        onLoadProject = { viewModel.loadProject(it) },
                        onDeleteProject = { viewModel.deleteProject(it) },
                        onExportWavUri = { viewModel.exportProcessedWav(it) },
                        onShareWav = { cb -> viewModel.prepareShareWav(cb) },
                        onExportMp3Uri = { viewModel.exportProcessedMp3(it) },
                        onShareMp3 = { viewModel.prepareShareMp3() },
                        onExportWithFormat = { uri, fmt, name ->
                            viewModel.exportAudioWithFormat(uri, fmt, name)
                        },
                        savedRecordings = savedRecordings,
                        onLoadRecording = { viewModel.loadIndexedRecording(it) },
                        onDeleteRecording = { viewModel.deleteIndexedRecording(it) },
                        onToggleNormalizeBeforeExport = { viewModel.toggleNormalizeBeforeExport() }
                    )

                    WorkspaceDestination.AiStudio -> AiStudioScreen(
                        onOpenTool = { tool ->
                            viewModel.navigateToDetail(WorkspaceDestination.AiDetail(tool))
                        }
                    )

                    is WorkspaceDestination.AiDetail -> when (dest.tool) {
                        AiToolType.TRANSCRIBE -> AiTranscribeScreen(
                            uiState = uiState,
                            recordingState = recordingState,
                            recordingLevel = recordingLevel,
                            recordingDurationMs = recordingDurationMs,
                            recordingWaveform = recordingWaveform,
                            transcriptionState = transcriptionState,
                            onStartMic = { viewModel.startOrResumeRecording() },
                            onStopAndTranscribeMic = { lang -> viewModel.stopMicAndTranscribe(lang) },
                            onTranscribeStudioAudio = { lang -> viewModel.transcribeActiveAudio(lang) }
                        )

                        AiToolType.SMART_MASTER -> AiSmartMasterScreen(
                            uiState = uiState,
                            analysis = smartMasterAnalysis,
                            onGoToRecording = { viewModel.navigateToSection(WorkspaceDestination.RecordingStudio) },
                            onRunSmartMaster = { viewModel.runSmartAutoMaster() }
                        )

                        AiToolType.SEARCH_ASSISTANT -> AiSearchAssistantScreen(
                            searchState = searchAssistantState,
                            onAskSearch = { query -> viewModel.askSearchAssistant(query) }
                        )
                    }

                    WorkspaceDestination.TextToSpeechStudio -> TextToSpeechStudioScreen(
                        uiState = uiState,
                        ttsState = ttsUiState,
                        onPreviewSpeech = { text, dialect, rate, pitch ->
                            viewModel.previewTtsSpeech(text, dialect, rate, pitch)
                        },
                        onStopPreview = { viewModel.stopTtsPreview() },
                        onGenerateToStudio = { text, dialect, rate, pitch, append ->
                            viewModel.generateTtsToStudio(text, dialect, rate, pitch, append)
                        }
                    )

                    WorkspaceDestination.VoiceTransformer -> VoiceTransformerScreen(
                        uiState = uiState,
                        onGoToRecording = { viewModel.navigateToSection(WorkspaceDestination.RecordingStudio) },
                        onApplyTransform = { pitchSt, formantShift, warmthDb, presenceDb, mix, label ->
                            viewModel.transformVoiceOffline(
                                pitchSemitones = pitchSt,
                                formantShift = formantShift,
                                warmthDb = warmthDb,
                                presenceDb = presenceDb,
                                mix = mix,
                                characterLabel = label
                            )
                        },
                        onUndo = { viewModel.undoLastEdit() }
                    )

                    WorkspaceDestination.Settings -> SettingsScreen(
                        uiState = uiState,
                        onSelectLanguage = { viewModel.setAppLanguage(it) },
                        onToggleAutoLoop = { viewModel.toggleAutoLoop() },
                        onToggleNormalizeBeforeExport = { viewModel.toggleNormalizeBeforeExport() }
                    )
                }
            }
        }

        val importProgress = uiState.importProgress
        if (importProgress != null) {
            val clampedProgress = importProgress.coerceIn(0f, 1f)
            val smoothImportProgress by animateFloatAsState(
                targetValue = clampedProgress,
                animationSpec = tween(durationMillis = 250, easing = LinearEasing),
                label = "smooth_import_progress"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StudioObsidian.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.55f)),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 420.dp)
                        .testTag("import_progress_overlay")
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.importStatusText.ifBlank { "جاري استيراد الملف الصوتي..." },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "${(smoothImportProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyanGlow
                            )
                        }
                        LinearProgressIndicator(
                            progress = { smoothImportProgress },
                            color = CyanGlow,
                            trackColor = StudioSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        }

        if (showExitConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showExitConfirmDialog = false },
                containerColor = StudioSurface,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                title = { Text(stringResource(R.string.confirm_exit_title)) },
                text = {
                    Text(
                        if (uiState.hasUnsavedChanges) {
                            "لديك تعديلات غير محفوظة في المشروع الحالي. هل تريد الخروج أو حفظ التعديلات أولاً؟"
                        } else {
                            "هل تريد إغلاق تطبيق Voice Pro؟"
                        }
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uiState.hasUnsavedChanges) {
                            TextButton(
                                onClick = {
                                    viewModel.saveCurrentProject {
                                        showExitConfirmDialog = false
                                        context.findActivity()?.finish()
                                    }
                                }
                            ) {
                                Text("حفظ وخروج", color = EmeraldActive)
                            }
                        }
                        Button(
                            onClick = {
                                showExitConfirmDialog = false
                                context.findActivity()?.finish()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanGlow,
                                contentColor = StudioObsidian
                            ),
                            modifier = Modifier.testTag("exit_confirm_button")
                        ) {
                            Text(stringResource(R.string.confirm_exit_ok))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExitConfirmDialog = false },
                        modifier = Modifier.testTag("exit_cancel_button")
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
    }
}
