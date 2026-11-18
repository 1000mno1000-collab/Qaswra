package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseAndAuthTest {

    @Test
    fun firebaseDefaultAppInitializesSafelyWithoutThrowing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialized = SonixCrashGuard.ensureFirebaseInitialized(context)
        assertTrue("Expected FirebaseApp to initialize from generated google-services resources", initialized)
        val apps = FirebaseApp.getApps(context)
        assertFalse("FirebaseApp.getApps(context) must not be empty", apps.isEmpty())

        val auth = FirebaseAuth.getInstance()
        assertNotNull("FirebaseAuth.getInstance() must succeed once initialized", auth)

        val dbId = context.getString(R.string.firestore_database_id)
        assertFalse("Named firestore_database_id must be present", dbId.isBlank())
        assertNotNull(FirebaseFirestore.getInstance(dbId))
    }

    @Test
    fun applicationIdAndGoogleServicesResourcesMatchProjectConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.aistudio.voicepro.sonixk", context.packageName)
        assertTrue(SonixCrashGuard.ensureFirebaseInitialized(context))

        val app = FirebaseApp.getInstance()
        assertEquals("gen-lang-client-0021883952", app.options.projectId)
        assertEquals("1:71794555883:android:4c65036e94f7e36e3b37ae", app.options.applicationId)

        val webClientId = context.getString(R.string.default_web_client_id)
        assertFalse("default_web_client_id must be generated from google-services.json", webClientId.isBlank())
    }

    @Test
    fun verifyFirebaseConnectivityLogsConfirmationToLogcat() = runBlocking {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = SonixCrashGuard.verifyFirebaseConnectivity(context)

        assertTrue(result.firebaseAppInitialized)
        assertTrue(result.authReady)
        assertEquals("com.aistudio.voicepro.sonixk", result.packageName)
        assertEquals("gen-lang-client-0021883952", result.projectId)
        assertTrue(result.summary.contains("CONFIRMED: Firebase ready"))
        assertEquals(result, SonixCrashGuard.firebaseConnectivityState.value)

        val logEntries = ShadowLog.getLogsForTag("SonixFirebaseCheck")
        assertTrue(
            "Expected Logcat confirmation under tag SonixFirebaseCheck",
            logEntries.any { it.msg.contains("CONFIRMED: Firebase ready") },
        )
    }

    @Test
    fun firestoreRepositoryRejectsBlankInputsWithFailureResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SonixCrashGuard.ensureFirebaseInitialized(context)
        val repo = com.example.data.remote.FirestoreStudioRepository(context)

        val presetRes = repo.saveCustomPreset(
            userId = "test-user",
            name = "   ",
            description = "desc",
            config = com.example.model.DspConfig(),
        )
        assertTrue("Blank preset name must return Result.failure", presetRes.isFailure)

        val draftRes = repo.saveDraft(
            userId = "test-user",
            existingDraftId = null,
            name = "   ",
            selectedPresetName = "Studio",
            durationMs = 1000L,
            sampleRate = 48000,
            config = com.example.model.DspConfig(),
        )
        assertTrue("Blank draft name must return Result.failure", draftRes.isFailure)
    }
}
