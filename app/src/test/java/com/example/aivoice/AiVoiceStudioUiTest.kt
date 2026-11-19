package com.example.aivoice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.model.AppLanguage
import com.example.ui.screens.AiVoiceStudioContentLayout
import com.example.ui.theme.SonixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiVoiceStudioUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stickyBottomBarRemainsVisibleBeforeAndAfterScrolling() {
        var previewClicked = false
        var approveClicked = false
        val stateHolder = mutableStateOf(
            AiVoiceStudioUiState(
                scriptText = "مرحبا بكم في تجربة الاستوديو",
                lastOutputWavPath = "/tmp/sample.wav",
                lastOutputDurationMs = 1500L,
            ),
        )

        composeRule.setContent {
            SonixTheme {
                AiVoiceStudioContentLayout(
                    state = stateHolder.value,
                    language = AppLanguage.AR,
                    hasStudioAudio = false,
                    onBack = {},
                    onSelectTab = {},
                    onDismissError = {},
                    onUseLocalFallback = {},
                    onPlayPause = {},
                    onReplay = {},
                    onStopPlayback = {},
                    onSeekMs = {},
                    onSaveWav = {},
                    onShare = {},
                    onInsertIntoStudio = {},
                    onScriptChange = { stateHolder.value = stateHolder.value.copy(scriptText = it) },
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onSelectCharacter = {},
                    onToggleFavorite = {},
                    onSelectEmotion = {},
                    onSpeechRateChange = {},
                    onIntensityChange = {},
                    onResetTuning = {},
                    onPickReferenceFile = {},
                    onUseStudioBufferAsReference = {},
                    onClearReference = {},
                    onReferenceTranscriptChange = {},
                    onRightsConfirmedChange = {},
                    onAddDialogueTurn = {},
                    onRemoveDialogueTurn = {},
                    onMoveDialogueTurn = { _, _ -> },
                    onUpdateDialogueTurn = { _, _ -> },
                    onSynthesizeDialogue = {},
                    onApplyCharacterToStudio = {},
                    onAddCharacterToDialogue = {},
                    onSaveCustomCharacter = { _, _ -> },
                    onDeleteSavedCharacter = {},
                    onSaveBackendUrl = {},
                    onSaveBackendToken = {},
                    onCheckHealth = { _, _ -> },
                    onPreviewClick = { previewClicked = true },
                    onCancelSynthesis = {},
                    onApproveClick = { approveClicked = true },
                    bottomHelpSlot = {
                        Column(modifier = Modifier.testTag("more_and_help_card")) {
                            Text("المزيد والمساعدة")
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_voice_sticky_bottom_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_sticky_preview_button").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_sticky_approve_button").assertIsDisplayed()

        // Scroll to the end of the page and confirm bar stays pinned
        composeRule.onNodeWithTag("ai_voice_scroll_list").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("more_and_help_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_sticky_bottom_bar").assertIsDisplayed()

        composeRule.onNodeWithTag("ai_voice_sticky_preview_button").performClick()
        composeRule.onNodeWithTag("ai_voice_sticky_approve_button").performClick()
        composeRule.waitForIdle()

        assertTrue(previewClicked)
        assertTrue(approveClicked)
    }

    @Test
    fun textCountersUpdateAndClearButtonResetsScript() {
        var state by mutableStateOf(AiVoiceStudioUiState(scriptText = "كلمة اولى"))

        composeRule.setContent {
            SonixTheme {
                AiVoiceStudioContentLayout(
                    state = state,
                    language = AppLanguage.AR,
                    hasStudioAudio = false,
                    onBack = {},
                    onSelectTab = {},
                    onDismissError = {},
                    onUseLocalFallback = {},
                    onPlayPause = {},
                    onReplay = {},
                    onStopPlayback = {},
                    onSeekMs = {},
                    onSaveWav = {},
                    onShare = {},
                    onInsertIntoStudio = {},
                    onScriptChange = { state = state.copy(scriptText = it) },
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onSelectCharacter = {},
                    onToggleFavorite = {},
                    onSelectEmotion = {},
                    onSpeechRateChange = {},
                    onIntensityChange = {},
                    onResetTuning = {},
                    onPickReferenceFile = {},
                    onUseStudioBufferAsReference = {},
                    onClearReference = {},
                    onReferenceTranscriptChange = {},
                    onRightsConfirmedChange = {},
                    onAddDialogueTurn = {},
                    onRemoveDialogueTurn = {},
                    onMoveDialogueTurn = { _, _ -> },
                    onUpdateDialogueTurn = { _, _ -> },
                    onSynthesizeDialogue = {},
                    onApplyCharacterToStudio = {},
                    onAddCharacterToDialogue = {},
                    onSaveCustomCharacter = { _, _ -> },
                    onDeleteSavedCharacter = {},
                    onSaveBackendUrl = {},
                    onSaveBackendToken = {},
                    onCheckHealth = { _, _ -> },
                    onPreviewClick = {},
                    onCancelSynthesis = {},
                    onApproveClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_voice_script_input").performTextReplacement("أهلا يا صديقي العزيز")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_voice_text_counters").assertTextContains("4 كلمة", substring = true)

        composeRule.onNodeWithTag("ai_voice_clear_script_button").performClick()
        composeRule.waitForIdle()
        assertEquals("", state.scriptText)
        composeRule.onNodeWithTag("ai_voice_text_counters").assertTextContains("0 كلمة", substring = true)
    }

    @Test
    fun studioCardsRenderEmotionsAndTuningControlsCleanly() {
        var state by mutableStateOf(
            AiVoiceStudioUiState(
                selectedEngine = AiVoiceEngineKind.VOXCPM_2,
                selectedEmotion = AiVoiceEmotion.NEUTRAL,
            ),
        )

        composeRule.setContent {
            SonixTheme {
                AiVoiceStudioContentLayout(
                    state = state,
                    language = AppLanguage.AR,
                    hasStudioAudio = false,
                    onBack = {},
                    onSelectTab = {},
                    onDismissError = {},
                    onUseLocalFallback = {},
                    onPlayPause = {},
                    onReplay = {},
                    onStopPlayback = {},
                    onSeekMs = {},
                    onSaveWav = {},
                    onShare = {},
                    onInsertIntoStudio = {},
                    onScriptChange = {},
                    onSelectEngine = { state = state.copy(selectedEngine = it) },
                    onSelectLanguage = {},
                    onSelectCharacter = {},
                    onToggleFavorite = {},
                    onSelectEmotion = { state = state.copy(selectedEmotion = it) },
                    onSpeechRateChange = {},
                    onIntensityChange = {},
                    onResetTuning = {},
                    onPickReferenceFile = {},
                    onUseStudioBufferAsReference = {},
                    onClearReference = {},
                    onReferenceTranscriptChange = {},
                    onRightsConfirmedChange = {},
                    onAddDialogueTurn = {},
                    onRemoveDialogueTurn = {},
                    onMoveDialogueTurn = { _, _ -> },
                    onUpdateDialogueTurn = { _, _ -> },
                    onSynthesizeDialogue = {},
                    onApplyCharacterToStudio = {},
                    onAddCharacterToDialogue = {},
                    onSaveCustomCharacter = { _, _ -> },
                    onDeleteSavedCharacter = {},
                    onSaveBackendUrl = {},
                    onSaveBackendToken = {},
                    onCheckHealth = { _, _ -> },
                    onPreviewClick = {},
                    onCancelSynthesis = {},
                    onApproveClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_voice_scroll_list")
            .performScrollToNode(hasTestTag("studio_group_emotion"))
        composeRule.onNodeWithTag("ai_voice_emotion_whisper").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_emotion_excited").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("ai_voice_scroll_list")
            .performScrollToNode(hasTestTag("studio_group_controls"))
        composeRule.onNodeWithTag("ai_voice_speed_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_intensity_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_reset_tuning_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fallbackBannerAppearsWhenConfiguredAndEngineCardsAreBothVisible() {
        val state = AiVoiceStudioUiState(
            errorTitle = "إعداد مطلوب",
            setupRequiredGuide = "يرجى التبديل للمحلي",
        )

        composeRule.setContent {
            SonixTheme {
                AiVoiceStudioContentLayout(
                    state = state,
                    language = AppLanguage.AR,
                    hasStudioAudio = false,
                    onBack = {},
                    onSelectTab = {},
                    onDismissError = {},
                    onUseLocalFallback = {},
                    onPlayPause = {},
                    onReplay = {},
                    onStopPlayback = {},
                    onSeekMs = {},
                    onSaveWav = {},
                    onShare = {},
                    onInsertIntoStudio = {},
                    onScriptChange = {},
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onSelectCharacter = {},
                    onToggleFavorite = {},
                    onSelectEmotion = {},
                    onSpeechRateChange = {},
                    onIntensityChange = {},
                    onResetTuning = {},
                    onPickReferenceFile = {},
                    onUseStudioBufferAsReference = {},
                    onClearReference = {},
                    onReferenceTranscriptChange = {},
                    onRightsConfirmedChange = {},
                    onAddDialogueTurn = {},
                    onRemoveDialogueTurn = {},
                    onMoveDialogueTurn = { _, _ -> },
                    onUpdateDialogueTurn = { _, _ -> },
                    onSynthesizeDialogue = {},
                    onApplyCharacterToStudio = {},
                    onAddCharacterToDialogue = {},
                    onSaveCustomCharacter = { _, _ -> },
                    onDeleteSavedCharacter = {},
                    onSaveBackendUrl = {},
                    onSaveBackendToken = {},
                    onCheckHealth = { _, _ -> },
                    onPreviewClick = {},
                    onCancelSynthesis = {},
                    onApproveClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_voice_use_local_fallback_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_scroll_list")
            .performScrollToNode(hasTestTag("studio_card_engine"))
        composeRule.onNodeWithTag("ai_voice_engine_${AiVoiceEngineKind.VOXCPM_2.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_voice_engine_${AiVoiceEngineKind.SONIX_LOCAL_STUDIO.id}").assertIsDisplayed()
    }
}
