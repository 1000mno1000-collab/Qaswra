package com.example.devpanel

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.google.firebase.firestore.IgnoreExtraProperties

@Immutable
enum class DeveloperPanelTab(val titleAr: String, val titleEn: String) {
    ENGINES("المحركات", "Engines"),
    SERVER("الخادم", "Server"),
    CREDITS("الأرصدة", "Credits"),
    PAYMENTS("المدفوعات", "Payments"),
    CHANGELOG("سجل التطوير", "Changelog"),
    TESTS("الاختبارات", "Tests"),
}

enum class OwnerVerificationStatus {
    DISABLED_IN_BUILD,
    NOT_AUTHENTICATED,
    VERIFYING,
    AUTHORIZED,
    DENIED,
}

@Immutable
data class OwnerVerificationState(
    val status: OwnerVerificationStatus = OwnerVerificationStatus.VERIFYING,
    val verifiedUid: String = "",
    val verifiedEmail: String = "",
    val firestoreVerified: Boolean = false,
    val backendVerified: Boolean = false,
    val statusMessageAr: String = "جاري التحقق من صلاحية المالك من الخادم...",
    val statusMessageEn: String = "Verifying owner privileges on server...",
) {
    val isAuthorized: Boolean
        get() = status == OwnerVerificationStatus.AUTHORIZED
}

@Immutable
data class EngineTelemetrySnapshot(
    val engineId: String,
    val displayName: String,
    val repoId: String,
    val licenseLabel: String,
    val clientIntegrated: Boolean,
    val backendConnected: Boolean = false,
    val backendPackageReady: Boolean = false,
    val backendLoadedInMemory: Boolean = false,
    val generationsCount: Int = 0,
    val failuresCount: Int = 0,
    val lastLatencyMs: Long? = null,
    val lastSampleRate: Int? = null,
    val lastError: String? = null,
)

@Immutable
data class ServerTelemetrySnapshot(
    val checked: Boolean = false,
    val connected: Boolean = false,
    val backendUrlConfigured: Boolean = false,
    val httpCode: Int = 0,
    val pingMs: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val cudaAvailable: Boolean = false,
    val gpuName: String = "—",
    val vramTotalMb: Double = 0.0,
    val vramUsedMb: Double = 0.0,
    val ramTotalMb: Double = 0.0,
    val ramUsedMb: Double = 0.0,
    val cpuPercent: Double = 0.0,
    val totalGenerations: Int = 0,
    val failedGenerations: Int = 0,
    val totalComputeSeconds: Double = 0.0,
    val gpuHourlyRateUsd: Double = 0.0,
    val estimatedComputeCostUsd: Double = 0.0,
    val hfTokenConfigured: Boolean = false,
    val errorDetail: String? = null,
)

enum class CreditType { FREE, PAID }
enum class CreditOperation { GRANT, DEDUCT, REFUND, FAILED_CHARGE }
enum class CreditTxStatus { COMPLETED, FAILED, REFUNDED }

@Keep
@IgnoreExtraProperties
@Immutable
data class CreditAccountRecord(
    val userId: String = "",
    val email: String = "",
    val freeCredits: Long = 0L,
    val paidCredits: Long = 0L,
    val totalSpentCredits: Long = 0L,
    val totalRefundedCredits: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Keep
@IgnoreExtraProperties
@Immutable
data class CreditTransactionRecord(
    val docId: String = "",
    val userId: String = "",
    val creditType: CreditType = CreditType.FREE,
    val operation: CreditOperation = CreditOperation.GRANT,
    val amount: Long = 0L,
    val status: CreditTxStatus = CreditTxStatus.COMPLETED,
    val reason: String = "",
    val engineId: String = "",
    val performedByUid: String = "",
    val createdAtMs: Long = 0L,
)

enum class PaymentType { PURCHASE, SUBSCRIPTION }
enum class PaymentStatus { SUCCEEDED, FAILED, ACTIVE, CANCELLED, REFUNDED }

@Keep
@IgnoreExtraProperties
@Immutable
data class PaymentRecord(
    val docId: String = "",
    val userId: String = "",
    val paymentType: PaymentType = PaymentType.PURCHASE,
    val productId: String = "",
    val amountUsd: Double = 0.0,
    val status: PaymentStatus = PaymentStatus.SUCCEEDED,
    val provider: String = "",
    val createdAtMs: Long = 0L,
)

enum class FinanceEntryType { AD_REVENUE, HOSTING_COST, ENGINE_COST }

@Keep
@IgnoreExtraProperties
@Immutable
data class FinanceLedgerRecord(
    val docId: String = "",
    val entryType: FinanceEntryType = FinanceEntryType.HOSTING_COST,
    val source: String = "",
    val amountUsd: Double = 0.0,
    val periodLabel: String = "",
    val notes: String = "",
    val createdAtMs: Long = 0L,
)

@Immutable
data class AdminFinanceConfig(
    val partnerSharePercent: Double = 0.0,
    val partnerName: String = "",
    val currencyCode: String = "USD",
)

@Immutable
data class ComputedFinanceSummary(
    val succeededPurchasesCount: Int = 0,
    val succeededPurchasesUsd: Double = 0.0,
    val failedPurchasesCount: Int = 0,
    val failedPurchasesUsd: Double = 0.0,
    val activeSubscriptionsCount: Int = 0,
    val activeSubscriptionsUsd: Double = 0.0,
    val adRevenuesUsd: Double = 0.0,
    val totalRevenueUsd: Double = 0.0,
    val ledgerCostsUsd: Double = 0.0,
    val liveServerComputeCostUsd: Double = 0.0,
    val totalCostsUsd: Double = 0.0,
    val netProfitUsd: Double = 0.0,
    val partnerSharePercent: Double = 0.0,
    val partnerShareUsd: Double = 0.0,
    val ownerNetUsd: Double = 0.0,
)

enum class DevChangelogCategory { IMPLEMENTED, UNCOMPLETED, ISSUE, TEST_PASSED, TEST_FAILED }
enum class DevChangelogStatus { DONE, IN_PROGRESS, OPEN, BLOCKED }

@Keep
@IgnoreExtraProperties
@Immutable
data class DevChangelogRecord(
    val docId: String = "",
    val title: String = "",
    val category: DevChangelogCategory = DevChangelogCategory.IMPLEMENTED,
    val status: DevChangelogStatus = DevChangelogStatus.DONE,
    val details: String = "",
    val changeDate: String = "",
    val createdAtMs: Long = 0L,
)

enum class AdminTestType(val titleAr: String, val titleEn: String) {
    ARABIC_TTS("توليد الصوت العربي", "Arabic TTS Test"),
    DIALECTS("اختبار اللهجات العربية", "Arabic Dialects Test"),
    EMOTIONS("اختبار المشاعر التعبيرية", "Emotions Test"),
    MULTI_CHARACTER("اختبار الشخصيات المتعددة", "Multi-Character Dialogue"),
    EXPORT_WAV_MP3("اختبار تصدير WAV وMP3", "WAV & MP3 Export Test"),
    SERVER_CONNECTION("اختبار اتصال الخادم", "Server Connection Test"),
}

@Keep
@IgnoreExtraProperties
@Immutable
data class AdminTestRunRecord(
    val docId: String = "",
    val testType: AdminTestType = AdminTestType.SERVER_CONNECTION,
    val engineId: String = "voxcpm2",
    val passed: Boolean = false,
    val latencyMs: Long = 0L,
    val summary: String = "",
    val errorDetail: String = "",
    val createdAtMs: Long = 0L,
)

@Immutable
data class DeveloperPanelUiState(
    val activeTab: DeveloperPanelTab = DeveloperPanelTab.ENGINES,
    val ownerVerification: OwnerVerificationState = OwnerVerificationState(),
    val isRefreshing: Boolean = false,
    val activeTestRunning: AdminTestType? = null,
    val selectedTestEngineId: String = "voxcpm2",
    val actionFeedback: String? = null,
    val errorBanner: String? = null,
    val voxCpmEngine: EngineTelemetrySnapshot = EngineTelemetrySnapshot(
        engineId = "voxcpm2",
        displayName = "VoxCPM2 (المحرك الأساسي متعدد اللغات)",
        repoId = "FenomAI/VoxCPM2",
        licenseLabel = "Apache-2.0",
        clientIntegrated = true,
    ),
    val audarFlashEngine: EngineTelemetrySnapshot = EngineTelemetrySnapshot(
        engineId = "audar_tts_flash",
        displayName = "Audar-TTS-V1-Flash (المحرك العربي المتخصص)",
        repoId = "audarai/Audar-TTS-V1-Flash",
        licenseLabel = "AudarAI Open License v1.0",
        clientIntegrated = true,
    ),
    val serverTelemetry: ServerTelemetrySnapshot = ServerTelemetrySnapshot(),
    val creditAccounts: List<CreditAccountRecord> = emptyList(),
    val creditTransactions: List<CreditTransactionRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val financeEntries: List<FinanceLedgerRecord> = emptyList(),
    val financeConfig: AdminFinanceConfig = AdminFinanceConfig(),
    val financeSummary: ComputedFinanceSummary = ComputedFinanceSummary(),
    val changelogEntries: List<DevChangelogRecord> = emptyList(),
    val testRuns: List<AdminTestRunRecord> = emptyList(),
) {
    val totalFreeCredits: Long
        get() = creditAccounts.sumOf { it.freeCredits }

    val totalPaidCredits: Long
        get() = creditAccounts.sumOf { it.paidCredits }

    val failedOrRefundedTransactions: List<CreditTransactionRecord>
        get() = creditTransactions.filter {
            it.status == CreditTxStatus.FAILED || it.status == CreditTxStatus.REFUNDED
        }
}
