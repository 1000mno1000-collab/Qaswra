package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.studio.ui.lit
import com.example.ui.components.ExportFormatDialog
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.ToolInfoDialog
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

@Immutable
private data class SpeechLocaleChoice(
    val tag: String,
    val labelAr: String,
    val labelEn: String,
    val labelFa: String,
)

@Composable
fun SpeechToTextScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val stt = uiState.speechState
    val recognizerAvailable = remember { viewModel.isSpeechRecognizerAvailable() }

    var infoDialogOpen by remember { mutableStateOf(false) }
    var pendingClipRouteAfterGrant by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingClipRouteAfterGrant) {
                viewModel.startStudioClipSpeechTranscription()
            } else {
                viewModel.startLiveSpeechDictation()
            }
        } else {
            viewModel.showStatusBanner(t(lang, "mic_permission_desc"))
        }
        pendingClipRouteAfterGrant = false
    }

    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.uiState.value.speechState.isListening) {
                viewModel.stopSpeechTranscription()
            }
        }
    }

    val locales = remember {
        listOf(
            SpeechLocaleChoice("ar-SA", "العربية", "Arabic", "عربی"),
            SpeechLocaleChoice("en-US", "English", "English", "انگلیسی"),
            SpeechLocaleChoice("fa-IR", "فارسی", "Persian", "فارسی"),
        )
    }

    fun launchWithMicPermission(isClip: Boolean) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (isClip) viewModel.startStudioClipSpeechTranscription() else viewModel.startLiveSpeechDictation()
        } else {
            pendingClipRouteAfterGrant = isClip
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_speech_to_text"),
            subtitle = t(lang, "speech_to_text_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                InfoBadgeButton(
                    contentDescription = t(lang, "tool_speech_to_text"),
                    testTag = "stt_info_badge",
                    accentColor = StudioColors.Cyan,
                    onClick = { infoDialogOpen = true },
                )
            },
        )

        if (!recognizerAvailable) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioColors.Amber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = t(lang, "stt_unavailable"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.Amber,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // Recognition Language Selector
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
                Text(
                    text = t(lang, "stt_language_label"),
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    locales.forEach { loc ->
                        val label = when (lang) {
                            AppLanguage.AR -> loc.labelAr
                            AppLanguage.EN -> loc.labelEn
                            AppLanguage.FA -> loc.labelFa
                        }
                        val selected = stt.localeTag == loc.tag
                        val localeInteraction = remember { MutableInteractionSource() }
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setSpeechLocale(loc.tag) },
                            interactionSource = localeInteraction,
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                                selectedLabelColor = StudioColors.Cyan,
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(localeInteraction, MaterialTheme.colorScheme.primary, selected = selected, cornerRadius = 10.dp)
                                .testTag("stt_locale_${loc.tag}"),
                        )
                    }
                }
            }
        }

        // Live Mic & Studio Clip Transcription Controls
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                if (stt.isListening) StudioColors.RoseBright.copy(alpha = 0.65f) else StudioColors.Border,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (stt.isListening) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(StudioColors.RoseBright),
                        )
                        LinearProgressIndicator(
                            progress = { (stt.rmsDb / 10f).coerceIn(0.08f, 1f) },
                            color = StudioColors.Cyan,
                            trackColor = StudioColors.BgTop,
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(CircleShape),
                        )
                    }
                }

                if (stt.partialText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stt.partialText,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.Cyan,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                if (!stt.errorMessage.isNullOrBlank()) {
                    Text(
                        text = stt.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.Amber,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stt.isListening) {
                        val stopInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.stopSpeechTranscription() },
                            interactionSource = stopInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Rose,
                                contentColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(stopInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("stt_stop_button"),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t(lang, "stt_stop_dictate"), style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        val dictateInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { launchWithMicPermission(isClip = false) },
                            enabled = recognizerAvailable,
                            interactionSource = dictateInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Cyan,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(dictateInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("stt_mic_dictate_button"),
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = t(lang, "stt_dictate_mic"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        val clipInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = {
                                if (uiState.hasAudio) {
                                    launchWithMicPermission(isClip = true)
                                } else {
                                    onOpenPicker(AudioPickerTarget.IMPORT_MAIN)
                                }
                            },
                            enabled = recognizerAvailable,
                            interactionSource = clipInteraction,
                            border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Violet),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(clipInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("stt_clip_transcribe_button"),
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (uiState.hasAudio) t(lang, "stt_transcribe_clip") else t(lang, "audio_tools_pick_source"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Editable Transcript Text Box + Copy / Share
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = t(lang, "tool_speech_to_text"),
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                    )
                    if (stt.displayText.isNotBlank()) {
                        val clearInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { viewModel.clearSpeechTranscription() },
                            interactionSource = clearInteraction,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(clearInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
                                .testTag("stt_clear_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = t(lang, "delete"),
                                tint = StudioColors.TextSecondary,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = stt.displayText,
                    onValueChange = { viewModel.updateSpeechTranscript(it) },
                    minLines = 6,
                    maxLines = 14,
                    placeholder = {
                        Text(
                            text = t(lang, "stt_input_placeholder"),
                            color = StudioColors.TextMuted,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stt_transcript_editor"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val copyInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            if (stt.displayText.isNotBlank()) {
                                clipboard.setText(AnnotatedString(stt.displayText))
                                viewModel.showStatusBanner(t(lang, "stt_copied_banner"))
                            }
                        },
                        enabled = stt.displayText.isNotBlank(),
                        interactionSource = copyInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(copyInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("stt_copy_text_button"),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "stt_copy_text"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    val shareInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            if (stt.displayText.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, stt.displayText)
                                }
                                context.startActivity(Intent.createChooser(intent, t(lang, "stt_share_text")))
                            }
                        },
                        enabled = stt.displayText.isNotBlank(),
                        interactionSource = shareInteraction,
                        border = BorderStroke(1.dp, StudioColors.Emerald),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(shareInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("stt_share_text_button"),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "stt_share_text"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    ToolInfoDialog(
        open = infoDialogOpen,
        title = t(lang, "tool_speech_to_text"),
        description = t(lang, "speech_to_text_info"),
        language = lang,
        onDismiss = { infoDialogOpen = false },
    )
}

@Composable
fun SmartMusicMixerScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenPicker: (AudioPickerTarget) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var infoDialogOpen by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }

    val hasVoice = uiState.hasAudio
    val hasMusic = !uiState.mixBgName.isNullOrBlank()
    val canMix = hasVoice && hasMusic

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "tool_smart_music_mixer"),
            subtitle = t(lang, "smart_mixer_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                InfoBadgeButton(
                    contentDescription = t(lang, "tool_smart_music_mixer"),
                    testTag = "smart_mixer_info_badge",
                    accentColor = StudioColors.Amber,
                    onClick = { infoDialogOpen = true },
                )
            },
        )

        // 1. Primary Voice Track Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                if (hasVoice) StudioColors.Cyan.copy(alpha = 0.45f) else StudioColors.Border,
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
                    Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = StudioColors.Cyan)
                    Text(
                        text = t(lang, "smart_mixer_voice_track"),
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                    )
                }

                if (hasVoice) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = uiState.activeProjectName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioColors.Cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatDurationMs(uiState.durationMs),
                            style = TelemetryTextStyle,
                            color = StudioColors.TextSecondary,
                        )
                    }
                } else {
                    Text(
                        text = t(lang, "no_audio_loaded"),
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }

                val pickVoiceInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { onOpenPicker(AudioPickerTarget.IMPORT_MAIN) },
                    interactionSource = pickVoiceInteraction,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(pickVoiceInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("smart_mixer_pick_voice_button"),
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "smart_mixer_pick_voice"))
                }
            }
        }

        // 2. Secondary Background Music Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                if (hasMusic) StudioColors.Amber.copy(alpha = 0.55f) else StudioColors.Border,
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = StudioColors.Amber)
                        Text(
                            text = t(lang, "smart_mixer_music_track"),
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                    }
                    if (hasMusic) {
                        val clearMusicInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { viewModel.clearMixBackgroundTrack() },
                            interactionSource = clearMusicInteraction,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(clearMusicInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
                                .testTag("smart_mixer_clear_music_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = t(lang, "delete"),
                                tint = StudioColors.TextSecondary,
                            )
                        }
                    }
                }

                if (hasMusic) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = uiState.mixBgName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioColors.Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatDurationMs(uiState.mixBgDurationMs),
                            style = TelemetryTextStyle,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }

                val pickMusicInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { onOpenPicker(AudioPickerTarget.MIX_BACKGROUND) },
                    interactionSource = pickMusicInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Amber.copy(alpha = 0.22f),
                        contentColor = StudioColors.Amber,
                    ),
                    border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(pickMusicInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("smart_mixer_pick_music_button"),
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "smart_mixer_pick_music"))
                }
            }
        }

        // 3. Auto-Ducking Volume Slider + Sandbox Preview + Final Apply/Export
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
                StudioParamSlider(
                    label = t(lang, "smart_mixer_music_volume"),
                    value = uiState.smartMixBgVolume,
                    min = 0f,
                    max = 1.0f,
                    formattedValue = "${(uiState.smartMixBgVolume * 100f).roundToInt()}%",
                    accentColor = StudioColors.Amber,
                    testTag = "smart_mixer_volume_slider",
                    onValueChange = { viewModel.setSmartMixBgVolume(it) },
                )

                if (canMix) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.Emerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = StudioColors.Emerald,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = t(lang, "smart_mixer_ducking_active"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.Emerald,
                            )
                        }
                    }
                }

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

                val mixPreviewInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { viewModel.toggleSmartDuckingPreviewPlayback() },
                    enabled = canMix && !uiState.isBusy,
                    interactionSource = mixPreviewInteraction,
                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(mixPreviewInteraction, MaterialTheme.colorScheme.primary, selected = uiState.isSmartMixPreviewActive, cornerRadius = 14.dp)
                        .testTag("smart_mixer_preview_button"),
                ) {
                    Icon(
                        imageVector = if (uiState.isSmartMixPreviewActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "smart_mixer_preview_duck"))
                }

                val mixApplyInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { viewModel.applySmartDuckingMixToStudio() },
                    enabled = canMix && !uiState.isBusy,
                    interactionSource = mixApplyInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Violet,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(mixApplyInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("smart_mixer_apply_button"),
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "smart_mixer_apply_mix"), style = MaterialTheme.typography.labelLarge)
                }

                val mixExportInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { exportDialogOpen = true },
                    enabled = hasVoice && !uiState.isBusy,
                    interactionSource = mixExportInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Cyan,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .lit(mixExportInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("smart_mixer_export_final_button"),
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
        title = t(lang, "tool_smart_music_mixer"),
        description = t(lang, "smart_mixer_info"),
        language = lang,
        onDismiss = { infoDialogOpen = false },
    )

    ExportFormatDialog(
        open = exportDialogOpen,
        language = lang,
        initialFileName = "${uiState.activeProjectName.ifBlank { "Sonix" }}_Mix",
        onDismiss = { exportDialogOpen = false },
        onSaveToDevice = { fmt, name -> viewModel.exportSmartDuckingMix(fmt, name, shareAfter = false) },
        onShareAudio = { fmt, name -> viewModel.exportSmartDuckingMix(fmt, name, shareAfter = true) },
        onShareToPackage = { fmt, name, pkg -> viewModel.exportSmartDuckingMix(fmt, name, shareAfter = true, targetPackage = pkg) },
        onSaveForUnity = { name -> viewModel.exportAudioForUnity(name, shareAfter = false, useSmartMixIfPresent = true) },
        onShareForUnity = { name -> viewModel.exportAudioForUnity(name, shareAfter = true, useSmartMixIfPresent = true) },
    )
}
