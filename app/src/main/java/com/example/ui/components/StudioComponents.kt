package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import kotlin.math.roundToInt

@Composable
fun StudioScrollColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun StudioFocusTopBar(
    title: String,
    subtitle: String? = null,
    language: AppLanguage,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioColors.CardSurface,
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = StudioColors.ElevatedSurface,
                    contentColor = StudioColors.Cyan,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = t(language, "back"),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
fun StudioSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    badgeText: String? = null,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.14f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = accentColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = accentColor,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = StudioColors.TextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun StudioParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    formattedValue: String,
    accentColor: Color = StudioColors.Cyan,
    steps: Int = 0,
    testTag: String,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = StudioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = StudioColors.BgTop,
                border = BorderStroke(1.dp, StudioColors.Border),
            ) {
                Text(
                    text = formattedValue,
                    style = TelemetryTextStyle,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = StudioColors.ElevatedSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        )
    }
}

@Composable
fun RealTimeWaveformCanvas(
    bars: List<Float>,
    isActive: Boolean,
    accentColor: Color = StudioColors.Cyan,
    modifier: Modifier = Modifier,
) {
    val activeBrush = Brush.verticalGradient(
        colors = listOf(accentColor, StudioColors.Violet),
    )
    val inactiveBrush = Brush.verticalGradient(
        colors = listOf(StudioColors.WaveformInactive, StudioColors.WaveformTrack),
    )

    Canvas(modifier = modifier) {
        val count = bars.size.coerceAtLeast(1)
        val centerY = size.height / 2f

        drawLine(
            color = StudioColors.Border.copy(alpha = 0.6f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
        )

        val stepW = size.width / count
        val barW = (stepW * 0.56f).coerceAtLeast(2.dp.toPx())
        val radius = barW / 2f

        for (i in 0 until count) {
            val amp = bars[i].coerceIn(0.05f, 1f)
            val barH = amp * size.height * 0.88f
            val left = i * stepW + (stepW - barW) / 2f
            val top = centerY - barH / 2f
            drawRoundRect(
                brush = if (isActive) activeBrush else inactiveBrush,
                topLeft = Offset(left, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
fun PersistentTransportBar(
    uiState: StudioUiState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleAutoLoop: () -> Unit,
    onToggleBypass: () -> Unit,
    onHoldBypassChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = uiState.language
    val durationMs = if (uiState.playback.durationMs > 0L) uiState.playback.durationMs else uiState.durationMs
    val progress = if (durationMs > 0L) {
        (uiState.playback.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val effectiveBypass = uiState.dspConfig.bypassAll || uiState.isHoldBypassing
    val meterColor = when {
        uiState.playback.outputLevel > 0.85f -> StudioColors.Rose
        uiState.playback.outputLevel > 0.6f -> StudioColors.Amber
        else -> StudioColors.Emerald
    }

    Surface(
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = StudioColors.CardSurface,
        border = BorderStroke(1.dp, StudioColors.BorderBright.copy(alpha = 0.7f)),
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatDurationMs(uiState.playback.positionMs),
                        style = TelemetryTextStyle,
                        color = StudioColors.Cyan,
                    )

                    Slider(
                        value = progress,
                        onValueChange = onSeekFraction,
                        enabled = uiState.hasAudio,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioColors.Cyan,
                            activeTrackColor = StudioColors.Cyan,
                            inactiveTrackColor = StudioColors.ElevatedSurface,
                            disabledThumbColor = StudioColors.TextMuted,
                            disabledActiveTrackColor = StudioColors.WaveformInactive,
                            disabledInactiveTrackColor = StudioColors.BgTop,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .testTag("transport_seek_slider"),
                    )

                    Text(
                        text = formatDurationMs(durationMs),
                        style = TelemetryTextStyle,
                        color = StudioColors.TextSecondary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPlayPause,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.playback.isPlaying) StudioColors.Amber else StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("transport_play_pause_button"),
                    ) {
                        Icon(
                            imageVector = if (uiState.playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (uiState.playback.isPlaying) t(lang, "pause") else t(lang, "play"),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (uiState.playback.isPlaying) t(lang, "pause") else t(lang, "play"),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }

                    IconButton(
                        onClick = onStop,
                        enabled = uiState.hasAudio,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.TextPrimary,
                            disabledContainerColor = StudioColors.BgMid,
                            disabledContentColor = StudioColors.TextMuted,
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("transport_stop_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = t(lang, "stop"),
                        )
                    }

                    IconButton(
                        onClick = onToggleAutoLoop,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (uiState.autoLoopOnPlayback) StudioColors.Emerald.copy(alpha = 0.2f) else StudioColors.ElevatedSurface,
                            contentColor = if (uiState.autoLoopOnPlayback) StudioColors.Emerald else StudioColors.TextSecondary,
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("transport_loop_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Repeat,
                            contentDescription = t(lang, "loop_repeat"),
                        )
                    }

                    // Output level meter pill
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(StudioColors.BgTop),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(uiState.playback.outputLevel.coerceIn(0.02f, 1f))
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(meterColor),
                            )
                        }
                        Text(
                            text = "${(uiState.playback.outputLevel * 100f).roundToInt()}%",
                            style = TelemetryTextStyle.copy(fontSize = 10.sp),
                            color = StudioColors.TextMuted,
                            maxLines = 1,
                        )
                    }

                    // A/B Compare / Bypass chip supporting tap-to-toggle AND press-and-hold
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (effectiveBypass) StudioColors.Amber.copy(alpha = 0.18f) else StudioColors.ElevatedSurface,
                        border = BorderStroke(
                            1.dp,
                            if (effectiveBypass) StudioColors.Amber else StudioColors.BorderBright,
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        onHoldBypassChange(true)
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            onHoldBypassChange(false)
                                        }
                                    },
                                    onTap = {
                                        onToggleBypass()
                                    },
                                )
                            }
                            .testTag("transport_bypass_chip"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = if (effectiveBypass) Icons.Rounded.GraphicEq else Icons.Rounded.Tune,
                                contentDescription = if (effectiveBypass) t(lang, "bypass_all") else t(lang, "dsp_active"),
                                tint = if (effectiveBypass) StudioColors.Amber else StudioColors.Cyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (effectiveBypass) t(lang, "bypass_all") else t(lang, "dsp_active"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (effectiveBypass) StudioColors.Amber else StudioColors.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
