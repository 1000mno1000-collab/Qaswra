package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.audio.aivoice.AiDialogueTurn
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.i18n.t
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

    var synthesizedSignature by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedSubTab by rememberSaveable { mutableIntStateOf(0) } // 0=Dialogue, 1=Clone & Save
    val isDialogueMode = state.activeTab == 1 && advancedSubTab == 0

    val currentInputSignature = remember(state) {
        buildString {
            append(state.activeTab).append('|')
            append(state.selectedEngine.id).append('|')
            append(state.selectedLanguage.code).append('|')
            append(state.selectedCharacterId).append('|')
            append(state.fixedSid).append('|')
            append(state.fixedSeed).append('|')
            append(state.selectedEmotion.id).append('|')
            append(state.speechRate).append('|')
            append(state.emotionIntensity).append('|')
            append(state.scriptText).append('|')
            append(state.referenceAudioPath.orEmpty()).append('|')
            append(state.referenceTranscript).append('|')
            append(state.referenceRightsConfirmed).append('|')
            append(
                state.dialogueTurns.joinToString(";") { turn ->
                    "${turn.id}:${turn.characterId}:${turn.text}:${turn.emotion.id}:${turn.emotionIntensity}:${turn.pace.id}:${turn.pauseAfterMs}"
                },
            )
        }
    }

    AiVoiceStudioContentLayout(
        state = state,
        language = lang,
        hasStudioAudio = uiState.hasAudio,
        advancedSubTab = advancedSubTab,
        onSelectAdvancedSubTab = { advancedSubTab = it.coerceIn(0, 2) },
        onBack = onBack,
        onSelectTab = viewModel::setAiVoiceTab,
        onDismissError = viewModel::dismissAiVoiceError,
        onUseLocalFallback = {
            if (isDialogueMode) {
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
        onScriptChange = { nextText ->
            if (nextText.isBlank()) {
                synthesizedSignature = null
            }
            viewModel.setAiVoiceScriptText(nextText)
        },
        onSelectEngine = viewModel::setAiVoiceEngine,
        onSelectLanguage = viewModel::setAiVoiceLanguage,
        onSelectCharacter = viewModel::selectAiVoiceCharacter,
        onToggleFavorite = viewModel::toggleAiVoiceCharacterFavorite,
        onSelectEmotion = viewModel::setAiVoiceEmotion,
        onSpeechRateChange = viewModel::setAiVoiceSpeechRate,
        onIntensityChange = viewModel::setAiVoiceEmotionIntensity,
        onResetTuning = viewModel::resetAiVoiceTuning,
        onPickReferenceFile = { pickReferenceAudioLauncher.launch(arrayOf("audio/*")) },
        onUseStudioBufferAsReference = viewModel::importAiVoiceReferenceFromCurrentBuffer,
        onClearReference = viewModel::clearAiVoiceReference,
        onReferenceTranscriptChange = viewModel::setAiVoiceReferenceTranscript,
        onRightsConfirmedChange = viewModel::setAiVoiceReferenceRightsConfirmed,
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
            advancedSubTab = 0
            viewModel.setAiVoiceTab(1)
        },
        onSaveCustomCharacter = viewModel::saveCustomAiVoiceCharacter,
        onDeleteSavedCharacter = viewModel::deleteSavedAiVoiceCharacter,
        onSaveBackendUrl = viewModel::saveAiVoiceBackendUrl,
        onSaveBackendToken = viewModel::saveAiVoiceBackendBearer,
        onCheckHealth = viewModel::checkAiVoiceBackendHealth,
        onPreviewClick = {
            val outputExists = !state.lastOutputWavPath.isNullOrBlank()
            if (outputExists && synthesizedSignature == currentInputSignature) {
                viewModel.playOrPauseAiVoiceOutput()
            } else {
                synthesizedSignature = currentInputSignature
                if (isDialogueMode) {
                    viewModel.synthesizeAiVoiceDialogue()
                } else {
                    viewModel.synthesizeAiVoiceSingle()
                }
            }
        },
        onCancelSynthesis = viewModel::cancelAiVoiceSynthesis,
        onApproveClick = {
            val hasValidText = if (isDialogueMode) {
                state.dialogueTurns.any { it.text.isNotBlank() }
            } else {
                state.scriptText.isNotBlank()
            }
            val hasFreshOutput = hasValidText &&
                !state.lastOutputWavPath.isNullOrBlank() &&
                synthesizedSignature == currentInputSignature
            if (hasFreshOutput) {
                viewModel.insertAiVoiceOutputIntoMainStudio(false)
            } else {
                synthesizedSignature = currentInputSignature
                if (isDialogueMode) {
                    viewModel.synthesizeAiVoiceDialogue()
                } else {
                    viewModel.synthesizeAiVoiceSingle()
                }
                if (hasValidText) {
                    viewModel.showStatusBanner(
                        if (isEnglish) {
                            "Generating voice for current text — tap Approve once ready to insert."
                        } else {
                            "جاري توليد الصوت للنص الحالي — اضغط اعتماد بعد الجاهزية لإدراجه بالاستوديو."
                        },
                    )
                }
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
}

@Composable
fun AiVoiceStudioContentLayout(
    state: AiVoiceStudioUiState,
    language: AppLanguage,
    hasStudioAudio: Boolean,
    advancedSubTab: Int = 0,
    onSelectAdvancedSubTab: (Int) -> Unit = {},
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
    onSelectCharacter: (AiVoiceCharacter) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectEmotion: (AiVoiceEmotion) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onResetTuning: () -> Unit,
    onPickReferenceFile: () -> Unit,
    onUseStudioBufferAsReference: () -> Unit,
    onClearReference: () -> Unit,
    onReferenceTranscriptChange: (String) -> Unit,
    onRightsConfirmedChange: (Boolean) -> Unit,
    onAddDialogueTurn: () -> Unit,
    onRemoveDialogueTurn: (String) -> Unit,
    onMoveDialogueTurn: (String, Int) -> Unit,
    onUpdateDialogueTurn: (String, (AiDialogueTurn) -> AiDialogueTurn) -> Unit,
    onSynthesizeDialogue: (AiVoiceEngineKind?) -> Unit,
    onApplyCharacterToStudio: (AiVoiceCharacter) -> Unit,
    onAddCharacterToDialogue: (AiVoiceCharacter) -> Unit,
    onSaveCustomCharacter: (String, String) -> Unit,
    onDeleteSavedCharacter: (String) -> Unit,
    onSaveBackendUrl: (String) -> Unit = {},
    onSaveBackendToken: (String) -> Unit = {},
    onCheckHealth: (String, String) -> Unit = { _, _ -> },
    onPreviewClick: () -> Unit,
    onCancelSynthesis: () -> Unit,
    onApproveClick: () -> Unit,
    bottomHelpSlot: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val isEnglish = language == AppLanguage.EN
    val showSynthesisBottomBar = state.activeTab == 0 || (state.activeTab == 1 && advancedSubTab == 0)

    Scaffold(
        containerColor = StudioColors.BgTop,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showSynthesisBottomBar) {
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
                    subtitle = if (isEnglish) "Nabra-82M • FFmpegKit Built-in Engine" else "Nabra-82M • محرك الاستوديو المدمج",
                    language = language,
                    onBack = onBack,
                )

                val tabs = listOf(
                    t(language, "ai_voice_tab_studio"),
                    t(language, "ai_voice_tab_advanced"),
                )
                val safeActiveTab = state.activeTab.coerceIn(0, tabs.lastIndex)

                TabRow(
                    selectedTabIndex = safeActiveTab,
                    containerColor = StudioColors.CardSurface,
                    contentColor = StudioColors.Cyan,
                    indicator = { tabPositions ->
                        if (safeActiveTab in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[safeActiveTab]),
                                color = StudioColors.Cyan,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("ai_voice_studio_tabs"),
                ) {
                    tabs.forEachIndexed { idx, label ->
                        Tab(
                            selected = safeActiveTab == idx,
                            onClick = { onSelectTab(idx) },
                            text = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (safeActiveTab == idx) FontWeight.Bold else FontWeight.Medium,
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
                                onUseLocalFallback = onUseLocalFallback,
                            )
                        }
                    }

                    if (!state.lastOutputWavPath.isNullOrBlank() && showSynthesisBottomBar) {
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

                    when (safeActiveTab) {
                        0 -> {
                            item(key = "studio_text") {
                                AiVoiceGroup1TextCard(
                                    scriptText = state.scriptText,
                                    isEnglish = isEnglish,
                                    onScriptChange = onScriptChange,
                                )
                            }
                            item(key = "card_1_engine") {
                                AiVoiceEngineSelectionCard(
                                    selectedEngine = state.selectedEngine,
                                    isEnglish = isEnglish,
                                    onSelectEngine = onSelectEngine,
                                )
                            }
                            item(key = "card_2_voice") {
                                AiVoiceGroup2VoiceSelectionCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSelectLanguage = onSelectLanguage,
                                    onSelectCharacter = onSelectCharacter,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                            item(key = "card_3_emotion") {
                                AiVoiceGroup3EmotionCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSelectEmotion = onSelectEmotion,
                                )
                            }
                            item(key = "card_4_controls") {
                                AiVoiceGroup4ControlsCard(
                                    state = state,
                                    isEnglish = isEnglish,
                                    onSpeechRateChange = onSpeechRateChange,
                                    onIntensityChange = onIntensityChange,
                                    onResetTuning = onResetTuning,
                                )
                            }
                            item(key = "group_more_and_help") {
                                bottomHelpSlot()
                            }
                        }
                        else -> {
                            item(key = "advanced_sub_switcher") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val subItems = listOf(
                                        0 to (if (isEnglish) "Dialogue" else "الحوار المتعدد"),
                                        1 to (if (isEnglish) "Clone & Save" else "استنساخ الصوت"),
                                        2 to (if (isEnglish) "Engine" else "المحرك المدمج"),
                                    )
                                    subItems.forEach { (idx, title) ->
                                        val selected = advancedSubTab == idx
                                        Surface(
                                            onClick = { onSelectAdvancedSubTab(idx) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) StudioColors.Cyan.copy(alpha = 0.16f) else StudioColors.CardSurface,
                                            border = BorderStroke(
                                                width = if (selected) 1.5.dp else 1.dp,
                                                color = if (selected) StudioColors.Cyan else StudioColors.Border,
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 48.dp)
                                                .testTag("ai_voice_advanced_sub_$idx"),
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                            ) {
                                                Text(
                                                    text = title,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selected) StudioColors.Cyan else StudioColors.TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            when (advancedSubTab) {
                                0 -> {
                                    item(key = "dialogue_studio") {
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
                                }
                                1 -> {
                                    item(key = "characters_and_cloning") {
                                        AiVoiceCharactersTab(
                                            state = state,
                                            hasStudioAudio = hasStudioAudio,
                                            isEnglish = isEnglish,
                                            onApplyCharacterToStudio = onApplyCharacterToStudio,
                                            onAddCharacterToDialogue = onAddCharacterToDialogue,
                                            onSaveCurrentAsCharacter = onSaveCustomCharacter,
                                            onDeleteSavedCharacter = onDeleteSavedCharacter,
                                            onPickReferenceFile = onPickReferenceFile,
                                            onUseStudioBufferAsReference = onUseStudioBufferAsReference,
                                            onClearReference = onClearReference,
                                            onReferenceTranscriptChange = onReferenceTranscriptChange,
                                            onRightsConfirmedChange = onRightsConfirmedChange,
                                            onSaveOutputWav = onSaveWav,
                                            onShareOutput = onShare,
                                        )
                                    }
                                }
                                else -> {
                                    item(key = "server_setup") {
                                        AiVoiceServerSetupTab(
                                            state = state,
                                            isEnglish = isEnglish,
                                            onSaveBackendUrl = onSaveBackendUrl,
                                            onSaveBackendToken = onSaveBackendToken,
                                            onCheckHealth = onCheckHealth,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
