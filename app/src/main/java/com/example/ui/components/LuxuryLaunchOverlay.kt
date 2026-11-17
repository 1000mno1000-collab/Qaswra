package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.ui.theme.StudioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun SonixLuxuryLaunchOverlay(
    visible: Boolean,
    language: AppLanguage,
    onFinished: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 160)),
        exit = fadeOut(animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing)) +
            scaleOut(
                targetScale = 1.06f,
                animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing),
            ),
    ) {
        val emblemScale = remember { Animatable(0.70f) }
        val emblemAlpha = remember { Animatable(0f) }
        val titleAlpha = remember { Animatable(0f) }
        val titleOffsetY = remember { Animatable(24f) }
        val shimmerProgress = remember { Animatable(-0.8f) }

        val infiniteTransition = rememberInfiniteTransition(label = "luxury_launch_infinite")
        val orbitRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orbit_rotation",
        )
        val haloPulse by infiniteTransition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "halo_pulse",
        )
        val wavePhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wave_phase",
        )

        LaunchedEffect(Unit) {
            launch {
                emblemAlpha.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
            }
            launch {
                emblemScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
            launch {
                delay(150L)
                launch { titleAlpha.animateTo(1f, tween(durationMillis = 440, easing = FastOutSlowInEasing)) }
                launch { titleOffsetY.animateTo(0f, tween(durationMillis = 440, easing = FastOutSlowInEasing)) }
            }
            launch {
                delay(300L)
                shimmerProgress.animateTo(1.8f, tween(durationMillis = 760, easing = FastOutSlowInEasing))
            }
            delay(1750L)
            onFinished()
        }

        val subtitle = when (language) {
            AppLanguage.AR -> "استوديو الهندسة الصوتية الاحترافي"
            AppLanguage.FA -> "استودیوی حرفه‌ای مهندسی صدا"
            AppLanguage.EN -> "MASTER AUDIO ENGINEERING STUDIO"
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF04070F),
                            Color(0xFF0A1124),
                            Color(0xFF070B14),
                        ),
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFinished,
                )
                .systemBarsPadding()
                .testTag("luxury_launch_overlay"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 28.dp),
            ) {
                Box(
                    modifier = Modifier.size(224.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Ambient halo + dual precision orbital arcs
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(emblemAlpha.value),
                    ) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val glowRadius = (size.minDimension / 2f) * haloPulse

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    StudioColors.Cyan.copy(alpha = 0.28f),
                                    StudioColors.Violet.copy(alpha = 0.14f),
                                    Color.Transparent,
                                ),
                                center = Offset(cx, cy),
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = Offset(cx, cy),
                        )

                        rotate(degrees = orbitRotation, pivot = Offset(cx, cy)) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        StudioColors.Cyan.copy(alpha = 0.78f),
                                        Color.Transparent,
                                        StudioColors.Violet.copy(alpha = 0.72f),
                                        Color.Transparent,
                                    ),
                                    center = Offset(cx, cy),
                                ),
                                radius = size.minDimension * 0.40f,
                                center = Offset(cx, cy),
                                style = Stroke(width = 2.2.dp.toPx()),
                            )
                        }

                        rotate(degrees = -orbitRotation * 0.65f, pivot = Offset(cx, cy)) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        StudioColors.VioletBright.copy(alpha = 0.42f),
                                        Color.Transparent,
                                        StudioColors.CyanHover.copy(alpha = 0.45f),
                                    ),
                                    center = Offset(cx, cy),
                                ),
                                radius = size.minDimension * 0.46f,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                        }
                    }

                    // Primary attached Sonix logo emblem in luminous frame + shimmer sweep
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = Color(0xFFFFFFFE),
                        shadowElevation = 20.dp,
                        border = BorderStroke(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    StudioColors.Cyan,
                                    StudioColors.Violet,
                                    StudioColors.CyanHover,
                                ),
                            ),
                        ),
                        modifier = Modifier
                            .size(116.dp)
                            .scale(emblemScale.value)
                            .alpha(emblemAlpha.value),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                painter = painterResource(R.drawable.sonix_primary_logo),
                                contentDescription = t(language, "app_name"),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(30.dp)),
                            )

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sweepX = size.width * shimmerProgress.value
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            StudioColors.Cyan.copy(alpha = 0.24f),
                                            Color.White.copy(alpha = 0.40f),
                                            Color.Transparent,
                                        ),
                                        start = Offset(sweepX - size.width * 0.45f, 0f),
                                        end = Offset(sweepX, size.height),
                                    ),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .offset(y = titleOffsetY.value.dp)
                        .alpha(titleAlpha.value),
                ) {
                    Text(
                        text = t(language, "app_name"),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                        ),
                        color = StudioColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelLarge.copy(
                            letterSpacing = if (language == AppLanguage.EN) 2.2.sp else 0.4.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = StudioColors.Cyan,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Animated mini studio spectrum bar pulse
                    Canvas(
                        modifier = Modifier.size(width = 132.dp, height = 26.dp),
                    ) {
                        val barCount = 9
                        val gap = 6.dp.toPx()
                        val totalGap = gap * (barCount - 1)
                        val barW = (size.width - totalGap) / barCount
                        val maxH = size.height
                        val minH = maxH * 0.24f
                        val mid = (barCount - 1) / 2f

                        for (i in 0 until barCount) {
                            val centerWeight = 1f - (abs(i - mid) / (mid + 1f)) * 0.55f
                            val wave = (sin(wavePhase + i * 0.72f) + 1f) * 0.5f
                            val h = (minH + (maxH - minH) * wave * centerWeight).coerceIn(minH, maxH)
                            val x = i * (barW + gap)
                            val y = (maxH - h) / 2f
                            val tColor = i / (barCount - 1f)
                            val barColor = if (tColor < 0.5f) {
                                StudioColors.Cyan
                            } else {
                                StudioColors.Violet
                            }
                            drawRoundRect(
                                color = barColor.copy(alpha = 0.88f),
                                topLeft = Offset(x, y),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                            )
                        }
                    }
                }
            }
        }
    }
}
