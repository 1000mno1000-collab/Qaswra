package com.example.audio.aivoice

import org.json.JSONObject

object AiVoicePromptBuilder {

    /**
     * Builds deterministic Style Conditioning instruction for VoxCPM2.
     */
    fun buildVoxCpmInstruction(request: AiVoiceSynthRequest): String {
        val parts = mutableListOf<String>()

        val profile = request.voiceDescriptionPrompt.lowercase()
        val catHint = when {
            "child" in profile || "طفل" in profile -> "child speaker"
            "elder" in profile || "مسن" in profile -> "elderly male speaker"
            request.category == AiVoiceCategory.MALE -> "male adult speaker"
            else -> "female adult speaker"
        }
        parts.add(catHint)
        parts.add("speaking ${request.language.titleEn}")

        val intensityLabel = when {
            request.emotionIntensity >= 0.80f -> "strong intense"
            request.emotionIntensity >= 0.45f -> "clear balanced"
            else -> "subtle gentle"
        }

        parts.add("with $intensityLabel ${request.emotion.voxDesignHintEn}")
        parts.add("at ${request.pace.promptHintEn} (rate=${"%.2f".format(java.util.Locale.US, request.speechRate)}x)")

        val userPrompt = request.voiceDescriptionPrompt.trim()
        if (userPrompt.isNotEmpty()) {
            parts.add("Role profile: $userPrompt")
        }

        return parts.joinToString(", ")
    }

    /**
     * Builds the deterministic JSON payload expected by the Sonix GPU Inference Backend (`/v1/voice-studio/synthesize`).
     */
    fun buildBackendPayloadJson(
        request: AiVoiceSynthRequest,
        textChunk: String,
        referenceAudioBase64: String? = null,
    ): JSONObject {
        val voxInstruction = buildVoxCpmInstruction(request)
        val processedText = textChunk.trim()

        return JSONObject().apply {
            put("engine", request.engine.id)
            put("model_repo", request.engine.modelRepoId)
            put("text", processedText)
            put("raw_text", processedText)
            put("language", request.language.code)
            put("dialect", "default")
            put("dialect_hint", request.language.titleEn)
            put("category", request.category.id)
            put("voice_mode", if (!referenceAudioBase64.isNullOrBlank()) "clone" else "design")
            put("voice_description", request.voiceDescriptionPrompt.trim())
            put("style_instruction", voxInstruction)
            put("emotion", request.emotion.id)
            put("emotion_intensity", request.emotionIntensity.toDouble())
            put("pace", request.pace.id)
            put("speaking_rate", request.speechRate.toDouble())
            put("pitch_semitones", request.pitchOffsetSemitones.toDouble())
            put("seed", request.fixedSeed)
            put("randomize_seed", false)
            put("fixed_sid", request.fixedSid)
            put("speaker_id", request.fixedSid)
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
     * Splits long input scripts on sentence/clause boundaries cleanly.
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
