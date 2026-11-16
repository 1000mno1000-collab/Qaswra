package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    @Test
    fun `app name matches Voice Pro`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voice Pro", appName)
    }

    @Test
    fun `focus workspace navigation enters echo space and returns home`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_echo_reverb"))
        composeTestRule.onNodeWithTag("section_echo_reverb").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("spatial_echo_pad").assertIsDisplayed()
        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("section_recording"))
        composeTestRule.onNodeWithTag("section_recording").assertIsDisplayed()
    }
}
