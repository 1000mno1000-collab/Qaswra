package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.model.TTS_DIALECTS
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsGender
import com.example.model.VOICE_CHARACTER_PRESETS
import com.example.ui.components.ExportFormatDialog
import com.example.ui.components.SaveDraftDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioScrollColumn
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AudioEditorScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val durationMs = uiState.durationMs.coerceAtLeast(0L)
    val startMs = (durationMs * uiState.trimStart).roundToInt().toLong()
    val endMs = (durationMs * uiState.trimEnd).roundToInt().toLong()
    val selLenMs = (endMs - startMs).coerceAtLeast(0L)

    var fadeInSec by remember { mutableFloatStateOf(2.0f) }
    var fadeOutSec by remember { mutableFloatStateOf(2.5f) }
    var mainMixGain by remember { mutableFloatStateOf(1.0f) }
    var bgMixGain by remember { mutableFloatStateOf(0.45f) }

    var showVoiceCard by remember { mutableStateOf(false) }
    var selectedCharId by remember { mutableStateOf<String?>(null) }
    var charPitch by remember { mutableFloatStateOf(0f) }
    var charFormant by remember { mutableFloatStateOf(1.0f) }

    var showTtsCard by remember { mutableStateOf(false) }
    var ttsText by remember { mutableStateOf("") }
    var selectedDialectId by remember { mutableStateOf(TTS_DIALECTS.first().id) }

    var exportDialogOpen by remember { mutableStateOf(false) }
    var saveDraftDialogOpen by remember { mutableStateOf(false) }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_editor_title"),
            subtitle = "${formatDurationMs(startMs)} -> ${formatDurationMs(endMs)} (${formatDurationMs(selLenMs)})",
            language = lang,
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { viewModel.undoLastEdit() },
                        enabled = uiState.canUndoEdit,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.Cyan,
                            disabledContentColor = StudioColors.TextMuted.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("editor_undo_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = t(lang, "undo_edit"))
                    }
                    IconButton(
                        onClick = { viewModel.redoLastEdit() },
                        enabled = uiState.canRedoEdit,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.Cyan,
                            disabledContentColor = StudioColors.TextMuted.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("editor_redo_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = t(lang, "redo_edit"))
                    }
                }
            },
        )

        // Interactive Waveform Trim & Selection Canvas
        Card(
            shape = RoundedCornerShape(22.dp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FilterChip(
                        selected = uiState.loopSelection,
                        onClick = { viewModel.toggleLoopSelection() },
                        leadingIcon = {
                            Icon(Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(t(lang, "loop_selection")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Emerald.copy(alpha = 0.2f),
                            selectedLabelColor = StudioColors.Emerald,
                        ),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_loop_selection_chip"),
                    )

                    Button(
                        onClick = { viewModel.saveEditsPermanent() },
                        enabled = uiState.hasAudio,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.Cyan,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_save_permanent_button"),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "save_changes"))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(StudioColors.BgTop)
                        .border(1.dp, StudioColors.BorderBright.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .pointerInput(uiState.trimStart, uiState.trimEnd, uiState.hasAudio) {
                            if (!uiState.hasAudio) return@pointerInput
                            detectTapGestures { offset ->
                                val nx = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val distStart = abs(nx - uiState.trimStart)
                                val distEnd = abs(nx - uiState.trimEnd)
                                if (distStart <= distEnd) {
                                    viewModel.updateTrimRange(nx, uiState.trimEnd)
                                } else {
                                    viewModel.updateTrimRange(uiState.trimStart, nx)
                                }
                            }
                        }
                        .pointerInput(uiState.hasAudio) {
                            if (!uiState.hasAudio) return@pointerInput
                            var draggingStart = true
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val nx = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val st = viewModel.uiState.value
                                    draggingStart = abs(nx - st.trimStart) <= abs(nx - st.trimEnd)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val nx = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    val st = viewModel.uiState.value
                                    if (draggingStart) {
                                        viewModel.updateTrimRange(nx, st.trimEnd)
                                    } else {
                                        viewModel.updateTrimRange(st.trimStart, nx)
                                    }
                                },
                            )
                        }
                        .testTag("editor_waveform_canvas"),
                ) {
                    val peaks = if (uiState.waveformPeaks.isNotEmpty()) {
                        uiState.waveformPeaks
                    } else {
                        List(80) { 0.08f }
                    }
                    val playFrac = if (durationMs > 0L) {
                        (uiState.playback.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sx = uiState.trimStart * size.width
                        val ex = uiState.trimEnd * size.width

                        // Selection tint highlight box
                        drawRect(
                            color = StudioColors.Cyan.copy(alpha = 0.12f),
                            topLeft = Offset(sx, 0f),
                            size = Size((ex - sx).coerceAtLeast(1f), size.height),
                        )

                        // Waveform bars
                        val count = peaks.size
                        val stepW = size.width / count
                        val barW = (stepW * 0.6f).coerceAtLeast(2.dp.toPx())
                        val cy = size.height / 2f

                        for (i in 0 until count) {
                            val barFrac = (i + 0.5f) / count
                            val inSelection = barFrac in uiState.trimStart..uiState.trimEnd
                            val h = peaks[i].coerceIn(0.06f, 1f) * size.height * 0.84f
                            val x = i * stepW + (stepW - barW) / 2f
                            drawRoundRect(
                                color = if (inSelection) StudioColors.Cyan else StudioColors.WaveformInactive.copy(alpha = 0.5f),
                                topLeft = Offset(x, cy - h / 2f),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                            )
                        }

                        // Playhead cursor
                        val px = playFrac * size.width
                        drawLine(
                            color = StudioColors.Rose,
                            start = Offset(px, 0f),
                            end = Offset(px, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )

                        // Start handle (Emerald)
                        drawLine(
                            color = StudioColors.Emerald,
                            start = Offset(sx, 0f),
                            end = Offset(sx, size.height),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                        drawCircle(
                            color = StudioColors.Emerald,
                            radius = 7.dp.toPx(),
                            center = Offset(sx, 14.dp.toPx()),
                        )

                        // End handle (Amber)
                        drawLine(
                            color = StudioColors.Amber,
                            start = Offset(ex, 0f),
                            end = Offset(ex, size.height),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                        drawCircle(
                            color = StudioColors.Amber,
                            radius = 7.dp.toPx(),
                            center = Offset(ex, size.height - 14.dp.toPx()),
                        )
                    }
                }

                // 4 Trim / Cut / Select All / Normalize Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.trimKeepSelection() },
                        enabled = uiState.hasAudio,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_trim_keep_button"),
                    ) {
                        Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t(lang, "trim_keep"), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.cutSelectedRange() },
                        enabled = uiState.hasAudio,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_cut_button"),
                    ) {
                        Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t(lang, "cut_selection"), maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetSelection() },
                        enabled = uiState.hasAudio,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_select_all_button"),
                    ) {
                        Icon(Icons.Rounded.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t(lang, "reset_selection"), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.normalizeAudioBuffer() },
                        enabled = uiState.hasAudio,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_normalize_button"),
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t(lang, "normalize_audio"), maxLines = 1)
                    }
                }
            }
        }

        // Clip Gain Card
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
                StudioParamSlider(
                    label = t(lang, "clip_volume"),
                    value = uiState.clipGainDb,
                    min = -24f,
                    max = 18f,
                    formattedValue = "${if (uiState.clipGainDb >= 0f) "+" else ""}${"%.1f".format(uiState.clipGainDb)} dB",
                    accentColor = StudioColors.Amber,
                    testTag = "editor_clip_gain_slider",
                    onValueChange = { viewModel.setClipGainDb(it) },
                )
                Button(
                    onClick = { viewModel.bakeClipGainDb(uiState.clipGainDb) },
                    enabled = uiState.hasAudio && abs(uiState.clipGainDb) > 0.05f,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Amber,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_apply_clip_gain_button"),
                ) {
                    Text(if (lang == AppLanguage.EN) "Apply Clip Gain" else "تطبيق مستوى الصوت على المقطع")
                }
            }
        }

        // Fade In & Fade Out Card
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
                StudioParamSlider(
                    label = t(lang, "fade_in_title"),
                    value = fadeInSec,
                    min = 0.5f,
                    max = 10f,
                    formattedValue = "${"%.1f".format(fadeInSec)} s",
                    accentColor = StudioColors.Emerald,
                    testTag = "editor_fade_in_slider",
                    onValueChange = { fadeInSec = it },
                )
                OutlinedButton(
                    onClick = { viewModel.applyFadeInSeconds(fadeInSec) },
                    enabled = uiState.hasAudio,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_apply_fade_in_button"),
                ) {
                    Text(t(lang, "fade_in_title"))
                }

                StudioParamSlider(
                    label = t(lang, "fade_out_title"),
                    value = fadeOutSec,
                    min = 0.5f,
                    max = 10f,
                    formattedValue = "${"%.1f".format(fadeOutSec)} s",
                    accentColor = StudioColors.Violet,
                    testTag = "editor_fade_out_slider",
                    onValueChange = { fadeOutSec = it },
                )
                OutlinedButton(
                    onClick = { viewModel.applyFadeOutSeconds(fadeOutSec) },
                    enabled = uiState.hasAudio,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_apply_fade_out_button"),
                ) {
                    Text(t(lang, "fade_out_title"))
                }
            }
        }

        // Dual-Track Mix Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Layers, contentDescription = null, tint = StudioColors.Violet)
                        Text(
                            text = t(lang, "mix_tracks_title"),
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioColors.TextPrimary,
                        )
                    }
                    if (uiState.mixBgName != null) {
                        IconButton(
                            onClick = { viewModel.clearMixBackgroundTrack() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("editor_clear_mix_bg_button"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = t(lang, "delete"), tint = StudioColors.RoseBright)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onOpenPicker(AudioPickerTarget.MIX_BACKGROUND) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_pick_mix_bg_button"),
                ) {
                    Text(
                        text = uiState.mixBgName?.let { "$it (${formatDurationMs(uiState.mixBgDurationMs)})" }
                            ?: if (lang == AppLanguage.EN) "Select Background Track" else "استيراد مسار الخلفية للمزج",
                    )
                }

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Main Track Level" else "مستوى الصوت الرئيسي",
                    value = mainMixGain,
                    min = 0f,
                    max = 1.5f,
                    formattedValue = "${(mainMixGain * 100f).roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "editor_mix_main_gain",
                    onValueChange = { mainMixGain = it },
                )
                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Background Track Level" else "مستوى مسار الخلفية",
                    value = bgMixGain,
                    min = 0f,
                    max = 1.5f,
                    formattedValue = "${(bgMixGain * 100f).roundToInt()}%",
                    accentColor = StudioColors.Violet,
                    testTag = "editor_mix_bg_gain",
                    onValueChange = { bgMixGain = it },
                )

                Button(
                    onClick = { viewModel.mixWithBackgroundTrack(mainMixGain, bgMixGain) },
                    enabled = uiState.hasAudio && uiState.mixBgName != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Violet,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_apply_mix_button"),
                ) {
                    Text(if (lang == AppLanguage.EN) "Mix Tracks Together" else "دمج ومزج المسارين")
                }
            }
        }

        // Expandable Inline Voice Character Transformer Card
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
                Surface(
                    onClick = { showVoiceCard = !showVoiceCard },
                    color = StudioColors.CardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_expand_voice_transformer"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.SpatialAudioOff, contentDescription = null, tint = StudioColors.Violet)
                            Text(t(lang, "section_voice_transformer_title"), style = MaterialTheme.typography.titleSmall)
                        }
                        Icon(
                            imageVector = if (showVoiceCard) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = StudioColors.TextSecondary,
                        )
                    }
                }

                if (showVoiceCard) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VOICE_CHARACTER_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = selectedCharId == preset.id,
                                onClick = {
                                    selectedCharId = preset.id
                                    charPitch = preset.pitchSemitones
                                    charFormant = preset.formantShift
                                },
                                label = { Text(t(lang, preset.titleKey)) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }

                    StudioParamSlider(
                        label = t(lang, "voice_pitch_semitones"),
                        value = charPitch,
                        min = -8f,
                        max = 8f,
                        formattedValue = "${"%.1f".format(charPitch)} st",
                        accentColor = StudioColors.Violet,
                        testTag = "editor_char_pitch_slider",
                        onValueChange = { charPitch = it },
                    )
                    StudioParamSlider(
                        label = t(lang, "voice_formant_shift"),
                        value = charFormant,
                        min = 0.72f,
                        max = 1.4f,
                        formattedValue = "${"%.2f".format(charFormant)}x",
                        accentColor = StudioColors.Cyan,
                        testTag = "editor_char_formant_slider",
                        onValueChange = { charFormant = it },
                    )

                    val chosen = VOICE_CHARACTER_PRESETS.firstOrNull { it.id == selectedCharId }
                    Button(
                        onClick = {
                            viewModel.transformVoiceOffline(
                                pitchSemitones = charPitch,
                                formantShift = charFormant,
                                warmthDb = chosen?.warmthDb ?: 0f,
                                presenceDb = chosen?.presenceDb ?: 0f,
                                mix = 1f,
                                characterLabel = chosen?.let { t(lang, it.titleKey) }
                                    ?: t(lang, "section_voice_transformer_title"),
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Violet, contentColor = StudioColors.BgTop),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_apply_voice_char_button"),
                    ) {
                        Text(t(lang, "voice_apply_transform"))
                    }
                }
            }
        }

        // Expandable Inline 10-Dialect TTS Card
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
                Surface(
                    onClick = { showTtsCard = !showTtsCard },
                    color = StudioColors.CardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_expand_tts"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = StudioColors.Cyan)
                            Text(t(lang, "section_tts_title"), style = MaterialTheme.typography.titleSmall)
                        }
                        Icon(
                            imageVector = if (showTtsCard) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = StudioColors.TextSecondary,
                        )
                    }
                }

                if (showTtsCard) {
                    val dialect = TTS_DIALECTS.first { it.id == selectedDialectId }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TTS_DIALECTS.forEach { d ->
                            FilterChip(
                                selected = d.id == selectedDialectId,
                                onClick = {
                                    selectedDialectId = d.id
                                    if (ttsText.isBlank()) ttsText = d.sampleText
                                },
                                label = { Text(t(lang, d.titleKey)) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = ttsText,
                        onValueChange = { ttsText = it },
                        label = { Text(t(lang, "tts_input_label")) },
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Cyan,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_tts_input"),
                    )

                    Button(
                        onClick = {
                            viewModel.generateTtsToStudio(
                                text = ttsText.ifBlank { dialect.sampleText },
                                dialect = dialect,
                                gender = TtsGender.MALE,
                                style = TtsDeliveryStyle.NATURAL_WARM,
                                speechRate = dialect.defaultRate,
                                pitch = dialect.defaultPitch,
                                appendToExisting = uiState.hasAudio,
                            )
                        },
                        enabled = !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("editor_tts_insert_button"),
                    ) {
                        Text(t(lang, "tts_insert_button"))
                    }
                }
            }
        }

        // Append File + Append Saved Projects + Export
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
                OutlinedButton(
                    onClick = { onOpenPicker(AudioPickerTarget.APPEND_CLIP) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_append_file_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t(lang, "merge_clip"))
                }

                if (uiState.projects.any { it.hasAudio }) {
                    Text(
                        text = if (lang == AppLanguage.EN) "Append from Saved Projects:" else "إلحاق من المشاريع المحفوظة:",
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
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("editor_append_project_${proj.id}"),
                            )
                        }
                    }
                }

                uiState.exportProgress?.let { prog ->
                    LinearProgressIndicator(
                        progress = { prog.coerceIn(0f, 1f) },
                        color = StudioColors.Cyan,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                OutlinedButton(
                    onClick = { saveDraftDialogOpen = true },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Violet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("editor_save_draft_button"),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "save_to_drafts"), style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = { exportDialogOpen = true },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Cyan,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("editor_open_export_dialog_button"),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "save_export_final"), style = MaterialTheme.typography.labelLarge)
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

    ExportFormatDialog(
        open = exportDialogOpen,
        language = lang,
        initialFileName = uiState.activeProjectName.ifBlank { "Sonix_Master" },
        onDismiss = { exportDialogOpen = false },
        onSaveToDevice = { fmt, name -> viewModel.exportRenderedAudio(fmt, name, shareAfter = false) },
        onShareAudio = { fmt, name -> viewModel.exportRenderedAudio(fmt, name, shareAfter = true) },
        onShareToPackage = { fmt, name, pkg -> viewModel.exportRenderedAudio(fmt, name, shareAfter = true, targetPackage = pkg) },
    )
}
