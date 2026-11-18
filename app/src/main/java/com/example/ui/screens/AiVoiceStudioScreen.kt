package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiDialogueTurn
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.model.AppLanguage
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Composable
fun AiVoiceStudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.aiVoiceUiState.collectAsStateWithLifecycle()
    val lang = uiState.language
    val isEnglish = lang == AppLanguage.EN

    BackHandler(onBack = onBack)

    val pickReferenceAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importAiVoiceReferenceFromUri(uri)
        }
    }

    var saveDialogOpen by rememberSaveable { mutableStateOf(false) }
    var newNameAr by rememberSaveable { mutableStateOf("") }
    var newNameEn by rememberSaveable { mutableStateOf("") }
    var synthesizedSignature by rememberSaveable { mutableStateOf<String?>(null) }

    val currentInputSignature = remember(state) {
        buildString {
            append(state.activeTab).append('|')
            append(state.selectedEngine.id).append('|')
            append(state.selectedLanguage.code).append('|')
            append(state.selectedDialect.id).append('|')
            append(state.selectedCharacterId).append('|')
            append(state.selectedEmotion.id).append('|')
            append(state.selectedPace.id).append('|')
            append(state.pitchOffsetSemitones).append('|')
            append(state.emotionIntensity).append('|')
            append(state.voiceDescriptionPrompt).append('|')
            append(state.scriptText).append('|')
            append(state.referenceAudioPath.orEmpty())
        }
    }

    AiVoiceStudioContentLayout(
        state = state,
        language = lang,
        hasStudioAudio = uiState.hasAudio,
        onBack = onBack,
        onSelectTab = viewModel::setAiVoiceTab,
        onDismissError = viewModel::dismissAiVoiceError,
        onUseLocalFallback = {
            if (state.activeTab == 1) {
                viewModel.synthesizeAiVoiceDialogue(AiVoiceEngineKind.SONIX_LOCAL_STUDIO)
            } else {
                viewModel.synthesizeAiVoiceSingle(AiVoiceEngineKind.SONIX_LOCAL_STUDIO)
            }
        },
        onPlayPause = viewModel::playOrPauseAiVoiceOutput,
        onReplay = viewModel::replayAiVoiceOutput,
        onStopPlayback = viewModel::stopAiVoiceOutput,
        onSeekMs = viewModel::seekAiVoiceOutput,
        onSaveWav = viewModel::saveAiVoiceOutputToDevice,
        onShare = viewModel::shareAiVoiceOutput,
        onInsertIntoStudio = { viewModel.insertAiVoiceOutputIntoMainStudio(false) },
        onScriptChange = viewModel::setAiVoiceScriptText,
        onSelectEngine = viewModel::setAiVoiceEngine,
        onSelectLanguage = viewModel::setAiVoiceLanguage,
        onSelectDialect = viewModel::setAiVoiceDialect,
        onSelectCharacter = viewModel::selectAiVoiceCharacter,
        onToggleFavorite = viewModel::toggleAiVoiceCharacterFavorite,
        onSelectEmotion = viewModel::setAiVoiceEmotion,
        onSelectPace = viewModel::setAiVoicePace,
        onPitchOffsetChange = viewModel::setAiVoicePitchOffset,
        onIntensityChange = viewModel::setAiVoiceEmotionIntensity,
        onDescriptionChange = viewModel::setAiVoiceDescriptionPrompt,
        onPickReferenceFile = { pickReferenceAudioLauncher.launch(arrayOf("audio/*")) },
        onUseStudioBufferAsReference = viewModel::importAiVoiceReferenceFromCurrentBuffer,
        onClearReference = viewModel::clearAiVoiceReference,
        onReferenceTranscriptChange = viewModel::setAiVoiceReferenceTranscript,
        onRightsConfirmedChange = viewModel::setAiVoiceReferenceRightsConfirmed,
        onOpenSaveCharacterDialog = {
            newNameAr = ""
            newNameEn = ""
            saveDialogOpen = true
        },
        onAddDialogueTurn = { viewModel.addDialogueTurn() },
        onRemoveDialogueTurn = viewModel::removeDialogueTurn,
        onMoveDialogueTurn = viewModel::moveDialogueTurn,
        onUpdateDialogueTurn = viewModel::updateDialogueTurn,
        onSynthesizeDialogue = viewModel::synthesizeAiVoiceDialogue,
        onApplyCharacterToStudio = { ch ->
            viewModel.selectAiVoiceCharacter(ch)
            viewModel.setAiVoiceTab(0)
        },
        onAddCharacterToDialogue = { ch ->
            viewModel.addDialogueTurn(ch.id)
            viewModel.setAiVoiceTab(1)
        },
        onSaveCustomCharacter = viewModel::saveCustomAiVoiceCharacter,
        onDeleteSavedCharacter = viewModel::deleteSavedAiVoiceCharacter,
        onSaveBackendUrl = viewModel::saveAiVoiceBackendUrl,
        onSaveBackendToken = viewModel::saveAiVoiceBackendBearer,
        onCheckBackendHealth = { url, token -> viewModel.checkAiVoiceBackendHealth(url, token) },
        onPreviewClick = {
            val outputExists = !state.lastOutputWavPath.isNullOrBlank()
            if (outputExists && synthesizedSignature == currentInputSignature) {
                viewModel.playOrPauseAiVoiceOutput()
            } else {
                synthesizedSignature = currentInputSignature
                if (state.activeTab == 1) {
                    viewModel.synthesizeAiVoiceDialogue()
                } else {
                    viewModel.synthesizeAiVoiceSingle()
                }
            }
        },
        onCancelSynthesis = viewModel::cancelAiVoiceSynthesis,
        onApproveClick = {
            if (!state.lastOutputWavPath.isNullOrBlank()) {
                viewModel.insertAiVoiceOutputIntoMainStudio(false)
            } else {
                synthesizedSignature = currentInputSignature
                if (state.activeTab == 1) {
                    viewModel.synthesizeAiVoiceDialogue()
                } else {
                    viewModel.synthesizeAiVoiceSingle()
                }
                viewModel.showStatusBanner(
                    if (isEnglish) {
                        "Generating voice first — tap Approve once ready to insert into studio."
                    } else {
                        "جاري توليد الصوت للمعاينة أولاً — اضغط اعتماد بعد الجاهزية لإدراجه بالاستوديو."
                    },
                )
            }
        },
        bottomHelpSlot = {
            SupportUpdatesQaSection(
                uiState = uiState,
                viewModel = viewModel,
                onOpenFullLab = null,
                collapsible = true,
                initiallyExpanded = false,
            )
        },
    )

    if (saveDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text(if (isEnglish) "Save Custom Character" else "إنشاء وحفظ شخصية صوتية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newNameAr,
                        onValueChange = { newNameAr = it },
                        label = { Text(if (isEnglish) "Character Name" else "اسم الشخصية") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newNameEn,
                        onValueChange = { newNameEn = it },
                        label = { Text(if (isEnglish) "English Name (Optional)" else "الاسم الإنجليزي (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCustomAiVoiceCharacter(newNameAr, newNameEn)
                        saveDialogOpen = false
                    },
                ) {
                    Text(if (isEnglish) "Save" else "حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) {
                    Text(if (isEnglish) "Cancel" else "إلغاء")
                }
            },
        )
    }
}

@Composable
fun AiVoiceStudioContentLayout(
    state: AiVoiceStudioUiState,
    language: AppLanguage,
    hasStudioAudio: Boolean,
    onBack: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onDismissError: () -> Unit,
    onUseLocalFallback: () -> Unit,
    onPlayPause: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeekMs: (Long) -> Unit,
    onSaveWav: () -> Unit,
    onShare: () -> Unit,
    onInsertIntoStudio: () -> Unit,
    onScriptChange: (String) -> Unit,
    onSelectEngine: (AiVoiceEngineKind) -> Unit,
    onSelectLanguage: (AiVoiceLanguage) -> Unit,
    onSelectDialect: (AiArabicDialect) -> Unit,
    onSelectCharacter: (AiVoiceCharacter) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectEmotion: (AiVoiceEmotion) -> Unit,
    onSelectPace: (AiDeliveryPace) -> Unit,
    onPitchOffsetChange: (Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPickReferenceFile: () -> Unit,
    onUseStudioBufferAsReference: () -> Unit,
    onClearReference: () -> Unit,
    onReferenceTranscriptChange: (String) -> Unit,
    onRightsConfirmedChange: (Boolean) -> Unit,
    onOpenSaveCharacterDialog: () -> Unit,
    onAddDialogueTurn: () -> Unit,
    onRemoveDialogueTurn: (String) -> Unit,
    onMoveDialogueTurn: (String, Int) -> Unit,
    onUpdateDialogueTurn: (String, (AiDialogueTurn) -> AiDialogueTurn) -> Unit,
    onSynthesizeDialogue: (AiVoiceEngineKind?) -> Unit,
    onApplyCharacterToStudio: (AiVoiceCharacter) -> Unit,
    onAddCharacterToDialogue: (AiVoiceCharacter) -> Unit,
    onSaveCustomCharacter: (String, String) -> Unit,
    onDeleteSavedCharacter: (String) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onSaveBackendToken: (String) -> Unit,
    onCheckBackendHealth: (String, String) -> Unit,
    onPreviewClick: () -> Unit,
    onCancelSynthesis: () -> Unit,
    onApproveClick: () -> Unit,
    bottomHelpSlot: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val isEnglish = language == AppLanguage.EN

    Scaffold(
        containerColor = StudioColors.BgTop,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (state.activeTab in 0..1) {
                AiVoiceStickyBottomActionBar(
                    state = state,
                    hasStudioAudio = hasStudioAudio,
                    isEnglish = isEnglish,
                    onPreviewClick = onPreviewClick,
                    onCancelSynthesis = onCancelSynthesis,
                    onApproveClick = onApproveClick,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 700.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StudioFocusTopBar(
                    title = if (isEnglish) "AI VOICE STUDIO" else "AI VOICE STUDIO – استوديو الصوت",
                    subtitle = "VoxCPM2 • Audar-Flash • Edge Neural",
                    language = language,
                    onBack = onBack,
                )

                val tabs = if (isEnglish) {
                    listOf("Studio", "Dialogue", "Voices", "Server")
                } else {
                    listOf("استوديو الصوت", "حوار متعدد", "الشخصيات", "السيرفر")
                }

                TabRow(
                    selectedTabIndex = state.activeTab,
                    containerColor = StudioColors.CardSurface,
                    contentColor = StudioColors.Cyan,
                    indicator = { tabPositions ->
                        if (state.activeTab in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[state.activeTab]),
                                color = StudioColors.Cyan,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("ai_voice_studio_tabs"),
                ) {
                    tabs.forEachIndexed { idx, label ->
                        Tab(
                            selected = state.activeTab == idx,
                            onClick = { onSelectTab(idx) },
                            text = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (state.activeTab == idx) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                            modifier = Modifier.testTag("ai_voice_tab_$idx"),
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("ai_voice_scroll_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (!state.errorTitle.isNullOrBlank()) {
                        item(key = "status_banner") {
                            AiVoiceConfigurationBanner(
                                state = state,
                                isEnglish = isEnglish,
                                onDismiss = onDismissError,
                                onOpenServerTab = { onSelectTab(3) },
                                onUseLocalFallback = onUseLocalFallback,
                            )
                        }
                    }

                    if (!state.lastOutputWavPath.isNullOrBlank() && state.activeTab in 0..1) {
                        item(key = "output_player") {
                            AiVoiceOutputPlayerCard(
                                state = state,
                                isEnglish = isEnglish,
                                onPlayPause = onPlayPause,
                                onReplay = onReplay,
                                onStop = onStopPlayback,
                                onSeekMs = onSeekMs,
                                onSaveWav = onSaveWav,
                                onShare = onShare,
                                onInsertIntoStudio = onInsertIntoStudio,
                            )
                        }
                    }

                    when (state.activeTab) {
                        0 -> {
                            item(key = "group_1_text") {
                                AiVoiceGroup1TextCard(
                                    scriptText = state.scriptText,
                                    isEnglish = isEnglish,
                                    onScriptChange = onScriptChange,
                                )
                            }
                            item(key = "group_2_voice") {
                                AiVoiceGroup2VoiceSelectionCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSelectEngine = onSelectEngine,
                                    onSelectLanguage = onSelectLanguage,
                                    onSelectDialect = onSelectDialect,
                                    onSelectCharacter = onSelectCharacter,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                            item(key = "group_3_emotion") {
                                AiVoiceGroup3EmotionCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSelectEmotion = onSelectEmotion,
                                )
                            }
                            item(key = "group_4_controls") {
                                AiVoiceGroup4ControlsCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSelectPace = onSelectPace,
                                    onPitchOffsetChange = onPitchOffsetChange,
                                    onIntensityChange = onIntensityChange,
                                    onDescriptionChange = onDescriptionChange,
                                )
                            }
                            item(key = "group_5_advanced") {
                                AiVoiceGroup5AdvancedCard(
                                    state = state,
                                    hasStudioAudio = hasStudioAudio,
                                    isEnglish = isEnglish,
                                    onPickReferenceFile = onPickReferenceFile,
                                    onUseStudioBufferAsReference = onUseStudioBufferAsReference,
                                    onClearReference = onClearReference,
                                    onReferenceTranscriptChange = onReferenceTranscriptChange,
                                    onRightsConfirmedChange = onRightsConfirmedChange,
                                    onOpenSaveCharacterDialog = onOpenSaveCharacterDialog,
                                    onOpenDialogueTab = { onSelectTab(1) },
                                    onOpenServerTab = { onSelectTab(3) },
                                    onSaveOutputWav = onSaveWav,
                                    onShareOutput = onShare,
                                )
                            }
                            item(key = "group_6_more_and_help") {
                                bottomHelpSlot()
                            }
                        }
                        1 -> item(key = "dialogue_studio") {
                            AiVoiceDialogueTab(
                                state = state,
                                isEnglish = isEnglish,
                                onAddTurn = onAddDialogueTurn,
                                onRemoveTurn = onRemoveDialogueTurn,
                                onMoveTurn = onMoveDialogueTurn,
                                onUpdateTurn = onUpdateDialogueTurn,
                                onSynthesizeDialogue = onSynthesizeDialogue,
                                onCancelSynthesis = onCancelSynthesis,
                            )
                        }
                        2 -> item(key = "characters_library") {
                            AiVoiceCharactersTab(
                                state = state,
                                isEnglish = isEnglish,
                                onApplyCharacterToStudio = onApplyCharacterToStudio,
                                onAddCharacterToDialogue = onAddCharacterToDialogue,
                                onSaveCurrentAsCharacter = onSaveCustomCharacter,
                                onDeleteSavedCharacter = onDeleteSavedCharacter,
                            )
                        }
                        else -> item(key = "server_setup") {
                            AiVoiceServerSetupTab(
                                state = state,
                                isEnglish = isEnglish,
                                onSaveBackendUrl = onSaveBackendUrl,
                                onSaveBackendToken = onSaveBackendToken,
                                onCheckHealth = onCheckBackendHealth,
                            )
                        }
                    }
                }
            }
        }
    }
}
