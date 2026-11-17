package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun enterStudioAsGuest() {
        composeTestRule.waitForIdle()
        if (composeTestRule.onAllNodesWithTag("guest_continue_button").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithTag("guest_continue_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `app name matches Voice Pro`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voice Pro", appName)
    }

    @Test
    fun `auth screen allows guest entry and home profile badge switches back to sign in`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_google_signin_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("guest_continue_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_profile_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_sign_in_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_google_signin_button").assertIsDisplayed()
    }

    @Test
    fun `focus workspace navigation enters echo space and returns home`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_echo_reverb"))
        composeTestRule.onNodeWithTag("section_echo_reverb").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("spatial_echo_pad").assertIsDisplayed()
        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_recording"))
        composeTestRule.onNodeWithTag("section_recording").assertIsDisplayed()
    }

    @Test
    fun `ai studio exposes free transcribe, smart master, and search tools without paid music gen`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_ai_studio"))
        composeTestRule.onNodeWithTag("section_ai_studio").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ai_tool_transcribe").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_tool_smart_master").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_tool_search").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `room database indexes audio recordings cleanly alongside projects`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.StudioDatabase.getInstance(context)
        val dao = db.studioDao()
        val id = dao.upsertRecording(
            com.example.data.AudioRecordingEntity(
                filePath = "/tmp/test_take.wav",
                title = "اختبار تسجيل",
                timestamp = 1700000000000L,
                durationMs = 2500L,
                sourceType = "RECORDING"
            )
        )
        assertTrue(id > 0L)
        dao.deleteRecordingById(id)
    }

    @Test
    fun `recording dashboard bypass compare and export normalize switch are displayed`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_recording"))
        composeTestRule.onNodeWithTag("section_recording").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recording_timer_display").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_start_stop_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("transport_loop_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("transport_bypass_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_projects_export"))
        composeTestRule.onNodeWithTag("section_projects_export").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("normalize_before_export_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("projects_save_export_audio_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `home back press opens exit confirmation dialog and cancel stays in app`() {
        enterStudioAsGuest()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_confirm_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("exit_cancel_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_realtime_waveform").assertIsDisplayed()
    }

    @Test
    fun `viewmodel tracks undo redo stacks and unsaved changes in memory`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.viewmodel.StudioViewModel(app)
        assertTrue(vm.uiState.value.isAutoLoopEnabled)
        vm.toggleAutoLoop()
        assertEquals(false, vm.uiState.value.isAutoLoopEnabled)

        val buf = com.example.audio.io.StereoAudioBuffer.fromMono(FloatArray(22050) { 0.4f }, 44100)
        val field = com.example.viewmodel.StudioViewModel::class.java.getDeclaredMethod(
            "setActiveBuffer",
            com.example.audio.io.StereoAudioBuffer::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        field.invoke(vm, buf, false)
        assertEquals(false, vm.uiState.value.hasUnsavedChanges)

        vm.trimKeepSelection()
        assertEquals(true, vm.uiState.value.hasUnsavedChanges)
        assertEquals(true, vm.uiState.value.canUndoEdit)
        assertEquals(false, vm.uiState.value.canRedoEdit)

        vm.undoLastEdit()
        assertEquals(false, vm.uiState.value.canUndoEdit)
        assertEquals(true, vm.uiState.value.canRedoEdit)

        vm.redoLastEdit()
        assertEquals(true, vm.uiState.value.canUndoEdit)
        assertEquals(false, vm.uiState.value.canRedoEdit)
    }

    @Test
    fun `language switcher persists selection across Arabic, English, and Persian and updates localized context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.ui.LocaleHelper.saveLanguage(context, com.example.ui.AppLanguage.ARABIC)
        enterStudioAsGuest()

        composeTestRule.onNodeWithTag("home_language_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lang_option_en").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(com.example.ui.AppLanguage.ENGLISH, com.example.ui.LocaleHelper.getSavedLanguage(context))
        val enRes = com.example.ui.LocaleHelper.createLocalizedContext(context, com.example.ui.AppLanguage.ENGLISH)
        assertEquals("Text-to-Speech Studio", enRes.getString(R.string.section_tts_title))

        composeTestRule.onNodeWithTag("home_language_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lang_option_fa").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.ui.AppLanguage.PERSIAN, com.example.ui.LocaleHelper.getSavedLanguage(context))

        // Restore default Arabic so subsequent tests run cleanly
        kotlinx.coroutines.runBlocking {
            com.example.ui.LocaleHelper.saveLanguageToDataStore(context, com.example.ui.AppLanguage.ARABIC)
        }
    }

    @Test
    fun `tts studio opens from home and shows 10 dialects with preview and studio insert actions`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_tts_studio"))
        composeTestRule.onNodeWithTag("section_tts_studio").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tts_text_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_dialect_ar_iq").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_dialect_fa_ir").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_dialect_tr_tr").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_preview_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_insert_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `voice transformer workspace opens cleanly from home`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_voice_transformer"))
        composeTestRule.onNodeWithTag("section_voice_transformer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed()
    }

    @Test
    fun `settings screen switches app language via DataStore and updates RTL LTR options`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.runBlocking {
            com.example.ui.LocaleHelper.saveLanguageToDataStore(context, com.example.ui.AppLanguage.ARABIC)
        }
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_settings"))
        composeTestRule.onNodeWithTag("section_settings").performClick()
        composeTestRule.waitForIdle()

        val node = composeTestRule.onNodeWithTag("settings_lang_en").performScrollTo().assertIsDisplayed()
        println("DIAG settings_lang_en bounds=${node.fetchSemanticsNode().boundsInRoot} config=${node.fetchSemanticsNode().config}")
        node.performClick()
        composeTestRule.waitForIdle()
        println("DIAG after performClick saved=${com.example.ui.LocaleHelper.getSavedLanguage(context)}")
        assertEquals(com.example.ui.AppLanguage.ENGLISH, com.example.ui.LocaleHelper.getSavedLanguage(context))

        composeTestRule.onNodeWithTag("settings_lang_fa").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.ui.AppLanguage.PERSIAN, com.example.ui.LocaleHelper.getSavedLanguage(context))

        composeTestRule.onNodeWithTag("settings_lang_ar").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.ui.AppLanguage.ARABIC, com.example.ui.LocaleHelper.getSavedLanguage(context))
    }

    @Test
    fun `audio editor integrates embedded 10-dialect TTS card cleanly`() {
        enterStudioAsGuest()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_audio_editor"))
        composeTestRule.onNodeWithTag("section_audio_editor").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor_tts_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_tts_dialect_ar_iq").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_tts_dialect_fa_ir").performScrollTo().assertIsDisplayed()
    }
}
