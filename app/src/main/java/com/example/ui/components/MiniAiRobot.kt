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
            AppLanguage.TR -> if (cleanName != null) "Tekrar hoş geldin, $cleanName ✦" else "Hoş geldin, üretici ✦"
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

            val squircleBrush = Brush.verticalGradient(
                listOf(Color(0xFF233454), Color(0xFF10192D)),
            )
            val glowCyan = StudioColors.Cyan.copy(alpha = antennaGlow)

            // Cute Rounded Square (Squircle) Body-Head
            val sqSize = minOf(w * 0.72f, h * 0.72f)
            val sqLeft = cx - sqSize / 2f
            val sqTop = h * 0.12f
            val sqRadius = sqSize * 0.34f

            // Subtle outer ambient cyan halo
            drawRoundRect(
                color = glowCyan.copy(alpha = 0.16f),
                topLeft = Offset(sqLeft - 2.dp.toPx(), sqTop - 2.dp.toPx()),
                size = Size(sqSize + 4.dp.toPx(), sqSize + 4.dp.toPx()),
                cornerRadius = CornerRadius(sqRadius + 2.dp.toPx(), sqRadius + 2.dp.toPx()),
            )

            // Main squircle shell
            drawRoundRect(
                brush = squircleBrush,
                topLeft = Offset(sqLeft, sqTop),
                size = Size(sqSize, sqSize),
                cornerRadius = CornerRadius(sqRadius, sqRadius),
            )
            drawRoundRect(
                color = glowCyan.copy(alpha = 0.85f),
                topLeft = Offset(sqLeft, sqTop),
                size = Size(sqSize, sqSize),
                cornerRadius = CornerRadius(sqRadius, sqRadius),
                style = Stroke(width = 1.6.dp.toPx()),
            )

            // Soft inner face screen
            val pad = 3.5.dp.toPx()
            val innerSize = sqSize - pad * 2f
            val innerRadius = (sqRadius - pad * 0.7f).coerceAtLeast(6.dp.toPx())
            drawRoundRect(
                color = Color(0xFF0A1120),
                topLeft = Offset(sqLeft + pad, sqTop + pad),
                size = Size(innerSize, innerSize),
                cornerRadius = CornerRadius(innerRadius, innerRadius),
            )

            // Subtle top cyan glass highlight
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(sqLeft + sqSize * 0.26f, sqTop + 2.6.dp.toPx()),
                end = Offset(sqLeft + sqSize * 0.74f, sqTop + 2.6.dp.toPx()),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Warm Human-Like Facial Features
            val faceCenterY = sqTop + sqSize * 0.50f
            val browY = sqTop + sqSize * 0.30f
            val eyeY = sqTop + sqSize * 0.45f
            val eyeOffset = sqSize * 0.19f
            val eyeLX = cx - eyeOffset
            val eyeRX = cx + eyeOffset
            val mouthY = sqTop + sqSize * 0.67f

            // Soft warm cheeks
            val cheekColor = Color(0xFFFB7185).copy(alpha = if (mood == RobotMood.HAPPY_JUMP || mood == RobotMood.WAVING) 0.34f else 0.20f)
            drawCircle(cheekColor, radius = 3.2.dp.toPx(), center = Offset(eyeLX - 2.dp.toPx(), mouthY - 2.dp.toPx()))
            drawCircle(cheekColor, radius = 3.2.dp.toPx(), center = Offset(eyeRX + 2.dp.toPx(), mouthY - 2.dp.toPx()))

            // Gentle arched human eyebrows
            val browStroke = 1.4.dp.toPx()
            val browColor = Color(0xFFA5F3FC).copy(alpha = 0.9f)
            val browLift = if (mood == RobotMood.STARTLED_CLIMB || mood == RobotMood.SLIPPING_HANG) -1.8.dp.toPx() else 0f
            drawArc(
                color = browColor,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(eyeLX - 3.6.dp.toPx(), browY + browLift - 2.dp.toPx()),
                size = Size(7.2.dp.toPx(), 4.5.dp.toPx()),
                style = Stroke(width = browStroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = browColor,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(eyeRX - 3.6.dp.toPx(), browY + browLift - 2.dp.toPx()),
                size = Size(7.2.dp.toPx(), 4.5.dp.toPx()),
                style = Stroke(width = browStroke, cap = StrokeCap.Round),
            )

            // Expressive warm eyes & friendly smile
            when (mood) {
                RobotMood.SLEEP_FALLING -> {
                    // Cozy closed sleepy eyelashes
                    drawArc(
                        color = StudioColors.Violet,
                        startAngle = 15f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(eyeLX - 3.4.dp.toPx(), eyeY - 1.8.dp.toPx()),
                        size = Size(6.8.dp.toPx(), 4.dp.toPx()),
                        style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = StudioColors.Violet,
                        startAngle = 15f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(eyeRX - 3.4.dp.toPx(), eyeY - 1.8.dp.toPx()),
                        size = Size(6.8.dp.toPx(), 4.dp.toPx()),
                        style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round),
                    )
                    // Peaceful tiny mouth
                    drawCircle(color = Color(0xFF94A3B8), radius = 1.4.dp.toPx(), center = Offset(cx, mouthY))
                }
                RobotMood.YAWNING -> {
                    drawLine(StudioColors.Cyan, Offset(eyeLX - 3.dp.toPx(), eyeY), Offset(eyeLX + 3.dp.toPx(), eyeY), 1.8.dp.toPx(), StrokeCap.Round)
                    drawLine(StudioColors.Cyan, Offset(eyeRX - 3.dp.toPx(), eyeY), Offset(eyeRX + 3.dp.toPx(), eyeY), 1.8.dp.toPx(), StrokeCap.Round)
                    drawCircle(color = StudioColors.Amber, radius = 2.6.dp.toPx(), center = Offset(cx, mouthY + 0.5.dp.toPx()))
                }
                RobotMood.HAPPY_JUMP, RobotMood.WAVING -> {
                    // Warm smiling eyes (^ ^) + bright sparkle catchlight on waving
                    if (mood == RobotMood.HAPPY_JUMP) {
                        drawArc(
                            color = StudioColors.Emerald,
                            startAngle = 195f,
                            sweepAngle = 150f,
                            useCenter = false,
                            topLeft = Offset(eyeLX - 3.6.dp.toPx(), eyeY - 2.dp.toPx()),
                            size = Size(7.2.dp.toPx(), 5.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = StudioColors.Emerald,
                            startAngle = 195f,
                            sweepAngle = 150f,
                            useCenter = false,
                            topLeft = Offset(eyeRX - 3.6.dp.toPx(), eyeY - 2.dp.toPx()),
                            size = Size(7.2.dp.toPx(), 5.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    } else {
                        listOf(eyeLX, eyeRX).forEach { ex ->
                            drawCircle(Color(0xFFE0F2FE), 3.3.dp.toPx(), Offset(ex, eyeY))
                            drawCircle(StudioColors.Cyan, 2.2.dp.toPx(), Offset(ex, eyeY))
                            drawCircle(Color(0xFF082F49), 1.2.dp.toPx(), Offset(ex, eyeY))
                            drawCircle(Color.White, 0.9.dp.toPx(), Offset(ex - 0.9.dp.toPx(), eyeY - 0.9.dp.toPx()))
                        }
                    }
                    // Big friendly smile
                    drawArc(
                        color = StudioColors.Cyan,
                        startAngle = 15f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(cx - 5.2.dp.toPx(), mouthY - 3.2.dp.toPx()),
                        size = Size(10.4.dp.toPx(), 6.dp.toPx()),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                RobotMood.STARTLED_CLIMB, RobotMood.SLIPPING_HANG -> {
                    listOf(eyeLX, eyeRX).forEach { ex ->
                        drawCircle(Color.White, 3.8.dp.toPx(), Offset(ex, eyeY))
                        drawCircle(StudioColors.Amber, 1.8.dp.toPx(), Offset(ex, eyeY))
                    }
                    drawCircle(color = StudioColors.Amber, radius = 2.1.dp.toPx(), center = Offset(cx, mouthY + 0.5.dp.toPx()))
                }
                else -> {
                    // Warm friendly human-like eyes with iris & catchlight
                    listOf(eyeLX, eyeRX).forEach { ex ->
                        drawCircle(Color(0xFFE0F2FE), 3.2.dp.toPx(), Offset(ex, eyeY))
                        drawCircle(StudioColors.Cyan, 2.1.dp.toPx(), Offset(ex, eyeY))
                        drawCircle(Color(0xFF0A192F), 1.1.dp.toPx(), Offset(ex, eyeY))
                        drawCircle(Color.White, 0.85.dp.toPx(), Offset(ex - 0.8.dp.toPx(), eyeY - 0.8.dp.toPx()))
                    }
                    // Warm gentle smile
                    drawArc(
                        color = Color(0xFF67E8F9),
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - 4.6.dp.toPx(), mouthY - 2.6.dp.toPx()),
                        size = Size(9.2.dp.toPx(), 5.2.dp.toPx()),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Friendly little expressive hands on the sides of the squircle
            val armRaisePx = handRaise.value * 11.dp.toPx()
            val hangArmsUp = mood == RobotMood.SLIPPING_HANG
            val armStartY = sqTop + sqSize * 0.62f
            val leftArmEndY = if (hangArmsUp) sqTop - 4.dp.toPx() else armStartY + 3.dp.toPx() - armRaisePx * 0.4f
            val rightArmEndY = if (hangArmsUp) sqTop - 4.dp.toPx() else armStartY + 3.dp.toPx() - armRaisePx
            drawLine(
                color = StudioColors.Cyan.copy(alpha = 0.85f),
                start = Offset(sqLeft, armStartY),
                end = Offset(sqLeft - 5.5.dp.toPx(), leftArmEndY),
                strokeWidth = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = Color(0xFFBAE6FD), radius = 2.1.dp.toPx(), center = Offset(sqLeft - 5.5.dp.toPx(), leftArmEndY))

            drawLine(
                color = StudioColors.Cyan.copy(alpha = 0.85f),
                start = Offset(sqLeft + sqSize, armStartY),
                end = Offset(sqLeft + sqSize + 5.5.dp.toPx(), rightArmEndY),
                strokeWidth = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = Color(0xFFBAE6FD), radius = 2.1.dp.toPx(), center = Offset(sqLeft + sqSize + 5.5.dp.toPx(), rightArmEndY))

            // Cute little swinging feet dangling gently over the card edge
            val legStartY = sqTop + sqSize
            val walkCycle = if (mood == RobotMood.WALKING_IN) sin(xFraction.value * 36f) * 4.dp.toPx() else 0f
            val swingL = if (mood == RobotMood.SITTING) legSwing * 3.dp.toPx() else walkCycle
            val swingR = if (mood == RobotMood.SITTING) -legSwing * 3.dp.toPx() else -walkCycle

            rotate(degrees = swingL * 3f, pivot = Offset(cx - sqSize * 0.22f, legStartY)) {
                drawLine(
                    color = Color(0xFF67E8F9).copy(alpha = 0.85f),
                    start = Offset(cx - sqSize * 0.22f, legStartY),
                    end = Offset(cx - sqSize * 0.24f, h * 0.94f),
                    strokeWidth = 2.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            rotate(degrees = swingR * 3f, pivot = Offset(cx + sqSize * 0.22f, legStartY)) {
                drawLine(
                    color = Color(0xFF67E8F9).copy(alpha = 0.85f),
                    start = Offset(cx + sqSize * 0.22f, legStartY),
                    end = Offset(cx + sqSize * 0.24f, h * 0.94f),
                    strokeWidth = 2.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
