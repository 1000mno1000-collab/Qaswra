package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.OnlineGuard
import com.example.audio.fx.VoiceEffect
import com.example.i18n.t
import com.example.model.AiToolType
import com.example.model.AppLanguage
import com.example.model.TTS_DIALECTS
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsGender
import com.example.model.VOICE_CHARACTER_PRESETS
import com.example.model.VoiceCharacterCategory
import com.example.model.VoiceCharacterPreset
import com.example.studio.ui.lit
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.components.PreviewAndApplyActionRow
import com.example.ui.components.SaveDraftDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioPreviewControlsBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.StudioSectionCard
import com.example.ui.components.ToolInfoDialog
import com.example.ui.components.UndoRedoActionsRow
import com.example.ui.components.rememberEditHistory
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

private data class TtsEditorSnapshot(
    val textInput: String,
    val dialectId: String,
    val gender: TtsGender,
    val style: TtsDeliveryStyle,
    val rateOffsetPercent: Float,
    val pitchOffsetPercent: Float,
)

private data class VoiceTransformSnapshot(
    val presetId: String?,
    val pitchSemitones: Float,
    val formantShift: Float,
    val warmthDb: Float,
    val presenceDb: Float,
    val mix: Float,
)

@Composable
fun AiStudioScreen(
    uiState: StudioUiState,
    onOpenTool: (AiToolType) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_ai_title"),
            subtitle = t(lang, "section_ai_subtitle"),
            language = lang,
            onBack = onBack,
        )

        StudioSectionCard(
            title = if (lang == AppLanguage.EN) "Smart Audio Transcription" else "تفريغ الصوت إلى نص",
            subtitle = if (lang == AppLanguage.EN) "Convert voice clips into clean punctuated text" else "تحويل الكلام المسجل إلى نص عربي أو إنجليزي منسق",
            icon = Icons.Rounded.Subtitles,
            accentColor = StudioColors.Cyan,
            badgeText = "AI",
            testTag = "ai_card_transcribe",
            onClick = { onOpenTool(AiToolType.TRANSCRIBE) },
        )

        StudioSectionCard(
            title = if (lang == AppLanguage.EN) "Smart Auto-Master" else "مهندس الصوت الذكي (Auto-Master)",
            subtitle = if (lang == AppLanguage.EN) "Analyze RMS, peak, noise floor & optimize DSP chain" else "تحليل الطيف والضوضاء وضبط سلسلة المؤثرات تلقائياً",
            icon = Icons.Rounded.AutoAwesome,
            accentColor = StudioColors.Emerald,
            badgeText = "DSP",
            testTag = "ai_card_smart_master",
            onClick = { onOpenTool(AiToolType.SMART_MASTER) },
        )

        StudioSectionCard(
            title = if (lang == AppLanguage.EN) "Audio Knowledge Assistant" else "مساعد الهندسة الصوتية الذكي",
            subtitle = if (lang == AppLanguage.EN) "Ask mixing, acoustic & microphone placement questions" else "اسأل عن تقنيات المكساج، العزل الصوتي، والمايكات",
            icon = Icons.Rounded.Search,
            accentColor = StudioColors.Violet,
            badgeText = "Q&A",
            testTag = "ai_card_search",
            onClick = { onOpenTool(AiToolType.SEARCH) },
        )
    }
}

@Composable
fun AiToolDetailScreen(
    tool: AiToolType,
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val title = when (tool) {
        AiToolType.TRANSCRIBE -> if (lang == AppLanguage.EN) "Smart Audio Transcription" else "تفريغ الصوت إلى نص"
        AiToolType.SMART_MASTER -> if (lang == AppLanguage.EN) "Smart Auto-Master" else "مهندس الصوت الذكي"
        AiToolType.SEARCH -> if (lang == AppLanguage.EN) "Audio Knowledge Assistant" else "مساعد الهندسة الصوتية"
    }
    var searchQuery by remember { mutableStateOf("") }
    val searchHistory = rememberEditHistory("")

    StudioScrollColumn {
        StudioFocusTopBar(
            title = title,
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = if (tool == AiToolType.SEARCH) searchHistory.canUndo else uiState.canUndoEdit,
                    canRedo = if (tool == AiToolType.SEARCH) searchHistory.canRedo else uiState.canRedoEdit,
                    language = lang,
                    undoTag = "ai_tool_undo_button",
                    redoTag = "ai_tool_redo_button",
                    onUndo = {
                        if (tool == AiToolType.SEARCH) {
                            searchHistory.undo()?.let { searchQuery = it }
                        } else {
                            viewModel.undoLastEdit()
                        }
                    },
                    onRedo = {
                        if (tool == AiToolType.SEARCH) {
                            searchHistory.redo()?.let { searchQuery = it }
                        } else {
                            viewModel.redoLastEdit()
                        }
                    },
                )
            },
        )

        when (tool) {
            AiToolType.TRANSCRIBE -> {
                val st = uiState.transcriptionState
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val startInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = { viewModel.transcribeActiveAudio() },
                                enabled = !st.isLoading,
                                interactionSource = startInteraction,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioColors.Cyan,
                                    contentColor = StudioColors.BgTop,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(startInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                    .testTag("ai_transcribe_start_button"),
                            ) {
                                if (st.isLoading) {
                                    CircularProgressIndicator(
                                        color = StudioColors.BgTop,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (lang == AppLanguage.EN) "Transcribe Studio Audio" else "بدء تفريغ الصوت")
                            }

                            val clearInteraction = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { viewModel.clearTranscription() },
                                interactionSource = clearInteraction,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(clearInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                    .testTag("ai_transcribe_clear_button"),
                            ) {
                                Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }

                        if (st.isLoading) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.transcriptionProgressLabel?.takeIf { it.isNotBlank() }?.let { progLabel ->
                                    Text(
                                        text = progLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.Cyan,
                                    )
                                }
                                val cancelInt = remember { MutableInteractionSource() }
                                OutlinedButton(
                                    onClick = { viewModel.cancelActiveOnlineRequest() },
                                    interactionSource = cancelInt,
                                    border = BorderStroke(1.dp, StudioColors.RoseBright),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .lit(cancelInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                                        .testTag("ai_transcribe_cancel_button"),
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t(lang, "cancel_request"))
                                }
                            }
                        }

                        if (!st.errorMessage.isNullOrBlank()) {
                            AiStatusCard(message = st.errorMessage, isAmber = true)
                        }

                        if (st.outputText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioColors.BgMid,
                                border = BorderStroke(1.dp, StudioColors.BorderBright),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.EN) "Transcript" else "النص المفرّغ",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioColors.Cyan,
                                        )
                                        val copyInteraction = remember { MutableInteractionSource() }
                                        IconButton(
                                            onClick = { clipboard.setText(AnnotatedString(st.outputText)) },
                                            interactionSource = copyInteraction,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .lit(copyInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
                                                .testTag("ai_transcribe_copy_button"),
                                        ) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = StudioColors.Cyan)
                                        }
                                    }
                                    Text(
                                        text = st.outputText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioColors.TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AiToolType.SMART_MASTER -> {
                val analysis = uiState.smartMasterState
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
                        val masterInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.runSmartAutoMaster() },
                            enabled = !uiState.isBusy,
                            interactionSource = masterInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Emerald,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .lit(masterInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("ai_smart_master_run_button"),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (lang == AppLanguage.EN) "Analyze & Auto-Master" else "تحليل الصوت وتطبيق الماسترينغ الذكي",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        if (uiState.smartMasterAnalyzingAi) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = StudioColors.Cyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (lang == AppLanguage.EN) {
                                        "Gemini 2.5 AI Audio Engineer analyzing acoustics & building FFmpeg chain..."
                                    } else {
                                        "مهندس الصوت الذكي (Gemini 2.5) يحلل الترددات ويجهز فلاتر FFmpegKit..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.Cyan,
                                )
                            }
                        }

                        if (analysis != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioColors.ElevatedSurface,
                                border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = if (lang == AppLanguage.EN) "Detected Acoustic Profile" else "البصمة الصوتية المكتشفة",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioColors.Emerald,
                                    )
                                    Text(
                                        text = analysis.detectedProfile,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioColors.TextPrimary,
                                    )
                                }
                            }

                            if (!analysis.aiDiagnosticNotes.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.Cyan.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.EN) "AI Engineer Diagnosis" else "تشخيص مهندس الصوت الذكي",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = StudioColors.Cyan,
                                        )
                                        Text(
                                            text = analysis.aiDiagnosticNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioColors.TextPrimary,
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MetricTile("RMS", "${"%.1f".format(analysis.rmsDb)} dB", StudioColors.Cyan, Modifier.weight(1f))
                                MetricTile("Peak", "${"%.1f".format(analysis.peakDb)} dB", StudioColors.Amber, Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MetricTile("Crest", "${"%.1f".format(analysis.crestFactorDb)} dB", StudioColors.Violet, Modifier.weight(1f))
                                MetricTile("Noise", "${"%.1f".format(analysis.noiseFloorDb)} dB", StudioColors.Emerald, Modifier.weight(1f))
                            }

                            if (!analysis.recommendedFfmpegFilter.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.BgMid,
                                    border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.45f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.EN) {
                                                "Recommended FFmpegKit Master Chain"
                                            } else {
                                                "سلسلة فلاتر FFmpegKit الموصى بها"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = StudioColors.Violet,
                                        )
                                        Text(
                                            text = analysis.recommendedFfmpegFilter,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = StudioColors.TextSecondary,
                                        )
                                        val applyFfmpegInt = remember { MutableInteractionSource() }
                                        Button(
                                            onClick = { viewModel.applyAiEngineerFfmpegFilter() },
                                            enabled = !uiState.isBusy && !uiState.isProcessing && !analysis.ffmpegFilterApplied,
                                            interactionSource = applyFfmpegInt,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (analysis.ffmpegFilterApplied) StudioColors.Emerald else StudioColors.Violet,
                                                contentColor = StudioColors.BgTop,
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 48.dp)
                                                .lit(applyFfmpegInt, StudioColors.Violet, cornerRadius = 12.dp)
                                                .testTag("ai_smart_master_apply_ffmpeg_button"),
                                        ) {
                                            Icon(
                                                imageVector = if (analysis.ffmpegFilterApplied) Icons.Rounded.CheckCircle else Icons.Rounded.GraphicEq,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (analysis.ffmpegFilterApplied) {
                                                    if (lang == AppLanguage.EN) "FFmpegKit Filter Applied" else "تم تطبيق فلاتر FFmpegKit بنجاح"
                                                } else {
                                                    if (lang == AppLanguage.EN) "Apply Recommended FFmpegKit Filters (1-Tap)" else "تطبيق فلاتر FFmpegKit المقترحة بضغطة زر"
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (lang == AppLanguage.EN) "Applied Optimizations" else "التعديلات المطبقة تلقائياً",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                )
                                analysis.appliedActions.forEach { act ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = StudioColors.Emerald,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = act,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AiToolType.SEARCH -> {
                val st = uiState.searchAssistantState

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
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                searchHistory.commit(it, debounceMs = 350L)
                            },
                            label = {
                                Text(if (lang == AppLanguage.EN) "Ask an audio engineering question" else "اكتب سؤالك في الهندسة الصوتية")
                            },
                            minLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioColors.Violet,
                                unfocusedBorderColor = StudioColors.Border,
                                focusedTextColor = StudioColors.TextPrimary,
                                unfocusedTextColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_search_query_input"),
                        )

                        val searchInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.askSearchAssistant(searchQuery) },
                            enabled = searchQuery.isNotBlank() && !st.isLoading,
                            interactionSource = searchInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Violet,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(searchInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("ai_search_submit_button"),
                        ) {
                            if (st.isLoading) {
                                CircularProgressIndicator(
                                    color = StudioColors.BgTop,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (lang == AppLanguage.EN) "Ask Studio Assistant" else "بحث وإجابة الاستوديو")
                        }

                        if (st.isLoading) {
                            val cancelSearchInt = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { viewModel.cancelActiveOnlineRequest() },
                                interactionSource = cancelSearchInt,
                                border = BorderStroke(1.dp, StudioColors.RoseBright),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(cancelSearchInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                                    .testTag("ai_search_cancel_button"),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t(lang, "cancel_request"))
                            }
                        }

                        if (!st.errorMessage.isNullOrBlank()) {
                            AiStatusCard(message = st.errorMessage, isAmber = true)
                        }

                        if (st.outputText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioColors.BgMid,
                                border = BorderStroke(1.dp, StudioColors.Border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = st.outputText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StudioColors.TextPrimary,
                                    modifier = Modifier.padding(14.dp),
                                )
                            }
                        }

                        if (st.sources.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (lang == AppLanguage.EN) "Sources" else "المصادر",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.Cyan,
                                )
                                st.sources.forEach { src ->
                                    val srcInteraction = remember { MutableInteractionSource() }
                                    Surface(
                                        onClick = { runCatching { uriHandler.openUri(src.uri) } },
                                        interactionSource = srcInteraction,
                                        shape = RoundedCornerShape(12.dp),
                                        color = StudioColors.ElevatedSurface,
                                        border = BorderStroke(1.dp, StudioColors.Border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp)
                                            .lit(srcInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(src.title, style = MaterialTheme.typography.labelLarge, color = StudioColors.TextPrimary)
                                                Text(src.domain, style = MaterialTheme.typography.bodySmall, color = StudioColors.Cyan)
                                            }
                                            Icon(Icons.Rounded.OpenInNew, contentDescription = src.title, tint = StudioColors.Cyan)
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
}

@Composable
private fun AiStatusCard(message: String, isAmber: Boolean) {
    val accent = if (isAmber) StudioColors.Amber else StudioColors.Rose
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = StudioColors.TextPrimary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.BgMid,
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = StudioColors.TextSecondary)
            Text(value, style = TelemetryTextStyle, color = color)
        }
    }
}

@Composable
fun TextToSpeechStudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    var selectedDialect by remember { mutableStateOf(TTS_DIALECTS.first()) }
    var selectedGender by remember { mutableStateOf(TtsGender.MALE) }
    var selectedStyle by remember { mutableStateOf(TtsDeliveryStyle.NATURAL_WARM) }
    var textInput by rememberSaveable { mutableStateOf(TTS_DIALECTS.first().sampleText) }
    var rateOffsetPercent by remember { mutableFloatStateOf(0f) }
    var pitchOffsetPercent by remember { mutableFloatStateOf(0f) }
    var appendToExisting by remember { mutableStateOf(false) }
    var articleUrl by rememberSaveable { mutableStateOf("") }
    var infoOpen by remember { mutableStateOf(false) }
    var preferVmUndo by remember { mutableStateOf(false) }
    val ttsHistory = rememberEditHistory(
        TtsEditorSnapshot(
            textInput = textInput,
            dialectId = selectedDialect.id,
            gender = selectedGender,
            style = selectedStyle,
            rateOffsetPercent = rateOffsetPercent,
            pitchOffsetPercent = pitchOffsetPercent,
        ),
    )

    fun currentTtsSnap(
        txt: String = textInput,
        dialectId: String = selectedDialect.id,
        gender: TtsGender = selectedGender,
        style: TtsDeliveryStyle = selectedStyle,
        rate: Float = rateOffsetPercent,
        pitch: Float = pitchOffsetPercent,
    ) = TtsEditorSnapshot(
        textInput = txt,
        dialectId = dialectId,
        gender = gender,
        style = style,
        rateOffsetPercent = rate,
        pitchOffsetPercent = pitch,
    )

    fun applyTtsSnap(snap: TtsEditorSnapshot) {
        textInput = snap.textInput
        selectedDialect = TTS_DIALECTS.firstOrNull { it.id == snap.dialectId } ?: TTS_DIALECTS.first()
        selectedGender = snap.gender
        selectedStyle = snap.style
        rateOffsetPercent = snap.rateOffsetPercent
        pitchOffsetPercent = snap.pitchOffsetPercent
    }

    val hasInternet = remember(uiState.ttsUiState, uiState.studioLuxuryEnhancement, uiState.isExtractingArticle) {
        OnlineGuard.isOnline(context)
    }
    val effectiveSpeechRate = (selectedDialect.defaultRate * (1f + rateOffsetPercent / 100f)).coerceIn(0.55f, 1.65f)
    val effectiveSpeechPitch = (selectedDialect.defaultPitch * (1f + pitchOffsetPercent / 100f)).coerceIn(0.65f, 1.45f)

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_tts_title"),
            subtitle = t(lang, "section_tts_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = ttsHistory.canUndo || uiState.canUndoEdit,
                    canRedo = ttsHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "tts_undo_button",
                    redoTag = "tts_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !ttsHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            ttsHistory.undo()?.let(::applyTtsSnap)
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !ttsHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            ttsHistory.redo()?.let(::applyTtsSnap)
                        }
                    },
                    extraTrailing = {
                        InfoBadgeButton(
                            contentDescription = t(lang, "section_tts_title"),
                            testTag = "tts_info_button",
                            accentColor = StudioColors.Cyan,
                            onClick = { infoOpen = true },
                        )
                    },
                )
            },
        )

        // Open Cloud Neural Engine Card (100% Online, Free & Keyless + Studio Luxury FFmpeg Filter)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                if (!hasInternet) {
                    StudioColors.Rose.copy(alpha = 0.6f)
                } else {
                    StudioColors.Emerald.copy(alpha = 0.55f)
                },
            ),
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = t(lang, "tts_engine_title"),
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StudioColors.Emerald.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = t(lang, "tts_engine_free_badge"),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.Emerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.ElevatedSurface,
                    border = BorderStroke(1.dp, StudioColors.Emerald),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_engine_free_card"),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = t(lang, "tts_engine_free_label"),
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioColors.Emerald,
                            )
                            Text(
                                text = t(lang, "tts_engine_free_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextSecondary,
                            )
                            Text(
                                text = if (uiState.localModelStatus.isTtsReady) {
                                    if (lang == AppLanguage.EN) {
                                        "Hybrid Route: Local ONNX Ready + Gemini 2.5 Audio Cloud"
                                    } else {
                                        "المسار الهجين: محرك ONNX المحلي جاهز + سحابة Gemini 2.5"
                                    }
                                } else {
                                    if (lang == AppLanguage.EN) {
                                        "Hybrid Route: Gemini 2.5 Audio Cloud (ONNX slot ready in assets/models/)"
                                    } else {
                                        "المسار الهجين: Gemini 2.5 Audio سحابياً (جاهز لاستقبال نماذج ONNX المحلية)"
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.Cyan,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = StudioColors.Emerald,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                val luxuryInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { viewModel.setStudioLuxuryEnhancement(!uiState.studioLuxuryEnhancement) },
                    interactionSource = luxuryInteraction,
                    shape = RoundedCornerShape(14.dp),
                    color = if (uiState.studioLuxuryEnhancement) StudioColors.ElevatedSurface else StudioColors.BgMid,
                    border = BorderStroke(
                        1.dp,
                        if (uiState.studioLuxuryEnhancement) StudioColors.Cyan else StudioColors.Border,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .lit(
                            luxuryInteraction,
                            StudioColors.Cyan,
                            selected = uiState.studioLuxuryEnhancement,
                            cornerRadius = 14.dp,
                        )
                        .testTag("tts_studio_luxury_toggle"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (uiState.studioLuxuryEnhancement) StudioColors.Cyan else StudioColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = t(lang, "tts_studio_luxury_title"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (uiState.studioLuxuryEnhancement) StudioColors.Cyan else StudioColors.TextPrimary,
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = StudioColors.Cyan.copy(alpha = 0.14f),
                                ) {
                                    Text(
                                        text = t(lang, "tts_studio_luxury_badge"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioColors.Cyan,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = t(lang, "tts_studio_luxury_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = uiState.studioLuxuryEnhancement,
                            onCheckedChange = { viewModel.setStudioLuxuryEnhancement(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StudioColors.BgTop,
                                checkedTrackColor = StudioColors.Cyan,
                            ),
                            modifier = Modifier.testTag("tts_studio_luxury_switch"),
                        )
                    }
                }

                if (!hasInternet) {
                    AiStatusCard(
                        message = OnlineGuard.offlineErrorMessage(lang),
                        isAmber = false,
                    )
                }
            }
        }

        LiquidAudioWavePreview(
            amplitude = if (uiState.ttsUiState.isPreviewing || uiState.playback.isPlaying) {
                0.72f.coerceAtLeast(uiState.playback.outputLevel)
            } else {
                0.15f
            },
            waveformBars = uiState.playback.waveformBars,
            isActive = uiState.ttsUiState.isPreviewing || uiState.ttsUiState.isSynthesizingPreview || uiState.playback.isPlaying,
            modifier = Modifier
                .fillMaxWidth()
                .height(106.dp)
                .testTag("tts_liquid_wave"),
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
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = t(lang, "tts_web_article_title"),
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.Cyan,
                )
                OutlinedTextField(
                    value = articleUrl,
                    onValueChange = { articleUrl = it },
                    placeholder = {
                        Text(
                            text = t(lang, "tts_web_article_hint"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_article_url_input"),
                )
                val fetchArticleInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        viewModel.extractArticleTextForTts(articleUrl) { extracted ->
                            preferVmUndo = false
                            textInput = extracted
                            ttsHistory.commit(currentTtsSnap(txt = extracted))
                        }
                    },
                    enabled = !uiState.isExtractingArticle && articleUrl.isNotBlank(),
                    interactionSource = fetchArticleInteraction,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(fetchArticleInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                        .testTag("tts_fetch_article_button"),
                ) {
                    if (uiState.isExtractingArticle) {
                        CircularProgressIndicator(
                            color = StudioColors.Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isExtractingArticle) {
                            t(lang, "tts_web_article_fetching")
                        } else {
                            t(lang, "tts_web_article_fetch")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (uiState.isExtractingArticle) {
                    val cancelExtractInt = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.cancelActiveOnlineRequest() },
                        interactionSource = cancelExtractInt,
                        border = BorderStroke(1.dp, StudioColors.RoseBright),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(cancelExtractInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                            .testTag("tts_article_cancel_button"),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "cancel_request"))
                    }
                }
            }
        }

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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t(lang, "tts_input_label"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val sampleInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            preferVmUndo = false
                            textInput = selectedDialect.sampleText
                            ttsHistory.commit(currentTtsSnap(txt = selectedDialect.sampleText))
                        },
                        interactionSource = sampleInteraction,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(sampleInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                            .testTag("tts_fill_sample_button"),
                    ) {
                        Text(t(lang, "tts_sample_button"))
                    }
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = {
                        preferVmUndo = false
                        textInput = it
                        ttsHistory.commit(currentTtsSnap(txt = it), debounceMs = 350L)
                    },
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_text_input"),
                )

                Text(t(lang, "tts_gender_label"), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        TtsGender.MALE to t(lang, "tts_gender_male"),
                        TtsGender.FEMALE to t(lang, "tts_gender_female"),
                    ).forEach { (genderOption, label) ->
                        val isSel = selectedGender == genderOption
                        val genderInteraction = remember { MutableInteractionSource() }
                        Surface(
                            onClick = {
                                preferVmUndo = false
                                selectedGender = genderOption
                                ttsHistory.commit(currentTtsSnap(gender = genderOption))
                            },
                            interactionSource = genderInteraction,
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (isSel) StudioColors.Cyan else StudioColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(genderInteraction, MaterialTheme.colorScheme.primary, selected = isSel, cornerRadius = 14.dp)
                                .testTag("tts_gender_${genderOption.name}"),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSel) StudioColors.Cyan else StudioColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Text(t(lang, "tts_style_label"), style = MaterialTheme.typography.titleSmall)
                TtsDeliveryStyle.entries.chunked(2).forEach { rowStyles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowStyles.forEach { st ->
                            val isSel = selectedStyle == st
                            val styleInteraction = remember { MutableInteractionSource() }
                            Surface(
                                onClick = {
                                    preferVmUndo = false
                                    selectedStyle = st
                                    ttsHistory.commit(currentTtsSnap(style = st))
                                },
                                interactionSource = styleInteraction,
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                                border = BorderStroke(1.dp, if (isSel) StudioColors.Violet else StudioColors.Border),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 46.dp)
                                    .lit(styleInteraction, MaterialTheme.colorScheme.primary, selected = isSel, cornerRadius = 14.dp)
                                    .testTag("tts_style_${st.name}"),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                    Text(
                                        text = t(lang, st.titleKey),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSel) StudioColors.Violet else StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Text(t(lang, "tts_dialect_section"), style = MaterialTheme.typography.titleSmall)

                TTS_DIALECTS.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { dialect ->
                            val selected = dialect.id == selectedDialect.id
                            val dialectInteraction = remember { MutableInteractionSource() }
                            Surface(
                                onClick = {
                                    preferVmUndo = false
                                    selectedDialect = dialect
                                    rateOffsetPercent = 0f
                                    pitchOffsetPercent = 0f
                                    ttsHistory.commit(
                                        currentTtsSnap(
                                            dialectId = dialect.id,
                                            rate = 0f,
                                            pitch = 0f,
                                        ),
                                    )
                                },
                                interactionSource = dialectInteraction,
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                                border = BorderStroke(1.dp, if (selected) StudioColors.Cyan else StudioColors.Border),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(dialectInteraction, MaterialTheme.colorScheme.primary, selected = selected, cornerRadius = 14.dp)
                                    .testTag("tts_dialect_${dialect.id}"),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                    Text(
                                        text = t(lang, dialect.titleKey),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) StudioColors.Cyan else StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                StudioParamSlider(
                    label = t(lang, "tts_speed_label"),
                    value = rateOffsetPercent,
                    min = -45f,
                    max = 55f,
                    formattedValue = "${if (rateOffsetPercent > 0f) "+" else ""}${rateOffsetPercent.roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "tts_rate_slider",
                    onValueChange = {
                        preferVmUndo = false
                        rateOffsetPercent = it
                        ttsHistory.commit(currentTtsSnap(rate = it), debounceMs = 350L)
                    },
                )

                StudioParamSlider(
                    label = t(lang, "tts_pitch_label"),
                    value = pitchOffsetPercent,
                    min = -35f,
                    max = 45f,
                    formattedValue = "${if (pitchOffsetPercent > 0f) "+" else ""}${pitchOffsetPercent.roundToInt()}%",
                    accentColor = StudioColors.Violet,
                    testTag = "tts_pitch_slider",
                    onValueChange = {
                        preferVmUndo = false
                        pitchOffsetPercent = it
                        ttsHistory.commit(currentTtsSnap(pitch = it), debounceMs = 350L)
                    },
                )

                if (uiState.hasAudio) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = t(lang, "tts_append_toggle"),
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = appendToExisting,
                            onCheckedChange = { appendToExisting = it },
                            modifier = Modifier.testTag("tts_append_switch"),
                        )
                    }
                }

                if (uiState.ttsUiState.isGenerating || uiState.ttsUiState.isSynthesizingPreview) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            color = StudioColors.Cyan,
                            trackColor = StudioColors.BgTop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .testTag("tts_synthesis_progress"),
                        )
                        val cancelTtsInt = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { viewModel.cancelActiveOnlineRequest() },
                            interactionSource = cancelTtsInt,
                            border = BorderStroke(1.dp, StudioColors.RoseBright),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(cancelTtsInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                                .testTag("tts_cancel_request_button"),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t(lang, "cancel_request"))
                        }
                    }
                }

                if (uiState.ttsUiState.usedFallbackEngine) {
                    AiStatusCard(message = t(lang, "tts_fallback_notice"), isAmber = true)
                }

                uiState.ttsUiState.errorMessage?.let { err ->
                    AiStatusCard(message = err, isAmber = false)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val previewInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            if (uiState.ttsUiState.isPreviewing) {
                                viewModel.stopTtsPreview()
                            } else if (!uiState.ttsUiState.isSynthesizingPreview) {
                                viewModel.previewTtsSpeech(
                                    text = textInput,
                                    dialect = selectedDialect,
                                    gender = selectedGender,
                                    style = selectedStyle,
                                    speechRate = effectiveSpeechRate,
                                    pitch = effectiveSpeechPitch,
                                )
                            }
                        },
                        enabled = !uiState.ttsUiState.isSynthesizingPreview && textInput.isNotBlank(),
                        interactionSource = previewInteraction,
                        border = BorderStroke(1.dp, StudioColors.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(previewInteraction, MaterialTheme.colorScheme.primary, selected = uiState.ttsUiState.isPreviewing, cornerRadius = 14.dp)
                            .testTag("tts_preview_button"),
                    ) {
                        if (uiState.ttsUiState.isSynthesizingPreview) {
                            CircularProgressIndicator(
                                color = StudioColors.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = t(lang, "tts_synthesizing"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.ttsUiState.isPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (uiState.ttsUiState.isPreviewing) t(lang, "tts_stop_preview") else t(lang, "tts_preview_button"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    val insertInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            preferVmUndo = true
                            viewModel.generateTtsToStudio(
                                text = textInput,
                                dialect = selectedDialect,
                                gender = selectedGender,
                                style = selectedStyle,
                                speechRate = effectiveSpeechRate,
                                pitch = effectiveSpeechPitch,
                                appendToExisting = appendToExisting,
                            )
                        },
                        enabled = !uiState.ttsUiState.isGenerating && !uiState.isBusy && textInput.isNotBlank(),
                        interactionSource = insertInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(insertInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("tts_insert_button"),
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "approve_and_start_processing"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    ToolInfoDialog(
        open = infoOpen,
        title = t(lang, "section_tts_title"),
        description = "${t(lang, "section_tts_subtitle")} • ${t(lang, "tts_web_article_title")}",
        language = lang,
        onDismiss = { infoOpen = false },
    )
}

@Composable
fun VoiceTransformerScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var selectedPreset by remember { mutableStateOf<VoiceCharacterPreset?>(null) }
    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var formantShift by remember { mutableFloatStateOf(1.0f) }
    var warmthDb by remember { mutableFloatStateOf(0f) }
    var presenceDb by remember { mutableFloatStateOf(0f) }
    var mix by remember { mutableFloatStateOf(0f) }
    var saveDraftDialogOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var selectedLiveEffect by remember { mutableStateOf(uiState.liveVoiceEffect) }
    var preferVmUndo by remember { mutableStateOf(false) }
    val voiceHistory = rememberEditHistory(
        VoiceTransformSnapshot(
            presetId = selectedPreset?.id,
            pitchSemitones = pitchSemitones,
            formantShift = formantShift,
            warmthDb = warmthDb,
            presenceDb = presenceDb,
            mix = mix,
        ),
    )

    fun currentVoiceSnap(
        presetId: String? = selectedPreset?.id,
        pitch: Float = pitchSemitones,
        formant: Float = formantShift,
        warmth: Float = warmthDb,
        presence: Float = presenceDb,
        mixVal: Float = mix,
    ) = VoiceTransformSnapshot(
        presetId = presetId,
        pitchSemitones = pitch,
        formantShift = formant,
        warmthDb = warmth,
        presenceDb = presence,
        mix = mixVal,
    )

    fun applyVoiceSnap(snap: VoiceTransformSnapshot) {
        selectedPreset = snap.presetId?.let { id -> VOICE_CHARACTER_PRESETS.firstOrNull { it.id == id } }
        pitchSemitones = snap.pitchSemitones
        formantShift = snap.formantShift
        warmthDb = snap.warmthDb
        presenceDb = snap.presenceDb
        mix = snap.mix
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startLiveVoiceProcessor(selectedLiveEffect)
        } else {
            viewModel.setMicPermissionDeniedError()
        }
    }

    LaunchedEffect(uiState.restoredToolSession?.id) {
        val restored = viewModel.consumeRestoredToolSession("voice_transformer")
        if (restored != null) {
            val fp = restored.floatParams
            val presetId = restored.stringParams["presetId"]
            selectedPreset = presetId?.let { id -> VOICE_CHARACTER_PRESETS.firstOrNull { it.id == id } }
            pitchSemitones = fp["pitchSemitones"] ?: pitchSemitones
            formantShift = fp["formantShift"] ?: formantShift
            warmthDb = fp["warmthDb"] ?: warmthDb
            presenceDb = fp["presenceDb"] ?: presenceDb
            mix = fp["mix"] ?: mix
        }
    }

    val latestHasAudio by rememberUpdatedState(uiState.hasAudio)
    val latestPresetId by rememberUpdatedState(selectedPreset?.id)
    val latestPitch by rememberUpdatedState(pitchSemitones)
    val latestFormant by rememberUpdatedState(formantShift)
    val latestWarmth by rememberUpdatedState(warmthDb)
    val latestPresence by rememberUpdatedState(presenceDb)
    val latestMix by rememberUpdatedState(mix)

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLiveVoiceProcessor()
            viewModel.stopToolPreview()
            if (latestHasAudio) {
                viewModel.saveToolSessionToCache(
                    toolId = "voice_transformer",
                    titleAr = "محول الصوت والشخصيات",
                    titleEn = "Voice Transformer",
                    summaryAr = "${"%.1f".format(latestPitch)} st / ${(latestMix * 100f).toInt()}%",
                    summaryEn = "${"%.1f".format(latestPitch)} st / ${(latestMix * 100f).toInt()}%",
                    floatParams = mapOf(
                        "pitchSemitones" to latestPitch,
                        "formantShift" to latestFormant,
                        "warmthDb" to latestWarmth,
                        "presenceDb" to latestPresence,
                        "mix" to latestMix,
                    ),
                    stringParams = latestPresetId?.let { mapOf("presetId" to it) } ?: emptyMap(),
                )
            }
        }
    }

    val humanPresets = remember {
        VOICE_CHARACTER_PRESETS.filter { it.category == VoiceCharacterCategory.HUMAN }
    }
    val mythicalPresets = remember {
        VOICE_CHARACTER_PRESETS.filter { it.category == VoiceCharacterCategory.MYTHICAL }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_voice_transformer_title"),
            subtitle = t(lang, "section_voice_transformer_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                UndoRedoActionsRow(
                    canUndo = voiceHistory.canUndo || uiState.canUndoEdit,
                    canRedo = voiceHistory.canRedo || uiState.canRedoEdit,
                    language = lang,
                    undoTag = "voice_transformer_undo_button",
                    redoTag = "voice_transformer_redo_button",
                    onUndo = {
                        if ((preferVmUndo && uiState.canUndoEdit) || !voiceHistory.canUndo) {
                            viewModel.undoLastEdit()
                        } else {
                            voiceHistory.undo()?.let(::applyVoiceSnap)
                        }
                    },
                    onRedo = {
                        if ((preferVmUndo && uiState.canRedoEdit) || !voiceHistory.canRedo) {
                            viewModel.redoLastEdit()
                        } else {
                            voiceHistory.redo()?.let(::applyVoiceSnap)
                        }
                    },
                    extraTrailing = {
                        InfoBadgeButton(
                            contentDescription = t(lang, "section_voice_transformer_title"),
                            testTag = "voice_transformer_info_button",
                            accentColor = StudioColors.Violet,
                            onClick = { infoOpen = true },
                        )
                    },
                )
            },
        )

        StudioPreviewControlsBar(
            uiState = uiState,
            onHoldBypassChange = { viewModel.setHoldBypass(it) },
            onToggleBypass = { viewModel.toggleBypass() },
            onUndo = { viewModel.undoLastEdit() },
            onRedo = { viewModel.redoLastEdit() },
            onResetZero = {
                preferVmUndo = false
                selectedPreset = null
                pitchSemitones = 0f
                formantShift = 1.0f
                warmthDb = 0f
                presenceDb = 0f
                mix = 0f
                voiceHistory.commit(
                    currentVoiceSnap(
                        presetId = null,
                        pitch = 0f,
                        formant = 1.0f,
                        warmth = 0f,
                        presence = 0f,
                        mixVal = 0f,
                    ),
                )
                viewModel.resetAllEffects()
            },
            onOpenSaveDraft = { saveDraftDialogOpen = true },
        )

        // Live zero-latency microphone character tester (VoiceProcessor)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                if (uiState.isLiveVoiceChangerActive) StudioColors.Emerald else StudioColors.Border,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = if (uiState.isLiveVoiceChangerActive) StudioColors.Emerald else StudioColors.Cyan,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t(lang, "live_mic_fx_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = t(lang, "live_mic_fx_subtitle"),
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }

                val liveOptions = remember {
                    listOf(
                        VoiceEffect.CHILD_NATURAL to "Child",
                        VoiceEffect.ABYSSAL_DRAGON to "Dragon",
                        VoiceEffect.ALIEN_WIZARD to "Alien",
                        VoiceEffect.DEEP to "Deep",
                        VoiceEffect.ROBOT to "Robot",
                        VoiceEffect.ECHO to "Echo",
                        VoiceEffect.CHIPMUNK to "Chipmunk",
                        VoiceEffect.NONE to "Natural",
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    liveOptions.forEach { (eff, fallbackLabel) ->
                        val isSel = selectedLiveEffect == eff
                        val chipLabel = when (eff) {
                            VoiceEffect.CHILD_NATURAL -> t(lang, "voice_char_child")
                            VoiceEffect.ABYSSAL_DRAGON -> t(lang, "voice_char_abyssal_dragon")
                            VoiceEffect.ALIEN_WIZARD -> t(lang, "voice_char_alien_wizard")
                            else -> fallbackLabel
                        }
                        FilterChip(
                            selected = isSel,
                            onClick = {
                                selectedLiveEffect = eff
                                if (uiState.isLiveVoiceChangerActive) {
                                    viewModel.setLiveVoiceEffect(eff)
                                }
                            },
                            label = {
                                Text(
                                    text = chipLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.2f),
                                selectedLabelColor = StudioColors.Cyan,
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 40.dp)
                                .testTag("live_fx_chip_${eff.name}"),
                        )
                    }
                }

                val liveToggleInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        if (uiState.isLiveVoiceChangerActive) {
                            viewModel.stopLiveVoiceProcessor()
                        } else if (viewModel.hasMicPermission()) {
                            viewModel.startLiveVoiceProcessor(selectedLiveEffect)
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    interactionSource = liveToggleInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isLiveVoiceChangerActive) StudioColors.RoseBright else StudioColors.Emerald,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(
                            liveToggleInteraction,
                            MaterialTheme.colorScheme.primary,
                            selected = uiState.isLiveVoiceChangerActive,
                            cornerRadius = 14.dp,
                        )
                        .testTag("live_mic_fx_toggle_button"),
                ) {
                    Icon(
                        imageVector = if (uiState.isLiveVoiceChangerActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isLiveVoiceChangerActive) {
                            t(lang, "live_mic_fx_stop")
                        } else {
                            t(lang, "live_mic_fx_start")
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Voice Character Presets split into Realistic Human + Mythical & Scary Voices
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
                    text = t(lang, "voice_section_human"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.Cyan,
                )
                humanPresets.forEach { preset ->
                    VoiceCharacterPresetRow(
                        preset = preset,
                        selected = preset.id == selectedPreset?.id,
                        accentColor = StudioColors.Cyan,
                        language = lang,
                        onClick = {
                            preferVmUndo = false
                            selectedPreset = preset
                            pitchSemitones = preset.pitchSemitones
                            formantShift = preset.formantShift
                            warmthDb = preset.warmthDb
                            presenceDb = preset.presenceDb
                            mix = 1f
                            voiceHistory.commit(
                                currentVoiceSnap(
                                    presetId = preset.id,
                                    pitch = preset.pitchSemitones,
                                    formant = preset.formantShift,
                                    warmth = preset.warmthDb,
                                    presence = preset.presenceDb,
                                    mixVal = 1f,
                                ),
                            )
                        },
                    )
                }

                HorizontalDivider(
                    color = StudioColors.Border,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Text(
                    text = t(lang, "voice_section_mythical"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.Violet,
                )
                mythicalPresets.forEach { preset ->
                    VoiceCharacterPresetRow(
                        preset = preset,
                        selected = preset.id == selectedPreset?.id,
                        accentColor = StudioColors.Violet,
                        language = lang,
                        onClick = {
                            preferVmUndo = false
                            selectedPreset = preset
                            pitchSemitones = preset.pitchSemitones
                            formantShift = preset.formantShift
                            warmthDb = preset.warmthDb
                            presenceDb = preset.presenceDb
                            mix = 1f
                            voiceHistory.commit(
                                currentVoiceSnap(
                                    presetId = preset.id,
                                    pitch = preset.pitchSemitones,
                                    formant = preset.formantShift,
                                    warmth = preset.warmthDb,
                                    presence = preset.presenceDb,
                                    mixVal = 1f,
                                ),
                            )
                        },
                    )
                }
            }
        }

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
                    label = t(lang, "voice_pitch_semitones"),
                    value = pitchSemitones,
                    min = -10f,
                    max = 10f,
                    formattedValue = "${if (pitchSemitones >= 0f) "+" else ""}${"%.1f".format(pitchSemitones)} st",
                    accentColor = StudioColors.Violet,
                    testTag = "voice_pitch_slider",
                    onValueChange = {
                        preferVmUndo = false
                        pitchSemitones = it
                        val nextMix = if (mix == 0f) 1f else mix
                        mix = nextMix
                        voiceHistory.commit(currentVoiceSnap(pitch = it, mixVal = nextMix), debounceMs = 350L)
                    },
                )
                StudioParamSlider(
                    label = t(lang, "voice_formant_shift"),
                    value = formantShift,
                    min = 0.7f,
                    max = 1.42f,
                    formattedValue = "${"%.2f".format(formantShift)}x",
                    accentColor = StudioColors.Cyan,
                    testTag = "voice_formant_slider",
                    onValueChange = {
                        preferVmUndo = false
                        formantShift = it
                        val nextMix = if (mix == 0f) 1f else mix
                        mix = nextMix
                        voiceHistory.commit(currentVoiceSnap(formant = it, mixVal = nextMix), debounceMs = 350L)
                    },
                )
                StudioParamSlider(
                    label = t(lang, "voice_warmth"),
                    value = warmthDb,
                    min = -6f,
                    max = 8f,
                    formattedValue = "${"%.1f".format(warmthDb)} dB",
                    accentColor = StudioColors.Amber,
                    testTag = "voice_warmth_slider",
                    onValueChange = {
                        preferVmUndo = false
                        warmthDb = it
                        val nextMix = if (mix == 0f) 1f else mix
                        mix = nextMix
                        voiceHistory.commit(currentVoiceSnap(warmth = it, mixVal = nextMix), debounceMs = 350L)
                    },
                )
                StudioParamSlider(
                    label = t(lang, "voice_presence"),
                    value = presenceDb,
                    min = -6f,
                    max = 8f,
                    formattedValue = "${"%.1f".format(presenceDb)} dB",
                    accentColor = StudioColors.Emerald,
                    testTag = "voice_presence_slider",
                    onValueChange = {
                        preferVmUndo = false
                        presenceDb = it
                        val nextMix = if (mix == 0f) 1f else mix
                        mix = nextMix
                        voiceHistory.commit(currentVoiceSnap(presence = it, mixVal = nextMix), debounceMs = 350L)
                    },
                )
                StudioParamSlider(
                    label = t(lang, "voice_mix"),
                    value = mix,
                    min = 0f,
                    max = 1f,
                    formattedValue = "${(mix * 100f).roundToInt()}%",
                    accentColor = StudioColors.Cyan,
                    testTag = "voice_mix_slider",
                    onValueChange = {
                        preferVmUndo = false
                        mix = it
                        voiceHistory.commit(currentVoiceSnap(mixVal = it), debounceMs = 350L)
                    },
                )

                uiState.purifyProgress?.let { prog ->
                    LinearProgressIndicator(
                        progress = { prog.coerceIn(0f, 1f) },
                        color = StudioColors.Violet,
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
                    previewTag = "voice_preview_transform_button",
                    applyTag = "voice_apply_transform_button",
                    applyLabel = t(lang, "apply_now"),
                    applyColor = StudioColors.Violet,
                    onPreviewClick = {
                        viewModel.previewVoiceTransformerOffline(
                            pitchSemitones = pitchSemitones,
                            formantShift = formantShift,
                            warmthDb = warmthDb,
                            presenceDb = presenceDb,
                            mix = mix,
                            bassBoostDb = selectedPreset?.bassBoostDb ?: 0f,
                            bassFreqHz = selectedPreset?.bassFreqHz ?: 75f,
                            vibratoHz = selectedPreset?.vibratoHz ?: 0f,
                            vibratoDepth = selectedPreset?.vibratoDepth ?: 0f,
                            echoDelayMs = selectedPreset?.echoDelayMs ?: 0f,
                            echoDecay = selectedPreset?.echoDecay ?: 0f,
                            dualLayerPitchSemitones = selectedPreset?.dualLayerPitchSemitones ?: 0f,
                            dualLayerFormantShift = selectedPreset?.dualLayerFormantShift ?: 1.0f,
                            dualLayerMix = selectedPreset?.dualLayerMix ?: 0f,
                        )
                    },
                    onApplyClick = {
                        preferVmUndo = true
                        viewModel.transformVoiceOffline(
                            pitchSemitones = pitchSemitones,
                            formantShift = formantShift,
                            warmthDb = warmthDb,
                            presenceDb = presenceDb,
                            mix = mix,
                            characterLabel = selectedPreset?.let { t(lang, it.titleKey) }
                                ?: t(lang, "section_voice_transformer_title"),
                            bassBoostDb = selectedPreset?.bassBoostDb ?: 0f,
                            bassFreqHz = selectedPreset?.bassFreqHz ?: 75f,
                            vibratoHz = selectedPreset?.vibratoHz ?: 0f,
                            vibratoDepth = selectedPreset?.vibratoDepth ?: 0f,
                            echoDelayMs = selectedPreset?.echoDelayMs ?: 0f,
                            echoDecay = selectedPreset?.echoDecay ?: 0f,
                            dualLayerPitchSemitones = selectedPreset?.dualLayerPitchSemitones ?: 0f,
                            dualLayerFormantShift = selectedPreset?.dualLayerFormantShift ?: 1.0f,
                            dualLayerMix = selectedPreset?.dualLayerMix ?: 0f,
                        )
                    },
                )
            }
        }

        // Sonix Hybrid AI Voice-to-Voice Transformer (Local ONNX -> Gemini 2.5 Audio + FFmpegKit)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (lang == AppLanguage.EN) {
                                "Sonix AI Voice-to-Voice (Realistic Human)"
                            } else {
                                "تحويل الصوت البشري بالذكاء الاصطناعي (Voice-to-Voice)"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.Cyan,
                        )
                        Text(
                            text = if (uiState.localModelStatus.isVoiceChangerReady) {
                                if (lang == AppLanguage.EN) {
                                    "Hybrid ONNX Local Model Active + Cloud Fallback"
                                } else {
                                    "المحرك الهجين: نموذج ONNX المحلي نشط مع دعم Gemini 2.5"
                                }
                            } else {
                                if (lang == AppLanguage.EN) {
                                    "Gemini 2.5 Audio Re-Voicing preserving words, dialect & emotion + FFmpegKit"
                                } else {
                                    "يعيد أداء كلامك بصوت بشري واقعي مع حفظ اللهجة والانفعال عبر Gemini 2.5 و FFmpegKit"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }

                uiState.voiceAiStatusNote?.takeIf { it.isNotBlank() }?.let { note ->
                    AiStatusCard(message = note, isAmber = true)
                }

                if (uiState.isVoiceAiConverting) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            color = StudioColors.Cyan,
                            trackColor = StudioColors.BgTop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                        )
                        val cancelAiVcInt = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { viewModel.cancelActiveOnlineRequest() },
                            interactionSource = cancelAiVcInt,
                            border = BorderStroke(1.dp, StudioColors.RoseBright),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(cancelAiVcInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                                .testTag("voice_ai_cancel_button"),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t(lang, "cancel_request"))
                        }
                    }
                }

                val targetCharacterLabel = selectedPreset?.let { t(lang, it.titleKey) }
                    ?: if (lang == AppLanguage.EN) "Realistic Studio Voice" else "شخصية استوديو طبيعية"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val previewAiInt = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            viewModel.previewVoiceWithAi(
                                preset = selectedPreset,
                                targetLabel = targetCharacterLabel,
                                pitchSemitones = pitchSemitones,
                                formantShift = formantShift,
                                warmthDb = warmthDb,
                                presenceDb = presenceDb,
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy && !uiState.isVoiceAiConverting,
                        interactionSource = previewAiInt,
                        border = BorderStroke(1.dp, StudioColors.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(previewAiInt, StudioColors.Cyan, cornerRadius = 14.dp)
                            .testTag("voice_ai_preview_button"),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (lang == AppLanguage.EN) "Preview AI" else "معاينة ذكية",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val applyAiInt = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            preferVmUndo = true
                            viewModel.convertVoiceWithAi(
                                preset = selectedPreset,
                                targetLabel = targetCharacterLabel,
                                pitchSemitones = pitchSemitones,
                                formantShift = formantShift,
                                warmthDb = warmthDb,
                                presenceDb = presenceDb,
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy && !uiState.isVoiceAiConverting,
                        interactionSource = applyAiInt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(applyAiInt, StudioColors.Cyan, cornerRadius = 14.dp)
                            .testTag("voice_ai_apply_button"),
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (lang == AppLanguage.EN) "Convert with AI" else "تحويل AI بشري",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

    ToolInfoDialog(
        open = infoOpen,
        title = t(lang, "section_voice_transformer_title"),
        description = "${t(lang, "section_voice_transformer_subtitle")} • ${t(lang, "voice_section_mythical")}",
        language = lang,
        onDismiss = { infoOpen = false },
    )
}

@Composable
private fun VoiceCharacterPresetRow(
    preset: VoiceCharacterPreset,
    selected: Boolean,
    accentColor: Color,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val charInteraction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = charInteraction,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
        border = BorderStroke(1.dp, if (selected) accentColor else StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .lit(charInteraction, MaterialTheme.colorScheme.primary, selected = selected, cornerRadius = 14.dp)
            .testTag("voice_char_${preset.id}"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = t(language, preset.titleKey),
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = t(language, preset.descKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accentColor)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onChangeDefaultSaveFolder: () -> Unit = {},
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var langDialogOpen by remember { mutableStateOf(false) }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_settings_title"),
            subtitle = t(lang, "section_settings_subtitle"),
            language = lang,
            onBack = onBack,
        )

        val saveFolderInteraction = remember { MutableInteractionSource() }
        Card(
            onClick = onChangeDefaultSaveFolder,
            interactionSource = saveFolderInteraction,
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .lit(saveFolderInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 20.dp)
                .testTag("settings_save_folder_card"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t(lang, "settings_save_folder_title"), style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                    Text(
                        text = uiState.defaultSaveFolderName ?: t(lang, "settings_save_folder_not_set"),
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.Amber,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = StudioColors.TextMuted)
            }
        }

        val langCardInteraction = remember { MutableInteractionSource() }
        Card(
            onClick = { langDialogOpen = true },
            interactionSource = langCardInteraction,
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .lit(langCardInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 20.dp)
                .testTag("settings_language_card"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = StudioColors.Cyan, modifier = Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t(lang, "app_language_title"), style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                    Text(
                        text = "${lang.nativeName} (${if (lang.isRtl) "RTL" else "LTR"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.Cyan,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = StudioColors.TextMuted)
            }
        }

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
                    text = t(lang, "settings_playback_section"),
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

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
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = StudioColors.Emerald)
                        Text(t(lang, "loop_repeat"), style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextPrimary)
                    }
                    Switch(
                        checked = uiState.autoLoopOnPlayback,
                        onCheckedChange = { viewModel.setAutoLoopOnPlayback(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioColors.BgTop,
                            checkedTrackColor = StudioColors.Emerald,
                        ),
                        modifier = Modifier.testTag("settings_loop_switch"),
                    )
                }
            }
        }

        SupportUpdatesQaSection(
            uiState = uiState,
            viewModel = viewModel,
        )
    }

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )
}
