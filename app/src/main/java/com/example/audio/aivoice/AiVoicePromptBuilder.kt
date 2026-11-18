package com.example.audio.aivoice

import org.json.JSONObject

object AiVoicePromptBuilder {

    /**
     * Builds natural-language Voice Design / Style Conditioning instruction for VoxCPM2.
     */
    fun buildVoxCpmInstruction(request: AiVoiceSynthRequest): String {
        val parts = mutableListOf<String>()

        val catHint = when (request.category) {
            AiVoiceCategory.MALE -> "male adult speaker"
            AiVoiceCategory.FEMALE -> "female adult speaker"
            AiVoiceCategory.CHILD -> "young child speaker"
            AiVoiceCategory.ELDERLY -> "elderly weathered speaker"
            AiVoiceCategory.NARRATOR -> "cinematic documentary narrator"
            AiVoiceCategory.CARTOON -> "expressive animated cartoon character"
            AiVoiceCategory.CUSTOM_DESIGN -> "custom designed character"
        }
        parts.add(catHint)

        if (request.language == AiVoiceLanguage.AR) {
            parts.add("speaking ${request.dialect.promptHintEn}")
        } else {
            parts.add("speaking ${request.language.titleEn}")
        }

        val intensityLabel = when {
            request.emotionIntensity >= 0.80f -> "strong intense"
            request.emotionIntensity >= 0.45f -> "clear balanced"
            else -> "subtle gentle"
        }

        if (request.emotion != AiVoiceEmotion.NEUTRAL) {
            parts.add("with $intensityLabel ${request.emotion.voxDesignHintEn}")
        } else {
            parts.add("with ${request.emotion.voxDesignHintEn}")
        }

        parts.add("at ${request.pace.promptHintEn}")

        val userPrompt = request.voiceDescriptionPrompt.trim()
        if (userPrompt.isNotEmpty()) {
            parts.add("Character details: $userPrompt")
        }

        return parts.joinToString(", ")
    }

    /**
     * Formats text with Audar-TTS-V1-Turbo native inline expressive tags
     * ([laughs], [whispers], [excited], [curious], [sighs]) while preserving Arabic text integrity.
     */
    fun buildAudarExpressiveText(
        text: String,
        emotion: AiVoiceEmotion,
        intensity: Float,
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return clean
        val tag = emotion.audarTag ?: return clean
        if (clean.contains(tag, ignoreCase = true)) return clean
        return if (intensity >= 0.78f && clean.length > 48 && (emotion == AiVoiceEmotion.SAD || emotion == AiVoiceEmotion.CRYING || emotion == AiVoiceEmotion.WHISPER)) {
            "$tag $clean"
        } else {
            "$tag $clean"
        }
    }

    /**
     * Builds the exact JSON payload expected by the Sonix GPU Inference Backend (`/v1/voice-studio/synthesize`).
     */
    fun buildBackendPayloadJson(
        request: AiVoiceSynthRequest,
        textChunk: String,
        referenceAudioBase64: String? = null,
    ): JSONObject {
        val voxInstruction = buildVoxCpmInstruction(request)
        val processedText = if (request.engine == AiVoiceEngineKind.AUDAR_TTS_V1_FLASH ||
            request.engine == AiVoiceEngineKind.AUDAR_TTS_V1_TURBO
        ) {
            buildAudarExpressiveText(textChunk, request.emotion, request.emotionIntensity)
        } else {
            textChunk.trim()
        }

        return JSONObject().apply {
            put("engine", request.engine.id)
            put("model_repo", request.engine.modelRepoId)
            put("text", processedText)
            put("raw_text", textChunk.trim())
            put("language", request.language.code)
            put("dialect", if (request.language == AiVoiceLanguage.AR) request.dialect.id else "default")
            put("dialect_hint", if (request.language == AiVoiceLanguage.AR) request.dialect.promptHintEn else request.language.titleEn)
            put("category", request.category.id)
            put("voice_mode", if (!referenceAudioBase64.isNullOrBlank()) "clone" else "design")
            put("voice_description", request.voiceDescriptionPrompt.trim())
            put("style_instruction", voxInstruction)
            put("emotion", request.emotion.id)
            put("emotion_intensity", request.emotionIntensity.toDouble())
            put("pace", request.pace.id)
            put("speaking_rate", request.pace.speedMultiplier.toDouble())
            put("pitch_semitones", request.pitchOffsetSemitones.toDouble())
            put("cfg_value", request.cfgValue.toDouble())
            put("inference_timesteps", request.inferenceTimesteps)
            put("normalize", request.normalizeAudio)
            if (!referenceAudioBase64.isNullOrBlank()) {
                put("reference_audio_base64", referenceAudioBase64)
                if (request.referenceTranscript.isNotBlank()) {
                    put("reference_transcript", request.referenceTranscript.trim())
                }
            }
        }
    }

    /**
     * Splits long input scripts on sentence/clause boundaries so long texts can be synthesized cleanly
     * without GPU request timeouts or model token truncation.
     */
    fun splitTextIntoChunks(text: String, maxCharsPerChunk: Int = 260): List<String> {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxCharsPerChunk) return listOf(normalized)

        val sentences = normalized.split(Regex("(?<=[.!؟?!\\n؛])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            if (s.length > maxCharsPerChunk) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                val words = s.split(" ")
                for (w in words) {
                    if (current.length + w.length + 1 > maxCharsPerChunk && current.isNotEmpty()) {
                        chunks.add(current.toString().trim())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(w)
                }
            } else {
                if (current.length + s.length + 1 > maxCharsPerChunk && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            }
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
        return chunks
    }
}
