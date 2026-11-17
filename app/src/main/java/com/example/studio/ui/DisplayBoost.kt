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
