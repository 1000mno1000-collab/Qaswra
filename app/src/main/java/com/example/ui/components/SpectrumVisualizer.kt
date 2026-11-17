package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time 60 FPS frequency spectrum visualizer built on Jetpack Compose Canvas.
 * Renders 32 logarithmic frequency bands (55 Hz – 14 kHz) with zero per-frame allocations,
 * fast attack / smooth exponential decay ballistics, and falling peak-hold caps.
 */
@Composable
fun SpectrumVisualizer(
    spectrumBands: List<Float>,
    isActive: Boolean,
    language: AppLanguage,
    peakLevel: Float = 0f,
    accentColor: Color = StudioColors.Cyan,
    title: String = t(language, "spectrum_visualizer_title"),
    subtitle: String = t(language, "spectrum_visualizer_subtitle"),
    modifier: Modifier = Modifier,
) {
    val bandCount = 32
    val smoothedBands = remember { FloatArray(bandCount) { 0.03f } }
    val peakCaps = remember { FloatArray(bandCount) { 0.04f } }
    val peakHoldElapsedSec = remember { FloatArray(bandCount) { 0f } }

    val latestSpectrum by rememberUpdatedState(spectrumBands)
    val latestIsActive by rememberUpdatedState(isActive)
    var frameTick by remember { mutableIntStateOf(0) }

    // Fixed-step 60 FPS frame ticker (~16.67 ms cadence) driving smooth spectral ballistics
    LaunchedEffect(isActive) {
        var lastNanos = 0L
        var accumulatorSec = 0f
        var idlePhase = 0f
        val targetFrameStepSec = 1f / 60f

        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos == 0L) {
                    lastNanos = frameNanos
                    return@withFrameNanos
                }
                val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = frameNanos
                accumulatorSec += dt

                // Pace state updates cleanly at 60 FPS even on 90/120 Hz panels
                if (accumulatorSec >= targetFrameStepSec * 0.85f || !latestIsActive) {
                    val stepDt = accumulatorSec.coerceIn(0.01f, 0.05f)
                    accumulatorSec = 0f
                    idlePhase += stepDt * 2.2f

                    val input = latestSpectrum
                    var anyMoving = latestIsActive

                    for (i in 0 until bandCount) {
                        val rawTarget = if (latestIsActive) {
                            val srcIdx = if (input.isNotEmpty()) {
                                ((i.toFloat() / bandCount) * input.size).toInt().coerceIn(0, input.lastIndex)
                            } else 0
                            input.getOrElse(srcIdx) { 0.03f }.coerceIn(0.03f, 1f)
                        } else {
                            // Calm acoustic noise-floor shimmer when idle
                            0.03f + 0.014f * ((sin(idlePhase + i * 0.35f) + 1f) * 0.5f)
                        }

                        val current = smoothedBands[i]
                        val alpha = if (rawTarget > current) {
                            1f - kotlin.math.exp(-stepDt * 34f) // snappy attack
                        } else {
                            1f - kotlin.math.exp(-stepDt * 12f) // natural studio decay
                        }
                        val next = (current + (rawTarget - current) * alpha).coerceIn(0.025f, 1f)
                        if (kotlin.math.abs(next - current) > 0.0015f) anyMoving = true
                        smoothedBands[i] = next

                        if (next >= peakCaps[i]) {
                            peakCaps[i] = next
                            peakHoldElapsedSec[i] = 0f
                        } else {
                            peakHoldElapsedSec[i] += stepDt
                            if (peakHoldElapsedSec[i] > 0.28f) {
                                peakCaps[i] = max(next, peakCaps[i] - stepDt * 0.65f)
                                if (peakCaps[i] > 0.05f) anyMoving = true
                            }
                        }
                    }
                    frameTick++
                    if (!latestIsActive && !anyMoving) {
                        // Still keep gentle floor alive without spinning unnecessarily fast
                        accumulatorSec = -0.04f
                    }
                }
            }
        }
    }

    val peakDbText = remember(peakLevel, isActive) {
        if (!isActive || peakLevel <= 0.002f) {
            "-∞ dB"
        } else {
            val db = (20.0 * ln(peakLevel.toDouble().coerceAtLeast(1e-4)) / ln(10.0)).coerceIn(-60.0, 0.0)
            "${"%.1f".format(db)} dB"
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.5f) else StudioColors.Border,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("spectrum_visualizer_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isActive) StudioColors.EmeraldBright else StudioColors.TextMuted),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextSecondary,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = t(language, "spectrum_visualizer_badge"),
                            style = TelemetryTextStyle.copy(fontSize = 10.sp),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        text = if (isActive) peakDbText else t(language, "spectrum_status_idle"),
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = if (isActive) StudioColors.EmeraldBright else StudioColors.TextMuted,
                    )
                }
            }

            // Pre-cached brushes keyed only by theme colors
            val bgBrush = remember {
                Brush.verticalGradient(
                    colors = listOf(
                        StudioColors.BgTop,
                        StudioColors.BgMid,
                        StudioColors.BgTop,
                    ),
                )
            }
            val barBrush = remember(accentColor) {
                Brush.verticalGradient(
                    colors = listOf(
                        StudioColors.RoseBright,
                        StudioColors.Amber,
                        StudioColors.VioletBright,
                        accentColor,
                        accentColor.copy(alpha = 0.42f),
                    ),
                )
            }
            val mirrorBrush = remember(accentColor) {
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.24f),
                        Color.Transparent,
                    ),
                )
            }

            val canvasDesc = title
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bgBrush)
                    .semantics { contentDescription = canvasDesc }
                    .testTag("spectrum_visualizer_canvas"),
            ) {
                // Subscribe DrawScope cleanly to the 60 FPS frameTick
                val tickRead = frameTick
                @Suppress("UNUSED_EXPRESSION")
                tickRead

                val w = size.width
                val h = size.height
                val mainHeight = h * 0.82f
                val floorY = mainHeight
                val mirrorHeight = h - floorY

                // Subtle dB reference grid lines (-12dB, -24dB, -36dB, -48dB)
                for (g in 1..4) {
                    val y = floorY * (g / 5f)
                    drawLine(
                        color = StudioColors.Border.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }

                // Vertical octave dividers
                val octaveFractions = floatArrayOf(0.2f, 0.45f, 0.72f)
                for (f in octaveFractions) {
                    val x = w * f
                    drawLine(
                        color = StudioColors.Border.copy(alpha = 0.35f),
                        start = Offset(x, 4f),
                        end = Offset(x, floorY),
                        strokeWidth = 1f,
                    )
                }

                // Floor baseline
                drawLine(
                    color = accentColor.copy(alpha = if (isActive) 0.45f else 0.2f),
                    start = Offset(0f, floorY),
                    end = Offset(w, floorY),
                    strokeWidth = 1.5f,
                )

                val horizontalPad = 8.dp.toPx()
                val usableW = max(1f, w - horizontalPad * 2f)
                val slotW = usableW / bandCount
                val barW = max(3f, slotW * 0.68f)
                val barOffset = (slotW - barW) * 0.5f
                val corner = CornerRadius(barW * 0.38f, barW * 0.38f)

                for (i in 0 until bandCount) {
                    val level = smoothedBands[i]
                    val barH = max(3f, level * (mainHeight - 10f))
                    val x = horizontalPad + i * slotW + barOffset
                    val topY = floorY - barH

                    // Active spectrum bar
                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(x, topY),
                        size = Size(barW, barH),
                        cornerRadius = corner,
                    )

                    // Mirror floor reflection
                    val reflH = min(mirrorHeight - 2f, barH * 0.28f)
                    if (reflH > 1f) {
                        drawRoundRect(
                            brush = mirrorBrush,
                            topLeft = Offset(x, floorY + 1.5f),
                            size = Size(barW, reflH),
                            cornerRadius = corner,
                        )
                    }

                    // Peak-hold cap indicator
                    val capLevel = peakCaps[i]
                    val capY = (floorY - max(4f, capLevel * (mainHeight - 10f)) - 3f).coerceAtLeast(3f)
                    val capColor = when {
                        capLevel > 0.85f -> StudioColors.RoseBright
                        capLevel > 0.6f -> StudioColors.Amber
                        else -> accentColor
                    }
                    drawRoundRect(
                        color = capColor.copy(alpha = if (isActive) 0.95f else 0.4f),
                        topLeft = Offset(x, capY),
                        size = Size(barW, 2.5.dp.toPx()),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }
            }

            // Frequency axis labels (Hz / kHz)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val freqLabels = remember { listOf("60Hz", "250Hz", "1kHz", "4kHz", "12kHz") }
                freqLabels.forEach { freq ->
                    Text(
                        text = freq,
                        style = TelemetryTextStyle.copy(fontSize = 10.sp),
                        color = StudioColors.TextMuted,
                    )
                }
            }
        }
    }
}
