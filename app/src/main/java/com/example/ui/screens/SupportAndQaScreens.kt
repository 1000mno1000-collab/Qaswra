package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.diagnostics.AppDiagnosticLog
import com.example.diagnostics.QaIssueReport
import com.example.model.AppLanguage
import com.example.network.AppUpdateManager
import com.example.studio.ui.lit
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import com.example.i18n.t

private const val SUPPORT_EMAIL = "1000iraq1000@gmail.com"

@Composable
fun SmartQaLabScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "support_lab_section_title"),
            subtitle = t(lang, "qa_card_title"),
            language = lang,
            onBack = onBack,
        )

        SupportUpdatesQaSection(
            uiState = uiState,
            viewModel = viewModel,
            onOpenFullLab = null,
        )
    }
}

@Composable
fun SupportUpdatesQaSection(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenFullLab: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lang = uiState.language
    val context = LocalContext.current
    val qaState by viewModel.qaScanState.collectAsStateWithLifecycle()
    val currentVersion = remember(context) { AppUpdateManager.getCurrentAppVersionName(context) }

    var supportMessage by rememberSaveable { mutableStateOf("") }
    var attachDiagnostics by rememberSaveable { mutableStateOf(true) }
    var inlineFeedback by remember { mutableStateOf<String?>(null) }

    val classicAccentBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                StudioColors.Cyan.copy(alpha = 0.65f),
                StudioColors.Amber.copy(alpha = 0.55f),
                StudioColors.Violet.copy(alpha = 0.65f),
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("support_updates_qa_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Classic Elegant Header with Version & Optional Full-Lab Expand Action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StudioColors.Cyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.4f)),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = t(lang, "support_lab_section_title"),
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = t(lang, "support_lab_section_title"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.BorderBright),
                    ) {
                        Text(
                            text = "v$currentVersion",
                            style = TelemetryTextStyle.copy(fontSize = 11.sp),
                            color = StudioColors.Amber,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                    if (onOpenFullLab != null) {
                        val openLabInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = onOpenFullLab,
                            interactionSource = openLabInt,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(openLabInt, StudioColors.Cyan, cornerRadius = 24.dp)
                                .testTag("open_smart_qa_lab_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = t(lang, "qa_card_title"),
                                tint = StudioColors.Cyan,
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(classicAccentBrush),
            )
        }

        inlineFeedback?.let { msg ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = StudioColors.Emerald.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("support_qa_feedback_banner"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.Emerald,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { inlineFeedback = null },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss",
                            tint = StudioColors.Emerald,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // Side-by-Side Classic Cards: App Updates (GitHub Releases) + Direct Customer Support
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 340.dp
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AppUpdateClassicCard(
                        uiState = uiState,
                        currentVersion = currentVersion,
                        viewModel = viewModel,
                        lang = lang,
                        compactMode = true,
                        modifier = Modifier.weight(1f),
                    )
                    CustomerSupportClassicCard(
                        lang = lang,
                        supportMessage = supportMessage,
                        onMessageChange = { supportMessage = it },
                        attachDiagnostics = attachDiagnostics,
                        onAttachChange = { attachDiagnostics = it },
                        compactMode = true,
                        onSendEmail = {
                            val body = buildSupportEmailBody(
                                lang = lang,
                                userMessage = supportMessage,
                                attachDiagnostics = attachDiagnostics,
                                qaReport = viewModel.getSmartQaFullReport(),
                                currentVersion = currentVersion,
                            )
                            val subject = if (lang == AppLanguage.EN) {
                                "Sonix ✦ Studio Support (v$currentVersion)"
                            } else {
                                "دعم تطبيق Sonix ✦ (إصدار $currentVersion)"
                            }
                            inlineFeedback = launchSupportEmail(
                                context = context,
                                subject = subject,
                                body = body,
                                lang = lang,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppUpdateClassicCard(
                        uiState = uiState,
                        currentVersion = currentVersion,
                        viewModel = viewModel,
                        lang = lang,
                        compactMode = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CustomerSupportClassicCard(
                        lang = lang,
                        supportMessage = supportMessage,
                        onMessageChange = { supportMessage = it },
                        attachDiagnostics = attachDiagnostics,
                        onAttachChange = { attachDiagnostics = it },
                        compactMode = false,
                        onSendEmail = {
                            val body = buildSupportEmailBody(
                                lang = lang,
                                userMessage = supportMessage,
                                attachDiagnostics = attachDiagnostics,
                                qaReport = viewModel.getSmartQaFullReport(),
                                currentVersion = currentVersion,
                            )
                            val subject = if (lang == AppLanguage.EN) {
                                "Sonix ✦ Studio Support (v$currentVersion)"
                            } else {
                                "دعم تطبيق Sonix ✦ (إصدار $currentVersion)"
                            }
                            inlineFeedback = launchSupportEmail(
                                context = context,
                                subject = subject,
                                body = body,
                                lang = lang,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Classic Card: Smart QA Bot & Open-Ended Full Diagnostics Lab
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
            border = BorderStroke(
                1.dp,
                when {
                    qaState.isRunning -> StudioColors.Cyan.copy(alpha = 0.7f)
                    qaState.completed && qaState.issues.isEmpty() -> StudioColors.Emerald.copy(alpha = 0.55f)
                    qaState.issues.isNotEmpty() -> StudioColors.Amber.copy(alpha = 0.6f)
                    else -> StudioColors.BorderBright
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("smart_qa_lab_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StudioColors.Cyan.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.4f)),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Science,
                                    contentDescription = t(lang, "qa_card_title"),
                                    tint = StudioColors.Cyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = t(lang, "qa_card_title"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.TextPrimary,
                            )
                            Text(
                                text = t(lang, "qa_card_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = StudioColors.BgMid,
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "#${qaState.scenariosChecked}",
                            style = TelemetryTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = StudioColors.Cyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // Side-by-Side Consent Start & Immediate Stop Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val startInt = remember { MutableInteractionSource() }
                    Button(
                        onClick = { viewModel.startSmartQaScan() },
                        enabled = !qaState.isRunning,
                        interactionSource = startInt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                            disabledContainerColor = StudioColors.ElevatedSurface,
                            disabledContentColor = StudioColors.TextMuted,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.35f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(startInt, StudioColors.Cyan, cornerRadius = 12.dp)
                            .testTag("qa_start_consent_button"),
                    ) {
                        if (qaState.isRunning) {
                            CircularProgressIndicator(
                                color = StudioColors.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = t(lang, "qa_start_consent_btn"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val stopInt = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.stopSmartQaScan() },
                        enabled = qaState.isRunning,
                        interactionSource = stopInt,
                        border = BorderStroke(
                            1.dp,
                            if (qaState.isRunning) StudioColors.RoseBright else StudioColors.Border,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StudioColors.RoseBright,
                            disabledContentColor = StudioColors.TextMuted,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(stopInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                            .testTag("qa_stop_now_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = t(lang, "qa_stop_now_btn"),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = t(lang, "qa_stop_now_btn"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Live Progress & Telemetry Metrics Strip
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val progressBarColor = if (qaState.issues.isNotEmpty()) StudioColors.Amber else StudioColors.Cyan
                    val progressBarMod = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("qa_progress_bar")
                    if (qaState.isRunning) {
                        LinearProgressIndicator(
                            color = progressBarColor,
                            trackColor = StudioColors.BgTop,
                            modifier = progressBarMod,
                        )
                    } else {
                        val idleFraction = if (qaState.completed && qaState.scenariosChecked > 0) 1f else 0f
                        LinearProgressIndicator(
                            progress = { idleFraction },
                            color = progressBarColor,
                            trackColor = StudioColors.BgTop,
                            modifier = progressBarMod,
                        )
                    }

                    Text(
                        text = if (lang == AppLanguage.EN) qaState.currentPhaseLabelEn else qaState.currentPhaseLabelAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextSecondary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QaTelemetryTile(
                        label = if (lang == AppLanguage.EN) "Checked" else "تم فحصها",
                        value = "${qaState.scenariosChecked}",
                        color = StudioColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                    QaTelemetryTile(
                        label = if (lang == AppLanguage.EN) "Passed" else "ناجحة",
                        value = "${qaState.passedChecksCount}",
                        color = StudioColors.Emerald,
                        modifier = Modifier.weight(1f),
                    )
                    QaTelemetryTile(
                        label = if (lang == AppLanguage.EN) "Issues" else "مشاكل",
                        value = "${qaState.issues.size}",
                        color = if (qaState.issues.isNotEmpty()) StudioColors.RoseBright else StudioColors.Emerald,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Numbered Issue Documentation or Clean Pass Banner
                if (qaState.completed && qaState.issues.isEmpty() && !qaState.stoppedByUser) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioColors.Emerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qa_no_issues_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = StudioColors.Emerald,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = t(lang, "qa_no_issues"),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.Emerald,
                            )
                        }
                    }
                } else if (qaState.issues.isNotEmpty()) {
                    HorizontalDivider(color = StudioColors.Border)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qa_numbered_issues_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val previewIssues = qaState.issues.take(20)
                        previewIssues.forEach { issue ->
                            QaIssueNumberedCard(issue = issue, lang = lang)
                        }
                        if (qaState.issues.size > previewIssues.size) {
                            Text(
                                text = if (lang == AppLanguage.EN) {
                                    "+${qaState.issues.size - previewIssues.size} more issues included in full report."
                                } else {
                                    "+${qaState.issues.size - previewIssues.size} مشاكل إضافية مرفقة في التقرير الكامل."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.Amber,
                            )
                        }
                    }
                }

                // Side-by-Side Copy Full Report & Email Support Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val copyInt = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            val report = viewModel.getSmartQaFullReport()
                            copyTextToClipboard(context, "Sonix QA Report", report)
                            inlineFeedback = if (lang == AppLanguage.EN) {
                                "Full QA & diagnostics report copied to clipboard."
                            } else {
                                "تم نسخ التقرير الفني الشامل إلى الحافظة."
                            }
                        },
                        interactionSource = copyInt,
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(copyInt, StudioColors.Cyan, cornerRadius = 12.dp)
                            .testTag("qa_copy_report_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = t(lang, "qa_copy_report_btn"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val emailInt = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            val report = viewModel.getSmartQaFullReport()
                            val subject = if (lang == AppLanguage.EN) {
                                "Sonix ✦ Smart QA & Diagnostics Report (v$currentVersion)"
                            } else {
                                "تقرير الفحص الذكي لتطبيق Sonix ✦ (إصدار $currentVersion)"
                            }
                            inlineFeedback = launchSupportEmail(
                                context = context,
                                subject = subject,
                                body = report,
                                lang = lang,
                            )
                        },
                        interactionSource = emailInt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Violet,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(emailInt, StudioColors.Violet, cornerRadius = 12.dp)
                            .testTag("qa_email_support_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = t(lang, "qa_email_support_btn"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUpdateClassicCard(
    uiState: StudioUiState,
    currentVersion: String,
    viewModel: StudioViewModel,
    lang: AppLanguage,
    compactMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val result = uiState.updateCheckResult
    val borderColor = when {
        result?.isUpdateAvailable == true -> StudioColors.Amber.copy(alpha = 0.7f)
        result != null -> StudioColors.Emerald.copy(alpha = 0.55f)
        else -> StudioColors.BorderBright
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.testTag("app_update_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioColors.Amber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.45f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = t(lang, "update_card_title"),
                            tint = StudioColors.Amber,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t(lang, "update_card_title"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                        maxLines = if (compactMode) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "v$currentVersion",
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = StudioColors.Amber,
                    )
                }
            }

            Text(
                text = t(lang, "update_card_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextSecondary,
                maxLines = if (compactMode) 3 else 4,
                overflow = TextOverflow.Ellipsis,
            )

            if (result != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (result.isUpdateAvailable) StudioColors.Amber.copy(alpha = 0.14f) else StudioColors.Emerald.copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        if (result.isUpdateAvailable) StudioColors.Amber.copy(alpha = 0.55f) else StudioColors.Emerald.copy(alpha = 0.55f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (result.isUpdateAvailable) {
                                t(lang, "update_available_badge")
                            } else {
                                t(lang, "update_latest_badge")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (result.isUpdateAvailable) StudioColors.Amber else StudioColors.Emerald,
                        )
                        Text(
                            text = "v${result.currentVersion} → v${result.latestVersion}",
                            style = TelemetryTextStyle.copy(fontSize = 11.sp),
                            color = StudioColors.TextPrimary,
                        )
                        if (result.releaseNotes.isNotBlank()) {
                            Text(
                                text = result.releaseNotes,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                val dlInt = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { viewModel.openAppUpdateLink() },
                    interactionSource = dlInt,
                    border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(dlInt, StudioColors.Emerald, cornerRadius = 12.dp)
                        .testTag("update_download_button"),
                ) {
                    Text(
                        text = t(lang, "update_download_btn"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            uiState.updateCheckError?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioColors.Rose.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.RoseBright,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (uiState.updateCheckLoading) {
                val cancelInt = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { viewModel.cancelActiveOnlineRequest() },
                    interactionSource = cancelInt,
                    border = BorderStroke(1.dp, StudioColors.RoseBright),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(cancelInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                        .testTag("update_cancel_button"),
                ) {
                    CircularProgressIndicator(
                        color = StudioColors.Amber,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = t(lang, "cancel_request"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val checkInt = remember { MutableInteractionSource() }
                Button(
                    onClick = { viewModel.checkForAppUpdate() },
                    interactionSource = checkInt,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Amber,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(checkInt, StudioColors.Amber, cornerRadius = 12.dp)
                        .testTag("update_check_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = t(lang, "update_check_btn"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerSupportClassicCard(
    lang: AppLanguage,
    supportMessage: String,
    onMessageChange: (String) -> Unit,
    attachDiagnostics: Boolean,
    onAttachChange: (Boolean) -> Unit,
    compactMode: Boolean,
    onSendEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.BorderBright),
        modifier = modifier.testTag("customer_support_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioColors.Violet.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.45f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SupportAgent,
                            contentDescription = t(lang, "support_card_title"),
                            tint = StudioColors.Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t(lang, "support_card_title"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                        maxLines = if (compactMode) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = SUPPORT_EMAIL,
                        style = TelemetryTextStyle.copy(fontSize = 10.sp),
                        color = StudioColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = t(lang, "support_card_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextSecondary,
                maxLines = if (compactMode) 3 else 4,
                overflow = TextOverflow.Ellipsis,
            )

            OutlinedTextField(
                value = supportMessage,
                onValueChange = onMessageChange,
                placeholder = {
                    Text(
                        text = t(lang, "support_message_hint"),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                minLines = 2,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Violet,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("support_message_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = t(lang, "support_attach_logs_toggle"),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = attachDiagnostics,
                    onCheckedChange = onAttachChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioColors.BgTop,
                        checkedTrackColor = StudioColors.Cyan,
                    ),
                    modifier = Modifier.testTag("support_attach_logs_switch"),
                )
            }

            val sendInt = remember { MutableInteractionSource() }
            Button(
                onClick = onSendEmail,
                interactionSource = sendInt,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Emerald,
                    contentColor = StudioColors.BgTop,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .lit(sendInt, StudioColors.Emerald, cornerRadius = 12.dp)
                    .testTag("support_send_email_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Email,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = t(lang, "support_send_email_btn"),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QaTelemetryTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StudioColors.BgMid,
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = TelemetryTextStyle.copy(fontWeight = FontWeight.Bold),
                color = color,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QaIssueNumberedCard(
    issue: QaIssueReport,
    lang: AppLanguage,
) {
    val issueHeader = if (lang == AppLanguage.EN) "Issue #${issue.number}" else "مشكلة #${issue.number}"
    val locationLabel = if (lang == AppLanguage.EN) "Location:" else "المكان:"
    val causeLabel = if (lang == AppLanguage.EN) "Cause:" else "السبب التقني:"
    val tipLabel = if (lang == AppLanguage.EN) "Recommendation:" else "التوجيه للأفضل:"

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.BgMid,
        border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("qa_issue_card_${issue.number}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (issue.errorCode.contains("WARN", ignoreCase = true)) {
                            Icons.Rounded.WarningAmber
                        } else {
                            Icons.Rounded.BugReport
                        },
                        contentDescription = null,
                        tint = StudioColors.Amber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = issueHeader,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.Amber,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StudioColors.Rose.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = issue.errorCode,
                        style = TelemetryTextStyle.copy(fontSize = 10.sp),
                        color = StudioColors.RoseBright,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = "$locationLabel ${issue.toolOrScreen} → ${issue.functionName}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = StudioColors.Cyan,
            )
            Text(
                text = "${issue.actionDetail}\n$causeLabel ${issue.technicalCause}",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextSecondary,
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StudioColors.Emerald.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "$tipLabel ${issue.recommendation}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = StudioColors.Emerald,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

private fun buildSupportEmailBody(
    lang: AppLanguage,
    userMessage: String,
    attachDiagnostics: Boolean,
    qaReport: String,
    currentVersion: String,
): String = buildString {
    appendLine("Sonix ✦ Studio Support Request (v$currentVersion)")
    appendLine("----------------------------------------")
    if (userMessage.isNotBlank()) {
        appendLine(userMessage.trim())
        appendLine()
    } else if (lang != AppLanguage.EN) {
        appendLine("مرحباً فريق الدعم الفني لتطبيق Sonix ✦،")
        appendLine()
    }
    if (attachDiagnostics) {
        appendLine(qaReport)
        val diagLines = AppDiagnosticLog.entries.value.take(5)
        if (diagLines.isNotEmpty() && !qaReport.contains("Runtime Diagnostic Log")) {
            appendLine()
            appendLine("--- Recent Diagnostics ---")
            diagLines.forEach { entry ->
                appendLine(AppDiagnosticLog.formatFullReport(entry))
            }
        }
    }
}

internal fun launchSupportEmail(
    context: Context,
    subject: String,
    body: String,
    lang: AppLanguage,
): String {
    val safeBody = body.take(12_000)
    val mailtoUri = Uri.parse("mailto:$SUPPORT_EMAIL")

    val primaryStarted = runCatching {
        val mailtoIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, safeBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(mailtoIntent)
        true
    }.getOrDefault(false)

    if (primaryStarted) {
        return if (lang == AppLanguage.EN) {
            "Opening email client to $SUPPORT_EMAIL"
        } else {
            "تم فتح تطبيق البريد للمراسلة مع $SUPPORT_EMAIL"
        }
    }

    val chooserStarted = runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, safeBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = if (lang == AppLanguage.EN) "Send Email to Support" else "إرسال إلى الدعم الفني"
        val chooserIntent = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
        true
    }.getOrDefault(false)

    if (chooserStarted) {
        return if (lang == AppLanguage.EN) {
            "Opening email chooser to $SUPPORT_EMAIL"
        } else {
            "تم فتح نافذة اختيار البريد للمراسلة مع $SUPPORT_EMAIL"
        }
    }

    copyTextToClipboard(
        context = context,
        label = "Sonix Support Email & Report",
        text = "To: $SUPPORT_EMAIL\nSubject: $subject\n\n$safeBody",
    )
    return if (lang == AppLanguage.EN) {
        "No email app configured — message & address ($SUPPORT_EMAIL) copied to clipboard."
    } else {
        "لا يوجد تطبيق بريد مُعد حالياً — تم نسخ البريد ($SUPPORT_EMAIL) والرسالة إلى الحافظة."
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
