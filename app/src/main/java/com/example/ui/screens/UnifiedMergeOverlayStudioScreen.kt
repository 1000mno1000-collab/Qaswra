package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.MergeStudioMode
import com.example.audio.MergeStudioParams
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.studio.ui.lit
import com.example.ui.components.ExportFormatDialog
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
fun UnifiedMergeOverlayStudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    initialMode: MergeStudioMode = MergeStudioMode.SEQUENTIAL,
    initialAutoDucking: Boolean = false,
    toolId: String = "merge_studio",
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var params by remember(toolId) {
        mutableStateOf(
            MergeStudioParams(
                mode = initialMode,
                autoDuckingEnabled = initialAutoDucking,
                duckingStrengthPct = if (initialAutoDucking) 65f else 0f,
            ),
        )
    }

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession(toolId)
        if (restored != null) {
            val modeName = restored.stringParams["mode"]
            val mode = runCatching { MergeStudioMode.valueOf(modeName ?: initialMode.name) }
                .getOrDefault(initialMode)
            val fp = restored.floatParams
            params = MergeStudioParams(
                mode = mode,
                crossfadeSec = fp["crossfadeSec"] ?: params.crossfadeSec,
                overlayFadeInSec = fp["overlayFadeInSec"] ?: params.overlayFadeInSec,
                overlayFadeOutSec = fp["overlayFadeOutSec"] ?: params.overlayFadeOutSec,
                overlayStartOffsetSec = fp["overlayStartOffsetSec"] ?: params.overlayStartOffsetSec,
                primaryLevelPct = fp["primaryLevelPct"] ?: params.primaryLevelPct,
                secondaryLevelPct = fp["secondaryLevelPct"] ?: params.secondaryLevelPct,
                autoDuckingEnabled = (fp["autoDuckingEnabled"] ?: 0f) > 0.5f,
                duckingStrengthPct = fp["duckingStrengthPct"] ?: params.duckingStrengthPct,
                autoLevelingEnabled = (fp["autoLevelingEnabled"] ?: 0f) > 0.5f,
                levelingStrengthPct = fp["levelingStrengthPct"] ?: params.levelingStrengthPct,
                echoTransitionEnabled = (fp["echoTransitionEnabled"] ?: 0f) > 0.5f,
                echoTransitionPct = fp["echoTransitionPct"] ?: params.echoTransitionPct,
                gapWhooshEnabled = (fp["gapWhooshEnabled"] ?: 0f) > 0.5f,
                transitionGapSec = fp["transitionGapSec"] ?: params.transitionGapSec,
                whooshIntensityPct = fp["whooshIntensityPct"] ?: params.whooshIntensityPct,
            )
        }
    }

    val latestParams by rememberUpdatedState(params)
    DisposableEffect(toolId) {
        onDispose {
            viewModel.stopToolPreview()
            val cur = latestParams
            viewModel.saveToolSessionToCache(
                toolId = toolId,
                titleAr = if (toolId == "smart_mixer") "ميكس الموسيقى والدمج" else "استوديو الدمج والتراكب",
                titleEn = if (toolId == "smart_mixer") "Smart Mixer & Overlay" else "Merge & Overlay Studio",
                summaryAr = when (cur.mode) {
                    MergeStudioMode.SEQUENTIAL -> "دمج متتالي"
                    MergeStudioMode.CROSSFADE -> "تداخل ${"%.1f".format(cur.crossfadeSec)}s"
                    MergeStudioMode.OVERLAY -> "تراكب حر"
                },
                summaryEn = when (cur.mode) {
                    MergeStudioMode.SEQUENTIAL -> "Sequential"
                    MergeStudioMode.CROSSFADE -> "Crossfade ${"%.1f".format(cur.crossfadeSec)}s"
                    MergeStudioMode.OVERLAY -> "Free Overlay"
                },
                floatParams = mapOf(
                    "crossfadeSec" to cur.crossfadeSec,
                    "overlayFadeInSec" to cur.overlayFadeInSec,
                    "overlayFadeOutSec" to cur.overlayFadeOutSec,
                    "overlayStartOffsetSec" to cur.overlayStartOffsetSec,
                    "primaryLevelPct" to cur.primaryLevelPct,
                    "secondaryLevelPct" to cur.secondaryLevelPct,
                    "autoDuckingEnabled" to if (cur.autoDuckingEnabled) 1f else 0f,
                    "duckingStrengthPct" to cur.duckingStrengthPct,
                    "autoLevelingEnabled" to if (cur.autoLevelingEnabled) 1f else 0f,
                    "levelingStrengthPct" to cur.levelingStrengthPct,
                    "echoTransitionEnabled" to if (cur.echoTransitionEnabled) 1f else 0f,
                    "echoTransitionPct" to cur.echoTransitionPct,
                    "gapWhooshEnabled" to if (cur.gapWhooshEnabled) 1f else 0f,
                    "transitionGapSec" to cur.transitionGapSec,
                    "whooshIntensityPct" to cur.whooshIntensityPct,
                ),
                stringParams = mapOf("mode" to cur.mode.name),
            )
        }
    }

    val paramHistory = rememberEditHistory(params)
    var preferVmUndo by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var infoDialogOpen by remember { mutableStateOf(false) }

    var expandDucking by remember { mutableStateOf(initialAutoDucking) }
    var expandLeveling by remember { mutableStateOf(false) }
    var expandEchoBeat by remember { mutableStateOf(false) }
    var expandGapWhoosh by remember { mutableStateOf(false) }

    val hasPrimary = uiState.hasAudio
    val hasSecondary = !uiState.mixBgName.isNullOrBlank()
    val canProcess = hasPrimary && hasSecondary

    fun updateParams(debounceMs: Long = 0L, transform: (MergeStudioParams) -> MergeStudioParams) {
        preferVmUndo = false
        val next = transform(params)
        params = next
        paramHistory.commit(next, debounceMs = debounceMs)
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "merge_studio_title"),
            subtitle = t(lang, "merge_studio_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = paramHistory.canUndo || uiState.canUndoEdit,
                    canRedo = paramHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "merge_studio_undo_button",
                    redoTag = "merge_studio_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !paramHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            paramHistory.undo()?.let { params = it }
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !paramHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            paramHistory.redo()?.let { params = it }
                        }
                    },
                    extraTrailing = {
                        InfoBadgeButton(
                            contentDescription = t(lang, "merge_studio_title"),
                            testTag = "merge_studio_info_badge",
                            accentColor = StudioColors.Cyan,
                            onClick = { infoDialogOpen = true },
                        )
                    },
                )
            },
        )

        // Track Cards: Primary + Secondary
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, if (hasPrimary) StudioColors.Cyan.copy(alpha = 0.45f) else StudioColors.Border),
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = StudioColors.Cyan)
                        Text(
                            text = if (hasPrimary) uiState.activeProjectName else t(lang, "no_audio_loaded"),
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hasPrimary) {
                        Text(
                            text = formatDurationMs(uiState.durationMs),
                            style = TelemetryTextStyle,
                            color = StudioColors.Cyan,
                        )
                    }
                }

                val pickPrimaryInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) },
                    interactionSource = pickPrimaryInteraction,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(pickPrimaryInteraction, StudioColors.Cyan, cornerRadius = 12.dp)
                        .testTag("merge_studio_pick_primary_button"),
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "audio_tools_pick_source"))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, if (hasSecondary) StudioColors.Amber.copy(alpha = 0.55f) else StudioColors.Border),
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = StudioColors.Amber)
                        Text(
                            text = uiState.mixBgName ?: t(lang, "smart_mixer_music_track"),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (hasSecondary) StudioColors.Amber else StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hasSecondary) {
                        Text(
                            text = formatDurationMs(uiState.mixBgDurationMs),
                            style = TelemetryTextStyle,
                            color = StudioColors.TextSecondary,
                        )
                        IconButton(
                            onClick = { viewModel.clearMixBackgroundTrack() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("merge_studio_clear_secondary_button"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = t(lang, "delete"), tint = StudioColors.RoseBright)
                        }
                    }
                }

                val pickSecInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { onOpenPicker(AudioPickerTarget.MIX_BACKGROUND) },
                    interactionSource = pickSecInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Amber.copy(alpha = 0.2f),
                        contentColor = StudioColors.Amber,
                    ),
                    border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(pickSecInteraction, StudioColors.Amber, cornerRadius = 12.dp)
                        .testTag("merge_studio_pick_secondary_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "smart_mixer_pick_music"))
                }

                if (uiState.projects.any { it.hasAudio }) {
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
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            )
                        }
                    }
                }
            }
        }

        // Mode Selector & Core Controls
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val modes = listOf(
                        MergeStudioMode.SEQUENTIAL to t(lang, "merge_mode_sequential"),
                        MergeStudioMode.CROSSFADE to t(lang, "merge_mode_crossfade"),
                        MergeStudioMode.OVERLAY to t(lang, "merge_mode_overlay"),
                    )
                    modes.forEach { (mode, label) ->
                        val selected = params.mode == mode
                        val chipInteraction = remember { MutableInteractionSource() }
                        FilterChip(
                            selected = selected,
                            onClick = { updateParams { it.copy(mode = mode) } },
                            interactionSource = chipInteraction,
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.2f),
                                selectedLabelColor = StudioColors.Cyan,
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(chipInteraction, StudioColors.Cyan, selected = selected, cornerRadius = 10.dp)
                                .testTag("merge_mode_${mode.name.lowercase()}"),
                        )
                    }
                }

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Primary Clip Balance" else "توازن المقطع الأول",
                    value = params.primaryLevelPct,
                    min = -60f,
                    max = 60f,
                    formattedValue = "${if (params.primaryLevelPct > 0f) "+" else ""}${params.primaryLevelPct.roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "merge_primary_level_slider",
                    onValueChange = { v -> updateParams(300L) { it.copy(primaryLevelPct = v) } },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Secondary Clip Balance" else "توازن المقطع الثاني",
                    value = params.secondaryLevelPct,
                    min = -60f,
                    max = 60f,
                    formattedValue = "${if (params.secondaryLevelPct > 0f) "+" else ""}${params.secondaryLevelPct.roundToInt()}%",
                    accentColor = StudioColors.Amber,
                    testTag = "merge_secondary_level_slider",
                    onValueChange = { v -> updateParams(300L) { it.copy(secondaryLevelPct = v) } },
                )

                if (params.mode == MergeStudioMode.CROSSFADE) {
                    StudioParamSlider(
                        label = t(lang, "merge_crossfade_duration"),
                        value = params.crossfadeSec,
                        min = 0.1f,
                        max = 15.0f,
                        formattedValue = "${"%.2f".format(params.crossfadeSec)} s",
                        accentColor = StudioColors.Emerald,
                        testTag = "merge_crossfade_slider",
                        onValueChange = { v -> updateParams(300L) { it.copy(crossfadeSec = v) } },
                    )
                }

                if (params.mode == MergeStudioMode.OVERLAY) {
                    StudioParamSlider(
                        label = t(lang, "merge_fade_in"),
                        value = params.overlayFadeInSec,
                        min = 0f,
                        max = 15f,
                        formattedValue = "${"%.2f".format(params.overlayFadeInSec)} s",
                        accentColor = StudioColors.Emerald,
                        testTag = "merge_fade_in_slider",
                        onValueChange = { v -> updateParams(300L) { it.copy(overlayFadeInSec = v) } },
                    )
                    StudioParamSlider(
                        label = t(lang, "merge_fade_out"),
                        value = params.overlayFadeOutSec,
                        min = 0f,
                        max = 15f,
                        formattedValue = "${"%.2f".format(params.overlayFadeOutSec)} s",
                        accentColor = StudioColors.Violet,
                        testTag = "merge_fade_out_slider",
                        onValueChange = { v -> updateParams(300L) { it.copy(overlayFadeOutSec = v) } },
                    )
                    StudioParamSlider(
                        label = t(lang, "merge_start_offset"),
                        value = params.overlayStartOffsetSec,
                        min = 0f,
                        max = 60f,
                        formattedValue = "${"%.2f".format(params.overlayStartOffsetSec)} s",
                        accentColor = StudioColors.Cyan,
                        testTag = "merge_start_offset_slider",
                        onValueChange = { v -> updateParams(300L) { it.copy(overlayStartOffsetSec = v) } },
                    )
                }
            }
        }

        // 4 Collapsible Pro Services
        CollapsibleServiceCard(
            title = t(lang, "merge_service_ducking"),
            expanded = expandDucking,
            enabled = params.autoDuckingEnabled,
            accent = StudioColors.Emerald,
            tagPrefix = "merge_service_ducking",
            onToggleExpand = { expandDucking = !expandDucking },
            onToggleEnabled = { on ->
                if (on) expandDucking = true
                updateParams { it.copy(autoDuckingEnabled = on) }
            },
        ) {
            StudioParamSlider(
                label = if (lang == AppLanguage.EN) "Ducking Attenuation Depth" else "عمق خفض الخلفية عند التحدث",
                value = params.duckingStrengthPct,
                min = 0f,
                max = 100f,
                formattedValue = "${params.duckingStrengthPct.roundToInt()}%",
                accentColor = StudioColors.Emerald,
                testTag = "merge_ducking_strength_slider",
                onValueChange = { v -> updateParams(300L) { it.copy(duckingStrengthPct = v, autoDuckingEnabled = true) } },
            )
        }

        CollapsibleServiceCard(
            title = t(lang, "merge_service_leveling"),
            expanded = expandLeveling,
            enabled = params.autoLevelingEnabled,
            accent = StudioColors.Cyan,
            tagPrefix = "merge_service_leveling",
            onToggleExpand = { expandLeveling = !expandLeveling },
            onToggleEnabled = { on ->
                if (on) expandLeveling = true
                updateParams { it.copy(autoLevelingEnabled = on) }
            },
        ) {
            StudioParamSlider(
                label = if (lang == AppLanguage.EN) "Loudnorm Equalization Strength" else "قوة موازنة الصوت الذكية",
                value = params.levelingStrengthPct,
                min = 0f,
                max = 100f,
                formattedValue = "${params.levelingStrengthPct.roundToInt()}%",
                accentColor = StudioColors.Cyan,
                testTag = "merge_leveling_strength_slider",
                onValueChange = { v -> updateParams(300L) { it.copy(levelingStrengthPct = v, autoLevelingEnabled = true) } },
            )
        }

        CollapsibleServiceCard(
            title = t(lang, "merge_service_echo_beat"),
            expanded = expandEchoBeat,
            enabled = params.echoTransitionEnabled,
            accent = StudioColors.Violet,
            tagPrefix = "merge_service_echo_beat",
            onToggleExpand = { expandEchoBeat = !expandEchoBeat },
            onToggleEnabled = { on ->
                if (on) expandEchoBeat = true
                updateParams { it.copy(echoTransitionEnabled = on) }
            },
        ) {
            StudioParamSlider(
                label = if (lang == AppLanguage.EN) "Echo Tail Transition Intensity" else "كثافة صدى الانتقال",
                value = params.echoTransitionPct,
                min = 0f,
                max = 100f,
                formattedValue = "${params.echoTransitionPct.roundToInt()}%",
                accentColor = StudioColors.Violet,
                testTag = "merge_echo_transition_slider",
                onValueChange = { v -> updateParams(300L) { it.copy(echoTransitionPct = v, echoTransitionEnabled = true) } },
            )
        }

        CollapsibleServiceCard(
            title = t(lang, "merge_service_gap_whoosh"),
            expanded = expandGapWhoosh,
            enabled = params.gapWhooshEnabled,
            accent = StudioColors.Amber,
            tagPrefix = "merge_service_gap_whoosh",
            onToggleExpand = { expandGapWhoosh = !expandGapWhoosh },
            onToggleEnabled = { on ->
                if (on) expandGapWhoosh = true
                updateParams { it.copy(gapWhooshEnabled = on) }
            },
        ) {
            StudioParamSlider(
                label = if (lang == AppLanguage.EN) "Transition Gap Duration" else "مدة الفجوة الزمنية بين المقاطع",
                value = params.transitionGapSec,
                min = 0f,
                max = 10f,
                formattedValue = "${"%.2f".format(params.transitionGapSec)} s",
                accentColor = StudioColors.Amber,
                testTag = "merge_gap_duration_slider",
                onValueChange = { v -> updateParams(300L) { it.copy(transitionGapSec = v, gapWhooshEnabled = true) } },
            )
            StudioParamSlider(
                label = if (lang == AppLanguage.EN) "Whoosh Transition Intensity" else "شدة مؤثر الانتقال Whoosh",
                value = params.whooshIntensityPct,
                min = 0f,
                max = 100f,
                formattedValue = "${params.whooshIntensityPct.roundToInt()}%",
                accentColor = StudioColors.Amber,
                testTag = "merge_whoosh_intensity_slider",
                onValueChange = { v -> updateParams(300L) { it.copy(whooshIntensityPct = v, gapWhooshEnabled = true) } },
            )
        }

        // Actions Card: Preview Edit FIRST, then Apply Now, then Export
        Card(
            shape = RoundedCornerShape(20.dp),
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
                uiState.exportProgress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        color = StudioColors.Cyan,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                PreviewAndApplyActionRow(
                    isPreviewPlaying = uiState.isToolPreviewPlaying,
                    previewEnabled = canProcess && !uiState.isBusy,
                    applyEnabled = canProcess && !uiState.isBusy,
                    language = lang,
                    previewTag = "merge_studio_preview_button",
                    applyTag = "merge_studio_apply_button",
                    applyLabel = t(lang, "apply_now"),
                    applyColor = StudioColors.Violet,
                    onPreviewClick = { viewModel.previewUnifiedMergeOverlay(params) },
                    onApplyClick = {
                        preferVmUndo = true
                        viewModel.applyUnifiedMergeOverlay(params)
                    },
                )

                val exportInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { exportDialogOpen = true },
                    enabled = hasPrimary && !uiState.isBusy,
                    interactionSource = exportInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Emerald,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .lit(exportInteraction, StudioColors.Emerald, cornerRadius = 14.dp)
                        .testTag("merge_studio_export_button"),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "save_export_final"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    ToolInfoDialog(
        open = infoDialogOpen,
        title = t(lang, "merge_studio_title"),
        description = t(lang, "merge_studio_subtitle"),
        language = lang,
        onDismiss = { infoDialogOpen = false },
    )

    ExportFormatDialog(
        open = exportDialogOpen,
        language = lang,
        initialFileName = "${uiState.activeProjectName.ifBlank { "Sonix" }}_Merged",
        onDismiss = { exportDialogOpen = false },
        onSaveToDevice = { fmt, name -> viewModel.exportUnifiedMergeOverlay(params, fmt, name, shareAfter = false) },
        onShareAudio = { fmt, name -> viewModel.exportUnifiedMergeOverlay(params, fmt, name, shareAfter = true) },
        onShareToPackage = { fmt, name, pkg -> viewModel.exportUnifiedMergeOverlay(params, fmt, name, shareAfter = true, targetPackage = pkg) },
        onSaveForUnity = { name -> viewModel.exportAudioForUnity(name, shareAfter = false) },
        onShareForUnity = { name -> viewModel.exportAudioForUnity(name, shareAfter = true) },
    )
}

@Composable
private fun CollapsibleServiceCard(
    title: String,
    expanded: Boolean,
    enabled: Boolean,
    accent: Color,
    tagPrefix: String,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = 0.6f) else StudioColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onToggleExpand,
                    color = Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("${tagPrefix}_expand"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = if (enabled) accent else StudioColors.TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (enabled) accent else StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = StudioColors.TextSecondary,
                        )
                    }
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioColors.BgTop,
                        checkedTrackColor = accent,
                    ),
                    modifier = Modifier.testTag("${tagPrefix}_switch"),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content()
                }
            }
        }
    }
}
