package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.dsp.VoiceCharacterPreset
import com.example.audio.tts.TtsDialect
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.TtsStudioUiState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorTtsGeneratorCard(
    hasAudio: Boolean,
    isBusy: Boolean,
    ttsState: TtsStudioUiState,
    onPreviewSpeech: (text: String, dialect: TtsDialect, rate: Float, pitch: Float) -> Unit,
    onStopPreview: () -> Unit,
    onGenerateToStudio: (text: String, dialect: TtsDialect, rate: Float, pitch: Float, append: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(!hasAudio) }
    var selectedDialect by remember { mutableStateOf(TtsDialect.IRAQI) }
    var text by remember { mutableStateOf(TtsDialect.IRAQI.sampleText) }
    var speechRate by remember { mutableFloatStateOf(TtsDialect.IRAQI.defaultRate) }
    var pitch by remember { mutableFloatStateOf(TtsDialect.IRAQI.defaultPitch) }
    var appendToExisting by remember { mutableStateOf(hasAudio) }

    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("editor_tts_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = { expanded = !expanded },
                color = StudioSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_tts_toggle_header")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = CyanGlow)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.section_tts_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = CyanGlow
                            )
                            Text(
                                text = stringResource(R.string.section_tts_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = CyanGlow
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.tts_input_label)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("editor_tts_text_input")
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TtsDialect.entries.forEach { dialect ->
                            val isSelected = dialect == selectedDialect
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val wasSample = TtsDialect.entries.any { it.sampleText == text } || text.isBlank()
                                    selectedDialect = dialect
                                    speechRate = dialect.defaultRate
                                    pitch = dialect.defaultPitch
                                    if (wasSample) text = dialect.sampleText
                                },
                                label = { Text(stringResource(dialect.titleRes), fontSize = 11.5.sp, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanGlow.copy(alpha = 0.22f),
                                    selectedLabelColor = CyanGlow,
                                    containerColor = StudioSurfaceElevated,
                                    labelColor = TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = StudioBorder,
                                    selectedBorderColor = CyanGlow
                                ),
                                modifier = Modifier.testTag("editor_tts_dialect_${dialect.id}")
                            )
                        }
                    }

                    StudioParamSlider(
                        label = stringResource(R.string.tts_speed_label),
                        value = speechRate,
                        valueRange = 0.55f..1.65f,
                        formattedValue = String.format(Locale.US, "%.2fx", speechRate),
                        onValueChange = { speechRate = it },
                        accentColor = CyanGlow
                    )

                    StudioParamSlider(
                        label = stringResource(R.string.tts_pitch_label),
                        value = pitch,
                        valueRange = 0.65f..1.45f,
                        formattedValue = String.format(Locale.US, "%.2fx", pitch),
                        onValueChange = { pitch = it },
                        accentColor = VioletGlow
                    )

                    if (hasAudio) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.tts_append_toggle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = appendToExisting,
                                onCheckedChange = { appendToExisting = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StudioObsidian,
                                    checkedTrackColor = EmeraldActive
                                )
                            )
                        }
                    }

                    if (!ttsState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = ttsState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = CoralRecord
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (ttsState.isPreviewing) onStopPreview()
                                else onPreviewSpeech(text, selectedDialect, speechRate, pitch)
                            },
                            enabled = text.isNotBlank() && !ttsState.isGenerating,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("editor_tts_preview_button")
                        ) {
                            Icon(
                                imageVector = if (ttsState.isPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (ttsState.isPreviewing) CoralRecord else CyanGlow,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    if (ttsState.isPreviewing) R.string.tts_stop_preview else R.string.tts_preview_button
                                ),
                                color = if (ttsState.isPreviewing) CoralRecord else CyanGlow,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = {
                                onGenerateToStudio(text, selectedDialect, speechRate, pitch, hasAudio && appendToExisting)
                            },
                            enabled = text.isNotBlank() && !ttsState.isGenerating && !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldActive,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                                .testTag("editor_tts_insert_button")
                        ) {
                            if (ttsState.isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = StudioObsidian, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.tts_insert_button),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorVoiceTransformerCard(
    isBusy: Boolean,
    purifyProgress: Float?,
    onApplyTransform: (
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        mix: Float,
        characterLabel: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(VoiceCharacterPreset.MALE_LUXURY) }
    var pitchSemitones by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.pitchSemitones) }
    var formantShift by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.formantShift) }
    var warmthDb by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.warmthDb) }
    var presenceDb by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.presenceDb) }
    val selectedTitle = stringResource(selectedPreset.titleRes)

    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("editor_voice_transformer_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = { expanded = !expanded },
                color = StudioSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_voice_transformer_toggle_header")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.SpatialAudioOff, contentDescription = null, tint = VioletGlow)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.section_voice_transformer_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = VioletGlow
                            )
                            Text(
                                text = stringResource(R.string.section_voice_transformer_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = VioletGlow
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceCharacterPreset.entries.forEach { preset ->
                        val isSelected = preset == selectedPreset
                        Surface(
                            onClick = {
                                selectedPreset = preset
                                pitchSemitones = preset.pitchSemitones
                                formantShift = preset.formantShift
                                warmthDb = preset.warmthDb
                                presenceDb = preset.presenceDb
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) VioletGlow.copy(alpha = 0.16f) else StudioObsidian,
                            border = BorderStroke(1.dp, if (isSelected) VioletGlow else StudioBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("editor_voice_char_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(preset.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) VioletGlow else TextPrimary
                                )
                                if (isSelected) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = VioletGlow, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    StudioParamSlider(
                        label = stringResource(R.string.voice_pitch_semitones),
                        value = pitchSemitones,
                        valueRange = -9f..10f,
                        formattedValue = String.format(Locale.US, "%+.1f st", pitchSemitones),
                        onValueChange = { pitchSemitones = it },
                        accentColor = CyanGlow
                    )

                    StudioParamSlider(
                        label = stringResource(R.string.voice_formant_shift),
                        value = formantShift,
                        valueRange = 0.72f..1.38f,
                        formattedValue = String.format(Locale.US, "%.2fx", formantShift),
                        onValueChange = { formantShift = it },
                        accentColor = VioletGlow
                    )

                    if (purifyProgress != null) {
                        LinearProgressIndicator(
                            progress = { purifyProgress.coerceIn(0f, 1f) },
                            color = VioletGlow,
                            trackColor = StudioSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }

                    Button(
                        onClick = {
                            onApplyTransform(
                                pitchSemitones,
                                formantShift,
                                warmthDb,
                                presenceDb,
                                1.0f,
                                selectedTitle
                            )
                        },
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = VioletGlow, contentColor = StudioObsidian),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("editor_voice_transform_apply_button")
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.voice_apply_transform),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
