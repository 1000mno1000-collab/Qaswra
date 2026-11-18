package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.devpanel.CreditTransactionRecord
import com.example.devpanel.CreditTxStatus
import com.example.devpanel.CreditType
import com.example.devpanel.DeveloperPanelUiState
import com.example.devpanel.FinanceEntryType
import com.example.devpanel.PaymentStatus
import com.example.devpanel.PaymentType
import com.example.ui.theme.StudioColors

// =========================================================================
// Section 3: Credits Management Tab
// =========================================================================

@Composable
internal fun CreditsManagementTab(
    devState: DeveloperPanelUiState,
    ownerUid: String,
    ownerEmail: String,
    isEn: Boolean,
    onAdjustCredits: (String, String, CreditType, Boolean, Long, String) -> Unit,
    onRefundTransaction: (CreditTransactionRecord) -> Unit,
) {
    var targetUid by rememberSaveable { mutableStateOf(ownerUid) }
    var targetEmail by rememberSaveable { mutableStateOf(ownerEmail) }
    var amountStr by rememberSaveable { mutableStateOf("100") }
    var reason by rememberSaveable { mutableStateOf("") }
    var creditType by remember { mutableStateOf(CreditType.FREE) }
    var isGrant by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Real Totals
        DevSectionCard(
            title = if (isEn) "Credits Pool Overview" else "إجمالي أرصدة المستخدمين",
            accent = StudioColors.Cyan,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Total Free Credits" else "مجموع الأرصدة المجانية",
                    value = "${devState.totalFreeCredits}",
                    accent = StudioColors.Emerald,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Total Paid Credits" else "مجموع الأرصدة المدفوعة",
                    value = "${devState.totalPaidCredits}",
                    accent = StudioColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Accounts" else "عدد الحسابات",
                    value = "${devState.creditAccounts.size}",
                    accent = StudioColors.Violet,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Failed / Refunded" else "فاشلة ومستردة",
                    value = "${devState.failedOrRefundedTransactions.size}",
                    accent = StudioColors.Amber,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Adjust Credits Form
        DevSectionCard(
            title = if (isEn) "Add / Deduct User Credits" else "إضافة أو خصم رصيد لمستخدم",
            accent = StudioColors.Violet,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isEn) "Target User UID" else "معرف المستخدم (UID)",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary,
                )
                TextButton(
                    onClick = {
                        targetUid = ownerUid
                        targetEmail = ownerEmail
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                ) {
                    Text(if (isEn) "Use My UID" else "تعبئة حساب المالك", color = StudioColors.Cyan)
                }
            }

            OutlinedTextField(
                value = targetUid,
                onValueChange = { targetUid = it },
                label = { Text("Firebase UID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("dev_credits_uid_input"),
            )
            OutlinedTextField(
                value = targetEmail,
                onValueChange = { targetEmail = it },
                label = { Text(if (isEn) "User Email (optional)" else "البريد الإلكتروني (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = creditType == CreditType.FREE,
                    onClick = { creditType = CreditType.FREE },
                    label = { Text(if (isEn) "Free Credits" else "رصيد مجاني") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StudioColors.Emerald.copy(alpha = 0.22f)),
                )
                FilterChip(
                    selected = creditType == CreditType.PAID,
                    onClick = { creditType = CreditType.PAID },
                    label = { Text(if (isEn) "Paid Credits" else "رصيد مدفوع") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f)),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isGrant,
                    onClick = { isGrant = true },
                    label = { Text(if (isEn) "Grant (+)" else "إضافة رصيد (+)") },
                )
                FilterChip(
                    selected = !isGrant,
                    onClick = { isGrant = false },
                    label = { Text(if (isEn) "Deduct (-)" else "خصم رصيد (-)") },
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { v -> amountStr = v.filter { it.isDigit() } },
                    label = { Text(if (isEn) "Amount" else "الكمية") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("dev_credits_amount_input"),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(if (isEn) "Reason" else "السبب") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f).testTag("dev_credits_reason_input"),
                )
            }

            val parsedAmount = amountStr.toLongOrNull() ?: 0L
            Button(
                onClick = {
                    if (targetUid.isNotBlank() && parsedAmount > 0L) {
                        onAdjustCredits(targetUid.trim(), targetEmail.trim(), creditType, isGrant, parsedAmount, reason.trim())
                        reason = ""
                    }
                },
                enabled = targetUid.isNotBlank() && parsedAmount > 0L,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGrant) StudioColors.Emerald else StudioColors.Amber,
                    contentColor = StudioColors.BgTop,
                ),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("dev_credits_submit_btn"),
            ) {
                Text(if (isGrant) (if (isEn) "Apply Credit Grant" else "تأكيد إضافة الرصيد") else (if (isEn) "Apply Deduction" else "تأكيد خصم الرصيد"))
            }
        }

        // Failed & Refunded Operations Section
        val failedList = devState.failedOrRefundedTransactions
        DevSectionCard(
            title = if (isEn) "Failed & Refunded Operations (${failedList.size})" else "العمليات الفاشلة والمستردة (${failedList.size})",
            accent = StudioColors.Amber,
        ) {
            if (failedList.isEmpty()) {
                Text(
                    text = if (isEn) "No failed or refunded transactions." else "لا توجد عمليات فاشلة أو مستردة مسجلة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            } else {
                failedList.forEach { tx ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${tx.userId.take(12)}… • ${tx.amount} (${tx.creditType.name})",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = "${tx.status.name} • ${tx.reason.ifBlank { "—" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                )
                            }
                            if (tx.status == CreditTxStatus.FAILED) {
                                OutlinedButton(
                                    onClick = { onRefundTransaction(tx) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("dev_refund_tx_${tx.docId}"),
                                ) {
                                    Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isEn) "Refund" else "استرداد")
                                }
                            } else {
                                DevStatusPill(if (isEn) "REFUNDED" else "مستردة", StudioColors.Emerald)
                            }
                        }
                    }
                }
            }
        }

        // User Operations Log
        DevSectionCard(
            title = if (isEn) "User Transactions Log (${devState.creditTransactions.size})" else "سجل عمليات المستخدمين (${devState.creditTransactions.size})",
            accent = StudioColors.Cyan,
        ) {
            if (devState.creditTransactions.isEmpty()) {
                Text(
                    text = if (isEn) "No transactions logged yet." else "لا توجد عمليات رصيد مسجلة بعد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            } else {
                devState.creditTransactions.take(40).forEach { tx ->
                    val txColor = when (tx.status) {
                        CreditTxStatus.COMPLETED -> StudioColors.Emerald
                        CreditTxStatus.FAILED -> StudioColors.Rose
                        CreditTxStatus.REFUNDED -> StudioColors.Amber
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${tx.operation.name} ${tx.amount} (${tx.creditType.name}) • ${tx.userId.take(10)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${tx.reason.ifBlank { tx.engineId.ifBlank { "—" } }} • ${formatDevDateTime(tx.createdAtMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DevStatusPill(tx.status.name, txColor)
                    }
                    HorizontalDivider(color = StudioColors.Border.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// =========================================================================
// Section 4: Payments & Finance Tab
// =========================================================================

@Composable
internal fun PaymentsFinanceTab(
    devState: DeveloperPanelUiState,
    ownerUid: String,
    isEn: Boolean,
    onRecordPayment: (String, PaymentType, String, Double, PaymentStatus, String) -> Unit,
    onRecordFinanceEntry: (FinanceEntryType, String, Double, String, String) -> Unit,
    onDeleteFinanceEntry: (String) -> Unit,
    onSavePartnerConfig: (Double, String) -> Unit,
) {
    val summary = devState.financeSummary

    var partnerPctStr by rememberSaveable { mutableStateOf("") }
    var partnerName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(devState.financeConfig) {
        if (partnerPctStr.isEmpty()) {
            partnerPctStr = if (devState.financeConfig.partnerSharePercent > 0.0) {
                devState.financeConfig.partnerSharePercent.toString()
            } else "0"
        }
        if (partnerName.isEmpty()) {
            partnerName = devState.financeConfig.partnerName
        }
    }

    var payUserId by rememberSaveable { mutableStateOf(ownerUid) }
    var payProductId by rememberSaveable { mutableStateOf("sonix_pro_pack") }
    var payAmountStr by rememberSaveable { mutableStateOf("") }
    var payProvider by rememberSaveable { mutableStateOf("google_play") }
    var payType by remember { mutableStateOf(PaymentType.PURCHASE) }
    var payStatus by remember { mutableStateOf(PaymentStatus.SUCCEEDED) }

    var ledgerType by remember { mutableStateOf(FinanceEntryType.AD_REVENUE) }
    var ledgerSource by rememberSaveable { mutableStateOf("") }
    var ledgerAmountStr by rememberSaveable { mutableStateOf("") }
    var ledgerPeriod by rememberSaveable { mutableStateOf(todayDevDateString().take(7)) }
    var ledgerNotes by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Real Financial Summary
        DevSectionCard(
            title = if (isEn) "Real Financial Summary (USD)" else "الملخص المالي الفعلي (بالدولار)",
            subtitle = if (isEn) "Calculated strictly from persisted Firestore records + live GPU compute." else "محسوب مباشرة من سجلات قاعدة البيانات وتكلفة الخادم الفعلية دون أي أرقام تقديرية وهمية.",
            accent = StudioColors.Emerald,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Succeeded (${summary.succeededPurchasesCount})" else "مشتريات ناجحة (${summary.succeededPurchasesCount})",
                    value = formatDevUsd(summary.succeededPurchasesUsd),
                    accent = StudioColors.Emerald,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Failed (${summary.failedPurchasesCount})" else "مشتريات فاشلة (${summary.failedPurchasesCount})",
                    value = formatDevUsd(summary.failedPurchasesUsd),
                    accent = StudioColors.Rose,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Active Subs (${summary.activeSubscriptionsCount})" else "اشتراكات فعالة (${summary.activeSubscriptionsCount})",
                    value = formatDevUsd(summary.activeSubscriptionsUsd),
                    accent = StudioColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Ad Revenues" else "إيرادات الإعلانات",
                    value = formatDevUsd(summary.adRevenuesUsd),
                    accent = StudioColors.Violet,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Total Revenues" else "إجمالي الإيرادات",
                    value = formatDevUsd(summary.totalRevenueUsd),
                    accent = StudioColors.EmeraldBright,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Total Costs" else "تكاليف الاستضافة والمحركات",
                    value = formatDevUsd(summary.totalCostsUsd),
                    accent = StudioColors.Amber,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "Net Profit" else "صافي الأرباح",
                    value = formatDevUsd(summary.netProfitUsd),
                    accent = if (summary.netProfitUsd >= 0.0) StudioColors.Emerald else StudioColors.Rose,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Partner Share (${summary.partnerSharePercent.toInt()}%)" else "حصة الشريك (${summary.partnerSharePercent.toInt()}%)",
                    value = formatDevUsd(summary.partnerShareUsd),
                    accent = StudioColors.VioletBright,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (isEn) {
                    "Owner Net: ${formatDevUsd(summary.ownerNetUsd)} • Live GPU Compute Cost included: ${formatDevUsd(summary.liveServerComputeCostUsd)}"
                } else {
                    "صافي المالك بعد حصة الشريك: ${formatDevUsd(summary.ownerNetUsd)} • تشمل تكلفة حوسبة GPU الحية: ${formatDevUsd(summary.liveServerComputeCostUsd)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
            )
        }

        // Partner Share Configuration
        DevSectionCard(
            title = if (isEn) "Partner Share Configuration" else "إعدادات حصة الشريك",
            accent = StudioColors.Violet,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = partnerName,
                    onValueChange = { partnerName = it },
                    label = { Text(if (isEn) "Partner Name" else "اسم الشريك") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = partnerPctStr,
                    onValueChange = { partnerPctStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(if (isEn) "Share %" else "النسبة %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("dev_partner_pct_input"),
                )
            }
            Button(
                onClick = {
                    val pct = (partnerPctStr.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
                    onSavePartnerConfig(pct, partnerName.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Violet, contentColor = StudioColors.BgTop),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("dev_partner_save_btn"),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isEn) "Save Partner Share" else "حفظ نسبة الشريك")
            }
        }

        // Record Payment / Subscription
        DevSectionCard(
            title = if (isEn) "Record Purchase / Subscription" else "تسجيل عملية شراء أو اشتراك",
            accent = StudioColors.Cyan,
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaymentType.entries.forEach { t ->
                    FilterChip(selected = payType == t, onClick = { payType = t }, label = { Text(t.name) })
                }
                PaymentStatus.entries.forEach { st ->
                    FilterChip(selected = payStatus == st, onClick = { payStatus = st }, label = { Text(st.name) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = payProductId,
                    onValueChange = { payProductId = it },
                    label = { Text(if (isEn) "Product ID" else "معرف الباقة") },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f),
                )
                OutlinedTextField(
                    value = payAmountStr,
                    onValueChange = { payAmountStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("USD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.9f),
                )
            }
            val parsedPayAmt = payAmountStr.toDoubleOrNull() ?: -1.0
            Button(
                onClick = {
                    if (payProductId.isNotBlank() && parsedPayAmt >= 0.0) {
                        onRecordPayment(payUserId.trim().ifBlank { ownerUid }, payType, payProductId.trim(), parsedPayAmt, payStatus, payProvider.trim())
                        payAmountStr = ""
                    }
                },
                enabled = payProductId.isNotBlank() && parsedPayAmt >= 0.0,
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("dev_record_payment_btn"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isEn) "Record Payment" else "حفظ العملية المالية")
            }
        }

        // Record Ad Revenue / Hosting / Engine Cost
        DevSectionCard(
            title = if (isEn) "Record Ad Revenue / Hosting / Engine Cost" else "تسجيل إيرادات إعلانات أو تكاليف استضافة",
            accent = StudioColors.Amber,
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FinanceEntryType.entries.forEach { et ->
                    val label = when (et) {
                        FinanceEntryType.AD_REVENUE -> if (isEn) "Ad Revenue" else "إيرادات إعلانات"
                        FinanceEntryType.HOSTING_COST -> if (isEn) "Hosting Cost" else "تكلفة استضافة"
                        FinanceEntryType.ENGINE_COST -> if (isEn) "Engine Cost" else "تكلفة محرك"
                    }
                    FilterChip(selected = ledgerType == et, onClick = { ledgerType = et }, label = { Text(label) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ledgerSource,
                    onValueChange = { ledgerSource = it },
                    label = { Text(if (isEn) "Source / Vendor" else "المصدر أو المزود") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = ledgerAmountStr,
                    onValueChange = { ledgerAmountStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("USD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.9f),
                )
            }
            OutlinedTextField(
                value = ledgerNotes,
                onValueChange = { ledgerNotes = it },
                label = { Text(if (isEn) "Period / Notes ($ledgerPeriod)" else "ملاحظات (${ledgerPeriod})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val parsedLedgerAmt = ledgerAmountStr.toDoubleOrNull() ?: -1.0
            Button(
                onClick = {
                    if (ledgerSource.isNotBlank() && parsedLedgerAmt >= 0.0) {
                        onRecordFinanceEntry(ledgerType, ledgerSource.trim(), parsedLedgerAmt, ledgerPeriod.trim(), ledgerNotes.trim())
                        ledgerSource = ""
                        ledgerAmountStr = ""
                        ledgerNotes = ""
                    }
                },
                enabled = ledgerSource.isNotBlank() && parsedLedgerAmt >= 0.0,
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Amber, contentColor = StudioColors.BgTop),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("dev_record_finance_btn"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isEn) "Save Finance Entry" else "تسجيل البند المالي")
            }

            if (devState.financeEntries.isNotEmpty()) {
                HorizontalDivider(color = StudioColors.Border)
                devState.financeEntries.take(20).forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${entry.source} • ${formatDevUsd(entry.amountUsd)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioColors.TextPrimary,
                            )
                            Text(
                                text = "${entry.entryType.name} • ${entry.periodLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextMuted,
                            )
                        }
                        IconButton(onClick = { onDeleteFinanceEntry(entry.docId) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = StudioColors.Rose)
                        }
                    }
                }
            }
        }
    }
}
