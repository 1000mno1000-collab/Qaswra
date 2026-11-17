package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import java.util.Locale

@Composable
fun EditorFadeControlsCard(
    onApplyFadeIn: (Float) -> Unit,
    onApplyFadeOut: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var fadeInSeconds by remember { mutableFloatStateOf(2.0f) }
    var fadeOutSeconds by remember { mutableFloatStateOf(3.0f) }

    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "تدرج الصوت (Fade In / Fade Out)",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            StudioParamSlider(
                label = stringResource(R.string.fade_in_title),
                value = fadeInSeconds,
                valueRange = 1f..10f,
                formattedValue = String.format(Locale.US, "%.1f ثانية", fadeInSeconds),
                onValueChange = { fadeInSeconds = it },
                accentColor = EmeraldActive
            )

            Button(
                onClick = { onApplyFadeIn(fadeInSeconds) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldActive,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("editor_fade_in_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "تطبيق ظهور تدريجي",
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(2.dp))

            StudioParamSlider(
                label = stringResource(R.string.fade_out_title),
                value = fadeOutSeconds,
                valueRange = 1f..10f,
                formattedValue = String.format(Locale.US, "%.1f ثانية", fadeOutSeconds),
                onValueChange = { fadeOutSeconds = it },
                accentColor = CyanGlow
            )

            Button(
                onClick = { onApplyFadeOut(fadeOutSeconds) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanGlow,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("editor_fade_out_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.TrendingDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "تطبيق اختفاء تدريجي",
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EditorDualTrackMixCard(
    backgroundName: String?,
    backgroundDurationMs: Long,
    onPickBackgroundTrack: () -> Unit,
    onClearBackgroundTrack: () -> Unit,
    onApplyMix: (mainGain: Float, bgGain: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var mainVoiceGain by remember { mutableFloatStateOf(1.0f) }
    var backgroundGain by remember { mutableFloatStateOf(0.45f) }

    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Layers, contentDescription = null, tint = CyanGlow)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.mix_tracks_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = CyanGlow
                )
            }

            Text(
                text = "استورد ملفاً صوتياً ثانياً لدمجه فوق مسار صوتك في نفس التوقيت (خلفية موسيقية أو مؤثر مزدوج) مع تحكم مستقل بمستوى كل مسار.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (backgroundName == null) {
                OutlinedButton(
                    onClick = onPickBackgroundTrack,
                    border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("editor_pick_mix_track_button")
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "استيراد مسار الخلفية للمزج",
                        color = CyanGlow,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Surface(
                    color = StudioObsidian,
                    border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = EmeraldActive)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = backgroundName,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "مدة الخلفية: ${formatDurationMs(backgroundDurationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = EmeraldActive
                            )
                        }
                        IconButton(onClick = onClearBackgroundTrack) {
                            Icon(Icons.Rounded.Close, contentDescription = "إزالة مسار الخلفية", tint = TextSecondary)
                        }
                    }
                }

                StudioParamSlider(
                    label = "مستوى الصوت الرئيسي (Main Voice)",
                    value = mainVoiceGain,
                    valueRange = 0f..1.5f,
                    formattedValue = "${(mainVoiceGain * 100).toInt()}%",
                    onValueChange = { mainVoiceGain = it },
                    accentColor = EmeraldActive
                )

                StudioParamSlider(
                    label = "مستوى مسار الخلفية (Background)",
                    value = backgroundGain,
                    valueRange = 0f..1.5f,
                    formattedValue = "${(backgroundGain * 100).toInt()}%",
                    onValueChange = { backgroundGain = it },
                    accentColor = VioletGlow
                )

                Button(
                    onClick = { onApplyMix(mainVoiceGain, backgroundGain) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanGlow,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("editor_apply_mix_button")
                ) {
                    Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "تطبيق المزج الصوتي",
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
