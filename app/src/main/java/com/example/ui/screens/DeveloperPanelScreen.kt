package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.devpanel.DeveloperPanelTab
import com.example.devpanel.OwnerVerificationStatus
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.StudioUiState
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeveloperPanelScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    if (!BuildConfig.DEVELOPER_PANEL_ENABLED) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val lang = uiState.language
    val isEn = lang == AppLanguage.EN
    val devState by viewModel.developerPanelUiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.authSession) {
        viewModel.refreshDeveloperPanel()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("developer_panel_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StudioFocusTopBar(
                title = t(lang, "developer_panel_title"),
                subtitle = t(lang, "developer_panel_subtitle"),
                language = lang,
                onBack = onBack,
                trailing = {
                    IconButton(
                        onClick = { viewModel.refreshDeveloperPanel() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.Cyan,
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("dev_panel_refresh_button"),
                    ) {
                        if (devState.isRefreshing) {
                            CircularProgressIndicator(
                                color = StudioColors.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = if (isEn) "Refresh Telemetry" else "تحديث البيانات",
                            )
                        }
                    }
                },
            )

            // Owner Server Verification Gate
            val verification = devState.ownerVerification
            val badgeAccent = when (verification.status) {
                OwnerVerificationStatus.AUTHORIZED -> StudioColors.Emerald
                OwnerVerificationStatus.VERIFYING -> StudioColors.Cyan
                OwnerVerificationStatus.NOT_AUTHENTICATED -> StudioColors.Amber
                OwnerVerificationStatus.DENIED,
                OwnerVerificationStatus.DISABLED_IN_BUILD -> StudioColors.Rose
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = badgeAccent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, badgeAccent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dev_panel_verification_banner"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (verification.isAuthorized) Icons.Rounded.VerifiedUser else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = badgeAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (isEn) verification.statusMessageEn else verification.statusMessageAr,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = badgeAccent,
                        )
                        if (verification.verifiedEmail.isNotBlank()) {
                            val backendLabel = if (verification.backendVerified) {
                                if (isEn) "Firestore + GPU Server Verified" else "موثّق من Firestore وخادم GPU"
                            } else {
                                if (isEn) "Firestore Server Verified" else "موثّق من خادم Firestore"
                            }
                            Text(
                                text = "${verification.verifiedEmail} • $backendLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            if (!verification.isAuthorized) {
                DevAccessLockedCard(
                    status = verification.status,
                    isEn = isEn,
                    onRetry = { viewModel.refreshDeveloperPanel() },
                    onBack = onBack,
                )
                return@Column
            }

            // Status / Error feedback banners
            devState.actionFeedback?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.Emerald.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.EmeraldBright,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.clearDeveloperFeedback() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق", tint = StudioColors.EmeraldBright)
                        }
                    }
                }
            }

            devState.errorBanner?.let { err ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.Rose.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.RoseBright,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.clearDeveloperFeedback() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "إغلاق", tint = StudioColors.RoseBright)
                        }
                    }
                }
            }

            // 6 Navigation Tabs
            val tabs = DeveloperPanelTab.entries
            val selectedIdx = tabs.indexOf(devState.activeTab).coerceAtLeast(0)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioColors.CardSurface,
                border = BorderStroke(1.dp, StudioColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ScrollableTabRow(
                    selectedTabIndex = selectedIdx,
                    containerColor = Color.Transparent,
                    contentColor = StudioColors.Cyan,
                    edgePadding = 8.dp,
                    indicator = { tabPositions ->
                        if (selectedIdx < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIdx]),
                                color = StudioColors.Cyan,
                            )
                        }
                    },
                ) {
                    tabs.forEach { tab ->
                        val selected = tab == devState.activeTab
                        Tab(
                            selected = selected,
                            onClick = { viewModel.selectDeveloperTab(tab) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("dev_tab_${tab.name.lowercase()}"),
                            text = {
                                Text(
                                    text = if (isEn) tab.titleEn else tab.titleAr,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                    color = if (selected) StudioColors.Cyan else StudioColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            when (devState.activeTab) {
                DeveloperPanelTab.ENGINES -> EnginesStatusTab(
                    devState = devState,
                    diagnosticEntries = uiState.diagnosticEntries,
                    isEn = isEn,
                )
                DeveloperPanelTab.SERVER -> ServerTelemetryTab(
                    server = devState.serverTelemetry,
                    isEn = isEn,
                    onRefresh = { viewModel.refreshDeveloperPanel() },
                )
                DeveloperPanelTab.CREDITS -> CreditsManagementTab(
                    devState = devState,
                    ownerUid = verification.verifiedUid,
                    ownerEmail = verification.verifiedEmail,
                    isEn = isEn,
                    onAdjustCredits = viewModel::adjustDeveloperCredits,
                    onRefundTransaction = viewModel::refundDeveloperTransaction,
                )
                DeveloperPanelTab.PAYMENTS -> PaymentsFinanceTab(
                    devState = devState,
                    ownerUid = verification.verifiedUid,
                    isEn = isEn,
                    onRecordPayment = viewModel::recordDeveloperPayment,
                    onRecordFinanceEntry = viewModel::recordDeveloperFinanceEntry,
                    onDeleteFinanceEntry = viewModel::deleteDeveloperFinanceEntry,
                    onSavePartnerConfig = viewModel::saveDeveloperPartnerConfig,
                )
                DeveloperPanelTab.CHANGELOG -> DevChangelogTab(
                    devState = devState,
                    diagnosticEntries = uiState.diagnosticEntries,
                    isEn = isEn,
                    onAddChangelog = viewModel::addDeveloperChangelog,
                    onDeleteChangelog = viewModel::deleteDeveloperChangelog,
                )
                DeveloperPanelTab.TESTS -> TestRunnerTab(
                    devState = devState,
                    isEn = isEn,
                    onSelectEngine = viewModel::selectDeveloperTestEngine,
                    onRunTest = viewModel::runDeveloperTest,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DevAccessLockedCard(
    status: OwnerVerificationStatus,
    isEn: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dev_panel_locked_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = StudioColors.Rose,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = when (status) {
                    OwnerVerificationStatus.VERIFYING ->
                        if (isEn) "Verifying owner identity from server..." else "جاري التحقق من صلاحية المالك عبر الخادم..."
                    OwnerVerificationStatus.NOT_AUTHENTICATED ->
                        if (isEn) "Owner Google account sign-in is required" else "يرجى تسجيل الدخول بحساب المالك المعتمد أولاً"
                    else ->
                        if (isEn) "Access Denied by Server Security Rules" else "تم رفض الوصول من خادم الصلاحيات — هذا القسم مخصص للمالك فقط"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = StudioColors.TextPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(if (isEn) "Retry Server Check" else "إعادة التحقق")
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.ElevatedSurface, contentColor = StudioColors.TextPrimary),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(if (isEn) "Back" else "رجوع")
                }
            }
        }
    }
}

@Composable
internal fun DevSectionCard(
    title: String,
    accent: Color = StudioColors.Cyan,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }
            }
            content()
        }
    }
}

@Composable
internal fun DevMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.ElevatedSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DevStatusPill(
    text: String,
    accent: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

internal fun formatDevUsd(amount: Double): String =
    String.format(Locale.US, "$%.2f", amount)

internal fun formatDevDateTime(ms: Long): String {
    if (ms <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}

internal fun todayDevDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
