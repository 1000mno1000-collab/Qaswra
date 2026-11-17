package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.model.EffectType
import com.example.model.ExportAudioFormat
import com.example.ui.components.SaveDraftDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioPreviewControlsBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

@Composable
private fun ActiveTrackPromptCard(
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
                if (uiState.canUndoEdit) {
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                    ) {
                        Text(t(lang, "undo_edit"))
                    }
                }
            }
            OutlinedButton(
                onClick = onPickMainAudio,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioColors.Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
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
    val lang = uiState.language
    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_merge_audio"),
            subtitle = if (lang == AppLanguage.EN) "Join multiple clips seamlessly with crossfade" else "إلحاق ودمج عدة مقاطع صوتية بسلاسة عالية",
            language = lang,
            onBack = onBack,
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
                Button(
                    onClick = { onOpenPicker(AudioPickerTarget.APPEND_CLIP) },
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("merge_append_library_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "merge_clip"))
                }

                if (uiState.projects.any { it.hasAudio }) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Join from Saved Projects:" else "أو إلحاق مشروع محفوظ مباشرة:",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.projects.filter { it.hasAudio }.forEach { proj ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.appendSavedProjectToCurrent(proj) },
                                label = { Text("${proj.name} (${formatDurationMs(proj.durationMs)})") },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }
                }
            }
        }
    }
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

    val splitMs = (uiState.durationMs * splitPoint).roundToInt().toLong()

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_split_tracks"),
            subtitle = if (lang == AppLanguage.EN) "Split by timeline point or isolate Stereo L/R channels" else "تقسيم المقطع زمنياً أو فصل قنوات الستيريو (يمين / يسار)",
            language = lang,
            onBack = onBack,
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
                    onValueChange = { splitPoint = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = loadPart == 1,
                        onClick = { loadPart = 1 },
                        label = { Text(if (lang == AppLanguage.EN) "Open Part 1" else "فتح الجزء الأول") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = loadPart == 2,
                        onClick = { loadPart = 2 },
                        label = { Text(if (lang == AppLanguage.EN) "Open Part 2" else "فتح الجزء الثاني") },
                        modifier = Modifier.weight(1f),
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

                Button(
                    onClick = { viewModel.splitAudioAtFraction(splitPoint, loadPart, saveBothToLibrary) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
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
                    OutlinedButton(
                        onClick = { viewModel.splitStereoChannel(keepLeft = true) },
                        enabled = uiState.hasAudio,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(if (lang == AppLanguage.EN) "Isolate Left (L)" else "عزل القناة اليسرى")
                    }
                    OutlinedButton(
                        onClick = { viewModel.splitStereoChannel(keepLeft = false) },
                        enabled = uiState.hasAudio,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
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

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_pitch_speed"),
            subtitle = if (lang == AppLanguage.EN) "Adjust pitch semitones & tempo independently" else "تعديل حدة الطبقة الصوتية وسرعة الإيقاع بدقة",
            language = lang,
            onBack = onBack,
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
                    },
                )

                Button(
                    onClick = { viewModel.applyPitchAndSpeed(semitones, tempo) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("pitch_speed_bake_button"),
                ) {
                    Icon(Icons.Rounded.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (lang == AppLanguage.EN) "Render Pitch & Speed into Clip" else "تثبيت الطبقة والسرعة في المقطع")
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
    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_reverse_audio"),
            subtitle = if (lang == AppLanguage.EN) "Reverse audio playback cleanly with Undo support" else "عكس اتجاه المقطع الصوتي مع دعم التراجع الفوري",
            language = lang,
            onBack = onBack,
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

        Button(
            onClick = { viewModel.applyReverseAudio() },
            enabled = uiState.hasAudio && !uiState.isBusy,
            colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .testTag("reverse_apply_button"),
        ) {
            Icon(Icons.Rounded.Undo, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(t(lang, "tool_reverse_audio"), style = MaterialTheme.typography.labelLarge)
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
    var midLevel by remember { mutableFloatStateOf(1.0f) }
    var sideLevel by remember { mutableFloatStateOf(1.0f) }
    var stereoWidth by remember { mutableFloatStateOf(0f) }
    var saveDraftDialogOpen by remember { mutableStateOf(false) }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_stereo_surround"),
            subtitle = if (lang == AppLanguage.EN) "Convert mono or narrow clips into wide spatial stereo" else "توسيع المجال الصوتي M/S وتحويل المونو إلى ستيريو محيطي",
            language = lang,
            onBack = onBack,
        )

        ActiveTrackPromptCard(uiState = uiState, onPickMainAudio = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) })

        StudioPreviewControlsBar(
            uiState = uiState,
            onHoldBypassChange = { viewModel.setHoldBypass(it) },
            onToggleBypass = { viewModel.toggleBypass() },
            onUndo = { viewModel.undoLastEdit() },
            onRedo = { viewModel.redoLastEdit() },
            onResetZero = {
                midLevel = 1.0f
                sideLevel = 1.0f
                stereoWidth = 0f
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
                    label = if (lang == AppLanguage.EN) "Mid Center Level" else "مستوى المنتصف (Mid)",
                    value = midLevel,
                    min = 0.4f,
                    max = 1.6f,
                    formattedValue = "${(midLevel * 100f).roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "stereo_mid_slider",
                    onValueChange = { midLevel = it },
                )
                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Side Surround Level" else "مستوى الجوانب المحيطية (Side)",
                    value = sideLevel,
                    min = 0.5f,
                    max = 2.2f,
                    formattedValue = "${(sideLevel * 100f).roundToInt()}%",
                    accentColor = StudioColors.Violet,
                    testTag = "stereo_side_slider",
                    onValueChange = { sideLevel = it },
                )
                StudioParamSlider(
                    label = t(lang, "param_stereo_width"),
                    value = stereoWidth,
                    min = 0f,
                    max = 1.0f,
                    formattedValue = "${(stereoWidth * 100f).roundToInt()}%",
                    accentColor = StudioColors.Emerald,
                    testTag = "stereo_width_slider",
                    onValueChange = { stereoWidth = it },
                )

                Button(
                    onClick = { viewModel.applySurroundStereo(midLevel, sideLevel, stereoWidth) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("stereo_apply_button"),
                ) {
                    Icon(Icons.Rounded.SurroundSound, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "apply_now"))
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

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_generate_silence"),
            subtitle = if (lang == AppLanguage.EN) "Create exact silent tracks or insert pauses" else "إنشاء مسار صمت نقي أو إضافته كوقفة زمنية للمشروع",
            language = lang,
            onBack = onBack,
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
                    onValueChange = { durationSec = it },
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
                        Switch(checked = appendMode, onCheckedChange = { appendMode = it })
                    }
                }

                Button(
                    onClick = { viewModel.insertGeneratedSilence(durationSec, appendMode) },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
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

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_format_convert"),
            subtitle = if (lang == AppLanguage.EN) "Convert between MP3, M4A, WAV, FLAC, and OGG" else "تحويل الصيغ بين MP3 و M4A و WAV و FLAC و OGG",
            language = lang,
            onBack = onBack,
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
                    onValueChange = { fileName = it },
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
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportAudioFormat.entries.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = { selectedFormat = fmt },
                            label = { Text(fmt.displayName) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
                    Button(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, fileName, shareAfter = false) },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("convert_save_button"),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "export_and_save"))
                    }

                    OutlinedButton(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, fileName, shareAfter = true) },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
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
    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.M4A) }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.extractAudioFromVideoUri(uri)
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_extract_video"),
            subtitle = if (lang == AppLanguage.EN) "Extract high-fidelity audio from any video" else "استخراج المسار الصوتي عالي الدقة من أي فيديو فوراً",
            language = lang,
            onBack = onBack,
        )

        Button(
            onClick = { videoPicker.launch(arrayOf("video/*")) },
            enabled = !uiState.isBusy,
            colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
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
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(ExportAudioFormat.M4A, ExportAudioFormat.WAV, ExportAudioFormat.MP3).forEach { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick = { selectedFormat = fmt },
                                label = { Text(fmt.displayName) },
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.exportRenderedAudio(selectedFormat, uiState.activeProjectName, shareAfter = false) },
                        enabled = !uiState.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
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
fun VideoAudioEditorScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var mixWithOriginal by remember { mutableStateOf(false) }
    var origGain by remember { mutableFloatStateOf(0.45f) }
    var newGain by remember { mutableFloatStateOf(1.0f) }
    var outputName by remember { mutableStateOf("Sonix_Video_Edit") }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) selectedVideoUri = uri
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_video_audio_edit"),
            subtitle = if (lang == AppLanguage.EN) "Dub or mix studio audio onto video losslessly" else "دمج أو استبدال صوت الفيديو بصوت الاستوديو بدون فقدان جودة الصورة",
            language = lang,
            onBack = onBack,
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
                OutlinedButton(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("video_edit_pick_video_button"),
                ) {
                    Icon(Icons.Rounded.Movie, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selectedVideoUri?.lastPathSegment ?: t(lang, "audio_tools_pick_video"),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Mix with original video audio" else "مزج مع صوت الفيديو الأصلي (دبلجة)",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = mixWithOriginal, onCheckedChange = { mixWithOriginal = it })
                }

                if (mixWithOriginal) {
                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "Original Video Audio Level" else "مستوى صوت الفيديو الأصلي",
                        value = origGain,
                        min = 0f,
                        max = 1.5f,
                        formattedValue = "${(origGain * 100f).roundToInt()}%",
                        accentColor = StudioColors.Violet,
                        testTag = "video_orig_gain_slider",
                        onValueChange = { origGain = it },
                    )
                }

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Studio Audio Level" else "مستوى صوت الاستوديو المضاف",
                    value = newGain,
                    min = 0.2f,
                    max = 1.5f,
                    formattedValue = "${(newGain * 100f).roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "video_new_gain_slider",
                    onValueChange = { newGain = it },
                )

                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    singleLine = true,
                    label = { Text(if (lang == AppLanguage.EN) "Output Video Name" else "اسم الفيديو الناتج") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

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

                Button(
                    onClick = {
                        selectedVideoUri?.let { uri ->
                            viewModel.exportVideoWithEditedAudio(
                                videoUri = uri,
                                mixWithOriginal = mixWithOriginal,
                                originalGain = origGain,
                                newGain = newGain,
                                outputName = outputName,
                                shareAfter = false,
                            )
                        }
                    },
                    enabled = selectedVideoUri != null && uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("video_edit_export_button"),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "export_and_save"))
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
    val estKb = ((uiState.durationMs / 1000.0) * bitrateKbps / 8.0).roundToInt().coerceAtLeast(1)

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_compress_audio"),
            subtitle = if (lang == AppLanguage.EN) "Reduce audio file size smoothly with AAC compression" else "ضغط وتقليل حجم الملف الصوتي مع الحفاظ على نقاء الصوت",
            language = lang,
            onBack = onBack,
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
                        FilterChip(
                            selected = bitrateKbps == br,
                            onClick = { bitrateKbps = br },
                            label = { Text("${br}k") },
                            modifier = Modifier.weight(1f),
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
                    onValueChange = { fileName = it },
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

                Button(
                    onClick = { viewModel.compressAndExportAudio(bitrateKbps, fileName, shareAfter = false) },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
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

    val clipLenMs = ((endFrac - startFrac).coerceAtLeast(0.02f) * uiState.durationMs).roundToInt().toLong()

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_ringtone_maker"),
            subtitle = if (lang == AppLanguage.EN) "Cut custom ringtones and assign to device" else "قص وتخصيص نغمات الرنين وحفظها وتعيينها للهاتف",
            language = lang,
            onBack = onBack,
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
                    onValueChange = { ringtoneTitle = it },
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
                    onValueChange = { startFrac = it },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "End Point" else "نهاية النغمة",
                    value = endFrac,
                    min = (startFrac + 0.05f).coerceAtMost(0.99f),
                    max = 1f,
                    formattedValue = formatDurationMs((uiState.durationMs * endFrac).roundToInt().toLong()),
                    accentColor = StudioColors.Emerald,
                    testTag = "ringtone_end_slider",
                    onValueChange = { endFrac = it },
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
                    Switch(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                }

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
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Emerald, contentColor = StudioColors.BgTop),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
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
