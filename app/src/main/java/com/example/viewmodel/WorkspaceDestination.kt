package com.example.viewmodel

import com.example.model.EffectType

enum class EchoDetailGroup(val arabicTitle: String, val subtitle: String) {
    ECHO_CORE("نواة الصدى والتكرار", "الشدة، العمق، زمن التأخير، والتغذية الراجعة"),
    REFLECTIONS("الانعكاسات والانتشار", "الانعكاسات المبكرة والمتأخرة، التأخير المسبق، والانتشار"),
    SPACE_TONE("الفراغ واللون الصوتي", "حجم القاعة، التلاشي، التخميد، الستيريو، والمزج")
}

enum class AiToolType(val arabicTitle: String, val subtitle: String) {
    TRANSCRIBE("تفريغ الصوت الذكي", "تحويل التسجيل والميكروفون إلى نص عبر gemini-3.5-transcribe"),
    MUSIC_GEN("توليد الموسيقى", "إنشاء مقاطع وموسيقى كاملة عبر Lyria 3 ودمجها بالاستوديو"),
    SEARCH_ASSISTANT("باحث الاستوديو المباشر", "معلومات محدثة من بحث Google عبر gemini-4-hydrogen")
}

sealed interface WorkspaceDestination {
    data object Home : WorkspaceDestination

    // Tier 1: Dedicated Focus Workspaces
    data object RecordingStudio : WorkspaceDestination
    data object VoicePurifier : WorkspaceDestination
    data object EchoReverbSpace : WorkspaceDestination
    data object EffectsChain : WorkspaceDestination
    data object AudioEditor : WorkspaceDestination
    data object Presets : WorkspaceDestination
    data object ProjectsExport : WorkspaceDestination
    data object AiStudio : WorkspaceDestination

    // Tier 2: Sub-tool Detail Panels
    data class EchoDetail(val group: EchoDetailGroup) : WorkspaceDestination
    data class EffectDetail(val effectType: EffectType) : WorkspaceDestination
    data class AiDetail(val tool: AiToolType) : WorkspaceDestination
}
