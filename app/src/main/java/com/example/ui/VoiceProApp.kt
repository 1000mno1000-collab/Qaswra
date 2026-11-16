package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.PersistentTransportBar
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.screens.AiMusicGenScreen
import com.example.ui.screens.AiSearchAssistantScreen
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.AiTranscribeScreen
import com.example.ui.screens.AudioEditorScreen
import com.example.ui.screens.EchoDetailScreen
import com.example.ui.screens.EchoReverbScreen
import com.example.ui.screens.EffectDetailScreen
import com.example.ui.screens.EffectsChainScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PresetsScreen
import com.example.ui.screens.ProjectsExportScreen
import com.example.ui.screens.RecordingStudioScreen
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.AiToolType
import com.example.viewmodel.StudioViewModel
import com.example.viewmodel.WorkspaceDestination

@Composable
fun VoiceProApp(viewModel: StudioViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val recordingLevel by viewModel.recordingLevel.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingWaveform by viewModel.recordingWaveform.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val outputLevel by viewModel.outputLevel.collectAsStateWithLifecycle()
    val gainReductionDb by viewModel.gainReductionDb.collectAsStateWithLifecycle()
    val savedProjects by viewModel.savedProjects.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val musicGenState by viewModel.musicGenState.collectAsStateWithLifecycle()
    val searchAssistantState by viewModel.searchAssistantState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    val destination = uiState.currentDestination
    BackHandler(enabled = uiState.navStack.size > 1) {
        viewModel.navigateBack()
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
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (destination !is WorkspaceDestination.Home) {
                    val (title, subtitle) = when (destination) {
                        WorkspaceDestination.RecordingStudio ->
                            stringResource(R.string.section_record_title) to stringResource(R.string.section_record_subtitle)
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
                        onBack = { viewModel.navigateBack() }
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
                        bypassAll = uiState.dspConfig.bypassAll,
                        onTogglePlay = { viewModel.togglePlayback() },
                        onStop = { viewModel.stopPlayback() },
                        onToggleBypass = { viewModel.toggleBypassAll() },
                        onSeek = { viewModel.seekPlayback(it) }
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
                        isPlaying = isPlaying,
                        outputLevel = outputLevel,
                        playbackProgress = playbackProgress,
                        onOpenSection = { viewModel.navigateToSection(it) }
                    )

                    WorkspaceDestination.RecordingStudio -> RecordingStudioScreen(
                        uiState = uiState,
                        recordingState = recordingState,
                        recordingLevel = recordingLevel,
                        recordingDurationMs = recordingDurationMs,
                        recordingWaveform = recordingWaveform,
                        isPlaying = isPlaying,
                        outputLevel = outputLevel,
                        playbackProgress = playbackProgress,
                        onStartOrResumeRecording = { viewModel.startOrResumeRecording() },
                        onPauseRecording = { viewModel.pauseRecording() },
                        onFinishRecording = { viewModel.finishRecording() },
                        onDiscardRecording = { viewModel.discardRecording() },
                        onImportAudioUri = { viewModel.importAudioUri(it) }
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
                        onMergeExternalUri = { viewModel.mergeExternalAudio(it) },
                        onMergeSavedProject = { viewModel.mergeSavedProject(it) },
                        onUndo = { viewModel.undoLastEdit() }
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
                        savedProjects = savedProjects,
                        onRenameProject = { viewModel.renameCurrentProject(it) },
                        onSaveProject = { viewModel.saveCurrentProject() },
                        onNewProject = { viewModel.createNewProject() },
                        onLoadProject = { viewModel.loadProject(it) },
                        onDeleteProject = { viewModel.deleteProject(it) },
                        onExportWavUri = { viewModel.exportProcessedWav(it) },
                        onShareWav = { cb -> viewModel.prepareShareWav(cb) }
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

                        AiToolType.MUSIC_GEN -> AiMusicGenScreen(
                            uiState = uiState,
                            musicGenState = musicGenState,
                            onGenerateMusic = { prompt, len -> viewModel.generateMusicTrack(prompt, len) },
                            onLoadIntoStudio = { replace -> viewModel.loadGeneratedMusicIntoStudio(replace) }
                        )

                        AiToolType.SEARCH_ASSISTANT -> AiSearchAssistantScreen(
                            searchState = searchAssistantState,
                            onAskSearch = { query -> viewModel.askSearchAssistant(query) }
                        )
                    }
                }
            }
        }
    }
}
