package com.example.devpanel

import com.example.BuildConfig
import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiVoiceCategory
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoicePromptBuilder
import com.example.audio.aivoice.AiVoiceSynthRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperPanelUnitTests {

    @Test
    fun defaultUiStateContainsZeroFabricatedNumbers() {
        val state = DeveloperPanelUiState()
        val summary = computeDeveloperFinanceSummary(state)

        assertEquals(0L, state.totalFreeCredits)
        assertEquals(0L, state.totalPaidCredits)
        assertTrue(state.creditAccounts.isEmpty())
        assertTrue(state.payments.isEmpty())
        assertTrue(state.testRuns.isEmpty())
        assertEquals(0.0, summary.totalRevenueUsd, 0.0001)
        assertEquals(0.0, summary.totalCostsUsd, 0.0001)
        assertEquals(0.0, summary.netProfitUsd, 0.0001)
        assertEquals(0.0, summary.partnerShareUsd, 0.0001)
        assertFalse(state.ownerVerification.isAuthorized)
        assertTrue(BuildConfig.DEVELOPER_PANEL_ENABLED)
    }

    @Test
    fun computeFinanceSummaryCalculatesRevenueCostsNetAndPartnerShareAccurately() {
        val state = DeveloperPanelUiState(
            payments = listOf(
                PaymentRecord("p1", "u1", PaymentType.PURCHASE, "pack_100", 20.0, PaymentStatus.SUCCEEDED, "google_play"),
                PaymentRecord("p2", "u2", PaymentType.PURCHASE, "pack_50", 10.0, PaymentStatus.FAILED, "google_play"),
                PaymentRecord("p3", "u3", PaymentType.SUBSCRIPTION, "pro_monthly", 15.0, PaymentStatus.ACTIVE, "google_play"),
                PaymentRecord("p4", "u4", PaymentType.SUBSCRIPTION, "old_sub", 15.0, PaymentStatus.CANCELLED, "google_play"),
            ),
            financeEntries = listOf(
                FinanceLedgerRecord("f1", FinanceEntryType.AD_REVENUE, "AdMob", 5.0, "2026-11"),
                FinanceLedgerRecord("f2", FinanceEntryType.HOSTING_COST, "RunPod", 8.0, "2026-11"),
                FinanceLedgerRecord("f3", FinanceEntryType.ENGINE_COST, "HF Inference", 2.0, "2026-11"),
            ),
            serverTelemetry = ServerTelemetrySnapshot(
                connected = true,
                estimatedComputeCostUsd = 2.0,
            ),
            financeConfig = AdminFinanceConfig(
                partnerSharePercent = 25.0,
                partnerName = "Partner A",
            ),
        )

        val summary = computeDeveloperFinanceSummary(state)

        assertEquals(1, summary.succeededPurchasesCount)
        assertEquals(20.0, summary.succeededPurchasesUsd, 0.0001)
        assertEquals(1, summary.failedPurchasesCount)
        assertEquals(10.0, summary.failedPurchasesUsd, 0.0001)
        assertEquals(1, summary.activeSubscriptionsCount)
        assertEquals(15.0, summary.activeSubscriptionsUsd, 0.0001)
        assertEquals(5.0, summary.adRevenuesUsd, 0.0001)
        // Total revenue = 20 + 15 + 5 = 40
        assertEquals(40.0, summary.totalRevenueUsd, 0.0001)
        // Total cost = (8 + 2) ledger + 2 server compute = 12
        assertEquals(12.0, summary.totalCostsUsd, 0.0001)
        // Net profit = 40 - 12 = 28
        assertEquals(28.0, summary.netProfitUsd, 0.0001)
        // Partner 25% of 28 = 7.0; owner net = 21.0
        assertEquals(7.0, summary.partnerShareUsd, 0.0001)
        assertEquals(21.0, summary.ownerNetUsd, 0.0001)
    }

    @Test
    fun partnerShareIsZeroWhenNetProfitIsNegative() {
        val state = DeveloperPanelUiState(
            payments = listOf(
                PaymentRecord("p1", "u1", PaymentType.PURCHASE, "pack", 5.0, PaymentStatus.SUCCEEDED, "google_play"),
            ),
            financeEntries = listOf(
                FinanceLedgerRecord("f1", FinanceEntryType.HOSTING_COST, "GPU", 20.0, "2026-11"),
            ),
            financeConfig = AdminFinanceConfig(partnerSharePercent = 50.0),
        )

        val summary = computeDeveloperFinanceSummary(state)
        assertEquals(-15.0, summary.netProfitUsd, 0.0001)
        assertEquals(0.0, summary.partnerShareUsd, 0.0001)
        assertEquals(-15.0, summary.ownerNetUsd, 0.0001)
    }

    @Test
    fun creditsTotalsAndFailedRefundedFiltersWorkAccurately() {
        val state = DeveloperPanelUiState(
            creditAccounts = listOf(
                CreditAccountRecord(userId = "u1", freeCredits = 50, paidCredits = 120),
                CreditAccountRecord(userId = "u2", freeCredits = 30, paidCredits = 80),
            ),
            creditTransactions = listOf(
                CreditTransactionRecord(docId = "t1", userId = "u1", amount = 50, status = CreditTxStatus.COMPLETED),
                CreditTransactionRecord(docId = "t2", userId = "u1", amount = 20, status = CreditTxStatus.FAILED),
                CreditTransactionRecord(docId = "t3", userId = "u2", amount = 10, status = CreditTxStatus.REFUNDED),
            ),
        )

        assertEquals(80L, state.totalFreeCredits)
        assertEquals(200L, state.totalPaidCredits)
        assertEquals(2, state.failedOrRefundedTransactions.size)
        assertEquals(listOf("t2", "t3"), state.failedOrRefundedTransactions.map { it.docId })
    }

    @Test
    fun audarFlashEngineSupportsArabicAndExpressiveTags() {
        val req = AiVoiceSynthRequest(
            engine = AiVoiceEngineKind.AUDAR_TTS_V1_FLASH,
            text = "مرحباً بكم في اختبار المحرك العربي التجاري.",
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.MSA,
            category = AiVoiceCategory.MALE,
            emotion = AiVoiceEmotion.WHISPER,
            emotionIntensity = 0.7f,
            pace = AiDeliveryPace.NORMAL,
        )
        val processed = AiVoicePromptBuilder.buildAudarExpressiveText(req.text, req.emotion, req.emotionIntensity)
        assertTrue(processed.startsWith("[whispers]"))
        assertEquals("audar_tts_flash", AiVoiceEngineKind.AUDAR_TTS_V1_FLASH.id)
    }
}
