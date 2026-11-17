# برومت Sonix - إنارة + فريمات + جودة فقط (انسخ كل شي من هنا لتحت)

أنت مطوّر Jetpack Compose محترف بالأداء. مهمتك على تطبيق Sonix: **ثلاث أشياء فقط**: الإنارة (glow)، معدل الفريمات، وجودة العرض.

## ممنوع منعاً باتاً
1. تغيير الثيم أو الألوان أو الخطوط أو الأشكال الموجودة. استخدم ألوان `MaterialTheme.colorScheme` الحالية للإنارة.
2. تغيير الترتيب أو التخطيط أو النصوص أو إضافة/حذف أي عنصر واجهة.
3. إنشاء `SonixTheme` أو `luxBackground` أو `GlassCard` أو `LuxButton` أو `AnimatedWaveform`. إذا لقيتها بالمشروع لا تحذفها ولا تستخدمها، وبلّغني.
4. لمس: `package.json` و `build.gradle.kts` و `strings.xml` و `.github/workflows/build.yml`. الشغل فقط داخل `app/src/main/`.
5. إضافة مكتبات، أو أي ميزة مدفوعة.

## المطلوب

### 1) الإنارة
- أنشئ الملف `Glow.kt` بالأسفل تحت `app/src/main/java/com/example/studio/ui/`.
- على كل زر وأداة قابلة للضغط (مربعات القوالب، الأزرار، الأشرطة): مرر `interactionSource` الخاص بها (`remember { MutableInteractionSource() }`) إلى `Card(onClick=..., interactionSource=...)` أو `Button(..., interactionSource=...)` أو `clickable`، ثم أضف `.lit(interaction, MaterialTheme.colorScheme.primary, selected = ...)`.
- الأداة المختارة تتوهج بشكل ثابت، وعند الضغط يزيد الوهج مع تصغير نابض خفيف.
- لا تغيّر حجم أو مكان أي عنصر؛ الوهج يُرسم خلف العنصر فقط.
- ضيف `contentPadding` كافي حول الشبكة إذا الوهج ينقص عند الحواف.

### 2) الفريمات
- أنشئ `DisplayBoost.kt` بالأسفل، واستدعِ `applyDisplayBoost()` داخل `onCreate` بـ MainActivity بعد `super.onCreate`.
- طبّق قواعد الأداء:
  - مفاتيح ثابتة `key` و `contentType` لكل `items` بالقوائم والشبكات.
  - قراءة حالات الأنميشن داخل `graphicsLayer {}` أو `drawBehind {}` فقط، لا داخل التركيب.
  - لا تقرأ `scrollState` أو `gridState` مباشرة بالتركيب؛ استخدم `derivedStateOf`.
  - `remember` لكل Brush وStroke وlambda ثقيلة.
  - لا `animateContentSize` ولا `blur` داخل القوائم.
  - علّم كلاسات الحالة بـ `@Immutable` أو `@Stable` إذا كانت تسبب recomposition زائد.
  - ممنوع أي عمل ثقيل (FFmpeg، قراءة ملفات، نسخ) على الـ Main thread.

### 3) الجودة
- كل `Image` أو `Canvas` يعرض صورة: `filterQuality = FilterQuality.High`.
- استبدل أي أيقونة bitmap بـ vector إذا متاح.
- لا تغيّر جودة الصوت: الخرج الحالي 48kHz / 320kbps يبقى كما هو.

بعد ما تخلص: اكتب قائمة بالملفات اللي أنشأتها أو عدّلتها، وأي شي ما سويته وسببه.

---

## الملف 1: `Glow.kt`

```kotlin
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
```

## الملف 2: `DisplayBoost.kt`

```kotlin
package com.example.studio.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build

/**
 * يطلب أعلى معدل تحديث تدعمه الشاشة (90/120Hz) وإظهار ألوان أوسع إذا الشاشة تدعمها.
 * استدعِه داخل onCreate بعد super.onCreate().
 */
fun Activity.applyDisplayBoost() {
    // أعلى refresh rate
    runCatching {
        val d = if (Build.VERSION.SDK_INT >= 30) display
        else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val best = d?.supportedModes?.maxByOrNull { it.refreshRate }
        if (best != null) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    // ألوان أوسع (للشاشات اللي تدعم ذلك فقط)
    if (Build.VERSION.SDK_INT >= 26 && resources.configuration.isScreenWideColorGamut) {
        runCatching { window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT }
    }
}
```
