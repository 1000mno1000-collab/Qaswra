package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.dsp.VoiceCharacterPreset
import com.example.ui.components.EmptyAudioNotice
import com.example.ui.components.StudioParamSlider
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.StudioUiState
import java.util.Locale

@Composable
fun VoiceTransformerScreen(
    uiState: StudioUiState,
    onGoToRecording: () -> Unit,
    onApplyTransform: (
        pitchSemitones: Float,
        formantShift: Float,
        warmthDb: Float,
        presenceDb: Float,
        mix: Float,
        characterLabel: String
    ) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!uiState.hasAudio) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            EmptyAudioNotice(
                title = stringResource(R.string.no_audio_loaded),
                actionLabel = stringResource(R.string.section_record_title),
                onAction = onGoToRecording
            )
        }
        return
    }

    var selectedPreset by remember { mutableStateOf(VoiceCharacterPreset.MALE_LUXURY) }
    var pitchSemitones by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.pitchSemitones) }
    var formantShift by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.formantShift) }
    var warmthDb by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.warmthDb) }
    var presenceDb by remember { mutableFloatStateOf(VoiceCharacterPreset.MALE_LUXURY.presenceDb) }
    var wetMix by remember { mutableFloatStateOf(1.0f) }

    val selectedTitle = stringResource(selectedPreset.titleRes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Offline Voice Character Presets
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = VioletGlow)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.section_voice_transformer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = VioletGlow
                    )
                }
                Text(
                    text = stringResource(R.string.section_voice_transformer_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

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
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) VioletGlow.copy(alpha = 0.16f) else StudioObsidian,
                        border = BorderStroke(1.dp, if (isSelected) VioletGlow else StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_char_${preset.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(preset.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) VioletGlow else TextPrimary
                                )
                                Text(
                                    text = stringResource(preset.descRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = VioletGlow
                                )
                            }
                        }
                    }
                }
            }
        }

        // Precision Formant & Pitch Sliders
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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

                StudioParamSlider(
                    label = stringResource(R.string.voice_warmth),
                    value = warmthDb,
                    valueRange = -5f..6f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", warmthDb),
                    onValueChange = { warmthDb = it },
                    accentColor = AmberWarm
                )

                StudioParamSlider(
                    label = stringResource(R.string.voice_presence),
                    value = presenceDb,
                    valueRange = -4f..6f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", presenceDb),
                    onValueChange = { presenceDb = it },
                    accentColor = EmeraldActive
                )

                StudioParamSlider(
                    label = stringResource(R.string.voice_mix),
                    value = wetMix,
                    valueRange = 0.1f..1.0f,
                    formattedValue = "${(wetMix * 100).toInt()}%",
                    onValueChange = { wetMix = it },
                    accentColor = CyanGlow
                )

                if (uiState.purifyProgress != null) {
                    LinearProgressIndicator(
                        progress = { uiState.purifyProgress.coerceIn(0f, 1f) },
                        color = VioletGlow,
                        trackColor = StudioSurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onApplyTransform(
                                pitchSemitones,
                                formantShift,
                                warmthDb,
                                presenceDb,
                                wetMix,
                                selectedTitle
                            )
                        },
                        enabled = !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioletGlow,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("voice_transform_apply_button")
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.voice_apply_transform),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (uiState.canUndoEdit) {
                        OutlinedButton(
                            onClick = onUndo,
                            enabled = !uiState.isBusy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("voice_transform_undo_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo_edit))
                        }
                    }
                }
            }
        }
    }
}
