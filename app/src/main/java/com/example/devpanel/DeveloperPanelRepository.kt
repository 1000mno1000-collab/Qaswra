package com.example.devpanel

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.audio.network.NetworkImmunityManager
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeveloperPanelRepository(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "DeveloperPanelRepo"
        private const val OWNER_BOOTSTRAP_EMAIL = "1000iraq1000@gmail.com"
    }

    private fun dbOrNull(): FirebaseFirestore? {
        if (FirebaseApp.getApps(appContext).isEmpty()) return null
        return runCatching {
            FirebaseFirestore.getInstance(appContext.getString(R.string.firestore_database_id))
        }.getOrNull()
    }

    private fun requireOwnerUid(): String {
        val user = runCatching { Firebase.auth.currentUser }.getOrNull()
        val uid = user?.uid.orEmpty()
        if (uid.isBlank() || user?.isAnonymous == true) {
            throw IllegalStateException("Owner Google authentication is required.")
        }
        return uid
    }

    private fun logFirestoreError(op: String, path: String, err: Throwable) {
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull() ?: "none"
        Log.e(TAG, "Firestore error op=$op path=$path uid=$uid msg=${err.message}", err)
    }

    /**
     * Performs true server-side owner verification against Firebase Auth + Firestore Security Rules
     * AND against the FastAPI Backend (/v1/admin/verify-owner) if a backend URL is configured.
     * Never relies solely on UI button hiding.
     */
    suspend fun verifyOwnerFromServer(backendUrl: String): OwnerVerificationState = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEVELOPER_PANEL_ENABLED) {
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.DISABLED_IN_BUILD,
                statusMessageAr = "تم تعطيل لوحة المطوّر في إعدادات البناء (DEVELOPER_PANEL_ENABLED=false).",
                statusMessageEn = "Developer panel disabled in build config.",
            )
        }

        val auth = runCatching { Firebase.auth }.getOrNull()
        val user = auth?.currentUser
        if (user == null || user.isAnonymous || user.uid.isBlank()) {
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.NOT_AUTHENTICATED,
                statusMessageAr = "يلزم تسجيل الدخول بحساب المالك الموثق للتحقق من الصلاحية.",
                statusMessageEn = "Sign in with verified owner account to verify access.",
            )
        }

        // Force token refresh from Firebase Auth servers so email_verified & revocation are checked
        val idToken = runCatching {
            user.reload().await()
            user.getIdToken(true).await()?.token.orEmpty()
        }.getOrDefault("")

        if (idToken.isBlank()) {
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.DENIED,
                verifiedUid = user.uid,
                verifiedEmail = user.email.orEmpty(),
                statusMessageAr = "تعذر التحقق من جلسة المصادقة عبر خادم Firebase.",
                statusMessageEn = "Failed refreshing Firebase ID token from server.",
            )
        }

        val db = dbOrNull()
        if (db == null) {
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.DENIED,
                verifiedUid = user.uid,
                verifiedEmail = user.email.orEmpty(),
                statusMessageAr = "خدمة Firestore غير مهيأة.",
                statusMessageEn = "Firestore is not initialized.",
            )
        }

        // Server-side Firestore Rules check: only authorized owner passes `allow get` on /admin_settings/finance
        val firestoreAuthorized = try {
            db.collection("admin_settings").document("finance").get(Source.SERVER).await()
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                false
            } else {
                // Network hiccup: check trusted /admins/{uid} server doc or verified token state conservatively
                val adminDocOk = runCatching {
                    db.collection("admins").document(user.uid).get(Source.SERVER).await().exists()
                }.getOrDefault(false)
                adminDocOk || (user.isEmailVerified && user.email.equals(OWNER_BOOTSTRAP_EMAIL, ignoreCase = true))
            }
        } catch (_: Throwable) {
            false
        }

        if (!firestoreAuthorized) {
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.DENIED,
                verifiedUid = user.uid,
                verifiedEmail = user.email.orEmpty(),
                firestoreVerified = false,
                backendVerified = false,
                statusMessageAr = "تم رفض الوصول من خادم الصلاحيات: هذا الحساب ليس حساب المالك المعتمد.",
                statusMessageEn = "Server rejected access: account is not the authorized owner.",
            )
        }

        val cleanUrl = backendUrl.trim().trimEnd('/')
        if (cleanUrl.isNotEmpty()) {
            val (code, body) = runCatching {
                authorizedGetJson("$cleanUrl/v1/admin/verify-owner", idToken)
            }.getOrDefault(0 to "")
            if (code == 401 || code == 403) {
                return@withContext OwnerVerificationState(
                    status = OwnerVerificationStatus.DENIED,
                    verifiedUid = user.uid,
                    verifiedEmail = user.email.orEmpty(),
                    firestoreVerified = true,
                    backendVerified = false,
                    statusMessageAr = "رفض خادم GPU صلاحية المالك (HTTP $code).",
                    statusMessageEn = "Backend server denied owner authorization (HTTP $code).",
                )
            }
            val backendOk = code in 200..299 &&
                (runCatching { JSONObject(body).optBoolean("authorized", false) }.getOrDefault(false))
            return@withContext OwnerVerificationState(
                status = OwnerVerificationStatus.AUTHORIZED,
                verifiedUid = user.uid,
                verifiedEmail = user.email.orEmpty(),
                firestoreVerified = true,
                backendVerified = backendOk,
                statusMessageAr = if (backendOk) {
                    "تم التحقق من المالك بنجاح على Firestore وخادم GPU."
                } else {
                    "تم التحقق من المالك على Firestore (خادم GPU غير متصل حالياً: HTTP $code)."
                },
                statusMessageEn = if (backendOk) {
                    "Owner verified by Firestore & GPU backend."
                } else {
                    "Owner verified by Firestore (GPU server offline: HTTP $code)."
                },
            )
        }

        OwnerVerificationState(
            status = OwnerVerificationStatus.AUTHORIZED,
            verifiedUid = user.uid,
            verifiedEmail = user.email.orEmpty(),
            firestoreVerified = true,
            backendVerified = false,
            statusMessageAr = "تم التحقق من المالك عبر قواعد خادم Firestore (لم يُربط رابط خادم GPU بعد).",
            statusMessageEn = "Owner verified via Firestore server rules (GPU backend URL not set).",
        )
    }

    suspend fun fetchBackendAdminTelemetry(backendUrl: String): Result<AdminOverviewResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = backendUrl.trim().trimEnd('/')
        if (cleanUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("لم يتم ضبط رابط خادم GPU بعد"))
        }
        val user = runCatching { Firebase.auth.currentUser }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("يلزم تسجيل الدخول بحساب المالك"))
        val token = runCatching { user.getIdToken(true).await()?.token.orEmpty() }.getOrDefault("")
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("تعذر الحصول على Firebase ID Token"))
        }

        val startNs = System.nanoTime()
        try {
            val (code, body) = authorizedGetJson("$cleanUrl/v1/admin/overview", token)
            val pingMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP $code: ${body.take(240)}"))
            }
            val json = JSONObject(body)
            val sys = json.optJSONObject("system") ?: JSONObject()
            val engines = json.optJSONObject("engines") ?: JSONObject()
            val vox = engines.optJSONObject("voxcpm2") ?: JSONObject()
            val flash = engines.optJSONObject("audar_tts_flash") ?: JSONObject()

            val serverSnapshot = ServerTelemetrySnapshot(
                checked = true,
                connected = true,
                backendUrlConfigured = true,
                httpCode = code,
                pingMs = pingMs,
                uptimeSeconds = json.optLong("uptime_seconds", 0L),
                cudaAvailable = sys.optBoolean("cuda_available", false),
                gpuName = sys.optString("gpu_name", "—"),
                vramTotalMb = sys.optDouble("vram_total_mb", 0.0),
                vramUsedMb = sys.optDouble("vram_used_mb", 0.0),
                ramTotalMb = sys.optDouble("ram_total_mb", 0.0),
                ramUsedMb = sys.optDouble("ram_used_mb", 0.0),
                cpuPercent = sys.optDouble("cpu_percent", 0.0),
                totalGenerations = json.optInt("total_generations", 0),
                failedGenerations = json.optInt("failed_generations", 0),
                totalComputeSeconds = json.optDouble("total_compute_seconds", 0.0),
                gpuHourlyRateUsd = json.optDouble("gpu_hourly_rate_usd", 0.0),
                estimatedComputeCostUsd = json.optDouble("estimated_compute_cost_usd", 0.0),
                hfTokenConfigured = json.optBoolean("hf_token_configured", false),
            )

            val voxSnap = EngineTelemetrySnapshot(
                engineId = "voxcpm2",
                displayName = "VoxCPM2 (المحرك الأساسي متعدد اللغات)",
                repoId = vox.optString("repo_id", "openbmb/VoxCPM2"),
                licenseLabel = vox.optString("license", "Apache-2.0"),
                clientIntegrated = true,
                backendConnected = true,
                backendPackageReady = vox.optBoolean("package_installed", false),
                backendLoadedInMemory = vox.optBoolean("loaded_in_memory", false),
                generationsCount = vox.optInt("generations", 0),
                failuresCount = vox.optInt("failures", 0),
                lastLatencyMs = if (vox.isNull("last_latency_ms")) null else vox.optLong("last_latency_ms"),
                lastSampleRate = if (vox.isNull("last_sample_rate")) null else vox.optInt("last_sample_rate"),
                lastError = vox.optString("last_error").takeIf { it.isNotBlank() && it != "null" },
            )

            val flashSnap = EngineTelemetrySnapshot(
                engineId = "audar_tts_flash",
                displayName = "Audar-TTS-V1-Flash (المحرك العربي المتخصص)",
                repoId = flash.optString("repo_id", "audarai/Audar-TTS-V1-Flash"),
                licenseLabel = flash.optString("license", "AudarAI Open License v1.0"),
                clientIntegrated = true,
                backendConnected = true,
                backendPackageReady = flash.optBoolean("package_installed", false),
                backendLoadedInMemory = flash.optBoolean("loaded_in_memory", false),
                generationsCount = flash.optInt("generations", 0),
                failuresCount = flash.optInt("failures", 0),
                lastLatencyMs = if (flash.isNull("last_latency_ms")) null else flash.optLong("last_latency_ms"),
                lastSampleRate = if (flash.isNull("last_sample_rate")) null else flash.optInt("last_sample_rate"),
                lastError = flash.optString("last_error").takeIf { it.isNotBlank() && it != "null" },
            )

            Result.success(AdminOverviewResponse(serverSnapshot, voxSnap, flashSnap))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun authorizedGetJson(url: String, bearerToken: String): Pair<Int, String> {
        val safeUrl = NetworkImmunityManager.enforceHttps(url)
        val conn = (URL(safeUrl).openConnection() as HttpURLConnection).apply {
            NetworkImmunityManager.enforceTls12Plus(this)
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("User-Agent", "Sonix-DeveloperPanel/1.0")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    // --- Real-time Owner Firestore Streams (Scoped, Two-Tier Flow Safe) ---

    fun observeCreditAccounts(): Flow<List<CreditAccountRecord>> = safeCollectionFlow("credit_accounts") { doc ->
        CreditAccountRecord(
            userId = doc.getString("userId").orEmpty().ifBlank { doc.id },
            email = doc.getString("email").orEmpty(),
            freeCredits = (doc.get("freeCredits") as? Number)?.toLong() ?: 0L,
            paidCredits = (doc.get("paidCredits") as? Number)?.toLong() ?: 0L,
            totalSpentCredits = (doc.get("totalSpentCredits") as? Number)?.toLong() ?: 0L,
            totalRefundedCredits = (doc.get("totalRefundedCredits") as? Number)?.toLong() ?: 0L,
            updatedAtMs = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    fun observeCreditTransactions(): Flow<List<CreditTransactionRecord>> = safeCollectionFlow("credit_transactions", limitCount = 80L) { doc ->
        CreditTransactionRecord(
            docId = doc.id,
            userId = doc.getString("userId").orEmpty(),
            creditType = safeEnum(doc.getString("creditType"), CreditType.FREE),
            operation = safeEnum(doc.getString("operation"), CreditOperation.GRANT),
            amount = (doc.get("amount") as? Number)?.toLong() ?: 0L,
            status = safeEnum(doc.getString("status"), CreditTxStatus.COMPLETED),
            reason = doc.getString("reason").orEmpty(),
            engineId = doc.getString("engineId").orEmpty(),
            performedByUid = doc.getString("performedByUid").orEmpty(),
            createdAtMs = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    fun observePayments(): Flow<List<PaymentRecord>> = safeCollectionFlow("payments", limitCount = 100L) { doc ->
        PaymentRecord(
            docId = doc.id,
            userId = doc.getString("userId").orEmpty(),
            paymentType = safeEnum(doc.getString("paymentType"), PaymentType.PURCHASE),
            productId = doc.getString("productId").orEmpty(),
            amountUsd = (doc.get("amountUsd") as? Number)?.toDouble() ?: 0.0,
            status = safeEnum(doc.getString("status"), PaymentStatus.SUCCEEDED),
            provider = doc.getString("provider").orEmpty(),
            createdAtMs = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    fun observeFinanceLedger(): Flow<List<FinanceLedgerRecord>> = safeCollectionFlow("finance_ledger", limitCount = 100L) { doc ->
        FinanceLedgerRecord(
            docId = doc.id,
            entryType = safeEnum(doc.getString("entryType"), FinanceEntryType.HOSTING_COST),
            source = doc.getString("source").orEmpty(),
            amountUsd = (doc.get("amountUsd") as? Number)?.toDouble() ?: 0.0,
            periodLabel = doc.getString("periodLabel").orEmpty(),
            notes = doc.getString("notes").orEmpty(),
            createdAtMs = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    fun observeFinanceConfig(): Flow<AdminFinanceConfig> = flow {
        val db = dbOrNull() ?: return@flow
        val uid = runCatching { requireOwnerUid() }.getOrNull() ?: return@flow
        if (uid.isBlank()) return@flow
        val docFlow = db.collection("admin_settings").document("finance")
            .snapshots()
            .map { snap ->
                if (!snap.exists()) {
                    AdminFinanceConfig()
                } else {
                    AdminFinanceConfig(
                        partnerSharePercent = ((snap.get("partnerSharePercent") as? Number)?.toDouble() ?: 0.0)
                            .coerceIn(0.0, 100.0),
                        partnerName = snap.getString("partnerName").orEmpty(),
                        currencyCode = snap.getString("currencyCode").orEmpty().ifBlank { "USD" },
                    )
                }
            }
            .catch { e ->
                val fex = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (fex == null && e is CancellationException) throw e
                logFirestoreError("GET", "admin_settings/finance", fex ?: e)
                emit(AdminFinanceConfig())
            }
        docFlow.collect { emit(it) }
    }

    fun observeDevChangelog(): Flow<List<DevChangelogRecord>> = safeCollectionFlow("dev_changelog", limitCount = 100L) { doc ->
        DevChangelogRecord(
            docId = doc.id,
            title = doc.getString("title").orEmpty(),
            category = safeEnum(doc.getString("category"), DevChangelogCategory.IMPLEMENTED),
            status = safeEnum(doc.getString("status"), DevChangelogStatus.DONE),
            details = doc.getString("details").orEmpty(),
            changeDate = doc.getString("changeDate").orEmpty(),
            createdAtMs = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    fun observeTestRuns(): Flow<List<AdminTestRunRecord>> = safeCollectionFlow("admin_test_runs", limitCount = 80L) { doc ->
        AdminTestRunRecord(
            docId = doc.id,
            testType = safeEnum(doc.getString("testType"), AdminTestType.SERVER_CONNECTION),
            engineId = doc.getString("engineId").orEmpty(),
            passed = doc.getBoolean("passed") == true,
            latencyMs = (doc.get("latencyMs") as? Number)?.toLong() ?: 0L,
            summary = doc.getString("summary").orEmpty(),
            errorDetail = doc.getString("errorDetail").orEmpty(),
            createdAtMs = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    private fun <T> safeCollectionFlow(
        collectionName: String,
        limitCount: Long = 60L,
        mapper: (DocumentSnapshot) -> T?,
    ): Flow<List<T>> = flow {
        val db = dbOrNull() ?: return@flow
        val ownerUid = runCatching { requireOwnerUid() }.getOrNull() ?: return@flow
        if (ownerUid.isBlank()) return@flow
        db.collection(collectionName)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limitCount)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    runCatching { mapper(doc) }.getOrNull()
                }
            }
            .catch { e ->
                val fex = generateSequence(e) { it.cause }.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
                if (fex == null && e is CancellationException) throw e
                logFirestoreError("LIST", collectionName, fex ?: e)
                emit(emptyList())
            }
            .collect { emit(it) }
    }

    // --- Owner-Verified Writes (Matched 1:1 with firestore.rules) ---

    suspend fun adjustUserCredits(
        targetUserId: String,
        targetEmail: String,
        creditType: CreditType,
        isGrant: Boolean,
        amount: Long,
        reason: String,
    ): Result<Unit> = runOwnerWrite("credit_accounts/$targetUserId") { db, ownerUid ->
        val cleanUid = targetUserId.trim().take(128)
        require(cleanUid.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
            "معرّف المستخدم يجب أن يحتوي على حروف إنجليزية وأرقام أو _ - فقط."
        }
        require(amount in 1L..1_000_000L) { "الكمية يجب أن تكون بين 1 و 1,000,000" }
        val cleanReason = reason.trim().take(200).ifEmpty { if (isGrant) "إضافة رصيد إدارية" else "خصم رصيد إداري" }
        val cleanEmail = targetEmail.trim().take(120)

        val accountRef = db.collection("credit_accounts").document(cleanUid)
        val txRef = db.collection("credit_transactions").document()
        db.runTransaction { tx ->
            val snap = tx.get(accountRef)
            val curFree = (snap.get("freeCredits") as? Number)?.toLong() ?: 0L
            val curPaid = (snap.get("paidCredits") as? Number)?.toLong() ?: 0L
            val curSpent = (snap.get("totalSpentCredits") as? Number)?.toLong() ?: 0L
            val curRefunded = (snap.get("totalRefundedCredits") as? Number)?.toLong() ?: 0L

            val delta = if (isGrant) amount else -amount
            val nextFree = if (creditType == CreditType.FREE) curFree + delta else curFree
            val nextPaid = if (creditType == CreditType.PAID) curPaid + delta else curPaid
            if (nextFree < 0L || nextPaid < 0L) {
                throw IllegalArgumentException("الرصيد المتاح لا يكفي لإتمام عملية الخصم")
            }

            if (!snap.exists()) {
                tx.set(
                    accountRef,
                    mapOf(
                        "userId" to cleanUid,
                        "email" to cleanEmail,
                        "freeCredits" to nextFree,
                        "paidCredits" to nextPaid,
                        "totalSpentCredits" to curSpent,
                        "totalRefundedCredits" to curRefunded,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            } else {
                tx.update(
                    accountRef,
                    mapOf(
                        "email" to (cleanEmail.ifBlank { snap.getString("email").orEmpty() }),
                        "freeCredits" to nextFree,
                        "paidCredits" to nextPaid,
                        "totalSpentCredits" to if (isGrant) curSpent else curSpent + amount,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }

            tx.set(
                txRef,
                mapOf(
                    "userId" to cleanUid,
                    "creditType" to creditType.name,
                    "operation" to if (isGrant) CreditOperation.GRANT.name else CreditOperation.DEDUCT.name,
                    "amount" to amount,
                    "status" to CreditTxStatus.COMPLETED.name,
                    "reason" to cleanReason,
                    "engineId" to "admin_panel",
                    "performedByUid" to ownerUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }.await()
    }

    suspend fun refundFailedTransaction(record: CreditTransactionRecord): Result<Unit> =
        runOwnerWrite("credit_transactions/${record.docId}") { db, ownerUid ->
            require(record.docId.isNotBlank() && record.status == CreditTxStatus.FAILED) {
                "يمكن استرداد العمليات الفاشلة فقط"
            }
            val txRef = db.collection("credit_transactions").document(record.docId)
            val accountRef = db.collection("credit_accounts").document(record.userId)
            db.runTransaction { tx ->
                val accSnap = tx.get(accountRef)
                val curFree = (accSnap.get("freeCredits") as? Number)?.toLong() ?: 0L
                val curPaid = (accSnap.get("paidCredits") as? Number)?.toLong() ?: 0L
                val curSpent = (accSnap.get("totalSpentCredits") as? Number)?.toLong() ?: 0L
                val curRefunded = (accSnap.get("totalRefundedCredits") as? Number)?.toLong() ?: 0L

                val nextFree = if (record.creditType == CreditType.FREE) curFree + record.amount else curFree
                val nextPaid = if (record.creditType == CreditType.PAID) curPaid + record.amount else curPaid

                if (!accSnap.exists()) {
                    tx.set(
                        accountRef,
                        mapOf(
                            "userId" to record.userId,
                            "email" to "",
                            "freeCredits" to nextFree,
                            "paidCredits" to nextPaid,
                            "totalSpentCredits" to curSpent,
                            "totalRefundedCredits" to (curRefunded + record.amount),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                } else {
                    tx.update(
                        accountRef,
                        mapOf(
                            "freeCredits" to nextFree,
                            "paidCredits" to nextPaid,
                            "totalRefundedCredits" to (curRefunded + record.amount),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                }

                tx.update(
                    txRef,
                    mapOf(
                        "status" to CreditTxStatus.REFUNDED.name,
                        "reason" to "${record.reason} [استرداد إداري]".take(200),
                        "performedByUid" to ownerUid,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }.await()
        }

    suspend fun recordPayment(
        userId: String,
        paymentType: PaymentType,
        productId: String,
        amountUsd: Double,
        status: PaymentStatus,
        provider: String,
    ): Result<Unit> = runOwnerWrite("payments") { db, ownerUid ->
        val cleanUid = userId.trim().take(128).ifEmpty { ownerUid }
        val cleanProd = productId.trim().take(80)
        require(cleanProd.isNotEmpty()) { "معرّف المنتج مطلوب" }
        require(amountUsd in 0.0..100_000.0) { "المبلغ غير صالح" }
        val cleanProvider = provider.trim().take(40).ifEmpty { "google_play" }

        db.collection("payments").document().set(
            mapOf(
                "userId" to cleanUid,
                "paymentType" to paymentType.name,
                "productId" to cleanProd,
                "amountUsd" to amountUsd,
                "status" to status.name,
                "provider" to cleanProvider,
                "recordedByUid" to ownerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun recordFinanceEntry(
        entryType: FinanceEntryType,
        source: String,
        amountUsd: Double,
        periodLabel: String,
        notes: String,
    ): Result<Unit> = runOwnerWrite("finance_ledger") { db, ownerUid ->
        val cleanSrc = source.trim().take(80)
        require(cleanSrc.isNotEmpty()) { "المصدر مطلوب" }
        require(amountUsd in 0.0..1_000_000.0) { "المبلغ غير صالح" }
        val cleanPeriod = periodLabel.trim().take(40).ifEmpty { "current" }
        db.collection("finance_ledger").document().set(
            mapOf(
                "entryType" to entryType.name,
                "source" to cleanSrc,
                "amountUsd" to amountUsd,
                "periodLabel" to cleanPeriod,
                "notes" to notes.trim().take(240),
                "createdByUid" to ownerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun deleteFinanceEntry(docId: String): Result<Unit> = runOwnerWrite("finance_ledger/$docId") { db, _ ->
        db.collection("finance_ledger").document(docId).delete().await()
    }

    suspend fun saveFinanceConfig(
        partnerSharePercent: Double,
        partnerName: String,
    ): Result<Unit> = runOwnerWrite("admin_settings/finance") { db, ownerUid ->
        val safePct = partnerSharePercent.coerceIn(0.0, 100.0)
        val cleanName = partnerName.trim().take(80)
        val docRef = db.collection("admin_settings").document("finance")
        val exists = runCatching { docRef.get(Source.SERVER).await().exists() }.getOrDefault(false)
        if (exists) {
            docRef.update(
                mapOf(
                    "partnerSharePercent" to safePct,
                    "partnerName" to cleanName,
                    "currencyCode" to "USD",
                    "updatedByUid" to ownerUid,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } else {
            docRef.set(
                mapOf(
                    "partnerSharePercent" to safePct,
                    "partnerName" to cleanName,
                    "currencyCode" to "USD",
                    "updatedByUid" to ownerUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }

    suspend fun addDevChangelog(
        title: String,
        category: DevChangelogCategory,
        status: DevChangelogStatus,
        details: String,
        changeDate: String,
    ): Result<Unit> = runOwnerWrite("dev_changelog") { db, ownerUid ->
        val cleanTitle = title.trim().take(120)
        require(cleanTitle.isNotEmpty()) { "عنوان السجل مطلوب" }
        val cleanDate = changeDate.trim().take(32).ifEmpty { "2026-11-18" }
        db.collection("dev_changelog").document().set(
            mapOf(
                "title" to cleanTitle,
                "category" to category.name,
                "status" to status.name,
                "details" to details.trim().take(500),
                "changeDate" to cleanDate,
                "createdByUid" to ownerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun deleteDevChangelog(docId: String): Result<Unit> = runOwnerWrite("dev_changelog/$docId") { db, _ ->
        db.collection("dev_changelog").document(docId).delete().await()
    }

    suspend fun recordTestRun(
        testType: AdminTestType,
        engineId: String,
        passed: Boolean,
        latencyMs: Long,
        summary: String,
        errorDetail: String = "",
    ): Result<Unit> = runOwnerWrite("admin_test_runs") { db, ownerUid ->
        db.collection("admin_test_runs").document().set(
            mapOf(
                "testType" to testType.name,
                "engineId" to engineId.trim().take(64).ifEmpty { "voxcpm2" },
                "passed" to passed,
                "latencyMs" to latencyMs.coerceIn(0L, 600_000L),
                "summary" to summary.trim().take(300).ifEmpty { "Completed" },
                "errorDetail" to errorDetail.trim().take(500),
                "executedByUid" to ownerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    private suspend inline fun runOwnerWrite(
        path: String,
        crossinline block: suspend (FirebaseFirestore, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = dbOrNull() ?: throw IllegalStateException("Firestore غير مهيأ")
            val ownerUid = requireOwnerUid()
            block(db, ownerUid)
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logFirestoreError("WRITE", path, t)
            Result.failure(t)
        }
    }

    private inline fun <reified E : Enum<E>> safeEnum(raw: String?, defaultVal: E): E {
        if (raw.isNullOrBlank()) return defaultVal
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: defaultVal
    }
}

data class AdminOverviewResponse(
    val server: ServerTelemetrySnapshot,
    val voxCpm: EngineTelemetrySnapshot,
    val audarFlash: EngineTelemetrySnapshot,
)
