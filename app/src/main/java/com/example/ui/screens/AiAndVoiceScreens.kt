package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.t
import com.example.model.AiToolType
import com.example.model.AppLanguage
import com.example.model.TTS_DIALECTS
import com.example.model.TtsDeliveryStyle
import com.example.model.TtsGender
import com.example.model.VOICE_CHARACTER_PRESETS
import com.example.model.VoiceCharacterPreset
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.components.SaveDraftDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioPreviewControlsBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

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

    StudioScrollColumn {
        StudioFocusTopBar(title = title, language = lang, onBack = onBack)

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
                            Button(
                                onClick = { viewModel.transcribeActiveAudio() },
                                enabled = !st.isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioColors.Cyan,
                                    contentColor = StudioColors.BgTop,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
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

                            OutlinedButton(
                                onClick = { viewModel.clearTranscription() },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_transcribe_clear_button"),
                            ) {
                                Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
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
                                        IconButton(
                                            onClick = { clipboard.setText(AnnotatedString(st.outputText)) },
                                            modifier = Modifier
                                                .size(48.dp)
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
                        Button(
                            onClick = { viewModel.runSmartAutoMaster() },
                            enabled = !uiState.isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Emerald,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .testTag("ai_smart_master_run_button"),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (lang == AppLanguage.EN) "Analyze & Auto-Master" else "تحليل الصوت وتطبيق الماسترينغ الذكي",
                                style = MaterialTheme.typography.labelLarge,
                            )
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
                var query by remember { mutableStateOf("") }

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
                            value = query,
                            onValueChange = { query = it },
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

                        Button(
                            onClick = { viewModel.askSearchAssistant(query) },
                            enabled = query.isNotBlank() && !st.isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Violet,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
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
                                    Surface(
                                        onClick = { runCatching { uriHandler.openUri(src.uri) } },
                                        shape = RoundedCornerShape(12.dp),
                                        color = StudioColors.ElevatedSurface,
                                        border = BorderStroke(1.dp, StudioColors.Border),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp),
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
    var selectedDialect by remember { mutableStateOf(TTS_DIALECTS.first()) }
    var selectedGender by remember { mutableStateOf(TtsGender.MALE) }
    var selectedStyle by remember { mutableStateOf(TtsDeliveryStyle.NATURAL_WARM) }
    var textInput by remember { mutableStateOf(TTS_DIALECTS.first().sampleText) }
    var speechRate by remember { mutableFloatStateOf(TTS_DIALECTS.first().defaultRate) }
    var speechPitch by remember { mutableFloatStateOf(TTS_DIALECTS.first().defaultPitch) }
    var appendToExisting by remember { mutableStateOf(false) }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_tts_title"),
            subtitle = t(lang, "section_tts_subtitle"),
            language = lang,
            onBack = onBack,
        )

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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t(lang, "tts_input_label"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { textInput = selectedDialect.sampleText },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("tts_fill_sample_button"),
                    ) {
                        Text(t(lang, "tts_sample_button"))
                    }
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
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
                        Surface(
                            onClick = { selectedGender = genderOption },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (isSel) StudioColors.Cyan else StudioColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
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
                            Surface(
                                onClick = { selectedStyle = st },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                                border = BorderStroke(1.dp, if (isSel) StudioColors.Violet else StudioColors.Border),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 46.dp)
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
                            Surface(
                                onClick = {
                                    selectedDialect = dialect
                                    speechRate = dialect.defaultRate
                                    speechPitch = dialect.defaultPitch
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                                border = BorderStroke(1.dp, if (selected) StudioColors.Cyan else StudioColors.Border),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
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
                    value = speechRate,
                    min = 0.55f,
                    max = 1.65f,
                    formattedValue = "${"%.2f".format(speechRate)}x",
                    accentColor = StudioColors.Cyan,
                    testTag = "tts_rate_slider",
                    onValueChange = { speechRate = it },
                )

                StudioParamSlider(
                    label = t(lang, "tts_pitch_label"),
                    value = speechPitch,
                    min = 0.65f,
                    max = 1.45f,
                    formattedValue = "${"%.2f".format(speechPitch)}x",
                    accentColor = StudioColors.Violet,
                    testTag = "tts_pitch_slider",
                    onValueChange = { speechPitch = it },
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
                                    speechRate = speechRate,
                                    pitch = speechPitch,
                                )
                            }
                        },
                        enabled = !uiState.ttsUiState.isSynthesizingPreview && textInput.isNotBlank(),
                        border = BorderStroke(1.dp, StudioColors.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
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

                    Button(
                        onClick = {
                            viewModel.generateTtsToStudio(
                                text = textInput,
                                dialect = selectedDialect,
                                gender = selectedGender,
                                style = selectedStyle,
                                speechRate = speechRate,
                                pitch = speechPitch,
                                appendToExisting = appendToExisting,
                            )
                        },
                        enabled = !uiState.ttsUiState.isGenerating && !uiState.isBusy && textInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("tts_insert_button"),
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "tts_insert_button"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
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

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_voice_transformer_title"),
            subtitle = t(lang, "section_voice_transformer_subtitle"),
            language = lang,
            onBack = onBack,
        )

        StudioPreviewControlsBar(
            uiState = uiState,
            onHoldBypassChange = { viewModel.setHoldBypass(it) },
            onToggleBypass = { viewModel.toggleBypass() },
            onUndo = { viewModel.undoLastEdit() },
            onRedo = { viewModel.redoLastEdit() },
            onResetZero = {
                selectedPreset = null
                pitchSemitones = 0f
                formantShift = 1.0f
                warmthDb = 0f
                presenceDb = 0f
                mix = 0f
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VOICE_CHARACTER_PRESETS.forEach { preset ->
                    val selected = preset.id == selectedPreset?.id
                    Surface(
                        onClick = {
                            selectedPreset = preset
                            pitchSemitones = preset.pitchSemitones
                            formantShift = preset.formantShift
                            warmthDb = preset.warmthDb
                            presenceDb = preset.presenceDb
                            mix = 1f
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                        border = BorderStroke(1.dp, if (selected) StudioColors.Violet else StudioColors.Border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
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
                                Text(t(lang, preset.titleKey), style = MaterialTheme.typography.titleSmall, color = StudioColors.TextPrimary)
                                Text(t(lang, preset.descKey), style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                            }
                            if (selected) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StudioColors.Violet)
                            }
                        }
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
                        pitchSemitones = it
                        if (mix == 0f) mix = 1f
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
                        formantShift = it
                        if (mix == 0f) mix = 1f
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
                        warmthDb = it
                        if (mix == 0f) mix = 1f
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
                        presenceDb = it
                        if (mix == 0f) mix = 1f
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
                    onValueChange = { mix = it },
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

                Button(
                    onClick = {
                        viewModel.transformVoiceOffline(
                            pitchSemitones = pitchSemitones,
                            formantShift = formantShift,
                            warmthDb = warmthDb,
                            presenceDb = presenceDb,
                            mix = mix,
                            characterLabel = selectedPreset?.let { t(lang, it.titleKey) }
                                ?: t(lang, "section_voice_transformer_title"),
                        )
                    },
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Violet,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("voice_apply_transform_button"),
                ) {
                    Icon(Icons.Rounded.SpatialAudioOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "voice_apply_transform"), style = MaterialTheme.typography.labelLarge)
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
fun SettingsScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
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

        Card(
            onClick = { langDialogOpen = true },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier
                .fillMaxWidth()
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
    }

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )
}
