package com.example.devpanel

import android.app.Application
import com.example.BuildConfig
import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiDialogueTurn
import com.example.audio.aivoice.AiVoiceCategory
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioCoordinator
import com.example.audio.aivoice.AiVoiceSynthOutcome
import com.example.audio.aivoice.AiVoiceSynthRequest
import com.example.audio.exportRenderedAudioToFile
import com.example.model.ExportAudioFormat
import com.example.model.StereoAudioBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class DeveloperPanelController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val coordinator: AiVoiceStudioCoordinator,
    private val getBackendUrl: () -> String,
    private val getBackendBearerToken: () -> String = { "" },
) {
    private val repo = DeveloperPanelRepository(app)

    private val _uiState = MutableStateFlow(
        DeveloperPanelUiState(
            ownerVerification = if (BuildConfig.DEVELOPER_PANEL_ENABLED) {
                OwnerVerificationState(status = OwnerVerificationStatus.VERIFYING)
            } else {
                OwnerVerificationState(status = OwnerVerificationStatus.DISABLED_IN_BUILD)
            },
        ),
    )
    val uiState: StateFlow<DeveloperPanelUiState> = _uiState.asStateFlow()

    private var collectorsJob: Job? = null

    fun selectTab(tab: DeveloperPanelTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun selectTestEngine(engineId: String) {
        _uiState.update { it.copy(selectedTestEngineId = engineId) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(actionFeedback = null, errorBanner = null) }
    }

    fun onAuthSessionChanged() {
        stopCollectors()
        if (!BuildConfig.DEVELOPER_PANEL_ENABLED) {
            _uiState.update {
                it.copy(
                    ownerVerification = OwnerVerificationState(
                        status = OwnerVerificationStatus.DISABLED_IN_BUILD,
                    ),
                )
            }
            return
        }
        verifyAndRefreshAll()
    }

    fun verifyAndRefreshAll() {
        if (!BuildConfig.DEVELOPER_PANEL_ENABLED) return
        scope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    ownerVerification = it.ownerVerification.copy(status = OwnerVerificationStatus.VERIFYING),
                )
            }
            val verification = repo.verifyOwnerFromServer(getBackendUrl())
            _uiState.update { it.copy(ownerVerification = verification) }

            if (verification.isAuthorized) {
                startFirestoreCollectors()
                refreshBackendOverviewInternal()
            } else {
                stopCollectors()
                _uiState.update {
                    it.copy(
                        creditAccounts = emptyList(),
                        creditTransactions = emptyList(),
                        payments = emptyList(),
                        financeEntries = emptyList(),
                        changelogEntries = emptyList(),
                        testRuns = emptyList(),
                    )
                }
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun startFirestoreCollectors() {
        if (collectorsJob?.isActive == true) return
        collectorsJob = scope.launch {
            launch {
                repo.observeCreditAccounts().collect { list ->
                    _uiState.update { it.copy(creditAccounts = list) }
                }
            }
            launch {
                repo.observeCreditTransactions().collect { list ->
                    _uiState.update { it.copy(creditTransactions = list) }
                }
            }
            launch {
                repo.observePayments().collect { list ->
                    _uiState.update { cur ->
                        val next = cur.copy(payments = list)
                        next.copy(financeSummary = recomputeFinanceSummary(next))
                    }
                }
            }
            launch {
                repo.observeFinanceLedger().collect { list ->
                    _uiState.update { cur ->
                        val next = cur.copy(financeEntries = list)
                        next.copy(financeSummary = recomputeFinanceSummary(next))
                    }
                }
            }
            launch {
                repo.observeFinanceConfig().collect { cfg ->
                    _uiState.update { cur ->
                        val next = cur.copy(financeConfig = cfg)
                        next.copy(financeSummary = recomputeFinanceSummary(next))
                    }
                }
            }
            launch {
                repo.observeDevChangelog().collect { list ->
                    _uiState.update { it.copy(changelogEntries = list) }
                }
            }
            launch {
                repo.observeTestRuns().collect { list ->
                    _uiState.update { it.copy(testRuns = list) }
                }
            }
        }
    }

    private suspend fun refreshBackendOverviewInternal() {
        val url = getBackendUrl().trim()
        if (url.isEmpty()) {
            _uiState.update { cur ->
                val next = cur.copy(
                    serverTelemetry = ServerTelemetrySnapshot(
                        checked = true,
                        connected = false,
                        backendUrlConfigured = false,
                        errorDetail = "لم يتم إدخال رابط خادم GPU في إعدادات AI VOICE STUDIO بعد.",
                    ),
                    voxCpmEngine = cur.voxCpmEngine.copy(
                        backendConnected = false,
                        backendPackageReady = false,
                        backendLoadedInMemory = false,
                    ),
                    audarFlashEngine = cur.audarFlashEngine.copy(
                        backendConnected = false,
                        backendPackageReady = false,
                        backendLoadedInMemory = false,
                    ),
                )
                next.copy(financeSummary = recomputeFinanceSummary(next))
            }
            return
        }

        val res = repo.fetchBackendAdminTelemetry(url)
        res.onSuccess { overview ->
            _uiState.update { cur ->
                val next = cur.copy(
                    serverTelemetry = overview.server,
                    voxCpmEngine = overview.voxCpm,
                    audarFlashEngine = overview.audarFlash,
                    errorBanner = null,
                )
                next.copy(financeSummary = recomputeFinanceSummary(next))
            }
        }.onFailure { err ->
            _uiState.update { cur ->
                val next = cur.copy(
                    serverTelemetry = ServerTelemetrySnapshot(
                        checked = true,
                        connected = false,
                        backendUrlConfigured = true,
                        errorDetail = err.localizedMessage ?: err.javaClass.simpleName,
                    ),
                    voxCpmEngine = cur.voxCpmEngine.copy(backendConnected = false),
                    audarFlashEngine = cur.audarFlashEngine.copy(backendConnected = false),
                )
                next.copy(financeSummary = recomputeFinanceSummary(next))
            }
        }
    }

    private fun recomputeFinanceSummary(state: DeveloperPanelUiState): ComputedFinanceSummary =
        computeDeveloperFinanceSummary(state)

    // --- Owner Mutations ---

    fun adjustUserCredits(
        targetUserId: String,
        targetEmail: String,
        creditType: CreditType,
        isGrant: Boolean,
        amount: Long,
        reason: String,
    ) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.adjustUserCredits(targetUserId, targetEmail, creditType, isGrant, amount, reason)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تم تحديث الرصيد وتسجيل العملية بنجاح.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر تعديل الرصيد") }
                }
        }
    }

    fun refundTransaction(record: CreditTransactionRecord) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.refundFailedTransaction(record)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تم استرداد الرصيد للعملية الفاشلة بنجاح.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر الاسترداد") }
                }
        }
    }

    fun recordPayment(
        userId: String,
        paymentType: PaymentType,
        productId: String,
        amountUsd: Double,
        status: PaymentStatus,
        provider: String,
    ) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.recordPayment(userId, paymentType, productId, amountUsd, status, provider)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تم تسجيل العملية المالية بنجاح.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر تسجيل العملية") }
                }
        }
    }

    fun recordFinanceEntry(
        entryType: FinanceEntryType,
        source: String,
        amountUsd: Double,
        periodLabel: String,
        notes: String,
    ) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.recordFinanceEntry(entryType, source, amountUsd, periodLabel, notes)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تم حفظ البند المالي في قاعدة البيانات.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر حفظ البند المالي") }
                }
        }
    }

    fun deleteFinanceEntry(docId: String) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.deleteFinanceEntry(docId)
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر حذف السجل") }
                }
        }
    }

    fun savePartnerShareConfig(partnerSharePercent: Double, partnerName: String) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.saveFinanceConfig(partnerSharePercent, partnerName)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تم تحديث نسبة الشريك بنجاح.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر حفظ الإعدادات") }
                }
        }
    }

    fun addDevChangelog(
        title: String,
        category: DevChangelogCategory,
        status: DevChangelogStatus,
        details: String,
        changeDate: String,
    ) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.addDevChangelog(title, category, status, details, changeDate)
                .onSuccess {
                    _uiState.update { it.copy(actionFeedback = "تمت إضافة السجل إلى سجل التطوير.", errorBanner = null) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر إضافة السجل") }
                }
        }
    }

    fun deleteDevChangelog(docId: String) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        scope.launch {
            repo.deleteDevChangelog(docId)
                .onFailure { err ->
                    _uiState.update { it.copy(errorBanner = err.localizedMessage ?: "تعذر حذف السجل") }
                }
        }
    }

    // --- Section 6: Real Diagnostic & Engine Test Suite ---

    fun runDiagnosticTest(testType: AdminTestType) {
        if (!_uiState.value.ownerVerification.isAuthorized) return
        if (_uiState.value.activeTestRunning != null) return

        val engineId = _uiState.value.selectedTestEngineId
        val engineKind = when (engineId) {
            "audar_tts_flash" -> AiVoiceEngineKind.AUDAR_TTS_V1_FLASH
            "edge_neural_free" -> AiVoiceEngineKind.EDGE_NEURAL_FREE
            else -> AiVoiceEngineKind.VOXCPM_2
        }

        scope.launch {
            _uiState.update {
                it.copy(
                    activeTestRunning = testType,
                    actionFeedback = null,
                    errorBanner = null,
                )
            }
            val startMs = System.currentTimeMillis()
            val outDir = File(app.cacheDir, "dev_panel_tests").apply { mkdirs() }
            try {
                val (passed, summary, errDetail) = when (testType) {
                    AdminTestType.SERVER_CONNECTION -> executeServerConnectionTest()
                    AdminTestType.ARABIC_TTS -> executeSynthesisTest(
                        engineKind = engineKind,
                        dialect = AiArabicDialect.MSA,
                        emotion = AiVoiceEmotion.NEUTRAL,
                        text = "مرحباً بكم في اختبار النطق العربي الفصيح من لوحة المطور.",
                        outDir = outDir,
                        label = "توليد العربية الفصحى",
                    )
                    AdminTestType.DIALECTS -> executeSynthesisTest(
                        engineKind = engineKind,
                        dialect = AiArabicDialect.IRAQI,
                        emotion = AiVoiceEmotion.NEUTRAL,
                        text = "هلا بيكم، هذا اختبار فعلي للهجة العراقية والعربية.",
                        outDir = outDir,
                        label = "اختبار اللهجة العراقية",
                    )
                    AdminTestType.EMOTIONS -> executeSynthesisTest(
                        engineKind = engineKind,
                        dialect = AiArabicDialect.MSA,
                        emotion = if (engineKind == AiVoiceEngineKind.AUDAR_TTS_V1_FLASH) AiVoiceEmotion.WHISPER else AiVoiceEmotion.SAD,
                        text = "هذه اللحظة مؤثرة جداً، أرجو أن يصل الإحساس بوضوح.",
                        outDir = outDir,
                        label = "اختبار التعبير الصوتي",
                    )
                    AdminTestType.MULTI_CHARACTER -> executeMultiCharacterTest(engineKind, outDir)
                    AdminTestType.EXPORT_WAV_MP3 -> executeExportWavMp3Test()
                }
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                repo.recordTestRun(
                    testType = testType,
                    engineId = if (testType == AdminTestType.EXPORT_WAV_MP3) "local_exporter" else engineKind.id,
                    passed = passed,
                    latencyMs = elapsedMs,
                    summary = summary,
                    errorDetail = errDetail,
                )
                refreshBackendOverviewInternal()
                _uiState.update {
                    it.copy(
                        actionFeedback = if (passed) summary else null,
                        errorBanner = if (!passed) "$summary${if (errDetail.isNotBlank()) " ($errDetail)" else ""}" else null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val msg = t.localizedMessage ?: t.javaClass.simpleName
                repo.recordTestRun(
                    testType = testType,
                    engineId = engineKind.id,
                    passed = false,
                    latencyMs = elapsedMs,
                    summary = "فشل الاختبار: ${testType.titleAr}",
                    errorDetail = msg,
                )
                _uiState.update { it.copy(errorBanner = msg) }
            } finally {
                runCatching { outDir.deleteRecursively() }
                _uiState.update { it.copy(activeTestRunning = null) }
            }
        }
    }

    private suspend fun executeServerConnectionTest(): Triple<Boolean, String, String> {
        val url = getBackendUrl().trim()
        if (url.isEmpty()) {
            return Triple(
                false,
                "رابط خادم GPU غير مضبوط بعد",
                "أدخل رابط HTTPS في AI VOICE STUDIO -> إعدادات السيرفر",
            )
        }
        val ver = repo.verifyOwnerFromServer(url)
        val overviewRes = repo.fetchBackendAdminTelemetry(url)
        return overviewRes.fold(
            onSuccess = { ov ->
                Triple(
                    ov.server.connected && ver.backendVerified,
                    "اتصال ناجح (${ov.server.pingMs} ms • ${ov.server.gpuName})",
                    "",
                )
            },
            onFailure = { err ->
                Triple(
                    false,
                    "فشل الاتصال بخادم GPU",
                    err.localizedMessage ?: err.javaClass.simpleName,
                )
            },
        )
    }

    private suspend fun executeSynthesisTest(
        engineKind: AiVoiceEngineKind,
        dialect: AiArabicDialect,
        emotion: AiVoiceEmotion,
        text: String,
        outDir: File,
        label: String,
    ): Triple<Boolean, String, String> {
        val backendUrl = getBackendUrl().trim()
        val req = AiVoiceSynthRequest(
            engine = engineKind,
            text = text,
            language = AiVoiceLanguage.AR,
            dialect = dialect,
            category = AiVoiceCategory.MALE,
            voiceDescriptionPrompt = "رجل عربي بصوت واضح متزن وإلقاء استوديو احترافي.",
            emotion = emotion,
            emotionIntensity = 0.72f,
            pace = AiDeliveryPace.NORMAL,
        )
        return when (val out = coordinator.synthesizeSingle(app, req, backendUrl, outDir, getBackendBearerToken())) {
            is AiVoiceSynthOutcome.Success -> {
                val f = File(out.audioFilePath)
                val valid = f.exists() && f.length() > 44L && out.durationMs > 0L
                runCatching { f.delete() }
                if (valid) {
                    Triple(
                        true,
                        "نجح $label (${out.sampleRate}Hz • ${out.durationMs}ms)",
                        "",
                    )
                } else {
                    Triple(false, "فشل $label: ملف الصوت الناتج فارغ", "0 bytes")
                }
            }
            is AiVoiceSynthOutcome.ConfigurationRequired -> Triple(
                false,
                "$label: يتطلب ربط خادم GPU",
                out.titleAr,
            )
            is AiVoiceSynthOutcome.Error -> Triple(
                false,
                "$label: تعذر التوليد",
                out.technicalDetail ?: out.messageAr,
            )
        }
    }

    private suspend fun executeMultiCharacterTest(
        engineKind: AiVoiceEngineKind,
        outDir: File,
    ): Triple<Boolean, String, String> {
        val backendUrl = getBackendUrl().trim()
        val c1 = AiVoiceCharacter(
            id = "dev_test_char_1",
            nameAr = "مذيع",
            nameEn = "Host",
            category = AiVoiceCategory.MALE,
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.MSA,
            preferredEngine = engineKind,
            voiceDescriptionPrompt = "رجل بصوت رصين واضح",
        )
        val c2 = AiVoiceCharacter(
            id = "dev_test_char_2",
            nameAr = "ضيفة",
            nameEn = "Guest",
            category = AiVoiceCategory.FEMALE,
            language = AiVoiceLanguage.AR,
            dialect = AiArabicDialect.MSA,
            preferredEngine = engineKind,
            voiceDescriptionPrompt = "امرأة بصوت دافئ واضح",
        )
        val turns = listOf(
            AiDialogueTurn(id = "t1", characterId = c1.id, text = "أهلاً بكِ في الاختبار.", pauseAfterMs = 250),
            AiDialogueTurn(id = "t2", characterId = c2.id, text = "شكراً لك، الصوت يعمل بنجاح.", pauseAfterMs = 200),
        )
        return when (
            val out = coordinator.synthesizeMultiCharacterDialogue(
                context = app,
                turns = turns,
                characters = listOf(c1, c2),
                selectedEngineOverride = engineKind,
                backendUrl = backendUrl,
                outputDir = outDir,
                bearerToken = getBackendBearerToken(),
            )
        ) {
            is AiVoiceSynthOutcome.Success -> {
                val f = File(out.audioFilePath)
                val ok = f.exists() && f.length() > 44L && out.durationMs > 0L
                runCatching { f.delete() }
                if (ok) {
                    Triple(true, "نجح حوار الشخصيتين (${out.durationMs}ms • ${out.sampleRate}Hz)", "")
                } else {
                    Triple(false, "فشل دمج الشخصيتين", "Invalid merged output")
                }
            }
            is AiVoiceSynthOutcome.ConfigurationRequired -> Triple(
                false,
                "حوار الشخصيات يتطلب ربط خادم GPU",
                out.titleAr,
            )
            is AiVoiceSynthOutcome.Error -> Triple(
                false,
                "فشل اختبار الشخصيات المتعددة",
                out.technicalDetail ?: out.messageAr,
            )
        }
    }

    private suspend fun executeExportWavMp3Test(): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        val sr = 44100
        val frames = sr // 1.0 second tone
        val left = FloatArray(frames) { i -> (0.35 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }
        val buf = StereoAudioBuffer(left, left.copyOf(), sr)

        val wavRes = exportRenderedAudioToFile(
            context = app,
            buffer = buf,
            format = ExportAudioFormat.WAV,
            baseFileName = "devtest_wav",
        )
        val mp3Res = exportRenderedAudioToFile(
            context = app,
            buffer = buf,
            format = ExportAudioFormat.MP3,
            baseFileName = "devtest_mp3",
            bitrateKbps = 192,
        )

        val wavOk = wavRes.file.exists() && wavRes.file.length() > 44L && wavRes.extension == "wav"
        val mp3NativeOk = mp3Res.file.exists() && mp3Res.file.length() > 512L && mp3Res.extension == "mp3"
        val wavSize = wavRes.file.length()
        val mp3Size = mp3Res.file.length()
        val mp3Ext = mp3Res.extension

        runCatching { wavRes.file.delete() }
        runCatching { mp3Res.file.delete() }

        when {
            wavOk && mp3NativeOk -> Triple(
                true,
                "نجح تصدير WAV (${wavSize}B) وMP3 (${mp3Size}B) فعلياً",
                "",
            )
            wavOk -> Triple(
                false,
                "نجح WAV (${wavSize}B)، لكن تصدير MP3 تحوّل إلى $mp3Ext (${mp3Size}B)",
                "libmp3lame unavailable; fell back to $mp3Ext",
            )
            else -> Triple(
                false,
                "فشل تصدير WAV أو MP3 على الجهاز",
                "wavOk=$wavOk mp3Ext=$mp3Ext",
            )
        }
    }

    fun stopCollectors() {
        collectorsJob?.cancel()
        collectorsJob = null
    }
}

internal fun computeDeveloperFinanceSummary(state: DeveloperPanelUiState): ComputedFinanceSummary {
    val succeededPurchases = state.payments.filter {
        it.paymentType == PaymentType.PURCHASE && it.status == PaymentStatus.SUCCEEDED
    }
    val failedPurchases = state.payments.filter {
        it.paymentType == PaymentType.PURCHASE && it.status == PaymentStatus.FAILED
    }
    val activeSubs = state.payments.filter {
        it.paymentType == PaymentType.SUBSCRIPTION &&
            (it.status == PaymentStatus.ACTIVE || it.status == PaymentStatus.SUCCEEDED)
    }
    val adRevs = state.financeEntries.filter { it.entryType == FinanceEntryType.AD_REVENUE }
    val ledgerCosts = state.financeEntries.filter {
        it.entryType == FinanceEntryType.HOSTING_COST || it.entryType == FinanceEntryType.ENGINE_COST
    }

    val purchaseRev = succeededPurchases.sumOf { it.amountUsd }
    val failedAmt = failedPurchases.sumOf { it.amountUsd }
    val subRev = activeSubs.sumOf { it.amountUsd }
    val adRev = adRevs.sumOf { it.amountUsd }
    val totalRev = purchaseRev + subRev + adRev

    val recordedCosts = ledgerCosts.sumOf { it.amountUsd }
    val serverComputeCost = state.serverTelemetry.estimatedComputeCostUsd
    val totalCosts = recordedCosts + serverComputeCost

    val netProfit = totalRev - totalCosts
    val sharePct = state.financeConfig.partnerSharePercent.coerceIn(0.0, 100.0)
    val partnerShare = if (netProfit > 0.0) netProfit * (sharePct / 100.0) else 0.0
    val ownerNet = netProfit - partnerShare

    return ComputedFinanceSummary(
        succeededPurchasesCount = succeededPurchases.size,
        succeededPurchasesUsd = purchaseRev,
        failedPurchasesCount = failedPurchases.size,
        failedPurchasesUsd = failedAmt,
        activeSubscriptionsCount = activeSubs.size,
        activeSubscriptionsUsd = subRev,
        adRevenuesUsd = adRev,
        totalRevenueUsd = totalRev,
        ledgerCostsUsd = recordedCosts,
        liveServerComputeCostUsd = serverComputeCost,
        totalCostsUsd = totalCosts,
        netProfitUsd = netProfit,
        partnerSharePercent = sharePct,
        partnerShareUsd = partnerShare,
        ownerNetUsd = ownerNet,
    )
}
