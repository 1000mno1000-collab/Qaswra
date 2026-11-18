package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiDialogueTurn
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.audio.formatDurationMs
import com.example.model.AppLanguage
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import kotlin.math.roundToInt

@Composable
fun AiVoiceConfigurationBanner(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onDismiss: () -> Unit,
    onOpenServerTab: () -> Unit,
    onUseLocalFallback: () -> Unit,
) {
    val title = state.errorTitle ?: return
    val isConfigRequired = !state.setupRequiredGuide.isNullOrBlank()
    val accent = if (isConfigRequired) StudioColors.Amber else StudioColors.RoseBright

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_voice_status_banner"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = title,
                        tint = accent,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("ai_voice_dismiss_error"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = if (isEnglish) "Dismiss" else "إغلاق",
                        tint = StudioColors.TextSecondary,
                    )
                }
            }

            if (!state.errorDetail.isNullOrBlank()) {
                Text(
                    text = state.errorDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextPrimary,
                )
            }

            if (!state.setupRequiredGuide.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioColors.ElevatedSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.setupRequiredGuide,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenServerTab,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_open_server_setup_btn"),
                    ) {
                        Text(
                            text = if (isEnglish) "Server Setup" else "ربط سيرفر GPU",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onUseLocalFallback,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_use_local_fallback_btn"),
                    ) {
                        Text(
                            text = if (isEnglish) "Use Local Engine" else "تشغيل بالمحلي الآن",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AiVoiceOutputPlayerCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onPlayPause: () -> Unit,
    onReplay: () -> Unit,
    onStop: () -> Unit,
    onSeekMs: (Long) -> Unit,
    onSaveWav: () -> Unit,
    onShare: () -> Unit,
    onInsertIntoStudio: () -> Unit,
) {
    val wavPath = state.lastOutputWavPath ?: return
    if (wavPath.isBlank()) return

    val durationMs = state.lastOutputDurationMs.coerceAtLeast(1L)
    val posMs = state.playbackPositionMs.coerceIn(0L, durationMs)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.ElevatedSurface),
        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_voice_output_player_card"),
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
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                    )
                    Column {
                        Text(
                            text = if (isEnglish) "Generated Audio Output" else "النتيجة الصوتية المولّدة",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.TextPrimary,
                        )
                        state.lastOutputEngine?.let { eng ->
                            Text(
                                text = "${if (isEnglish) eng.titleEn else eng.titleAr} • ${state.lastOutputSampleRate / 1000} kHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.Cyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    text = "${formatDurationMs(posMs)} / ${formatDurationMs(durationMs)}",
                    style = TelemetryTextStyle,
                    color = StudioColors.TextPrimary,
                )
            }

            if (!state.lastOutputAppliedPrompt.isNullOrBlank()) {
                Text(
                    text = state.lastOutputAppliedPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Slider(
                value = posMs.toFloat(),
                onValueChange = { onSeekMs(it.toLong()) },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = StudioColors.Cyan,
                    activeTrackColor = StudioColors.Cyan,
                    inactiveTrackColor = StudioColors.BgTop,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_player_seek_slider"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlayPause,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Cyan,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_play_pause_button"),
                ) {
                    Icon(
                        imageVector = if (state.isPlayingOutput) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlayingOutput) "Pause" else "Play",
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.isPlayingOutput) {
                            if (isEnglish) "Pause" else "إيقاف مؤقت"
                        } else {
                            if (isEnglish) "Play" else "تشغيل"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = onReplay,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_replay_button"),
                ) {
                    Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isEnglish) "Replay" else "إعادة", maxLines = 1)
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("ai_voice_stop_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = if (isEnglish) "Stop" else "إيقاف",
                        tint = StudioColors.RoseBright,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSaveWav,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_save_wav_button"),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isEnglish) "Save WAV" else "حفظ", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_share_button"),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isEnglish) "Share" else "مشاركة", maxLines = 1)
                }
                Button(
                    onClick = onInsertIntoStudio,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Violet,
                        contentColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_insert_studio_button"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isEnglish) "To Studio" else "للاستوديو",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiVoiceDialogueTab(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onAddTurn: () -> Unit,
    onRemoveTurn: (String) -> Unit,
    onMoveTurn: (String, Int) -> Unit,
    onUpdateTurn: (String, (AiDialogueTurn) -> AiDialogueTurn) -> Unit,
    onSynthesizeDialogue: (AiVoiceEngineKind?) -> Unit,
    onCancelSynthesis: () -> Unit,
) {
    val characters = state.allCharacters

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isEnglish) "Multi-Character Dialogue Builder" else "منشئ الحوار متعدد الشخصيات",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.Cyan,
                )
                Text(
                    text = if (isEnglish) {
                        "Assign distinct voice characters, emotions, pacing, and pauses for each line, then stitch everything into one cohesive WAV audio file."
                    } else {
                        "خصّص لكل متحدث شخصيته الصوتية وانفعاله وسرعته وفترة الصمت بعد جملته، وسيتم دمج الحوار بالكامل في ملف صوتي واحد متصل."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            }
        }

        state.dialogueTurns.forEachIndexed { index, turn ->
            val selectedChar = characters.firstOrNull { it.id == turn.characterId } ?: characters.first()
            var charMenuOpen by remember(turn.id) { mutableStateOf(false) }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(1.dp, StudioColors.BorderBright),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialogue_turn_card_$index"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (isEnglish) "Line #${index + 1}" else "الجملة #${index + 1}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.Amber,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onMoveTurn(turn.id, -1) },
                                enabled = index > 0,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move Up", tint = StudioColors.TextSecondary)
                            }
                            IconButton(
                                onClick = { onMoveTurn(turn.id, 1) },
                                enabled = index < state.dialogueTurns.lastIndex,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move Down", tint = StudioColors.TextSecondary)
                            }
                            IconButton(
                                onClick = { onRemoveTurn(turn.id) },
                                enabled = state.dialogueTurns.size > 1,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = StudioColors.RoseBright)
                            }
                        }
                    }

                    Box {
                        OutlinedButton(
                            onClick = { charMenuOpen = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(
                                text = "${selectedChar.displayName(isEnglish)} • ${selectedChar.category.titleAr}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = charMenuOpen,
                            onDismissRequest = { charMenuOpen = false },
                        ) {
                            characters.forEach { ch ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${ch.displayName(isEnglish)} (${if (isEnglish) ch.dialect.titleEn else ch.dialect.titleAr})")
                                    },
                                    onClick = {
                                        onUpdateTurn(turn.id) { it.copy(characterId = ch.id, emotion = ch.defaultEmotion, pace = ch.defaultPace) }
                                        charMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = turn.text,
                        onValueChange = { txt -> onUpdateTurn(turn.id) { it.copy(text = txt) } },
                        label = { Text(if (isEnglish) "Dialogue Text" else "نص المتحدث") },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Cyan,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AiVoiceEmotion.entries.forEach { emo ->
                            val selected = turn.emotion == emo
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateTurn(turn.id) { it.copy(emotion = emo) } },
                                label = { Text(if (isEnglish) emo.titleEn else emo.titleAr, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                                    selectedLabelColor = StudioColors.Cyan,
                                ),
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AiDeliveryPace.entries.forEach { pace ->
                            FilterChip(
                                selected = turn.pace == pace,
                                onClick = { onUpdateTurn(turn.id) { it.copy(pace = pace) } },
                                label = { Text(if (isEnglish) pace.titleEn else pace.titleAr, maxLines = 1) },
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (isEnglish) {
                                "Emotion: ${(turn.emotionIntensity * 100).roundToInt()}% • Pause after: ${turn.pauseAfterMs} ms"
                            } else {
                                "شدة الانفعال: ${(turn.emotionIntensity * 100).roundToInt()}% • صمت بعد الجملة: ${turn.pauseAfterMs} مللي ثانية"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextSecondary,
                        )
                        Slider(
                            value = turn.emotionIntensity,
                            onValueChange = { v -> onUpdateTurn(turn.id) { it.copy(emotionIntensity = v) } },
                            valueRange = 0.1f..1.0f,
                        )
                        Slider(
                            value = turn.pauseAfterMs.toFloat(),
                            onValueChange = { v -> onUpdateTurn(turn.id) { it.copy(pauseAfterMs = v.roundToInt()) } },
                            valueRange = 0f..2000f,
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onAddTurn,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("dialogue_add_turn_button"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isEnglish) "Add Dialogue Line" else "+ إضافة جملة جديدة للحوار")
        }

        if (state.isSynthesizing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { state.synthesisProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = StudioColors.Cyan,
                )
                Text(
                    text = state.synthesisStatusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Cyan,
                )
                OutlinedButton(
                    onClick = onCancelSynthesis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(if (isEnglish) "Cancel Generation" else "إلغاء التوليد")
                }
            }
        } else {
            Button(
                onClick = { onSynthesizeDialogue(null) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Cyan,
                    contentColor = StudioColors.BgTop,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("dialogue_generate_full_button"),
            ) {
                Text(
                    text = if (isEnglish) "Generate & Stitch Full Dialogue" else "توليد الحوار كاملاً كملف صوتي متصل",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun AiVoiceCharactersTab(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onApplyCharacterToStudio: (AiVoiceCharacter) -> Unit,
    onAddCharacterToDialogue: (AiVoiceCharacter) -> Unit,
    onSaveCurrentAsCharacter: (String, String) -> Unit,
    onDeleteSavedCharacter: (String) -> Unit,
) {
    var saveDialogOpen by remember { mutableStateOf(false) }
    var newNameAr by remember { mutableStateOf("") }
    var newNameEn by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                newNameAr = ""
                newNameEn = ""
                saveDialogOpen = true
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = StudioColors.Violet,
                contentColor = StudioColors.TextPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 50.dp)
                .testTag("characters_save_current_button"),
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isEnglish) "Save Current Voice Design as Character" else "حفظ إعدادات الصوت الحالية كشخصية مفضلة",
                fontWeight = FontWeight.Bold,
            )
        }

        state.allCharacters.forEach { character ->
            val isSelected = state.selectedCharacterId == character.id
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) StudioColors.Cyan else StudioColors.Border,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("character_library_card_${character.id}"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = character.displayName(isEnglish),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) StudioColors.Cyan else StudioColors.TextPrimary,
                            )
                            Text(
                                text = "${if (isEnglish) character.category.titleEn else character.category.titleAr} • ${if (isEnglish) character.dialect.titleEn else character.dialect.titleAr} • ${character.preferredEngine.modelRepoId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                        if (!character.isBuiltIn) {
                            IconButton(
                                onClick = { onDeleteSavedCharacter(character.id) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = StudioColors.RoseBright)
                            }
                        }
                    }

                    Text(
                        text = character.voiceDescriptionPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onApplyCharacterToStudio(character) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Cyan,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(if (isEnglish) "Use in Studio" else "استخدام بالاستوديو", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onAddCharacterToDialogue(character) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(if (isEnglish) "+ Dialogue" else "+ للحوار", maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (saveDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text(if (isEnglish) "Save Custom Character" else "حفظ شخصية صوتية جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newNameAr,
                        onValueChange = { newNameAr = it },
                        label = { Text(if (isEnglish) "Character Name (Arabic/Display)" else "اسم الشخصية") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newNameEn,
                        onValueChange = { newNameEn = it },
                        label = { Text(if (isEnglish) "English Name (Optional)" else "الاسم الإنجليزي (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCurrentAsCharacter(newNameAr, newNameEn)
                        saveDialogOpen = false
                    },
                ) {
                    Text(if (isEnglish) "Save" else "حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) {
                    Text(if (isEnglish) "Cancel" else "إلغاء")
                }
            },
        )
    }
}

@Composable
fun AiVoiceServerSetupTab(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSaveBackendUrl: (String) -> Unit,
    onCheckHealth: () -> Unit,
) {
    var urlInput by remember(state.backendServerUrl) { mutableStateOf(state.backendServerUrl) }
    val health = state.backendHealth

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = StudioColors.Cyan)
                    Text(
                        text = if (isEnglish) "Hugging Face & GPU Architecture Notice" else "حالة الاستضافة الرسمية وواجهات Inference Providers",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.Cyan,
                    )
                }
                Text(
                    text = if (isEnglish) {
                        "• FenomAI/VoxCPM2 (2B diffusion-AR, 48kHz) and audarai/Audar-TTS-V1-Turbo (1.64B Arabic-first) both have NO managed serverless Hugging Face Inference Provider.\n" +
                            "• Never embed HF tokens inside the APK. Deploy the included `/backend` FastAPI GPU server (RunPod, Modal, or HF Dedicated Endpoint) with HF_TOKEN stored strictly in server env vars, then enter the HTTPS URL below.\n" +
                            "• Without a custom URL, VoxCPM2 probes the official public demo Space (`openbmb-voxcpm-demo.hf.space`) for prompt design when awake."
                    } else {
                        "• بعد فحص صفحات النماذج الرسمية وخدمات Hugging Face Inference Providers: كلا النموذجين FenomAI/VoxCPM2 (2B باراميتر) و audarai/Audar-TTS-V1-Turbo (1.64B) لا يتوفر لهما مزود Inference Serverless عام مجاني، كما لا يمكن تشغيلهما محلياً داخل معالج الهاتف.\n" +
                            "• لذلك يتضمن المشروع خادم GPU خلفي جاهز في مجلد `/backend` (FastAPI + Docker + VoxCPM2 + Audar-TTS) يحتفظ بأي توكن HF_TOKEN على الخادم فقط دون وضع أي مفاتيح داخل التطبيق.\n" +
                            "• بدون رابط خادم مخصص: يحاول VoxCPM2 الفضاء الرسمي العام مؤقتاً عند توفره، بينما يتوفر «محرك الاستوديو المحلي» فوراً كبديل مباشر على الهاتف."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(if (isEnglish) "Dedicated GPU Backend HTTPS URL" else "رابط خادم GPU الخلفي (HTTPS)") },
                    placeholder = { Text("https://your-sonix-voice-gpu.example.com") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_voice_backend_url_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSaveBackendUrl(urlInput) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_save_backend_url_button"),
                    ) {
                        Text(if (isEnglish) "Save URL" else "حفظ الرابط", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onCheckHealth,
                        enabled = !health.isChecking,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_check_health_button"),
                    ) {
                        if (health.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isEnglish) "Test Connection" else "فحص الاتصال")
                    }
                }

                if (health.isChecked) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.ElevatedSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = if (health.serverReachable || health.voxCpmPublicSpaceAvailable) {
                                        Icons.Rounded.CloudDone
                                    } else {
                                        Icons.Rounded.CloudOff
                                    },
                                    contentDescription = null,
                                    tint = if (health.serverReachable) StudioColors.Emerald else StudioColors.Amber,
                                )
                                Text(
                                    text = health.statusDetail,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = StudioColors.TextPrimary,
                                )
                            }
                            if (health.serverReachable) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "VoxCPM2: ${if (health.voxCpm2Ready) "جاهز" else "غير مفعل"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (health.voxCpm2Ready) StudioColors.Emerald else StudioColors.TextMuted,
                                    )
                                    Text(
                                        text = "Audar-TTS: ${if (health.audarTurboReady) "جاهز" else "غير مفعل"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (health.audarTurboReady) StudioColors.Emerald else StudioColors.TextMuted,
                                    )
                                }
                            }
                            if (health.serverGpuInfo.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StudioColors.Cyan, modifier = Modifier.size(14.dp))
                                    Text(
                                        text = "GPU: ${health.serverGpuInfo}",
                                        style = TelemetryTextStyle,
                                        color = StudioColors.Cyan,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
