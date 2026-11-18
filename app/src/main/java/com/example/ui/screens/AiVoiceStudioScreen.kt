package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.audio.aivoice.AiVoiceCategory
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.audio.formatDurationMs
import com.example.model.AppLanguage
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StudioFocusTopBar(
                title = if (isEnglish) "AI VOICE STUDIO" else "AI VOICE STUDIO – استوديو الأصوات الذكي",
                subtitle = "VoxCPM2 • Audar-Flash • Edge Neural Free",
                language = lang,
                onBack = onBack,
            )

            val tabs = if (isEnglish) {
                listOf("Voice Studio", "Dialogue", "Characters", "GPU Server")
            } else {
                listOf("استوديو الصوت", "حوار متعدد", "الشخصيات", "السيرفر والربط")
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
                        onClick = { viewModel.setAiVoiceTab(idx) },
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
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (!state.errorTitle.isNullOrBlank()) {
                    item(key = "status_banner") {
                        AiVoiceConfigurationBanner(
                            state = state,
                            isEnglish = isEnglish,
                            onDismiss = { viewModel.dismissAiVoiceError() },
                            onOpenServerTab = { viewModel.setAiVoiceTab(3) },
                            onUseLocalFallback = {
                                if (state.activeTab == 1) {
                                    viewModel.synthesizeAiVoiceDialogue(AiVoiceEngineKind.SONIX_LOCAL_STUDIO)
                                } else {
                                    viewModel.synthesizeAiVoiceSingle(AiVoiceEngineKind.SONIX_LOCAL_STUDIO)
                                }
                            },
                        )
                    }
                }

                if (!state.lastOutputWavPath.isNullOrBlank() && state.activeTab in 0..1) {
                    item(key = "output_player") {
                        AiVoiceOutputPlayerCard(
                            state = state,
                            isEnglish = isEnglish,
                            onPlayPause = { viewModel.playOrPauseAiVoiceOutput() },
                            onReplay = { viewModel.replayAiVoiceOutput() },
                            onStop = { viewModel.stopAiVoiceOutput() },
                            onSeekMs = { viewModel.seekAiVoiceOutput(it) },
                            onSaveWav = { viewModel.saveAiVoiceOutputToDevice() },
                            onShare = { viewModel.shareAiVoiceOutput() },
                            onInsertIntoStudio = { viewModel.insertAiVoiceOutputIntoMainStudio(false) },
                        )
                    }
                }

                when (state.activeTab) {
                    0 -> item(key = "single_studio") {
                        AiVoiceSingleStudioContent(
                            state = state,
                            hasStudioAudio = uiState.hasAudio,
                            isEnglish = isEnglish,
                            onSelectEngine = viewModel::setAiVoiceEngine,
                            onSelectCharacter = viewModel::selectAiVoiceCharacter,
                            onSelectLanguage = viewModel::setAiVoiceLanguage,
                            onSelectDialect = viewModel::setAiVoiceDialect,
                            onSelectCategory = viewModel::setAiVoiceCategory,
                            onSelectEmotion = viewModel::setAiVoiceEmotion,
                            onIntensityChange = viewModel::setAiVoiceEmotionIntensity,
                            onSelectPace = viewModel::setAiVoicePace,
                            onPitchOffsetChange = viewModel::setAiVoicePitchOffset,
                            onDescriptionChange = viewModel::setAiVoiceDescriptionPrompt,
                            onScriptChange = viewModel::setAiVoiceScriptText,
                            onPickReferenceFile = { pickReferenceAudioLauncher.launch(arrayOf("audio/*")) },
                            onUseStudioBufferAsReference = viewModel::importAiVoiceReferenceFromCurrentBuffer,
                            onClearReference = viewModel::clearAiVoiceReference,
                            onReferenceTranscriptChange = viewModel::setAiVoiceReferenceTranscript,
                            onRightsConfirmedChange = viewModel::setAiVoiceReferenceRightsConfirmed,
                            onSynthesize = { viewModel.synthesizeAiVoiceSingle() },
                            onCancel = viewModel::cancelAiVoiceSynthesis,
                        )
                    }
                    1 -> item(key = "dialogue_studio") {
                        AiVoiceDialogueTab(
                            state = state,
                            isEnglish = isEnglish,
                            onAddTurn = { viewModel.addDialogueTurn() },
                            onRemoveTurn = viewModel::removeDialogueTurn,
                            onMoveTurn = viewModel::moveDialogueTurn,
                            onUpdateTurn = viewModel::updateDialogueTurn,
                            onSynthesizeDialogue = viewModel::synthesizeAiVoiceDialogue,
                            onCancelSynthesis = viewModel::cancelAiVoiceSynthesis,
                        )
                    }
                    2 -> item(key = "characters_library") {
                        AiVoiceCharactersTab(
                            state = state,
                            isEnglish = isEnglish,
                            onApplyCharacterToStudio = { ch ->
                                viewModel.selectAiVoiceCharacter(ch)
                                viewModel.setAiVoiceTab(0)
                            },
                            onAddCharacterToDialogue = { ch ->
                                viewModel.addDialogueTurn(ch.id)
                                viewModel.setAiVoiceTab(1)
                            },
                            onSaveCurrentAsCharacter = viewModel::saveCustomAiVoiceCharacter,
                            onDeleteSavedCharacter = viewModel::deleteSavedAiVoiceCharacter,
                        )
                    }
                    else -> item(key = "server_setup") {
                        AiVoiceServerSetupTab(
                            state = state,
                            isEnglish = isEnglish,
                            onSaveBackendUrl = viewModel::saveAiVoiceBackendUrl,
                            onSaveBackendToken = viewModel::saveAiVoiceBackendBearer,
                            onCheckHealth = { url, token -> viewModel.checkAiVoiceBackendHealth(url, token) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiVoiceSingleStudioContent(
    state: AiVoiceStudioUiState,
    hasStudioAudio: Boolean,
    isEnglish: Boolean,
    onSelectEngine: (AiVoiceEngineKind) -> Unit,
    onSelectCharacter: (com.example.audio.aivoice.AiVoiceCharacter) -> Unit,
    onSelectLanguage: (AiVoiceLanguage) -> Unit,
    onSelectDialect: (AiArabicDialect) -> Unit,
    onSelectCategory: (AiVoiceCategory) -> Unit,
    onSelectEmotion: (AiVoiceEmotion) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onSelectPace: (AiDeliveryPace) -> Unit,
    onPitchOffsetChange: (Float) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onScriptChange: (String) -> Unit,
    onPickReferenceFile: () -> Unit,
    onUseStudioBufferAsReference: () -> Unit,
    onClearReference: () -> Unit,
    onReferenceTranscriptChange: (String) -> Unit,
    onRightsConfirmedChange: (Boolean) -> Unit,
    onSynthesize: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Engine Selection
        StudioBlockCard(title = if (isEnglish) "1. AI Voice Engine" else "١. محرك الذكاء الصوتي") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiVoiceEngineKind.entries.forEach { engine ->
                    val selected = state.selectedEngine == engine
                    val tagLabel = when {
                        engine == AiVoiceEngineKind.EDGE_NEURAL_FREE -> if (isEnglish) "FREE • NO CARD" else "مجاني مباشر"
                        engine.requiresRemoteGpu -> if (isEnglish) "GPU SERVER" else "خادم GPU"
                        else -> if (isEnglish) "OFFLINE" else "محلي"
                    }
                    val tagColor = when {
                        engine == AiVoiceEngineKind.EDGE_NEURAL_FREE -> StudioColors.Emerald
                        engine.requiresRemoteGpu -> StudioColors.Violet
                        else -> StudioColors.Amber
                    }
                    Surface(
                        onClick = { onSelectEngine(engine) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) StudioColors.Cyan.copy(alpha = 0.14f) else StudioColors.ElevatedSurface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) StudioColors.Cyan else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .testTag("ai_voice_engine_${engine.id}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (isEnglish) engine.titleEn else engine.titleAr,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (selected) StudioColors.Cyan else StudioColors.TextPrimary,
                                )
                                Text(
                                    text = "${if (isEnglish) engine.badgeEn else engine.badgeAr} • ${engine.modelRepoId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextSecondary,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = tagColor.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, tagColor.copy(alpha = 0.45f)),
                            ) {
                                Text(
                                    text = tagLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = tagColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Quick Character Presets
        StudioBlockCard(title = if (isEnglish) "2. Character Presets" else "٢. شخصيات صوتية سريعة") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.allCharacters, key = { it.id }) { ch ->
                    val selected = state.selectedCharacterId == ch.id
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectCharacter(ch) },
                        label = { Text(ch.displayName(isEnglish), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = StudioColors.Cyan,
                        ),
                    )
                }
            }
        }

        // 3. Language & Arabic Dialects
        StudioBlockCard(title = if (isEnglish) "3. Language & Arabic Dialect" else "٣. اللغة واللهجة العربية") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AiVoiceLanguage.entries, key = { it.code }) { language ->
                    FilterChip(
                        selected = state.selectedLanguage == language,
                        onClick = { onSelectLanguage(language) },
                        label = { Text(if (isEnglish) language.titleEn else language.titleAr, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = StudioColors.Cyan,
                        ),
                    )
                }
            }

            if (state.selectedLanguage == AiVoiceLanguage.AR) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isEnglish) "Arabic Dialect" else "اللهجة العربية المطلوبة:",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Amber,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiArabicDialect.entries.forEach { dialect ->
                        FilterChip(
                            selected = state.selectedDialect == dialect,
                            onClick = { onSelectDialect(dialect) },
                            label = { Text(if (isEnglish) dialect.titleEn else dialect.titleAr, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioColors.Amber.copy(alpha = 0.20f),
                                selectedLabelColor = StudioColors.Amber,
                            ),
                        )
                    }
                }
            }
        }

        // 4. Voice Category & Character Design Prompt
        StudioBlockCard(title = if (isEnglish) "4. Voice Category & Prompt Design" else "٤. الفئة وتصميم الشخصية بالوصف") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiVoiceCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = state.selectedCategory == cat,
                        onClick = { onSelectCategory(cat) },
                        label = { Text(if (isEnglish) cat.titleEn else cat.titleAr, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Violet.copy(alpha = 0.24f),
                            selectedLabelColor = StudioColors.Cyan,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = state.voiceDescriptionPrompt,
                onValueChange = onDescriptionChange,
                label = {
                    Text(
                        if (isEnglish) {
                            "Describe voice character (age, timbre, warmth, tone)"
                        } else {
                            "صف الصوت المطلوب (العمر، الخامة، النبرة، الأسلوب)"
                        },
                    )
                },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_prompt_input"),
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val templates = listOf(
                    "رجل عراقي في الأربعين، صوت عميق وخشن قليلاً، حزين وبطيء",
                    "امرأة شابة هادئة، تبتسم أثناء الحديث وبنبرة دافئة مريحة",
                    "رجل مسن بصوت متعب وحنين صادق",
                    "شخصية كرتونية مرحة، سريعة ومليئة بالحماس والضحك",
                )
                items(templates) { tpl ->
                    OutlinedButton(
                        onClick = { onDescriptionChange(tpl) },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tpl.take(26) + "…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 5. Emotions, Intensity, Pace & Pitch
        StudioBlockCard(title = if (isEnglish) "5. Emotion, Intensity & Pacing" else "٥. المشاعر والانفعالات وسرعة الإلقاء") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiVoiceEmotion.entries.forEach { emo ->
                    FilterChip(
                        selected = state.selectedEmotion == emo,
                        onClick = { onSelectEmotion(emo) },
                        label = { Text(if (isEnglish) emo.titleEn else emo.titleAr, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = StudioColors.Cyan,
                        ),
                    )
                }
            }

            Column {
                Text(
                    text = if (isEnglish) {
                        "Emotion Intensity: ${(state.emotionIntensity * 100).roundToInt()}%"
                    } else {
                        "شدة الانفعال: ${(state.emotionIntensity * 100).roundToInt()}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Cyan,
                )
                Slider(
                    value = state.emotionIntensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = StudioColors.Cyan, activeTrackColor = StudioColors.Cyan),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiDeliveryPace.entries.forEach { pace ->
                    FilterChip(
                        selected = state.selectedPace == pace,
                        onClick = { onSelectPace(pace) },
                        label = { Text(if (isEnglish) pace.titleEn else pace.titleAr, maxLines = 1) },
                    )
                }
            }

            Column {
                val sign = if (state.pitchOffsetSemitones > 0f) "+" else ""
                Text(
                    text = if (isEnglish) {
                        "Pitch Shift: $sign${"%.1f".format(state.pitchOffsetSemitones)} semitones"
                    } else {
                        "طبقة الصوت: $sign${"%.1f".format(state.pitchOffsetSemitones)} نصف درجة"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary,
                )
                Slider(
                    value = state.pitchOffsetSemitones,
                    onValueChange = onPitchOffsetChange,
                    valueRange = -8f..8f,
                )
            }
        }

        // 6. Voice Cloning Reference Audio
        StudioBlockCard(title = if (isEnglish) "6. Voice Cloning Reference (Optional)" else "٦. استنساخ صوت من تسجيل مرجعي (اختياري)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickReferenceFile,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_pick_ref_button"),
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEnglish) "Upload Audio" else "رفع عينة صوت", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUseStudioBufferAsReference,
                    enabled = hasStudioAudio,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_use_studio_ref_button"),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEnglish) "From Studio" else "من الاستوديو", maxLines = 1)
                }
            }

            if (!state.referenceAudioName.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioColors.ElevatedSurface,
                    border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.referenceAudioName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.Emerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatDurationMs(state.referenceAudioDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                        IconButton(onClick = onClearReference, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear Reference", tint = StudioColors.RoseBright)
                        }
                    }
                }

                OutlinedTextField(
                    value = state.referenceTranscript,
                    onValueChange = onReferenceTranscriptChange,
                    label = {
                        Text(
                            if (isEnglish) {
                                "Spoken words inside reference audio (helps exact cloning)"
                            } else {
                                "ما يُقال داخل العينة المرجعية (يرفع دقة الاستنساخ)"
                            },
                        )
                    },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = state.referenceRightsConfirmed,
                        onCheckedChange = onRightsConfirmedChange,
                        colors = CheckboxDefaults.colors(checkedColor = StudioColors.Cyan),
                    )
                    Text(
                        text = if (isEnglish) {
                            "I confirm I have permission/rights to use this reference voice."
                        } else {
                            "أؤكد امتلاكي الإذن وحق الاستخدام القانوني لهذه العينة الصوتية."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }
            }
        }

        // 7. Script Text & Generate Button
        StudioBlockCard(title = if (isEnglish) "7. Script & Synthesize" else "٧. النص المطلوب نطقه والتوليد") {
            OutlinedTextField(
                value = state.scriptText,
                onValueChange = onScriptChange,
                label = { Text(if (isEnglish) "Enter text to synthesize" else "اكتب النص لتحويله إلى صوت بشري واقعي") },
                minLines = 3,
                maxLines = 7,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_script_input"),
            )

            if (state.isSynthesizing) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { state.synthesisProgress.coerceIn(0f, 1f) },
                        color = StudioColors.Cyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        text = state.synthesisStatusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.Cyan,
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_cancel_button"),
                    ) {
                        Text(if (isEnglish) "Cancel" else "إلغاء التوليد")
                    }
                }
            } else {
                Button(
                    onClick = onSynthesize,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Cyan,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("ai_voice_synthesize_button"),
                ) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isEnglish) "Generate Studio Voice" else "توليد الصوت الاحترافي الآن",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioBlockCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = StudioColors.Cyan,
            )
            content()
        }
    }
}
