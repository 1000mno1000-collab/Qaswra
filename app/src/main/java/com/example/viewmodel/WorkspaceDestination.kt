package com.example.viewmodel

import com.example.model.EffectType

enum class EchoDetailGroup(val arabicTitle: String, val subtitle: String) {
    ECHO_CORE("نواة الصدى والتكرار", "الشدة، العمق، زمن التأخير، والتغذية الراجعة"),
    REFLECTIONS("الانعكاسات والانتشار", "الانعكاسات المبكرة والمتأخرة، التأخير المسبق، والانتشار"),
    SPACE_TONE("الفراغ واللون الصوتي", "حجم القاعة، التلاشي، التخميد، الستيريو، والمزج")
}

enum class AiToolType(val arabicTitle: String, val subtitle: String) {
    TRANSCRIBE("تفريغ الصوت الذكي", "تحويل التسجيل والميكروفون إلى نص مجاناً عبر gemini-2.5-flash"),
    SMART_MASTER("مهندس الصوت الذكي", "تحليل طيفي تلقائي لضبط العزل والمعادل والضاغط مجاناً"),
    SEARCH_ASSISTANT("باحث الاستوديو المباشر", "معلومات محدثة من بحث Google عبر gemini-2.5-flash")
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
    data object TextToSpeechStudio : WorkspaceDestination
    data object VoiceTransformer : WorkspaceDestination
    data object Settings : WorkspaceDestination

    // Tier 2: Sub-tool Detail Panels
    data class EchoDetail(val group: EchoDetailGroup) : WorkspaceDestination
    data class EffectDetail(val effectType: EffectType) : WorkspaceDestination
    data class AiDetail(val tool: AiToolType) : WorkspaceDestination
}
