package com.example.ui.screens

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EchoReverbParams
import com.example.ui.components.StudioParamSlider
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.EchoDetailGroup
import com.example.viewmodel.EchoPadAxisMode
import com.example.viewmodel.StudioUiState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun EchoReverbScreen(
    uiState: StudioUiState,
    outputLevel: Float,
    onToggleProMode: (Boolean) -> Unit,
    onSelectAxisMode: (EchoPadAxisMode) -> Unit,
    onSpatialTouch: (Float, Float) -> Unit,
    onUpdateEchoReverb: ((EchoReverbParams) -> EchoReverbParams) -> Unit,
    onOpenDetailGroup: (EchoDetailGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val params = uiState.dspConfig.echoReverb

    // Compute current orb coordinates (0..1) from live parameters
    val (orbX, orbY) = if (!uiState.isProEchoMode) {
        ((params.reverbSize - 0.18f) / 0.80f).coerceIn(0f, 1f) to
            ((params.echoStrength - 0.08f) / 0.68f).coerceIn(0f, 1f)
    } else {
        when (uiState.echoPadAxisMode) {
            EchoPadAxisMode.DEPTH_AND_DIFFUSION -> params.echoDepth to params.diffusion
            EchoPadAxisMode.REFLECTIONS_BALANCE -> params.earlyReflections to params.lateReflections
            EchoPadAxisMode.DISTANCE_AND_DAMPING -> (params.preDelayMs / 140f).coerceIn(0f, 1f) to params.damping
            EchoPadAxisMode.ECHO_FEEDBACK -> ((params.delayTimeMs - 40f) / 1000f).coerceIn(0f, 1f) to
                (params.feedback / 0.85f).coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Simple vs Pro Mode Segmented Bar
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeSegmentButton(
                    label = stringResource(R.string.mode_simple),
                    selected = !uiState.isProEchoMode,
                    onClick = { onToggleProMode(false) },
                    modifier = Modifier.weight(1f).testTag("echo_mode_simple")
                )
                ModeSegmentButton(
                    label = stringResource(R.string.mode_pro),
                    selected = uiState.isProEchoMode,
                    onClick = { onToggleProMode(true) },
                    modifier = Modifier.weight(1f).testTag("echo_mode_pro")
                )
            }
        }

        // Pro Mode Axis Selector
        if (uiState.isProEchoMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EchoPadAxisMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.echoPadAxisMode == mode,
                        onClick = { onSelectAxisMode(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanGlow.copy(alpha = 0.2f),
                            selectedLabelColor = CyanGlow,
                            containerColor = StudioSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = uiState.echoPadAxisMode == mode,
                            borderColor = StudioBorder,
                            selectedBorderColor = CyanGlow
                        )
                    )
                }
            }
        }

        // Interactive 2D Spatial Echo Field
        SpatialEchoFieldCanvas(
            orbX = orbX,
            orbY = orbY,
            params = params,
            liveEnergy = outputLevel,
            xAxisLabel = if (uiState.isProEchoMode) uiState.echoPadAxisMode.xLabel else "المسافة وحجم القاعة",
            yAxisLabel = if (uiState.isProEchoMode) uiState.echoPadAxisMode.yLabel else "شدة الصدى والانتشار",
            onTouchPosition = onSpatialTouch,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .testTag("spatial_echo_pad")
        )

        Text(
            text = stringResource(R.string.echo_pad_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        // Quick Vibe Chips in Simple Mode OR Essential Sliders
        if (!uiState.isProEchoMode) {
            StudioParamSlider(
                label = stringResource(R.string.param_wet_dry),
                value = params.wetDryMix,
                valueRange = 0f..1f,
                formattedValue = "${(params.wetDryMix * 100).toInt()}%",
                onValueChange = { v -> onUpdateEchoReverb { it.copy(wetDryMix = v) } },
                accentColor = CyanGlow,
                testTag = "simple_wet_dry_slider"
            )
            StudioParamSlider(
                label = stringResource(R.string.param_delay_time),
                value = params.delayTimeMs,
                valueRange = 40f..1000f,
                formattedValue = "${params.delayTimeMs.toInt()} ms",
                onValueChange = { v -> onUpdateEchoReverb { it.copy(delayTimeMs = v) } },
                accentColor = VioletGlow,
                testTag = "simple_delay_slider"
            )
        }

        // Tier 2 Detail Nodes — tap any node to enter its dedicated precision workspace
        EchoDetailGroup.entries.forEach { group ->
            val icon = when (group) {
                EchoDetailGroup.ECHO_CORE -> Icons.Rounded.Waves
                EchoDetailGroup.REFLECTIONS -> Icons.Rounded.BlurOn
                EchoDetailGroup.SPACE_TONE -> Icons.Rounded.GraphicEq
            }
            val accent = when (group) {
                EchoDetailGroup.ECHO_CORE -> CyanGlow
                EchoDetailGroup.REFLECTIONS -> VioletGlow
                EchoDetailGroup.SPACE_TONE -> AmberWarm
            }
            val summary = when (group) {
                EchoDetailGroup.ECHO_CORE ->
                    "${(params.echoStrength * 100).toInt()}% شدة • ${params.delayTimeMs.toInt()}ms"
                EchoDetailGroup.REFLECTIONS ->
                    "مبكرة ${(params.earlyReflections * 100).toInt()}% • انتشار ${(params.diffusion * 100).toInt()}%"
                EchoDetailGroup.SPACE_TONE ->
                    "حجم ${(params.reverbSize * 100).toInt()}% • مزج ${(params.wetDryMix * 100).toInt()}%"
            }

            Surface(
                onClick = { onOpenDetailGroup(group) },
                shape = RoundedCornerShape(20.dp),
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("echo_node_${group.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.arabicTitle, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(group.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(summary, style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) CyanGlow.copy(alpha = 0.2f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, CyanGlow.copy(alpha = 0.7f)) else null,
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) CyanGlow else TextSecondary
            )
        }
    }
}

@Composable
private fun SpatialEchoFieldCanvas(
    orbX: Float,
    orbY: Float,
    params: EchoReverbParams,
    liveEnergy: Float,
    xAxisLabel: String,
    yAxisLabel: String,
    onTouchPosition: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "echo_field")
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(StudioObsidian)
            .border(1.dp, StudioBorder, RoundedCornerShape(26.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val nx = (offset.x / size.width).coerceIn(0f, 1f)
                    val ny = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    onTouchPosition(nx, ny)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    onTouchPosition(nx, ny)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val px = orbX.coerceIn(0f, 1f) * w
            val py = (1f - orbY.coerceIn(0f, 1f)) * h
            val orbCenter = Offset(px, py)

            // Ambient radial glow under orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CyanGlow.copy(alpha = 0.28f + liveEnergy * 0.2f),
                        VioletGlow.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = orbCenter,
                    radius = w * (0.35f + params.reverbSize * 0.35f)
                ),
                center = orbCenter,
                radius = w * 0.7f
            )

            // Subtle spatial acoustic grid
            val cols = 6
            val rows = 5
            for (c in 1 until cols) {
                val gx = (w / cols) * c
                drawLine(
                    color = StudioBorder.copy(alpha = 0.35f),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1.dp.toPx()
                )
            }
            for (r in 1 until rows) {
                val gy = (h / rows) * r
                drawLine(
                    color = StudioBorder.copy(alpha = 0.35f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Concentric echo ripple rings propagating outward
            val ringCount = 4
            val maxRadius = (w * 0.45f) * (0.5f + params.reverbSize * 0.7f)
            for (i in 0 until ringCount) {
                val progress = (wavePhase + i.toFloat() / ringCount) % 1f
                val r = progress * maxRadius
                val alpha = (1f - progress) * (0.35f + params.echoStrength * 0.45f)
                drawCircle(
                    color = if (i % 2 == 0) CyanGlow.copy(alpha = alpha) else VioletGlow.copy(alpha = alpha),
                    radius = r.coerceAtLeast(8f),
                    center = orbCenter,
                    style = Stroke(width = (1.5f + params.diffusion * 1.5f).dp.toPx())
                )
            }

            // Orbiting early/late reflection particle dots
            val particles = 8
            val scatterR = 36.dp.toPx() + params.diffusion * 54.dp.toPx()
            for (p in 0 until particles) {
                val angle = (p * (2f * PI / particles) + wavePhase * 2f * PI).toDouble()
                val rx = px + cos(angle).toFloat() * scatterR * params.stereoWidth.coerceIn(0.4f, 1.4f)
                val ry = py + sin(angle).toFloat() * scatterR * 0.75f
                drawCircle(
                    color = if (p % 2 == 0) AmberWarm.copy(alpha = 0.65f) else EmeraldActive.copy(alpha = 0.6f),
                    radius = (3f + params.earlyReflections * 4f).dp.toPx(),
                    center = Offset(rx, ry)
                )
            }

            // Interactive Luminous Orb
            drawCircle(
                color = CyanGlow.copy(alpha = 0.35f),
                radius = 24.dp.toPx(),
                center = orbCenter
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, CyanGlow, VioletGlow),
                    center = orbCenter,
                    radius = 15.dp.toPx()
                ),
                radius = 14.dp.toPx(),
                center = orbCenter
            )
        }

        // Axis telemetry badges inside field corners
        Surface(
            color = StudioSurface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Text(
                text = "↑ $yAxisLabel: ${(orbY * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = VioletGlow,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        Surface(
            color = StudioSurface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            Text(
                text = "↔ $xAxisLabel: ${(orbX * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = CyanGlow,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun EchoDetailScreen(
    group: EchoDetailGroup,
    params: EchoReverbParams,
    onUpdate: ((EchoReverbParams) -> EchoReverbParams) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (group) {
            EchoDetailGroup.ECHO_CORE -> {
                StudioParamSlider(
                    label = stringResource(R.string.param_echo_strength),
                    value = params.echoStrength,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.echoStrength * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(echoStrength = v) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_echo_depth),
                    value = params.echoDepth,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.echoDepth * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(echoDepth = v) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_delay_time),
                    value = params.delayTimeMs,
                    valueRange = 20f..1200f,
                    formattedValue = "${params.delayTimeMs.toInt()} ms",
                    onValueChange = { v -> onUpdate { it.copy(delayTimeMs = v) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_feedback),
                    value = params.feedback,
                    valueRange = 0f..0.90f,
                    formattedValue = "${(params.feedback * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(feedback = v) } },
                    accentColor = AmberWarm
                )
            }

            EchoDetailGroup.REFLECTIONS -> {
                StudioParamSlider(
                    label = stringResource(R.string.param_early_reflections),
                    value = params.earlyReflections,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.earlyReflections * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(earlyReflections = v) } },
                    accentColor = EmeraldActive
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_late_reflections),
                    value = params.lateReflections,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.lateReflections * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(lateReflections = v) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_pre_delay),
                    value = params.preDelayMs,
                    valueRange = 0f..150f,
                    formattedValue = "${params.preDelayMs.toInt()} ms",
                    onValueChange = { v -> onUpdate { it.copy(preDelayMs = v) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_diffusion),
                    value = params.diffusion,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.diffusion * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(diffusion = v) } },
                    accentColor = VioletGlow
                )
            }

            EchoDetailGroup.SPACE_TONE -> {
                StudioParamSlider(
                    label = stringResource(R.string.param_reverb_size),
                    value = params.reverbSize,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.reverbSize * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(reverbSize = v) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_reverb_decay),
                    value = params.reverbDecay,
                    valueRange = 0f..0.95f,
                    formattedValue = "${(params.reverbDecay * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(reverbDecay = v) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_damping),
                    value = params.damping,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.damping * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(damping = v) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_stereo_width),
                    value = params.stereoWidth,
                    valueRange = 0f..2f,
                    formattedValue = String.format(Locale.US, "%.2fx", params.stereoWidth),
                    onValueChange = { v -> onUpdate { it.copy(stereoWidth = v) } },
                    accentColor = EmeraldActive
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_wet_dry),
                    value = params.wetDryMix,
                    valueRange = 0f..1f,
                    formattedValue = "${(params.wetDryMix * 100).toInt()}%",
                    onValueChange = { v -> onUpdate { it.copy(wetDryMix = v) } },
                    accentColor = CyanGlow
                )
            }
        }
    }
}
