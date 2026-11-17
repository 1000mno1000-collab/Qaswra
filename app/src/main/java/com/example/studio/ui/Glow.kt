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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * وهج ناعم خلف العنصر (طبقات شفافة متدرجة، بدون blur فخفيف على الـ GPU).
 * لا يغيّر حجم أو مكان العنصر.
 */
fun Modifier.glow(
    color: Color,
    enabled: Boolean,
    radius: Dp = 14.dp,
    cornerRadius: Dp = 16.dp,
    maxAlpha: Float = 0.55f
): Modifier = composed {
    val a = animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(250),
        label = "glow"
    )
    drawBehind {
        val t = a.value
        if (t > 0.01f) {
            val layers = 5
            val step = radius.toPx() / layers
            val cr = cornerRadius.toPx()
            for (i in layers downTo 1) {
                val grow = step * i
                drawRoundRect(
                    color = color.copy(alpha = maxAlpha * t * (1f - i / (layers + 1f)) * 0.5f),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + grow * 2f, size.height + grow * 2f),
                    cornerRadius = CornerRadius(cr + grow, cr + grow)
                )
            }
        }
    }
}

/**
 * إنارة + تصغير نابض عند الضغط. مرر نفس interactionSource المستخدم بالـ clickable/Card/Button.
 * selected = true يخلي الوهج ثابت (للأداة المختارة).
 */
fun Modifier.lit(
    interaction: MutableInteractionSource,
    color: Color,
    selected: Boolean = false,
    cornerRadius: Dp = 16.dp
): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    this
        .graphicsLayer {
            scaleX = s.value
            scaleY = s.value
        }
        .glow(
            color = color,
            enabled = pressed || selected,
            radius = if (pressed) 20.dp else 14.dp,
            cornerRadius = cornerRadius
        )
}
