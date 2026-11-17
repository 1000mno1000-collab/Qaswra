package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.MergeStudioMode
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.model.EffectType
import com.example.model.ExportAudioFormat
import com.example.studio.ui.lit
import com.example.ui.components.PreviewAndApplyActionRow
import com.example.ui.components.SaveDraftDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioPreviewControlsBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.UndoRedoActionsRow
import com.example.ui.components.rememberEditHistory
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

@Composable
fun ActiveTrackPromptCard(
    uiState: StudioUiState,
    onPickMainAudio: () -> Unit,
) {
    val lang = uiState.language
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, if (uiState.hasAudio) StudioColors.Border else StudioColors.Cyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.hasAudio) uiState.activeProjectName else t(lang, "no_audio_loaded"),
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                    )
                    if (uiState.hasAudio) {
                        Text(
                            text = formatDurationMs(uiState.durationMs),
                            style = TelemetryTextStyle,
                            color = StudioColors.Cyan,
                        )
                    }
                }
            }
            val pickInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onPickMainAudio,
                interactionSource = pickInteraction,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioColors.Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .lit(pickInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                    .testTag("tool_pick_audio_button"),
            ) {
                Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t(lang, "audio_tools_pick_source"))
            }
        }
    }
}

@Composable
fun AudioMergeScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    UnifiedMergeOverlayStudioScreen(
        uiState = uiState,
        viewModel = viewModel,
        initialMode = MergeStudioMode.SEQUENTIAL,
        initialAutoDucking = false,
        toolId = "merge_studio",
        onOpenPicker = onOpenPicker,
        onBack = onBack,
    )
}

@Composable
fun AudioSplitScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var splitPoint by remember { mutableFloatStateOf(0.5f) }
    var saveBothToLibrary by remember { mutableStateOf(true) }
    var loadPart by remember { mutableIntStateOf(1) }
    var preferVmUndo by remember { mutableStateOf(false) }
    val splitHistory = rememberEditHistory(Triple(splitPoint, loadPart, saveBothToLibrary))

    val splitMs = (uiState.durationMs * splitPoint).roundToInt().toLong()

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_split_tracks"),
            subtitle = if (lang == AppLanguage.EN) "Split by timeline point or isolate Stereo L/R channels" else "تقسيم المقطع زمنياً أو فصل قنوات الستيريو (يمين / يسار)",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = splitHistory.canUndo || uiState.canUndoEdit,
                    canRedo = splitHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "split_undo_button",
                    redoTag = "split_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !splitHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            splitHistory.undo()?.let { (pt, part, saveBoth) ->
                                splitPoint = pt
                                loadPart = part
                                saveBothToLibrary = saveBoth
                            }
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !splitHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            splitHistory.redo()?.let { (pt, part, saveBoth) ->
                                splitPoint = pt
                                loadPart = part
                                saveBothToLibrary = saveBoth
                            }
                        }
                    },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

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
                    label = if (lang == AppLanguage.EN) "Split Point Position" else "نقطة التقسيم الزمنية",
                    value = splitPoint,
                    min = 0.05f,
                    max = 0.95f,
                    formattedValue = formatDurationMs(splitMs),
                    accentColor = StudioColors.Cyan,
                    testTag = "split_point_slider",
                    onValueChange = {
                        preferVmUndo = false
                        splitPoint = it
                        splitHistory.commit(Triple(it, loadPart, saveBothToLibrary), debounceMs = 350L)
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val part1Interaction = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = loadPart == 1,
                        onClick = {
                            preferVmUndo = false
                            loadPart = 1
                            splitHistory.commit(Triple(splitPoint, 1, saveBothToLibrary))
                        },
                        interactionSource = part1Interaction,
                        label = { Text(if (lang == AppLanguage.EN) "Open Part 1" else "فتح الجزء الأول") },
                        modifier = Modifier
                            .weight(1f)
                            .lit(part1Interaction, MaterialTheme.colorScheme.primary, selected = loadPart == 1, cornerRadius = 10.dp),
                    )
                    val part2Interaction = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = loadPart == 2,
                        onClick = {
                            preferVmUndo = false
                            loadPart = 2
                            splitHistory.commit(Triple(splitPoint, 2, saveBothToLibrary))
                        },
                        interactionSource = part2Interaction,
                        label = { Text(if (lang == AppLanguage.EN) "Open Part 2" else "فتح الجزء الثاني") },
                        modifier = Modifier
                            .weight(1f)
                            .lit(part2Interaction, MaterialTheme.colorScheme.primary, selected = loadPart == 2, cornerRadius = 10.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Save both parts to studio archive" else "حفظ الجزئين في أرشيف الاستوديو",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = saveBothToLibrary, onCheckedChange = { saveBothToLibrary = it })
                }

                val splitExecInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        preferVmUndo = true
                        viewModel.splitAudioAtFraction(splitPoint, loadPart, saveBothToLibrary)
                    },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    interactionSource = splitExecInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(splitExecInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("split_execute_button"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "apply_now"))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val leftInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.splitStereoChannel(keepLeft = true) },
                        enabled = uiState.hasAudio,
                        interactionSource = leftInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(leftInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp),
                    ) {
                        Text(if (lang == AppLanguage.EN) "Isolate Left (L)" else "عزل القناة اليسرى")
                    }
                    val rightInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.splitStereoChannel(keepLeft = false) },
                        enabled = uiState.hasAudio,
                        interactionSource = rightInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(rightInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp),
                    ) {
                        Text(if (lang == AppLanguage.EN) "Isolate Right (R)" else "عزل القناة اليمنى")
                    }
                }
            }
        }
    }
}

@Composable
fun PitchAndSpeedScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val semitones = uiState.dspConfig.pitchShift.semitones
    val tempo = uiState.dspConfig.timeStretch.tempoRatio
    var saveDraftDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession("pitch_speed")
        if (restored != null) {
            val st = restored.floatParams["semitones"] ?: 0f
            val tr = restored.floatParams["tempoRatio"] ?: 1f
            viewModel.updateDspConfig { prev ->
                prev.copy(
                    pitchShift = prev.pitchShift.copy(semitones = st, wetMix = 1f),
                    timeStretch = prev.timeStretch.copy(tempoRatio = tr),
                )
                    .withSlotEnabled(EffectType.PITCH_SHIFT, kotlin.math.abs(st) >= 0.05f)
                    .withSlotEnabled(EffectType.TIME_STRETCH, kotlin.math.abs(tr - 1f) >= 0.02f)
            }
        }
    }

    val latestSemi by rememberUpdatedState(semitones)
    val latestTempo by rememberUpdatedState(tempo)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopToolPreview()
            viewModel.saveToolSessionToCache(
                toolId = "pitch_speed",
                titleAr = "الطبقة والسرعة",
                titleEn = "Pitch & Speed",
                summaryAr = "${"%+.1f".format(latestSemi)} st • ${"%.2f".format(latestTempo)}x",
                summaryEn = "${"%+.1f".format(latestSemi)} st • ${"%.2f".format(latestTempo)}x",
                floatParams = mapOf("semitones" to latestSemi, "tempoRatio" to latestTempo),
            )
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_pitch_speed"),
            subtitle = if (lang == AppLanguage.EN) "Adjust pitch semitones & tempo independently" else "تعديل حدة الطبقة الصوتية وسرعة الإيقاع بدقة",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = uiState.canUndoEdit,
                    canRedo = uiState.canRedoEdit,
                    language = lang,
                    undoTag = "pitch_speed_top_undo_button",
                    redoTag = "pitch_speed_top_redo_button",
                    onUndo = { viewModel.undoLastEdit() },
                    onRedo = { viewModel.redoLastEdit() },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

        StudioPreviewControlsBar(
            uiState = uiState,
            onHoldBypassChange = { viewModel.setHoldBypass(it) },
            onToggleBypass = { viewModel.toggleBypass() },
            onUndo = { viewModel.undoLastEdit() },
            onRedo = { viewModel.redoLastEdit() },
            onResetZero = { viewModel.resetAllEffects() },
            onOpenSaveDraft = { saveDraftDialogOpen = true },
        )

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
                    label = t(lang, "effect_pitch_shift"),
                    value = semitones,
                    min = -12f,
                    max = 12f,
                    formattedValue = "${"%+.1f".format(semitones)} st",
                    accentColor = StudioColors.Cyan,
                    testTag = "pitch_tool_semitones",
                    onValueChange = {
                        viewModel.updateDspConfig { prev ->
                            prev.copy(pitchShift = prev.pitchShift.copy(semitones = it, wetMix = 1f))
                                .withSlotEnabled(EffectType.PITCH_SHIFT, kotlin.math.abs(it) >= 0.05f)
                        }
                        viewModel.ensureLivePreviewPlaying()
                    },
                )

                StudioParamSlider(
                    label = t(lang, "effect_time_stretch"),
                    value = tempo,
                    min = 0.5f,
                    max = 2.0f,
                    formattedValue = "${"%.2f".format(tempo)}x",
                    accentColor = StudioColors.Violet,
                    testTag = "speed_tool_tempo",
                    onValueChange = {
                        viewModel.updateDspConfig { prev ->
                            prev.copy(timeStretch = prev.timeStretch.copy(tempoRatio = it))
                                .withSlotEnabled(EffectType.TIME_STRETCH, kotlin.math.abs(it - 1f) >= 0.02f)
                        }
                        viewModel.ensureLivePreviewPlaying()
                    },
                )

                val applyPitchInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { viewModel.applyPitchAndSpeed(semitones, tempo) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    interactionSource = applyPitchInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .lit(applyPitchInteraction, StudioColors.Cyan, cornerRadius = 14.dp)
                        .testTag("pitch_speed_bake_button"),
                ) {
                    Text(t(lang, "apply_now"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    SaveDraftDialog(
        open = saveDraftDialogOpen,
        language = lang,
        initialName = uiState.activeProjectName.ifBlank { t(lang, "untitled_project") },
        activePresetLabel = uiState.selectedPresetLabel,
        isCloudSignedIn = uiState.isCloudSynced,
        onDismiss = { saveDraftDialogOpen = false },
        onSaveDraft = { draftName -> viewModel.saveCloudDraft(draftName) },
    )
}

@Composable
fun ReverseAudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopToolPreview()
            viewModel.saveToolSessionToCache(
                toolId = "reverse_audio",
                titleAr = "عكس الصوت",
                titleEn = "Reverse Audio",
                summaryAr = "جلسة عكس المقطع",
                summaryEn = "Reverse clip session",
                floatParams = emptyMap(),
            )
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_reverse_audio"),
            subtitle = if (lang == AppLanguage.EN) "Reverse audio playback cleanly with Undo support" else "عكس اتجاه المقطع الصوتي مع دعم التراجع الفوري",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = uiState.canUndoEdit,
                    canRedo = uiState.canRedoEdit,
                    language = lang,
                    undoTag = "reverse_undo_button",
                    redoTag = "reverse_redo_button",
                    onUndo = { viewModel.undoLastEdit() },
                    onRedo = { viewModel.redoLastEdit() },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

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
                PreviewAndApplyActionRow(
                    isPreviewPlaying = uiState.isToolPreviewPlaying,
                    previewEnabled = uiState.hasAudio && !uiState.isBusy,
                    applyEnabled = uiState.hasAudio && !uiState.isBusy,
                    language = lang,
                    previewTag = "reverse_preview_button",
                    applyTag = "reverse_apply_button",
                    applyLabel = t(lang, "apply_now"),
                    applyColor = StudioColors.Violet,
                    onPreviewClick = { viewModel.previewReverseAudio() },
                    onApplyClick = { viewModel.applyReverseAudio() },
                )
            }
        }
    }
}

@Composable
fun SurroundStereoScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    // True Zero 0% neutral start across all Surround Stereo sliders
    var midAdjustPct by remember { mutableFloatStateOf(0f) }
    var sideBoostPct by remember { mutableFloatStateOf(0f) }
    var widthPct by remember { mutableFloatStateOf(0f) }
    var saveDraftDialogOpen by remember { mutableStateOf(false) }
    var preferVmUndo by remember { mutableStateOf(false) }
    val stereoHistory = rememberEditHistory(Triple(midAdjustPct, sideBoostPct, widthPct))

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession("surround_stereo")
        if (restored != null) {
            midAdjustPct = restored.floatParams["midAdjustPct"] ?: 0f
            sideBoostPct = restored.floatParams["sideBoostPct"] ?: 0f
            widthPct = restored.floatParams["widthPct"] ?: 0f
        }
    }

    val latestMid by rememberUpdatedState(midAdjustPct)
    val latestSide by rememberUpdatedState(sideBoostPct)
    val latestWidth by rememberUpdatedState(widthPct)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopToolPreview()
            viewModel.saveToolSessionToCache(
                toolId = "surround_stereo",
                titleAr = "ستيريو محيطي M/S",
                titleEn = "Surround Stereo",
                summaryAr = "Mid ${latestMid.roundToInt()}% • Side ${latestSide.roundToInt()}% • ${latestWidth.roundToInt()}%",
                summaryEn = "Mid ${latestMid.roundToInt()}% • Side ${latestSide.roundToInt()}% • ${latestWidth.roundToInt()}%",
                floatParams = mapOf(
                    "midAdjustPct" to latestMid,
                    "sideBoostPct" to latestSide,
                    "widthPct" to latestWidth,
                ),
            )
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_stereo_surround"),
            subtitle = if (lang == AppLanguage.EN) "Convert mono or narrow clips into wide spatial stereo" else "توسيع المجال الصوتي M/S وتحويل المونو إلى ستيريو محيطي",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = stereoHistory.canUndo || uiState.canUndoEdit,
                    canRedo = stereoHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "stereo_top_undo_button",
                    redoTag = "stereo_top_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !stereoHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            stereoHistory.undo()?.let { (m, s, w) ->
                                midAdjustPct = m
                                sideBoostPct = s
                                widthPct = w
                            }
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !stereoHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            stereoHistory.redo()?.let { (m, s, w) ->
                                midAdjustPct = m
                                sideBoostPct = s
                                widthPct = w
                            }
                        }
                    },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

        StudioPreviewControlsBar(
            uiState = uiState,
            onHoldBypassChange = { viewModel.setHoldBypass(it) },
            onToggleBypass = { viewModel.toggleBypass() },
            onUndo = { viewModel.undoLastEdit() },
            onRedo = { viewModel.redoLastEdit() },
            onResetZero = {
                preferVmUndo = false
                midAdjustPct = 0f
                sideBoostPct = 0f
                widthPct = 0f
                stereoHistory.commit(Triple(0f, 0f, 0f))
                viewModel.updateSurroundLiveParams(0f, 0f, 0f)
                viewModel.resetAllEffects()
            },
            onOpenSaveDraft = { saveDraftDialogOpen = true },
        )

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
                    label = if (lang == AppLanguage.EN) "Mid Center Offset" else "إزاحة المنتصف (Mid)",
                    value = midAdjustPct,
                    min = -60f,
                    max = 60f,
                    formattedValue = "${if (midAdjustPct > 0f) "+" else ""}${midAdjustPct.roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "stereo_mid_slider",
                    onValueChange = {
                        preferVmUndo = false
                        midAdjustPct = it
                        stereoHistory.commit(Triple(it, sideBoostPct, widthPct), debounceMs = 350L)
                        viewModel.updateSurroundLiveParams(it, sideBoostPct, widthPct)
                        viewModel.ensureLivePreviewPlaying()
                    },
                )
                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Side Surround Boost" else "توسيع الجوانب المحيطية (Side)",
                    value = sideBoostPct,
                    min = 0f,
                    max = 120f,
                    formattedValue = "+${sideBoostPct.roundToInt()}%",
                    accentColor = StudioColors.Violet,
                    testTag = "stereo_side_slider",
                    onValueChange = {
                        preferVmUndo = false
                        sideBoostPct = it
                        stereoHistory.commit(Triple(midAdjustPct, it, widthPct), debounceMs = 350L)
                        viewModel.updateSurroundLiveParams(midAdjustPct, it, widthPct)
                        viewModel.ensureLivePreviewPlaying()
                    },
                )
                StudioParamSlider(
                    label = t(lang, "param_stereo_width"),
                    value = widthPct,
                    min = 0f,
                    max = 100f,
                    formattedValue = "${widthPct.roundToInt()}%",
                    accentColor = StudioColors.Emerald,
                    testTag = "stereo_width_slider",
                    onValueChange = {
                        preferVmUndo = false
                        widthPct = it
                        stereoHistory.commit(Triple(midAdjustPct, sideBoostPct, it), debounceMs = 350L)
                        viewModel.updateSurroundLiveParams(midAdjustPct, sideBoostPct, it)
                        viewModel.ensureLivePreviewPlaying()
                    },
                )

                val applyStereoInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        preferVmUndo = true
                        viewModel.applySurroundStereoFromOffsets(midAdjustPct, sideBoostPct, widthPct)
                    },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    interactionSource = applyStereoInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .lit(applyStereoInteraction, StudioColors.Cyan, cornerRadius = 14.dp)
                        .testTag("stereo_apply_button"),
                ) {
                    Text(t(lang, "apply_now"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    SaveDraftDialog(
        open = saveDraftDialogOpen,
        language = lang,
        initialName = uiState.activeProjectName.ifBlank { t(lang, "untitled_project") },
        activePresetLabel = uiState.selectedPresetLabel,
        isCloudSignedIn = uiState.isCloudSynced,
        onDismiss = { saveDraftDialogOpen = false },
        onSaveDraft = { draftName -> viewModel.saveCloudDraft(draftName) },
    )
}

@Composable
fun GenerateSilenceScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var durationSec by remember { mutableFloatStateOf(3.0f) }
    var appendMode by remember { mutableStateOf(uiState.hasAudio) }
    var preferVmUndo by remember { mutableStateOf(false) }
    val silenceHistory = rememberEditHistory(durationSec to appendMode)

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession("generate_silence")
        if (restored != null) {
            durationSec = restored.floatParams["durationSec"] ?: durationSec
            appendMode = (restored.floatParams["appendMode"] ?: 0f) > 0.5f
        }
    }

    val latestDur by rememberUpdatedState(durationSec)
    val latestAppend by rememberUpdatedState(appendMode)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveToolSessionToCache(
                toolId = "generate_silence",
                titleAr = "توليد صمت",
                titleEn = "Generate Silence",
                summaryAr = "${"%.1f".format(latestDur)}s",
                summaryEn = "${"%.1f".format(latestDur)}s",
                floatParams = mapOf(
                    "durationSec" to latestDur,
                    "appendMode" to if (latestAppend) 1f else 0f,
                ),
            )
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_generate_silence"),
            subtitle = if (lang == AppLanguage.EN) "Create exact silent tracks or insert pauses" else "إنشاء مسار صمت نقي أو إضافته كوقفة زمنية للمشروع",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = silenceHistory.canUndo || uiState.canUndoEdit,
                    canRedo = silenceHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "silence_undo_button",
                    redoTag = "silence_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !silenceHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            silenceHistory.undo()?.let { (d, app) ->
                                durationSec = d
                                appendMode = app
                            }
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !silenceHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            silenceHistory.redo()?.let { (d, app) ->
                                durationSec = d
                                appendMode = app
                            }
                        }
                    },
                )
            },
        )

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
                    label = if (lang == AppLanguage.EN) "Silence Duration" else "مدة الصمت (بالثواني)",
                    value = durationSec,
                    min = 0.5f,
                    max = 60f,
                    formattedValue = "${"%.1f".format(durationSec)} s",
                    accentColor = StudioColors.Cyan,
                    testTag = "silence_duration_slider",
                    onValueChange = {
                        preferVmUndo = false
                        durationSec = it
                        silenceHistory.commit(it to appendMode, debounceMs = 350L)
                    },
                )

                if (uiState.hasAudio) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (lang == AppLanguage.EN) "Append to end of current track" else "إلحاق بنهاية المقطع الحالي",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = appendMode,
                            onCheckedChange = {
                                preferVmUndo = false
                                appendMode = it
                                silenceHistory.commit(durationSec to it)
                            },
                        )
                    }
                }

                val silenceInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        preferVmUndo = true
                        viewModel.insertGeneratedSilence(durationSec, appendMode)
                    },
                    enabled = !uiState.isBusy,
                    interactionSource = silenceInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(silenceInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("silence_generate_button"),
                ) {
                    Icon(Icons.Rounded.HourglassEmpty, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "apply_now"))
                }
            }
        }
    }
}

@Composable
fun FormatConverterScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.MP3) }
    var fileName by remember(uiState.activeProjectName) {
        mutableStateOf(uiState.activeProjectName.ifBlank { "Sonix_Converted" })
    }
    val convertHistory = rememberEditHistory(selectedFormat to fileName)

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_format_convert"),
            subtitle = if (lang == AppLanguage.EN) "Convert between MP3, M4A, WAV, FLAC, and OGG" else "تحويل الصيغ بين MP3 و M4A و WAV و FLAC و OGG",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = convertHistory.canUndo || uiState.canUndoEdit,
                    canRedo = convertHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "convert_undo_button",
                    redoTag = "convert_redo_button",
                    onUndo = {
                        if (convertHistory.canUndo) {
                            convertHistory.undo()?.let { (fmt, name) ->
                                selectedFormat = fmt
                                fileName = name
                            }
                        } else {
                            viewModel.undoLastEdit()
                        }
                    },
                    onRedo = {
                        if (convertHistory.canRedo) {
                            convertHistory.redo()?.let { (fmt, name) ->
                                selectedFormat = fmt
                                fileName = name
                            }
                        } else {
                            viewModel.redoLastEdit()
                        }
                    },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

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
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        convertHistory.commit(selectedFormat to it, debounceMs = 350L)
                    },
                    singleLine = true,
                    label = { Text(if (lang == AppLanguage.EN) "Output Filename" else "اسم الملف الناتج") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportAudioFormat.entries.forEach { fmt ->
                        val isFmtSelected = selectedFormat == fmt
                        val fmtInteraction = remember { MutableInteractionSource() }
                        FilterChip(
                            selected = isFmtSelected,
                            onClick = {
                                selectedFormat = fmt
                                convertHistory.commit(fmt to fileName)
                            },
                            interactionSource = fmtInteraction,
                            label = { Text(fmt.displayName) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(fmtInteraction, MaterialTheme.colorScheme.primary, selected = isFmtSelected, cornerRadius = 10.dp),
                        )
                    }
                }

                uiState.exportProgress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        color = StudioColors.Cyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val convertSaveInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, fileName, shareAfter = false) },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        interactionSource = convertSaveInteraction,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(convertSaveInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("convert_save_button"),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "export_and_save"))
                    }

                    val convertShareInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, fileName, shareAfter = true) },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        interactionSource = convertShareInteraction,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(convertShareInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = t(lang, "share_audio"))
                    }
                }
            }
        }
    }
}

@Composable
fun ExtractVideoAudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.WAV) }

    LaunchedEffect(uiState.pendingExtractVideoUri) {
        val stagedUri = viewModel.consumePendingExtractVideoUri()
        if (stagedUri != null) {
            viewModel.extractAudioFromVideoUri(stagedUri)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.extractAudioFromVideoUri(uri)
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_extract_video"),
            subtitle = if (lang == AppLanguage.EN) "Extract high-fidelity audio from any video" else "استخراج المسار الصوتي عالي الدقة من أي فيديو فوراً",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = uiState.canUndoEdit,
                    canRedo = uiState.canRedoEdit,
                    language = lang,
                    undoTag = "extract_video_undo_button",
                    redoTag = "extract_video_redo_button",
                    onUndo = { viewModel.undoLastEdit() },
                    onRedo = { viewModel.redoLastEdit() },
                )
            },
        )

        val pickVideoInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = { videoPicker.launch(arrayOf("video/*")) },
            enabled = !uiState.isBusy,
            interactionSource = pickVideoInteraction,
            colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .lit(pickVideoInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp)
                .testTag("extract_pick_video_button"),
        ) {
            Icon(Icons.Rounded.VideoFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(t(lang, "audio_tools_pick_video"), style = MaterialTheme.typography.labelLarge)
        }

        if (uiState.hasAudio) {
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
                        text = "${uiState.activeProjectName} • ${formatDurationMs(uiState.durationMs)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.Emerald,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            ExportAudioFormat.WAV,
                            ExportAudioFormat.MP3,
                            ExportAudioFormat.FLAC,
                            ExportAudioFormat.M4A,
                        ).forEach { fmt ->
                            val isFmtSelected = selectedFormat == fmt
                            val fmtInteraction = remember { MutableInteractionSource() }
                            FilterChip(
                                selected = isFmtSelected,
                                onClick = { selectedFormat = fmt },
                                interactionSource = fmtInteraction,
                                label = { Text(fmt.displayName) },
                                modifier = Modifier.lit(fmtInteraction, MaterialTheme.colorScheme.primary, selected = isFmtSelected, cornerRadius = 10.dp),
                            )
                        }
                    }
                    val extractExportInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, uiState.activeProjectName, shareAfter = false) },
                        enabled = !uiState.isBusy,
                        interactionSource = extractExportInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(extractExportInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "export_and_save"))
                    }
                }
            }
        }
    }
}

@Composable
fun CompressAudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var bitrateKbps by remember { mutableIntStateOf(96) }
    var fileName by remember(uiState.activeProjectName) {
        mutableStateOf("${uiState.activeProjectName.ifBlank { "Sonix" }}_Compressed")
    }
    val compressHistory = rememberEditHistory(bitrateKbps to fileName)
    val estKb = ((uiState.durationMs / 1000.0) * bitrateKbps / 8.0).roundToInt().coerceAtLeast(1)

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_compress_audio"),
            subtitle = if (lang == AppLanguage.EN) "Reduce audio file size smoothly with AAC compression" else "ضغط وتقليل حجم الملف الصوتي مع الحفاظ على نقاء الصوت",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = compressHistory.canUndo || uiState.canUndoEdit,
                    canRedo = compressHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "compress_undo_button",
                    redoTag = "compress_redo_button",
                    onUndo = {
                        if (compressHistory.canUndo) {
                            compressHistory.undo()?.let { (br, name) ->
                                bitrateKbps = br
                                fileName = name
                            }
                        } else {
                            viewModel.undoLastEdit()
                        }
                    },
                    onRedo = {
                        if (compressHistory.canRedo) {
                            compressHistory.redo()?.let { (br, name) ->
                                bitrateKbps = br
                                fileName = name
                            }
                        } else {
                            viewModel.redoLastEdit()
                        }
                    },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

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
                Text(
                    text = if (lang == AppLanguage.EN) "Target Bitrate" else "معدل الضغط (Bitrate)",
                    style = MaterialTheme.typography.titleSmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(64, 96, 128, 192).forEach { br ->
                        val brSelected = bitrateKbps == br
                        val brInteraction = remember { MutableInteractionSource() }
                        FilterChip(
                            selected = brSelected,
                            onClick = {
                                bitrateKbps = br
                                compressHistory.commit(br to fileName)
                            },
                            interactionSource = brInteraction,
                            label = { Text("${br}k") },
                            modifier = Modifier
                                .weight(1f)
                                .lit(brInteraction, MaterialTheme.colorScheme.primary, selected = brSelected, cornerRadius = 10.dp),
                        )
                    }
                }

                if (uiState.hasAudio) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Estimated Size: ~${estKb} KB" else "الحجم التقديري بعد الضغط: ~${estKb} KB",
                        style = TelemetryTextStyle,
                        color = StudioColors.Emerald,
                    )
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        compressHistory.commit(bitrateKbps to it, debounceMs = 350L)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                val compressInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { viewModel.compressAndExportAudio(bitrateKbps, fileName, shareAfter = false) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    interactionSource = compressInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .lit(compressInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("compress_export_button"),
                ) {
                    Icon(Icons.Rounded.Compress, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "export_and_save"))
                }
            }
        }
    }
}

@Composable
fun RingtoneMakerScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    var startFrac by remember(uiState.trimStart) { mutableFloatStateOf(uiState.trimStart) }
    var endFrac by remember(uiState.trimEnd) { mutableFloatStateOf(uiState.trimEnd) }
    var ringtoneTitle by remember(uiState.activeProjectName) {
        mutableStateOf("Sonix_${uiState.activeProjectName.ifBlank { "Ringtone" }}")
    }
    var setAsDefault by remember { mutableStateOf(true) }
    val ringtoneHistory = rememberEditHistory(
        listOf<Any>(startFrac, endFrac, ringtoneTitle, setAsDefault),
    )

    fun commitRingtone(
        s: Float = startFrac,
        e: Float = endFrac,
        title: String = ringtoneTitle,
        def: Boolean = setAsDefault,
        debounceMs: Long = 0L,
    ) {
        ringtoneHistory.commit(listOf<Any>(s, e, title, def), debounceMs = debounceMs)
    }

    fun applyRingtone(snap: List<Any>) {
        startFrac = snap[0] as Float
        endFrac = snap[1] as Float
        ringtoneTitle = snap[2] as String
        setAsDefault = snap[3] as Boolean
    }

    val clipLenMs = ((endFrac - startFrac).coerceAtLeast(0.02f) * uiState.durationMs).roundToInt().toLong()

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_ringtone_maker"),
            subtitle = if (lang == AppLanguage.EN) "Cut custom ringtones and assign to device" else "قص وتخصيص نغمات الرنين وحفظها وتعيينها للهاتف",
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = ringtoneHistory.canUndo || uiState.canUndoEdit,
                    canRedo = ringtoneHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "ringtone_undo_button",
                    redoTag = "ringtone_redo_button",
                    onUndo = {
                        if (ringtoneHistory.canUndo) {
                            ringtoneHistory.undo()?.let(::applyRingtone)
                        } else {
                            viewModel.undoLastEdit()
                        }
                    },
                    onRedo = {
                        if (ringtoneHistory.canRedo) {
                            ringtoneHistory.redo()?.let(::applyRingtone)
                        } else {
                            viewModel.redoLastEdit()
                        }
                    },
                )
            },
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

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
                OutlinedTextField(
                    value = ringtoneTitle,
                    onValueChange = {
                        ringtoneTitle = it
                        commitRingtone(title = it, debounceMs = 350L)
                    },
                    singleLine = true,
                    label = { Text(if (lang == AppLanguage.EN) "Ringtone Title" else "اسم نغمة الرنين") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Start Point" else "بداية النغمة",
                    value = startFrac,
                    min = 0f,
                    max = (endFrac - 0.05f).coerceAtLeast(0.01f),
                    formattedValue = formatDurationMs((uiState.durationMs * startFrac).roundToInt().toLong()),
                    accentColor = StudioColors.Cyan,
                    testTag = "ringtone_start_slider",
                    onValueChange = {
                        startFrac = it
                        commitRingtone(s = it, debounceMs = 350L)
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "End Point" else "نهاية النغمة",
                    value = endFrac,
                    min = (startFrac + 0.05f).coerceAtMost(0.99f),
                    max = 1f,
                    formattedValue = formatDurationMs((uiState.durationMs * endFrac).roundToInt().toLong()),
                    accentColor = StudioColors.Emerald,
                    testTag = "ringtone_end_slider",
                    onValueChange = {
                        endFrac = it
                        commitRingtone(e = it, debounceMs = 350L)
                    },
                )

                Text(
                    text = if (lang == AppLanguage.EN) "Ringtone length: ${formatDurationMs(clipLenMs)}" else "مدة النغمة المحددة: ${formatDurationMs(clipLenMs)}",
                    style = TelemetryTextStyle,
                    color = StudioColors.Cyan,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Set as default phone ringtone" else "تعيين كنغمة رنين افتراضية للجهاز",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = setAsDefault,
                        onCheckedChange = {
                            setAsDefault = it
                            commitRingtone(def = it)
                        },
                    )
                }

                val ringtoneSaveInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        viewModel.createRingtoneFromSelection(
                            startFrac = startFrac,
                            endFrac = endFrac,
                            title = ringtoneTitle,
                            setAsDefault = setAsDefault,
                            onNeedsWriteSettings = {
                                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    interactionSource = ringtoneSaveInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Emerald, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .lit(ringtoneSaveInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("ringtone_save_button"),
                ) {
                    Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (lang == AppLanguage.EN) "Save & Set Ringtone" else "حفظ وتعيين النغمة")
                }
            }
        }
    }
}
