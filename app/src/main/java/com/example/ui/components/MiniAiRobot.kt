package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.StudioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private enum class RobotMood {
    WALKING_IN,
    WAVING,
    SITTING,
    SLIPPING_HANG,
    SOMERSAULT,
    HAPPY_JUMP,
    YAWNING,
    SLEEP_FALLING,
    STARTLED_CLIMB,
}

/**
 * Interactive animated Mini AI Robot perched cleanly along the upper border of the
 * Smart Assistant Card on HomeScreen.
 *
 * Behaviors:
 * 1. Welcome entrance: walks onto card edge -> waves -> shows personalized greeting speech bubble -> sits swinging legs.
 * 2. Tap interactions: triggers funny physics-feel stunts (slip & hang then climb up, somersault roll, happy spin-jump).
 * 3. 16s Idle cycle: yawns, nods off with floating "Zzz...", loses balance, falls playfully, wakes startled & climbs back.
 */
@Composable
fun MiniAiRobotCompanion(
    userDisplayName: String?,
    language: AppLanguage,
    onRobotTappedNotify: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var mood by remember { mutableStateOf(RobotMood.WALKING_IN) }
    var showGreetingBubble by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var trickCounter by remember { mutableIntStateOf(0) }

    // Physics-like animated transforms
    val xFraction = remember { Animatable(-0.18f) }
    val offsetYDp = remember { Animatable(0f) }
    val tiltDeg = remember { Animatable(0f) }
    val handRaise = remember { Animatable(0f) }

    val greetingText = remember(userDisplayName, language) {
        val cleanName = userDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        when (language) {
            AppLanguage.EN -> if (cleanName != null) "Welcome back, $cleanName ✦" else "Welcome, Creator ✦"
            AppLanguage.FA -> if (cleanName != null) "خوش آمدی، $cleanName ✦" else "خوش آمدی هنرمند ✦"
            AppLanguage.AR -> if (cleanName != null) "أهلاً بك يا $cleanName ✦" else "أهلاً بك يا مبدع ✦"
        }
    }

    // Gentle idle leg swing & antenna glow
    val inf = rememberInfiniteTransition(label = "robot_idle")
    val legSwing by inf.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "leg_swing",
    )
    val zzzDrift by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "zzz_drift",
    )
    val antennaGlow by inf.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "antenna_glow",
    )

    // Initial welcome entrance
    LaunchedEffect(Unit) {
        mood = RobotMood.WALKING_IN
        xFraction.snapTo(-0.14f)
        offsetYDp.snapTo(-2f)
        tiltDeg.snapTo(0f)
        // Walk from left side onto center-right perch
        xFraction.animateTo(0.74f, animationSpec = tween(1350, easing = FastOutSlowInEasing))
        mood = RobotMood.WAVING
        showGreetingBubble = true
        handRaise.animateTo(1f, animationSpec = tween(250))
        repeat(3) {
            handRaise.animateTo(0.55f, tween(210))
            handRaise.animateTo(1f, tween(210))
        }
        delay(2400L)
        showGreetingBubble = false
        handRaise.animateTo(0f, tween(280))
        // Small hop into comfy sitting posture
        offsetYDp.animateTo(-10f, tween(140))
        offsetYDp.animateTo(4f, spring(dampingRatio = 0.52f))
        mood = RobotMood.SITTING
    }

    // 16-second inactivity -> Yawn -> Zzz Sleep Fall -> Startled climb back
    LaunchedEffect(interactionNonce) {
        delay(16_500L)
        if (mood == RobotMood.SITTING) {
            mood = RobotMood.YAWNING
            tiltDeg.animateTo(-6f, tween(450))
            delay(1700L)
            mood = RobotMood.SLEEP_FALLING
            // Nod off & tip forward
            tiltDeg.animateTo(14f, tween(550))
            delay(2200L)
            // Lose balance & slip off the edge
            tiltDeg.animateTo(72f, tween(340, easing = FastOutSlowInEasing))
            offsetYDp.animateTo(26f, tween(240))
            delay(420L)
            // Wake startled & scramble back up!
            mood = RobotMood.STARTLED_CLIMB
            tiltDeg.animateTo(-12f, spring(dampingRatio = 0.45f))
            offsetYDp.animateTo(-10f, tween(260))
            tiltDeg.animateTo(0f, spring(dampingRatio = 0.55f))
            offsetYDp.animateTo(4f, spring(dampingRatio = 0.55f))
            mood = RobotMood.SITTING
        }
    }

    fun triggerFunnyTapTrick() {
        onRobotTappedNotify?.invoke()
        interactionNonce++
        showGreetingBubble = false
        val trick = (trickCounter % 3)
        trickCounter++
        scope.launch {
            when (trick) {
                0 -> {
                    // Edge slip & grab onto card edge, dangle legs, climb back up
                    mood = RobotMood.SLIPPING_HANG
                    tiltDeg.animateTo(16f, tween(150))
                    offsetYDp.animateTo(24f, tween(280, easing = FastOutSlowInEasing))
                    delay(650L)
                    // Pull up effort
                    tiltDeg.animateTo(-8f, tween(220))
                    offsetYDp.animateTo(-8f, tween(240))
                    tiltDeg.animateTo(0f, spring(dampingRatio = 0.5f))
                    offsetYDp.animateTo(4f, spring(dampingRatio = 0.5f))
                    mood = RobotMood.SITTING
                }
                1 -> {
                    // Acrobat somersault roll to left & hop back
                    mood = RobotMood.SOMERSAULT
                    val targetX = if (xFraction.value > 0.5f) 0.22f else 0.78f
                    launch { xFraction.animateTo(targetX, tween(760, easing = FastOutSlowInEasing)) }
                    launch { offsetYDp.animateTo(-16f, tween(380)) }
                    tiltDeg.animateTo(if (targetX < xFraction.value) -360f else 360f, tween(760))
                    tiltDeg.snapTo(0f)
                    offsetYDp.animateTo(4f, spring(dampingRatio = 0.52f))
                    mood = RobotMood.SITTING
                }
                else -> {
                    // Happy bounce + wave
                    mood = RobotMood.HAPPY_JUMP
                    launch { handRaise.animateTo(1f, tween(180)) }
                    offsetYDp.animateTo(-20f, tween(220, easing = FastOutSlowInEasing))
                    tiltDeg.animateTo(-15f, tween(120))
                    tiltDeg.animateTo(15f, tween(120))
                    tiltDeg.animateTo(0f, tween(140))
                    offsetYDp.animateTo(4f, spring(dampingRatio = 0.45f))
                    handRaise.animateTo(0f, tween(260))
                    mood = RobotMood.SITTING
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val robotSizePx = with(density) { 54.dp.toPx() }
        val robotXPx = ((maxW - robotSizePx) * xFraction.value.coerceIn(-0.18f, 0.94f)).roundToInt()
        val robotYPx = with(density) { (10.dp + offsetYDp.value.dp).roundToPx() }

        // Speech bubble or Zzz indicator positioned near robot
        val bubbleAlignRight = xFraction.value > 0.45f
        Box(
            modifier = Modifier
                .align(if (bubbleAlignRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            AnimatedVisibility(
                visible = showGreetingBubble,
                enter = fadeIn(tween(240)) + scaleIn(tween(240)),
                exit = fadeOut(tween(220)) + scaleOut(tween(220)),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.ElevatedSurface,
                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.7f)),
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = greetingText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        ),
                        color = StudioColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = mood == RobotMood.SLEEP_FALLING || mood == RobotMood.YAWNING,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                Text(
                    text = if (mood == RobotMood.YAWNING) "(*O*)" else "Zzz...",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                    ),
                    color = StudioColors.Violet,
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = -zzzDrift * 14f
                            alpha = 1f - zzzDrift * 0.45f
                        },
                )
            }

            AnimatedVisibility(
                visible = mood == RobotMood.STARTLED_CLIMB,
                enter = fadeIn(tween(80)),
                exit = fadeOut(tween(180)),
            ) {
                Text(
                    text = "!!",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = StudioColors.Amber,
                )
            }
        }

        val clickInteraction = remember { MutableInteractionSource() }
        val desc = if (language == AppLanguage.EN) "Interactive Mini AI Robot" else "الروبوت الذكي التفاعلي"
        Canvas(
            modifier = Modifier
                .offset { IntOffset(robotXPx, robotYPx) }
                .size(54.dp)
                .graphicsLayer {
                    rotationZ = tiltDeg.value
                }
                .semantics { contentDescription = desc }
                .clickable(
                    interactionSource = clickInteraction,
                    indication = null,
                    onClick = { triggerFunnyTapTrick() },
                )
                .testTag("home_mini_ai_robot"),
        ) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f

            val bodyBrush = Brush.verticalGradient(
                listOf(Color(0xFF283755), Color(0xFF151E33)),
            )
            val visorColor = Color(0xFF0A0F1D)
            val glowCyan = StudioColors.Cyan.copy(alpha = antennaGlow)

            // Antenna
            drawLine(
                color = Color(0xFF7B93BC),
                start = Offset(cx, h * 0.20f),
                end = Offset(cx, h * 0.06f),
                strokeWidth = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = if (mood == RobotMood.STARTLED_CLIMB) StudioColors.Amber else glowCyan,
                radius = 4.dp.toPx(),
                center = Offset(cx, h * 0.06f),
            )

            // Little Head
            val headW = w * 0.56f
            val headH = h * 0.34f
            val headLeft = cx - headW / 2f
            val headTop = h * 0.18f
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx()),
            )
            drawRoundRect(
                color = StudioColors.Cyan.copy(alpha = 0.65f),
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                style = Stroke(width = 1.4.dp.toPx()),
            )

            // Face Visor
            drawRoundRect(
                color = visorColor,
                topLeft = Offset(headLeft + 4.dp.toPx(), headTop + 4.dp.toPx()),
                size = Size(headW - 8.dp.toPx(), headH - 7.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )

            // Eyes & expression
            val eyeY = headTop + headH * 0.45f
            val eyeLX = cx - headW * 0.18f
            val eyeRX = cx + headW * 0.18f
            when (mood) {
                RobotMood.SLEEP_FALLING -> {
                    // Closed sleepy arcs
                    drawLine(StudioColors.Violet, Offset(eyeLX - 3.dp.toPx(), eyeY), Offset(eyeLX + 3.dp.toPx(), eyeY), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(StudioColors.Violet, Offset(eyeRX - 3.dp.toPx(), eyeY), Offset(eyeRX + 3.dp.toPx(), eyeY), 2.dp.toPx(), StrokeCap.Round)
                }
                RobotMood.YAWNING -> {
                    drawLine(StudioColors.Cyan, Offset(eyeLX - 2.5.dp.toPx(), eyeY), Offset(eyeLX + 2.5.dp.toPx(), eyeY), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(StudioColors.Cyan, Offset(eyeRX - 2.5.dp.toPx(), eyeY), Offset(eyeRX + 2.5.dp.toPx(), eyeY), 2.dp.toPx(), StrokeCap.Round)
                    // Open yawning O mouth
                    drawCircle(
                        color = StudioColors.Amber,
                        radius = 2.6.dp.toPx(),
                        center = Offset(cx, headTop + headH * 0.72f),
                    )
                }
                RobotMood.STARTLED_CLIMB, RobotMood.SLIPPING_HANG -> {
                    drawCircle(StudioColors.Amber, 3.3.dp.toPx(), Offset(eyeLX, eyeY))
                    drawCircle(StudioColors.Amber, 3.3.dp.toPx(), Offset(eyeRX, eyeY))
                }
                else -> {
                    val eyeColor = if (mood == RobotMood.HAPPY_JUMP) StudioColors.Emerald else StudioColors.Cyan
                    drawCircle(eyeColor, 2.7.dp.toPx(), Offset(eyeLX, eyeY))
                    drawCircle(eyeColor, 2.7.dp.toPx(), Offset(eyeRX, eyeY))
                }
            }

            // Torso
            val torsoW = w * 0.42f
            val torsoH = h * 0.28f
            val torsoTop = headTop + headH + 1.5.dp.toPx()
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(cx - torsoW / 2f, torsoTop),
                size = Size(torsoW, torsoH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = StudioColors.Violet.copy(alpha = 0.6f),
                topLeft = Offset(cx - torsoW / 2f, torsoTop),
                size = Size(torsoW, torsoH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.2.dp.toPx()),
            )
            // Chest core LED
            drawCircle(
                color = StudioColors.Cyan,
                radius = 2.5.dp.toPx(),
                center = Offset(cx, torsoTop + torsoH * 0.5f),
            )

            // Arms
            val armRaisePx = handRaise.value * 11.dp.toPx()
            val hangArmsUp = mood == RobotMood.SLIPPING_HANG
            val leftArmEndY = if (hangArmsUp) torsoTop - 10.dp.toPx() else torsoTop + torsoH * 0.7f - armRaisePx * 0.4f
            val rightArmEndY = if (hangArmsUp) torsoTop - 10.dp.toPx() else torsoTop + torsoH * 0.7f - armRaisePx
            drawLine(
                color = Color(0xFF8FA7CC),
                start = Offset(cx - torsoW / 2f, torsoTop + 3.dp.toPx()),
                end = Offset(cx - torsoW * 0.85f, leftArmEndY),
                strokeWidth = 2.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF8FA7CC),
                start = Offset(cx + torsoW / 2f, torsoTop + 3.dp.toPx()),
                end = Offset(cx + torsoW * 0.85f, rightArmEndY),
                strokeWidth = 2.8.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Dangling / Walking Legs hanging gently over the card border
            val legStartY = torsoTop + torsoH
            val walkCycle = if (mood == RobotMood.WALKING_IN) sin(xFraction.value * 36f) * 4.dp.toPx() else 0f
            val swingL = if (mood == RobotMood.SITTING) legSwing * 3.dp.toPx() else walkCycle
            val swingR = if (mood == RobotMood.SITTING) -legSwing * 3.dp.toPx() else -walkCycle

            rotate(degrees = swingL * 3f, pivot = Offset(cx - torsoW * 0.24f, legStartY)) {
                drawLine(
                    color = Color(0xFF7B93BC),
                    start = Offset(cx - torsoW * 0.24f, legStartY),
                    end = Offset(cx - torsoW * 0.26f, h * 0.96f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            rotate(degrees = swingR * 3f, pivot = Offset(cx + torsoW * 0.24f, legStartY)) {
                drawLine(
                    color = Color(0xFF7B93BC),
                    start = Offset(cx + torsoW * 0.24f, legStartY),
                    end = Offset(cx + torsoW * 0.26f, h * 0.96f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
