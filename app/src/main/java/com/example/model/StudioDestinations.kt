package com.example.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface StudioRoute {
    @Serializable
    data object Home : StudioRoute

    @Serializable
    data object Record : StudioRoute

    @Serializable
    data object VoicePurifier : StudioRoute

    @Serializable
    data object EchoSpace : StudioRoute

    @Serializable
    data class EchoDetail(val group: EchoDetailGroup) : StudioRoute

    @Serializable
    data object EffectsChain : StudioRoute

    @Serializable
    data class EffectDetail(val effect: EffectType) : StudioRoute

    @Serializable
    data object Editor : StudioRoute

    @Serializable
    data object Presets : StudioRoute

    @Serializable
    data object ProjectsExport : StudioRoute

    @Serializable
    data object AiStudio : StudioRoute

    @Serializable
    data class AiToolDetail(val tool: AiToolType) : StudioRoute

    @Serializable
    data object TtsStudio : StudioRoute

    @Serializable
    data object VoiceTransformer : StudioRoute

    @Serializable
    data object Settings : StudioRoute
}
