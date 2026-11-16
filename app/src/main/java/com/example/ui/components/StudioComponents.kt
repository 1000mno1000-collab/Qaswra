package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
fun StudioFocusTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioMidnight.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, StudioBorder.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .testTag("back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = CyanGlow
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PersistentTransportBar(
    hasAudio: Boolean,
    isPlaying: Boolean,
    progress: Float,
    durationMs: Long,
    outputLevel: Float,
    bypassAll: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onToggleBypass: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioMidnight.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, StudioBorder),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Subtle progress bar + tap to seek
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(hasAudio) {
                        if (!hasAudio) return@pointerInput
                        detectTapGestures { offset ->
                            // RTL visual mapping: right edge is 0, left edge is 1
                            val frac = (1f - (offset.x / size.width)).coerceIn(0f, 1f)
                            onSeek(frac)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                    drawRoundRect(
                        color = StudioSurfaceElevated,
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    val filledW = size.width * progress.coerceIn(0f, 1f)
                    if (filledW > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(VioletGlow, CyanGlow)),
                            topLeft = Offset(size.width - filledW, 0f),
                            size = Size(filledW, size.height),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onTogglePlay,
                        enabled = hasAudio,
                        shape = CircleShape,
                        color = if (hasAudio) CyanGlow else StudioSurfaceElevated,
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("transport_play_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                                tint = if (hasAudio) StudioObsidian else TextMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    IconButton(
                        onClick = onStop,
                        enabled = hasAudio,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(StudioSurface)
                            .testTag("transport_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.stop),
                            tint = if (hasAudio) TextPrimary else TextMuted
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        val currentMs = (durationMs * progress).toLong()
                        Text(
                            text = "${formatDurationMs(currentMs)} / ${formatDurationMs(durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hasAudio) TextPrimary else TextMuted
                        )
                        // Mini live audio level bar
                        Spacer(Modifier.height(4.dp))
                        Canvas(modifier = Modifier.width(88.dp).height(4.dp)) {
                            drawRoundRect(color = StudioSurfaceElevated, cornerRadius = CornerRadius(4f, 4f))
                            val lvl = (outputLevel * 1.4f).coerceIn(0f, 1f)
                            if (lvl > 0.01f) {
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(listOf(EmeraldActive, CyanGlow, AmberWarm)),
                                    size = Size(size.width * lvl, size.height),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }
                        }
                    }
                }

                // DSP Bypass toggle pill
                Surface(
                    onClick = onToggleBypass,
                    shape = RoundedCornerShape(50),
                    color = if (bypassAll) StudioSurface else CyanGlow.copy(alpha = 0.16f),
                    border = BorderStroke(
                        1.dp,
                        if (bypassAll) StudioBorder else CyanGlow.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("transport_bypass_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = if (bypassAll) TextMuted else CyanGlow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(if (bypassAll) R.string.bypass_all else R.string.dsp_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (bypassAll) TextSecondary else CyanGlow
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    onValueChange: (Float) -> Unit,
    accentColor: Color = CyanGlow,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = formattedValue,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = StudioSurfaceElevated
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            )
        }
    }
}

@Composable
fun AnimatedAcousticRibbon(
    peaks: List<Float>,
    isActive: Boolean,
    liveEnergy: Float,
    progress: Float = 0f,
    accentPrimary: Color = CyanGlow,
    accentSecondary: Color = VioletGlow,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "acoustic_ribbon")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f

        if (peaks.isNotEmpty()) {
            val count = peaks.size
            val barWidth = (w / count) * 0.62f
            val step = w / count
            for (i in 0 until count) {
                val amp = peaks[i].coerceIn(0.04f, 1f)
                val x = w - (i * step + step * 0.5f) // RTL progression
                val normIdx = i.toFloat() / count.toFloat()
                val played = normIdx <= progress
                val boost = if (isActive) (1f + liveEnergy * 0.25f) else 1f
                val barH = (amp * h * 0.84f * boost).coerceIn(4f, h * 0.92f)
                val color = if (played) accentPrimary else accentSecondary.copy(alpha = 0.45f)
                drawLine(
                    color = color,
                    start = Offset(x, centerY - barH / 2f),
                    end = Offset(x, centerY + barH / 2f),
                    strokeWidth = barWidth.coerceAtLeast(2f),
                    cap = StrokeCap.Round
                )
            }
        } else {
            // Flowing harmonic sine ribbons when no waveform loaded yet
            val ribbons = 3
            for (r in 0 until ribbons) {
                val path = Path()
                val amp = (h * (0.14f + r * 0.05f)) * (if (isActive) (0.7f + liveEnergy * 1.4f) else 0.45f)
                val freq = 1.6f + r * 0.7f
                val ribbonPhase = phase * (1f + r * 0.35f) + r * 1.1f
                val steps = 72
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    val x = t * w
                    val envelope = sin(t * PI).toFloat()
                    val y = centerY + sin((t * freq * 2f * PI + ribbonPhase).toDouble()).toFloat() * amp * envelope
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            accentPrimary.copy(alpha = 0.7f - r * 0.18f),
                            accentSecondary.copy(alpha = 0.75f - r * 0.2f),
                            AmberWarm.copy(alpha = 0.45f)
                        )
                    ),
                    style = Stroke(width = (3.5f - r * 0.8f).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * Real-time Canvas audio waveform visualizer driven by live AudioRecord PCM frames during
 * recording and processed post-DSP PCM frames during playback.
 */
@Composable
fun RealTimeWaveformCanvas(
    liveSamples: FloatArray,
    fallbackPeaks: List<Float>,
    isLive: Boolean,
    liveEnergy: Float,
    progress: Float = 0f,
    accentPrimary: Color = CyanGlow,
    accentSecondary: Color = VioletGlow,
    modifier: Modifier = Modifier
) {
    if (!isLive || liveSamples.isEmpty()) {
        AnimatedAcousticRibbon(
            peaks = fallbackPeaks,
            isActive = isLive,
            liveEnergy = liveEnergy,
            progress = progress,
            accentPrimary = accentPrimary,
            accentSecondary = accentSecondary,
            modifier = modifier
        )
        return
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val count = liveSamples.size
        if (count < 2) return@Canvas

        // Subtle center reference axis
        drawLine(
            color = accentSecondary.copy(alpha = 0.22f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx()
        )

        // Mirrored vertical energy bars from real-time PCM samples
        val barCount = 48.coerceAtMost(count)
        val stride = (count.toFloat() / barCount.toFloat()).coerceAtLeast(1f)
        val slotW = w / barCount
        for (i in 0 until barCount) {
            val sampleIdx = (i * stride).toInt().coerceIn(0, count - 1)
            val amp = abs(liveSamples[sampleIdx]).coerceIn(0.02f, 1f)
            val boosted = (amp * 1.35f).coerceIn(0.03f, 1f)
            val barH = (boosted * h * 0.78f).coerceAtLeast(3f)
            val x = w - (i * slotW + slotW * 0.5f)
            drawLine(
                color = accentSecondary.copy(alpha = 0.28f + boosted * 0.35f),
                start = Offset(x, midY - barH / 2f),
                end = Offset(x, midY + barH / 2f),
                strokeWidth = (slotW * 0.45f).coerceAtLeast(2f),
                cap = StrokeCap.Round
            )
        }

        // Real-time signed PCM oscilloscope curve + filled glow envelope
        val wavePath = Path()
        val fillPath = Path()
        val xStep = w / (count - 1).coerceAtLeast(1)

        for (i in 0 until count) {
            val x = w - i * xStep // RTL flow
            val sample = liveSamples[i].coerceIn(-1f, 1f)
            val y = midY - sample * (h * 0.44f)
            if (i == 0) {
                wavePath.moveTo(x, y)
                fillPath.moveTo(x, midY)
                fillPath.lineTo(x, y)
            } else {
                val prevX = w - (i - 1) * xStep
                val prevY = midY - liveSamples[i - 1].coerceIn(-1f, 1f) * (h * 0.44f)
                val cx = (prevX + x) * 0.5f
                wavePath.quadraticTo(prevX, prevY, cx, (prevY + y) * 0.5f)
                fillPath.quadraticTo(prevX, prevY, cx, (prevY + y) * 0.5f)
                if (i == count - 1) {
                    wavePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
        }
        fillPath.lineTo(0f, midY)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(
                    accentPrimary.copy(alpha = 0.28f),
                    accentSecondary.copy(alpha = 0.08f),
                    accentPrimary.copy(alpha = 0.28f)
                )
            )
        )

        drawPath(
            path = wavePath,
            brush = Brush.horizontalGradient(listOf(accentSecondary, accentPrimary, AmberWarm)),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun RangeWaveformEditorCanvas(
    peaks: List<Float>,
    trimStart: Float,
    trimEnd: Float,
    playbackProgress: Float,
    onRangeChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingStart by remember { mutableStateOf(false) }
    var draggingEnd by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(StudioObsidian)
            .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
            .pointerInput(trimStart, trimEnd) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // RTL: fraction 0 is right edge, 1 is left edge
                        val frac = (1f - offset.x / size.width).coerceIn(0f, 1f)
                        val distStart = abs(frac - trimStart)
                        val distEnd = abs(frac - trimEnd)
                        if (distStart <= distEnd) {
                            draggingStart = true
                            draggingEnd = false
                        } else {
                            draggingStart = false
                            draggingEnd = true
                        }
                    },
                    onDragEnd = {
                        draggingStart = false
                        draggingEnd = false
                    },
                    onDragCancel = {
                        draggingStart = false
                        draggingEnd = false
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val frac = (1f - change.position.x / size.width).coerceIn(0f, 1f)
                        if (draggingStart) {
                            onRangeChange(frac.coerceAtMost(trimEnd - 0.03f), trimEnd)
                        } else if (draggingEnd) {
                            onRangeChange(trimStart, frac.coerceAtLeast(trimStart + 0.03f))
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 14.dp)) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Selection region highlight (RTL: xStart is right, xEnd is left)
            val xStart = w * (1f - trimStart)
            val xEnd = w * (1f - trimEnd)
            val leftEdge = minOf(xStart, xEnd)
            val selWidth = abs(xStart - xEnd)

            drawRect(
                color = CyanGlow.copy(alpha = 0.12f),
                topLeft = Offset(leftEdge, 0f),
                size = Size(selWidth, h)
            )

            // Waveform bars
            val count = max(1, peaks.size)
            val step = w / count
            for (i in peaks.indices) {
                val frac = i.toFloat() / count.toFloat()
                val inRange = frac in trimStart..trimEnd
                val x = w - (i * step + step * 0.5f)
                val barH = (peaks[i].coerceIn(0.05f, 1f) * h * 0.85f)
                drawLine(
                    color = if (inRange) CyanGlow else TextMuted.copy(alpha = 0.35f),
                    start = Offset(x, midY - barH / 2f),
                    end = Offset(x, midY + barH / 2f),
                    strokeWidth = (step * 0.58f).coerceAtLeast(2f),
                    cap = StrokeCap.Round
                )
            }

            // Playback head
            val playX = w * (1f - playbackProgress.coerceIn(0f, 1f))
            drawLine(
                color = Color.White,
                start = Offset(playX, 0f),
                end = Offset(playX, h),
                strokeWidth = 2.dp.toPx()
            )

            // Start & End trim handles
            drawLine(
                color = EmeraldActive,
                start = Offset(xStart, 0f),
                end = Offset(xStart, h),
                strokeWidth = 3.dp.toPx()
            )
            drawCircle(
                color = EmeraldActive,
                radius = 7.dp.toPx(),
                center = Offset(xStart, 10.dp.toPx())
            )

            drawLine(
                color = AmberWarm,
                start = Offset(xEnd, 0f),
                end = Offset(xEnd, h),
                strokeWidth = 3.dp.toPx()
            )
            drawCircle(
                color = AmberWarm,
                radius = 7.dp.toPx(),
                center = Offset(xEnd, h - 10.dp.toPx())
            )
        }
    }
}

@Composable
fun EmptyAudioNotice(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(CyanGlow.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = CyanGlow,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(16.dp),
                color = CyanGlow,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioObsidian,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}
