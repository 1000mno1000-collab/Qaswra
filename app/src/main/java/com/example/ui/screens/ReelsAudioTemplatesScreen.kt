package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.ReelsAudioTemplateId
import com.example.audio.ReelsTemplateParams
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.studio.ui.lit
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.PreviewAndApplyActionRow
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.ToolInfoDialog
import com.example.ui.components.UndoRedoActionsRow
import com.example.ui.components.rememberEditHistory
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

@Composable
fun ReelsAudioTemplatesScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenAudioPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var selectedTemplate by remember { mutableStateOf(ReelsAudioTemplateId.REELS_VIRAL_PUNCH) }
    var voiceBoostPct by remember { mutableFloatStateOf(selectedTemplate.defaultBoostPct) }
    var noiseIsolationPct by remember { mutableFloatStateOf(selectedTemplate.defaultNoiseIsoPct) }
    var infoOpen by remember { mutableStateOf(false) }
    var preferVmUndo by remember { mutableStateOf(false) }

    val history = rememberEditHistory(Triple(selectedTemplate, voiceBoostPct, noiseIsolationPct))

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession("reels_audio_templates")
        if (restored != null) {
            val tmplName = restored.stringParams["templateId"]
            ReelsAudioTemplateId.entries.firstOrNull { it.name == tmplName }?.let {
                selectedTemplate = it
            }
            voiceBoostPct = restored.floatParams["voiceBoostPct"] ?: selectedTemplate.defaultBoostPct
            noiseIsolationPct = restored.floatParams["noiseIsolationPct"] ?: selectedTemplate.defaultNoiseIsoPct
            history.commit(Triple(selectedTemplate, voiceBoostPct, noiseIsolationPct))
        }
    }

    val latestTmpl by rememberUpdatedState(selectedTemplate)
    val latestBoost by rememberUpdatedState(voiceBoostPct)
    val latestIso by rememberUpdatedState(noiseIsolationPct)
    val latestHasAudio by rememberUpdatedState(uiState.hasAudio)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopToolPreview()
            if (latestHasAudio) {
                viewModel.saveToolSessionToCache(
                    toolId = "reels_audio_templates",
                    titleAr = "قوالب الريلز والبودكاست",
                    titleEn = "Reels & Podcast Audio",
                    summaryAr = "-14 LUFS • ${latestBoost.roundToInt()}%",
                    summaryEn = "-14 LUFS • ${latestBoost.roundToInt()}%",
                    floatParams = mapOf(
                        "voiceBoostPct" to latestBoost,
                        "noiseIsolationPct" to latestIso,
                    ),
                    stringParams = mapOf("templateId" to latestTmpl.name),
                )
            }
        }
    }

    val currentParams = ReelsTemplateParams(
        templateId = selectedTemplate,
        voiceBoostPct = voiceBoostPct,
        noiseIsolationPct = noiseIsolationPct,
        targetLufs = -14f,
    )

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "reels_audio_title"),
            subtitle = t(lang, "reels_audio_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = history.canUndo || uiState.canUndoEdit,
                    canRedo = history.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "reels_audio_undo_button",
                    redoTag = "reels_audio_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !history.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            history.undo()?.let { (tmpl, boost, iso) ->
                                selectedTemplate = tmpl
                                voiceBoostPct = boost
                                noiseIsolationPct = iso
                            }
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !history.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            history.redo()?.let { (tmpl, boost, iso) ->
                                selectedTemplate = tmpl
                                voiceBoostPct = boost
                                noiseIsolationPct = iso
                            }
                        }
                    },
                    extraTrailing = {
                        InfoBadgeButton(
                            contentDescription = t(lang, "reels_audio_title"),
                            testTag = "reels_audio_info_button",
                            accentColor = StudioColors.Amber,
                            onClick = { infoOpen = true },
                        )
                    },
                )
            },
        )

        ActiveTrackPromptCard(
            uiState = uiState,
            onPickMainAudio = { onOpenAudioPicker(AudioPickerTarget.IMPORT_MAIN) },
        )

        // Social Loudness Standard Banner (-14 LUFS)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioColors.Amber.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Podcasts,
                    contentDescription = null,
                    tint = StudioColors.Amber,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = t(lang, "reels_audio_lufs_badge"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.Amber,
                    )
                    Text(
                        text = if (lang == AppLanguage.EN) {
                            "Pure voice engineering: announcer punch, room noise isolation & -14 LUFS loudness lock for Instagram Reels, TikTok & YouTube Shorts."
                        } else {
                            "هندسة صوتية خالصة: تضخيم صوت المعلق + عزل الضوضاء + موازنة الشدة (-14 LUFS) المعتمدة في إنستغرام، تيك توك ويوتيوب شورتس."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }
                Text(
                    text = "-14 LUFS",
                    style = TelemetryTextStyle,
                    color = StudioColors.Cyan,
                )
            }
        }

        // 4 Engineered Pure-Audio Templates
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (lang == AppLanguage.EN) "Choose Engineering Template" else "اختر قالب الهندسة الصوتية",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                ReelsAudioTemplateId.entries.forEach { template ->
                    val isSel = template == selectedTemplate
                    val cardInteraction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = {
                            preferVmUndo = false
                            selectedTemplate = template
                            voiceBoostPct = template.defaultBoostPct
                            noiseIsolationPct = template.defaultNoiseIsoPct
                            history.commit(Triple(template, template.defaultBoostPct, template.defaultNoiseIsoPct))
                        },
                        interactionSource = cardInteraction,
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                        border = BorderStroke(1.dp, if (isSel) StudioColors.Amber else StudioColors.Border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .lit(cardInteraction, StudioColors.Amber, selected = isSel, cornerRadius = 14.dp)
                            .testTag("reels_template_${template.name.lowercase()}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (isSel) StudioColors.Amber else StudioColors.Cyan,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (lang == AppLanguage.EN) template.titleEn else template.titleAr,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = if (lang == AppLanguage.EN) template.subtitleEn else template.subtitleAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                )
                            }
                            if (isSel) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = StudioColors.Amber,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Precision Sliders + Preview & Apply (Heavy Processing Engine -> Explicit Preview button kept)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StudioParamSlider(
                    label = t(lang, "reels_voice_boost"),
                    value = voiceBoostPct,
                    min = 0f,
                    max = 100f,
                    formattedValue = "${voiceBoostPct.roundToInt()}%",
                    accentColor = StudioColors.Amber,
                    testTag = "reels_voice_boost_slider",
                    onValueChange = { v ->
                        preferVmUndo = false
                        voiceBoostPct = v
                        history.commit(Triple(selectedTemplate, v, noiseIsolationPct), debounceMs = 350L)
                    },
                )

                StudioParamSlider(
                    label = t(lang, "reels_noise_isolation"),
                    value = noiseIsolationPct,
                    min = 0f,
                    max = 100f,
                    formattedValue = "${noiseIsolationPct.roundToInt()}%",
                    accentColor = StudioColors.Emerald,
                    testTag = "reels_noise_iso_slider",
                    onValueChange = { v ->
                        preferVmUndo = false
                        noiseIsolationPct = v
                        history.commit(Triple(selectedTemplate, voiceBoostPct, v), debounceMs = 350L)
                    },
                )

                uiState.purifyProgress?.let { prog ->
                    LinearProgressIndicator(
                        progress = { prog.coerceIn(0f, 1f) },
                        color = StudioColors.Amber,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                PreviewAndApplyActionRow(
                    isPreviewPlaying = uiState.isToolPreviewPlaying,
                    previewEnabled = uiState.hasAudio && !uiState.isBusy,
                    applyEnabled = uiState.hasAudio && !uiState.isBusy,
                    language = lang,
                    previewTag = "reels_audio_preview_button",
                    applyTag = "reels_audio_apply_button",
                    applyLabel = if (lang == AppLanguage.EN) "Apply (-14 LUFS) Template" else "تطبيق قالب الهندسة (-14 LUFS)",
                    applyColor = StudioColors.Amber,
                    onPreviewClick = { viewModel.previewReelsAudioTemplate(currentParams) },
                    onApplyClick = {
                        preferVmUndo = true
                        viewModel.applyReelsAudioTemplate(currentParams)
                    },
                )
            }
        }
    }

    ToolInfoDialog(
        open = infoOpen,
        title = t(lang, "reels_audio_title"),
        description = t(lang, "tool_reels_audio_templates_info"),
        language = lang,
        onDismiss = { infoOpen = false },
    )
}
