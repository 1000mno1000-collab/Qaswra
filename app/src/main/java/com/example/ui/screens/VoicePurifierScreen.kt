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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.dsp.VoicePurifyPreset
import com.example.model.EffectType
import com.example.model.NoiseReductionParams
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
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoicePurifierScreen(
    uiState: StudioUiState,
    noiseSuppressionDb: Float,
    onTogglePurifier: () -> Unit,
    onApplyPreset: (VoicePurifyPreset) -> Unit,
    onUpdateParams: ((NoiseReductionParams) -> NoiseReductionParams) -> Unit,
    onRunDeepCleanOffline: () -> Unit,
    onUndoEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled = uiState.dspConfig.chain.firstOrNull { it.type == EffectType.NOISE_REDUCTION }?.enabled == true
    val nr = uiState.dspConfig.noiseReduction

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Live Purifier & Noise Suppression Meter
        Surface(
            color = StudioSurface,
            border = BorderStroke(
                1.dp,
                if (isEnabled) EmeraldActive.copy(alpha = 0.65f) else StudioBorder
            ),
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = if (isEnabled) EmeraldActive else TextSecondary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "المُنقّي الصوتي الحي (Real-Time)",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (isEnabled) {
                                    "يعزل الضجيج والهمهمة مباشرة أثناء التشغيل"
                                } else {
                                    "متوقف — شغّله لمعاينة التصفية فوراً"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isEnabled) EmeraldActive else TextSecondary
                            )
                        }
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { onTogglePurifier() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioObsidian,
                            checkedTrackColor = EmeraldActive
                        ),
                        modifier = Modifier.testTag("purifier_enable_switch")
                    )
                }

                // Live Noise Attenuation Meter
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "مقدار عزل الضوضاء الحالي",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "-%.1f dB", noiseSuppressionDb),
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldActive
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (noiseSuppressionDb / 24f).coerceIn(0f, 1f) },
                        color = EmeraldActive,
                        trackColor = StudioSurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                // Spectral Engine Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "العزل الطيفي الذكي (STFT Spectral)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "يفصل صوت المتحدث عن ضجيج المراوح والهواء حتى أثناء الكلام",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = nr.spectralClean,
                        onCheckedChange = { checked ->
                            onUpdateParams { it.copy(spectralClean = checked) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioObsidian,
                            checkedTrackColor = CyanGlow
                        ),
                        modifier = Modifier.testTag("purifier_spectral_switch")
                    )
                }
            }
        }

        // Studio Purification Presets
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = CyanGlow)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "أوضاع التصفية الجاهزة",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoicePurifyPreset.entries.forEach { preset ->
                        val isMatch = isEnabled &&
                            abs(nr.amount - preset.params.amount) < 0.03f &&
                            abs(nr.thresholdDb - preset.params.thresholdDb) < 1.5f &&
                            abs(nr.vocalClarity - preset.params.vocalClarity) < 0.03f

                        FilterChip(
                            selected = isMatch,
                            onClick = { onApplyPreset(preset) },
                            label = {
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldActive.copy(alpha = 0.22f),
                                selectedLabelColor = EmeraldActive
                            ),
                            modifier = Modifier.testTag("purify_preset_${preset.id}")
                        )
                    }
                }
            }
        }

        // Precision 5-stage Purification Controls
        StudioParamSlider(
            label = "قوة العزل الطيفي للضجيج",
            value = nr.amount,
            valueRange = 0f..1f,
            formattedValue = "${(nr.amount * 100).toInt()}%",
            onValueChange = { v -> onUpdateParams { it.copy(amount = v) } },
            accentColor = EmeraldActive,
            testTag = "purifier_amount_slider"
        )

        StudioParamSlider(
            label = "عتبة بوابة الصمت (Gate Threshold)",
            value = nr.thresholdDb,
            valueRange = -65f..-15f,
            formattedValue = "${nr.thresholdDb.toInt()} dB",
            onValueChange = { v -> onUpdateParams { it.copy(thresholdDb = v) } },
            accentColor = CyanGlow,
            testTag = "purifier_threshold_slider"
        )

        StudioParamSlider(
            label = "تصفية الهواء والهمهمة السفلية (HPF)",
            value = nr.highPassHz,
            valueRange = 30f..180f,
            formattedValue = "${nr.highPassHz.toInt()} Hz",
            onValueChange = { v -> onUpdateParams { it.copy(highPassHz = v) } },
            accentColor = VioletGlow,
            testTag = "purifier_hpf_slider"
        )

        StudioParamSlider(
            label = "صفاء الصوت البشري وحضوره (Clarity)",
            value = nr.vocalClarity,
            valueRange = 0f..1f,
            formattedValue = "${(nr.vocalClarity * 100).toInt()}%",
            onValueChange = { v -> onUpdateParams { it.copy(vocalClarity = v) } },
            accentColor = CyanGlow,
            testTag = "purifier_clarity_slider"
        )

        StudioParamSlider(
            label = "تنعيم الحروف الحادة والصفير (De-Esser)",
            value = nr.deEssAmount,
            valueRange = 0f..1f,
            formattedValue = "${(nr.deEssAmount * 100).toInt()}%",
            onValueChange = { v -> onUpdateParams { it.copy(deEssAmount = v) } },
            accentColor = AmberWarm,
            testTag = "purifier_deess_slider"
        )

        // Deep Profiled Offline Purification Action Card
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "التصفية العميقة ببصمة الضوضاء",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanGlow
                )
                Text(
                    text = "يحلل أهدأ أجزاء المقطع لالتقاط بصمة الضوضاء الحقيقية، ثم ينقي كامل الصوت بدقة عالية ويحفظه داخل المحرر مع إمكانية التراجع.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (uiState.purifyProgress != null) {
                    LinearProgressIndicator(
                        progress = { uiState.purifyProgress },
                        color = EmeraldActive,
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
                        onClick = onRunDeepCleanOffline,
                        enabled = uiState.hasAudio && uiState.purifyProgress == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldActive,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("purify_deep_apply_button")
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "تطبيق التصفية العميقة الآن",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (uiState.canUndoEdit) {
                        OutlinedButton(
                            onClick = onUndoEdit,
                            border = BorderStroke(1.dp, AmberWarm.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarm),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("purify_undo_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "تراجع")
                        }
                    }
                }
            }
        }
    }
}
