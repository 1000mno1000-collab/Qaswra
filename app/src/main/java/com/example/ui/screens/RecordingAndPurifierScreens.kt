package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.EffectType
import com.example.model.VOICE_PURIFY_PRESETS
import com.example.ui.components.RealTimeWaveformCanvas
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioScrollColumn
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

@Composable
fun RecordingStudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.setMicPermissionDeniedError()
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importAudioUri(uri)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (uiState.recorder.isRecording && !uiState.recorder.isPaused) 1.16f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micScale",
    )

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_record_title"),
            subtitle = t(lang, "section_record_subtitle"),
            language = lang,
            onBack = onBack,
        )

        if (!uiState.micPermissionError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = StudioColors.Rose.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = t(lang, "mic_permission_title"),
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.RoseBright,
                    )
                    Text(
                        text = uiState.micPermissionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextPrimary,
                    )
                    Button(
                        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Rose,
                            contentColor = StudioColors.TextPrimary,
                        ),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("grant_mic_permission_button"),
                    ) {
                        Text(t(lang, "grant_permission"))
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (uiState.recorder.isRecording) {
                                StudioColors.Rose.copy(alpha = 0.2f)
                            } else {
                                StudioColors.ElevatedSurface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = t(lang, "section_record_title"),
                        tint = if (uiState.recorder.isRecording) StudioColors.Rose else StudioColors.Cyan,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Text(
                    text = formatDurationMs(uiState.recorder.elapsedMs),
                    style = TelemetryTextStyle.copy(fontSize = 32.sp),
                    color = if (uiState.recorder.isRecording) StudioColors.RoseBright else StudioColors.TextPrimary,
                )

                RealTimeWaveformCanvas(
                    bars = if (uiState.recorder.isRecording) uiState.recorder.waveformBars else uiState.playback.waveformBars,
                    isActive = uiState.recorder.isRecording || uiState.playback.isPlaying,
                    accentColor = if (uiState.recorder.isRecording) StudioColors.Rose else StudioColors.Cyan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                )

                // Mic Input Meter
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = t(lang, "mic_level"),
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioColors.TextSecondary,
                        )
                        Text(
                            text = "${(uiState.recorder.micLevel * 100f).roundToInt()}%",
                            style = TelemetryTextStyle,
                            color = StudioColors.Cyan,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(StudioColors.BgTop),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(uiState.recorder.micLevel.coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.recorder.micLevel > 0.85f) StudioColors.Rose else StudioColors.Emerald,
                                ),
                        )
                    }
                }

                if (!uiState.recorder.isRecording) {
                    Button(
                        onClick = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                viewModel.startRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Rose,
                            contentColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("record_start_button"),
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "record_start"), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (uiState.recorder.isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Amber,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("record_pause_resume_button"),
                        ) {
                            Icon(
                                imageVector = if (uiState.recorder.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (uiState.recorder.isPaused) t(lang, "record_resume") else t(lang, "record_pause"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.discardRecording() },
                            border = BorderStroke(1.dp, StudioColors.Rose),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("record_discard_button"),
                        ) {
                            Text(t(lang, "record_discard"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Button(
                        onClick = { viewModel.finishRecording() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Emerald,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("record_finish_button"),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "record_finish"), style = MaterialTheme.typography.labelLarge)
                    }
                }

                OutlinedButton(
                    onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                    border = BorderStroke(1.dp, StudioColors.Cyan),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("record_import_audio_button"),
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "import_audio"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Saved Recordings Archive
        if (uiState.recordings.isNotEmpty()) {
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
                        text = if (lang == AppLanguage.EN) "Studio Recordings Archive" else "أرشيف التسجيلات والاستيراد",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                    )

                    uiState.recordings.forEach { rec ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.BgMid,
                            border = BorderStroke(1.dp, StudioColors.Border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = rec.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${formatDurationMs(rec.durationMs)} • ${rec.sampleRate} Hz • ${rec.sourceType.name}",
                                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                                        color = StudioColors.TextSecondary,
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.loadRecordingIntoStudio(rec) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = StudioColors.ElevatedSurface,
                                        contentColor = StudioColors.Cyan,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("recording_load_${rec.id}"),
                                ) {
                                    Icon(Icons.Rounded.FileOpen, contentDescription = t(lang, "load"))
                                }

                                IconButton(
                                    onClick = { viewModel.deleteRecording(rec) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = StudioColors.ElevatedSurface,
                                        contentColor = StudioColors.RoseBright,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("recording_delete_${rec.id}"),
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = t(lang, "delete"))
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
fun VoicePurifierScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val nr = uiState.dspConfig.noiseReduction
    val enabled = uiState.dspConfig.isEnabled(EffectType.NOISE_REDUCTION)
    val suppNorm = (uiState.playback.noiseSuppressionDb / 32f).coerceIn(0f, 1f)

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_purifier_title"),
            subtitle = t(lang, "section_purifier_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.toggleEffectEnabled(EffectType.NOISE_REDUCTION, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioColors.BgTop,
                        checkedTrackColor = StudioColors.Emerald,
                    ),
                    modifier = Modifier.testTag("purifier_enable_switch"),
                )
            },
        )

        // Live Noise Suppression Telemetry Meter
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
                    Text(
                        text = if (lang == AppLanguage.EN) "Live Noise Suppression" else "مستوى عزل الضوضاء المباشر",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "-${"%.1f".format(uiState.playback.noiseSuppressionDb)} dB",
                        style = TelemetryTextStyle,
                        color = StudioColors.Emerald,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(StudioColors.BgTop),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(suppNorm.coerceAtLeast(0.02f))
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(StudioColors.Emerald),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (lang == AppLanguage.EN) "STFT 512-pt Spectral Cleaner" else "المحرك الطيفي (STFT 512-pt)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = nr.spectralCleanerEnabled,
                        onCheckedChange = { checked ->
                            viewModel.updateDspConfig { prev ->
                                prev.copy(noiseReduction = prev.noiseReduction.copy(spectralCleanerEnabled = checked))
                                    .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioColors.BgTop,
                            checkedTrackColor = StudioColors.Cyan,
                        ),
                        modifier = Modifier.testTag("purifier_spectral_switch"),
                    )
                }
            }
        }

        // 4 Voice Purify Presets
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
                    text = if (lang == AppLanguage.EN) "Quick Purification Modes" else "أوضاع التنقية السريعة",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                VOICE_PURIFY_PRESETS.forEach { preset ->
                    val isMatched = kotlin.math.abs(nr.spectralStrength - preset.spectralStrength) < 0.03f &&
                        kotlin.math.abs(nr.reductionDb - preset.reductionDb) < 1f
                    Surface(
                        onClick = { viewModel.applyVoicePurifyPreset(preset) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isMatched) StudioColors.ElevatedSurface else StudioColors.BgMid,
                        border = BorderStroke(
                            1.dp,
                            if (isMatched) StudioColors.Emerald else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .testTag("purify_preset_${preset.id}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (lang == AppLanguage.EN) preset.titleEn else preset.titleAr,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = if (lang == AppLanguage.EN) preset.subtitleEn else preset.subtitleAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                )
                            }
                            if (isMatched) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = StudioColors.Emerald,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Detailed Sliders
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
                    label = if (lang == AppLanguage.EN) "Spectral Denoise Strength" else "شدة العزل الطيفي",
                    value = nr.spectralStrength,
                    min = 0f,
                    max = 1f,
                    formattedValue = "${(nr.spectralStrength * 100f).roundToInt()}%",
                    accentColor = StudioColors.Emerald,
                    testTag = "purifier_spectral_strength_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(spectralStrength = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Noise Gate Threshold" else "عتبة البوابة الصوتية",
                    value = nr.thresholdDb,
                    min = -70f,
                    max = -18f,
                    formattedValue = "${nr.thresholdDb.roundToInt()} dB",
                    accentColor = StudioColors.Cyan,
                    testTag = "purifier_threshold_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(thresholdDb = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Noise Reduction Depth" else "عمق خفض الضوضاء",
                    value = nr.reductionDb,
                    min = 0f,
                    max = 36f,
                    formattedValue = "${nr.reductionDb.roundToInt()} dB",
                    accentColor = StudioColors.Cyan,
                    testTag = "purifier_reduction_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(reductionDb = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "High-Pass Rumble Filter" else "تصفية الترددات المنخفضة",
                    value = nr.highPassHz,
                    min = 20f,
                    max = 260f,
                    formattedValue = "${nr.highPassHz.roundToInt()} Hz",
                    accentColor = StudioColors.Violet,
                    testTag = "purifier_highpass_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(highPassHz = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "De-Esser (Sibilance)" else "إزالة صفير الحروف (De-Ess)",
                    value = nr.deEssAmount,
                    min = 0f,
                    max = 1f,
                    formattedValue = "${(nr.deEssAmount * 100f).roundToInt()}%",
                    accentColor = StudioColors.Amber,
                    testTag = "purifier_deess_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(deEssAmount = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                StudioParamSlider(
                    label = if (lang == AppLanguage.EN) "Vocal Clarity Boost" else "تعزيز وضوح الصوت",
                    value = nr.clarityBoostDb,
                    min = 0f,
                    max = 8f,
                    formattedValue = "+${"%.1f".format(nr.clarityBoostDb)} dB",
                    accentColor = StudioColors.Emerald,
                    testTag = "purifier_clarity_slider",
                    onValueChange = { v ->
                        viewModel.updateDspConfig { prev ->
                            prev.copy(noiseReduction = prev.noiseReduction.copy(clarityBoostDb = v))
                                .withSlotEnabled(EffectType.NOISE_REDUCTION, true)
                        }
                    },
                )

                uiState.purifyProgress?.let { prog ->
                    LinearProgressIndicator(
                        progress = { prog.coerceIn(0f, 1f) },
                        color = StudioColors.Emerald,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                Button(
                    onClick = { viewModel.purifyAudioOffline() },
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Emerald,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("purifier_deep_clean_button"),
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (lang == AppLanguage.EN) "Deep Offline Spectral Clean" else "تنقية عميقة للمقطع وحفظها في الاستوديو",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
