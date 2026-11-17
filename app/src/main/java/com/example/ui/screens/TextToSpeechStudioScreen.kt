package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.tts.TtsDialect
import com.example.ui.components.StudioParamSlider
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
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.TtsStudioUiState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextToSpeechStudioScreen(
    uiState: StudioUiState,
    ttsState: TtsStudioUiState,
    onPreviewSpeech: (text: String, dialect: TtsDialect, rate: Float, pitch: Float) -> Unit,
    onStopPreview: () -> Unit,
    onGenerateToStudio: (text: String, dialect: TtsDialect, rate: Float, pitch: Float, append: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDialect by remember { mutableStateOf(TtsDialect.IRAQI) }
    var text by remember { mutableStateOf(TtsDialect.IRAQI.sampleText) }
    var speechRate by remember { mutableFloatStateOf(TtsDialect.IRAQI.defaultRate) }
    var pitch by remember { mutableFloatStateOf(TtsDialect.IRAQI.defaultPitch) }
    var appendToExisting by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Text Input Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = CyanGlow)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.section_tts_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanGlow
                        )
                    }
                    TextButton(
                        onClick = { text = selectedDialect.sampleText },
                        modifier = Modifier.testTag("tts_sample_button")
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.tts_sample_button), fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.tts_input_label)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_text_input")
                )
            }
        }

        // 10 Dialects & Languages Selector Card
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.tts_dialect_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = VioletGlow
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TtsDialect.entries.forEach { dialect ->
                        val isSelected = dialect == selectedDialect
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val prevWasSample = TtsDialect.entries.any { it.sampleText == text } || text.isBlank()
                                selectedDialect = dialect
                                speechRate = dialect.defaultRate
                                pitch = dialect.defaultPitch
                                if (prevWasSample) {
                                    text = dialect.sampleText
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(dialect.titleRes),
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletGlow.copy(alpha = 0.24f),
                                selectedLabelColor = VioletGlow,
                                containerColor = StudioSurfaceElevated,
                                labelColor = TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = StudioBorder,
                                selectedBorderColor = VioletGlow
                            ),
                            modifier = Modifier.testTag("tts_dialect_${dialect.id}")
                        )
                    }
                }
            }
        }

        // Tone & Insertion Controls Card
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

                if (uiState.hasAudio) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.tts_append_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = appendToExisting,
                            onCheckedChange = { appendToExisting = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StudioObsidian,
                                checkedTrackColor = EmeraldActive
                            ),
                            modifier = Modifier.testTag("tts_append_switch")
                        )
                    }
                }

                if (!ttsState.errorMessage.isNullOrBlank()) {
                    Surface(
                        color = CoralRecord.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = ttsState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = CoralRecord,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (ttsState.isPreviewing) {
                                onStopPreview()
                            } else {
                                onPreviewSpeech(text, selectedDialect, speechRate, pitch)
                            }
                        },
                        enabled = text.isNotBlank() && !ttsState.isGenerating,
                        border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("tts_preview_button")
                    ) {
                        Icon(
                            imageVector = if (ttsState.isPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (ttsState.isPreviewing) CoralRecord else CyanGlow
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (ttsState.isPreviewing) R.string.tts_stop_preview else R.string.tts_preview_button
                            ),
                            color = if (ttsState.isPreviewing) CoralRecord else CyanGlow,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            onGenerateToStudio(text, selectedDialect, speechRate, pitch, appendToExisting)
                        },
                        enabled = text.isNotBlank() && !ttsState.isGenerating && !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldActive,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.25f)
                            .height(52.dp)
                            .testTag("tts_insert_button")
                    ) {
                        if (ttsState.isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = StudioObsidian,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.tts_insert_button),
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
