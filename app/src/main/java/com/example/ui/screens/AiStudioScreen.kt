package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ai.AiTextState
import com.example.ai.LyriaTrackLength
import com.example.ai.MusicGenState
import com.example.audio.io.RecordingState
import com.example.ui.components.AnimatedAcousticRibbon
import com.example.ui.components.formatDurationMs
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.AiToolType
import com.example.viewmodel.StudioUiState

@Composable
fun AiStudioScreen(
    onOpenTool: (AiToolType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "أدوات الذكاء الصوتي المدعومة بنماذج Gemini وLyria عبر Firebase AI Logic",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        AiToolHubCard(
            tool = AiToolType.TRANSCRIBE,
            icon = Icons.Rounded.Subtitles,
            accent = CyanGlow,
            tag = "ai_tool_transcribe",
            onClick = { onOpenTool(AiToolType.TRANSCRIBE) }
        )

        AiToolHubCard(
            tool = AiToolType.MUSIC_GEN,
            icon = Icons.Rounded.MusicNote,
            accent = VioletGlow,
            tag = "ai_tool_music_gen",
            onClick = { onOpenTool(AiToolType.MUSIC_GEN) }
        )

        AiToolHubCard(
            tool = AiToolType.SEARCH_ASSISTANT,
            icon = Icons.Rounded.Search,
            accent = EmeraldActive,
            tag = "ai_tool_search",
            onClick = { onOpenTool(AiToolType.SEARCH_ASSISTANT) }
        )
    }
}

@Composable
private fun AiToolHubCard(
    tool: AiToolType,
    icon: ImageVector,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.arabicTitle, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(tool.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun AiTranscribeScreen(
    uiState: StudioUiState,
    recordingState: RecordingState,
    recordingLevel: Float,
    recordingDurationMs: Long,
    recordingWaveform: List<Float>,
    transcriptionState: AiTextState,
    onStartMic: () -> Boolean,
    onStopAndTranscribeMic: (String) -> Unit,
    onTranscribeStudioAudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var languageHint by remember { mutableStateOf("العربية والإنجليزية") }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) onStartMic()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "التسجيل المباشر والتفريغ عبر gemini-3.5-transcribe",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanGlow
                )

                OutlinedTextField(
                    value = languageHint,
                    onValueChange = { languageHint = it },
                    label = { Text("اللغة المتوقعة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (recordingState == RecordingState.RECORDING) {
                    AnimatedAcousticRibbon(
                        peaks = recordingWaveform,
                        isActive = true,
                        liveEnergy = recordingLevel,
                        accentPrimary = CoralRecord,
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                    )
                    Text(
                        text = "جارٍ الاستماع… ${formatDurationMs(recordingDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CoralRecord
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (recordingState != RecordingState.RECORDING) {
                        Button(
                            onClick = {
                                if (hasMicPermission) onStartMic()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CoralRecord, contentColor = TextPrimary),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("transcribe_mic_start")
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("سجّل وفرّغ مباشرة")
                        }
                    } else {
                        Button(
                            onClick = { onStopAndTranscribeMic(languageHint) },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("transcribe_mic_stop")
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("إنهاء وتفريغ النص")
                        }
                    }

                    if (uiState.hasAudio && recordingState == RecordingState.IDLE) {
                        OutlinedButton(
                            onClick = { onTranscribeStudioAudio(languageHint) },
                            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("transcribe_current_audio")
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = CyanGlow)
                            Spacer(Modifier.width(6.dp))
                            Text("تفريغ مقطع الاستوديو", color = CyanGlow)
                        }
                    }
                }
            }
        }

        AiTextResultCard(
            state = transcriptionState,
            emptyHint = "اضغط على «سجّل وفرّغ مباشرة» أو قم بتفريغ المقطع الصوتي النشط بالاستوديو.",
            onCopy = { txt -> clipboard.setText(AnnotatedString(txt)) }
        )
    }
}

@Composable
fun AiMusicGenScreen(
    uiState: StudioUiState,
    musicGenState: MusicGenState,
    onGenerateMusic: (String, LyriaTrackLength) -> Unit,
    onLoadIntoStudio: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by remember {
        mutableStateOf("موسيقى سينمائية هادئة بآلة العود والبيانو وإيقاع إلكتروني ناعم بسرعة 95 BPM")
    }
    var selectedLength by remember { mutableStateOf(LyriaTrackLength.SHORT_CLIP) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "توليد الموسيقى باستخدام Lyria 3",
                    style = MaterialTheme.typography.titleMedium,
                    color = VioletGlow
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("صف النمط الموسيقي، الآلات، والمزاج") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("music_prompt_input")
                )

                LyriaTrackLength.entries.forEach { option ->
                    val selected = selectedLength == option
                    Surface(
                        onClick = { selectedLength = option },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) VioletGlow.copy(alpha = 0.16f) else StudioObsidian,
                        border = BorderStroke(1.dp, if (selected) VioletGlow else StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("music_length_${option.name.lowercase()}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = option.arabicLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) VioletGlow else TextPrimary
                            )
                            Text(
                                text = option.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Button(
                    onClick = { onGenerateMusic(prompt, selectedLength) },
                    enabled = prompt.isNotBlank() && musicGenState !is MusicGenState.Loading,
                    colors = ButtonDefaults.buttonColors(containerColor = VioletGlow, contentColor = StudioObsidian),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("generate_music_button")
                ) {
                    if (musicGenState is MusicGenState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = StudioObsidian,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("جارٍ التأليف الموسيقي…")
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("توليد المقطوعة الموسيقية")
                    }
                }
            }
        }

        when (musicGenState) {
            is MusicGenState.Success -> {
                Surface(
                    color = StudioSurface,
                    border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "تم التوليد عبر ${musicGenState.modelUsed}",
                            style = MaterialTheme.typography.titleMedium,
                            color = EmeraldActive
                        )
                        Text(
                            text = "المدة: ${formatDurationMs(musicGenState.buffer.durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanGlow
                        )
                        AnimatedAcousticRibbon(
                            peaks = musicGenState.buffer.computeWaveformPeaks(80),
                            isActive = false,
                            liveEnergy = 0f,
                            accentPrimary = VioletGlow,
                            modifier = Modifier.fillMaxWidth().height(72.dp)
                        )
                        if (!musicGenState.notes.isNullOrBlank()) {
                            Text(
                                text = musicGenState.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onLoadIntoStudio(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanGlow, contentColor = StudioObsidian),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("فتح بالاستوديو")
                            }
                            if (uiState.hasAudio) {
                                OutlinedButton(
                                    onClick = { onLoadIntoStudio(false) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).height(50.dp)
                                ) {
                                    Text("دمج مع مقطعي")
                                }
                            }
                        }
                    }
                }
            }

            is MusicGenState.Error -> {
                ErrorNoticeCard(musicGenState.message)
            }

            else -> Unit
        }
    }
}

@Composable
fun AiSearchAssistantScreen(
    searchState: AiTextState,
    onAskSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "استشارة صوتية مدعومة ببيانات بحث Google",
                    style = MaterialTheme.typography.titleMedium,
                    color = EmeraldActive
                )
                Text(
                    text = "استعلم عبر gemini-4-hydrogen مع البحث المباشر عن إعدادات EQ، الميكروفونات، أو حقوق المؤثرات.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("اكتب سؤالك هنا…") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("search_assistant_input")
                )
                Button(
                    onClick = { if (query.isNotBlank()) onAskSearch(query) },
                    enabled = query.isNotBlank() && searchState !is AiTextState.Loading && searchState !is AiTextState.Streaming,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("search_assistant_submit")
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("بحث وإجابة فورية")
                }
            }
        }

        AiTextResultCard(
            state = searchState,
            emptyHint = "مثال: ما أفضل إعدادات Compressor وEQ لتسجيل البودكاست باللغة العربية؟",
            onCopy = { txt -> clipboard.setText(AnnotatedString(txt)) },
            onOpenUrl = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        )
    }
}

@Composable
private fun AiTextResultCard(
    state: AiTextState,
    emptyHint: String,
    onCopy: (String) -> Unit,
    onOpenUrl: ((String) -> Unit)? = null
) {
    when (state) {
        AiTextState.Idle -> {
            Surface(
                color = StudioSurface.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(18.dp)
                )
            }
        }

        AiTextState.Loading -> {
            Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CyanGlow)
                    Text("جارٍ المعالجة عبر Firebase AI Logic…", color = TextPrimary)
                }
            }
        }

        is AiTextState.Streaming -> {
            Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(18.dp)
                )
            }
        }

        is AiTextState.Success -> {
            Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("النتيجة", style = MaterialTheme.typography.titleMedium, color = EmeraldActive)
                        OutlinedButton(onClick = { onCopy(state.text) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("نسخ")
                        }
                    }

                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )

                    if (state.sources.isNotEmpty() && onOpenUrl != null) {
                        Text(
                            text = "المصادر من بحث Google:",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanGlow
                        )
                        state.sources.take(5).forEach { src ->
                            Surface(
                                onClick = { onOpenUrl(src.uri) },
                                color = StudioObsidian,
                                border = BorderStroke(1.dp, StudioBorder),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(src.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                        if (src.domain.isNotBlank()) {
                                            Text(src.domain, style = MaterialTheme.typography.labelSmall, color = AmberWarm)
                                        }
                                    }
                                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        is AiTextState.Error -> {
            ErrorNoticeCard(state.message)
        }
    }
}

@Composable
private fun ErrorNoticeCard(message: String) {
    Surface(
        color = CoralRecord.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(16.dp)
        )
    }
}
