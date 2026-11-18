package com.example.studio.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * تحديد ناعم وأنيق داخل حدود العنصر بدون أي توهج خارجي مزعج.
 * لا يغيّر حجم أو مكان العنصر.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glow(
    color: Color,
    enabled: Boolean,
    radius: Dp = 14.dp,
    cornerRadius: Dp = 16.dp,
    maxAlpha: Float = 0.55f,
): Modifier = composed {
    val a = animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(180),
        label = "subtle_accent",
    )
    drawWithContent {
        drawContent()
        val t = a.value
        if (t > 0.01f) {
            val cr = cornerRadius.toPx()
            drawRoundRect(
                color = color.copy(alpha = 0.08f * t),
                cornerRadius = CornerRadius(cr, cr),
            )
            drawRoundRect(
                color = color.copy(alpha = 0.24f * t),
                cornerRadius = CornerRadius(cr, cr),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/**
 * استجابة لمسية ناعمة بالضغط مع إبراز خفيف ومتناسق بدون أي هالة زرقاء خارجية.
 */
fun Modifier.lit(
    interaction: MutableInteractionSource,
    color: Color,
    selected: Boolean = false,
    cornerRadius: Dp = 16.dp,
): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val tintAlpha = animateFloatAsState(
        targetValue = when {
            pressed -> 0.10f
            selected -> 0.04f
            else -> 0f
        },
        animationSpec = tween(150),
        label = "press_tint",
    )
    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .drawWithContent {
            drawContent()
            val alpha = tintAlpha.value
            if (alpha > 0.005f) {
                val cr = cornerRadius.toPx()
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    cornerRadius = CornerRadius(cr, cr),
                )
            }
        }
}
