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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.devpanel.AdminTestRunRecord
import com.example.devpanel.AdminTestType
import com.example.devpanel.DevChangelogCategory
import com.example.devpanel.DevChangelogStatus
import com.example.devpanel.DeveloperPanelUiState
import com.example.devpanel.EngineTelemetrySnapshot
import com.example.devpanel.ServerTelemetrySnapshot
import com.example.diagnostics.AppDiagnosticEntry
import com.example.diagnostics.AppDiagnosticLog
import com.example.ui.theme.StudioColors
import java.util.Locale

// =========================================================================
// Section 1: Voice Engines Status Tab
// =========================================================================

@Composable
internal fun EnginesStatusTab(
    devState: DeveloperPanelUiState,
    diagnosticEntries: List<AppDiagnosticEntry>,
    isEn: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EngineStatusDetailCard(
            engine = devState.voxCpmEngine,
            latestTest = devState.testRuns.firstOrNull { it.engineId == devState.voxCpmEngine.engineId },
            isEn = isEn,
        )
        EngineStatusDetailCard(
            engine = devState.audarFlashEngine,
            latestTest = devState.testRuns.firstOrNull { it.engineId == devState.audarFlashEngine.engineId },
            isEn = isEn,
        )

        val engineErrors = remember(devState.voxCpmEngine, devState.audarFlashEngine, diagnosticEntries) {
            buildList {
                devState.voxCpmEngine.lastError?.let { add("VoxCPM2" to it) }
                devState.audarFlashEngine.lastError?.let { add("Audar-TTS-V1-Flash" to it) }
                diagnosticEntries.take(8).forEach { entry ->
                    add("${entry.toolName} (${entry.errorCode})" to (if (isEn) entry.causeEn else entry.causeAr))
                }
            }
        }

        DevSectionCard(
            title = if (isEn) "Captured Engine & App Issues (${engineErrors.size})" else "الأخطاء والمشكلات المرصودة فعلياً (${engineErrors.size})",
            accent = if (engineErrors.isEmpty()) StudioColors.Emerald else StudioColors.Rose,
        ) {
            if (engineErrors.isEmpty()) {
                Text(
                    text = if (isEn) "No engine errors reported." else "لا توجد أخطاء مسجلة من المحركات أو السجل التشخيصي حالياً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            } else {
                engineErrors.forEach { (source, detail) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(source, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = StudioColors.RoseBright)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineStatusDetailCard(
    engine: EngineTelemetrySnapshot,
    latestTest: AdminTestRunRecord?,
    isEn: Boolean,
) {
    val runtimeStatusText = when {
        !engine.backendConnected -> if (isEn) "Client Ready • GPU Server Offline" else "واجهة العميل مدمجة • خادم GPU غير متصل"
        engine.backendLoadedInMemory -> if (isEn) "Loaded in GPU Memory (Active)" else "محمّل بالذاكرة وجاهز للتوليد الفعلي"
        engine.backendPackageReady -> if (isEn) "Installed • Cold (Loads on first request)" else "مثبّت على الخادم • يُحمّل عند الطلب"
        else -> if (isEn) "Package Missing on GPU Server" else "الحزمة غير مثبتة على الخادم"
    }
    val statusColor = when {
        !engine.backendConnected -> StudioColors.Amber
        engine.backendLoadedInMemory -> StudioColors.Emerald
        engine.backendPackageReady -> StudioColors.Cyan
        else -> StudioColors.Rose
    }

    DevSectionCard(
        title = engine.displayName,
        subtitle = "${engine.repoId} • ${engine.licenseLabel}",
        accent = statusColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevStatusPill(
                text = if (engine.clientIntegrated) {
                    if (isEn) "Android Integrated" else "مدمج بالتطبيق"
                } else {
                    if (isEn) "Not Integrated" else "غير مدمج"
                },
                accent = if (engine.clientIntegrated) StudioColors.Cyan else StudioColors.Rose,
            )
            DevStatusPill(text = runtimeStatusText, accent = statusColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevMetricTile(
                label = if (isEn) "Generations" else "عمليات التوليد",
                value = "${engine.generationsCount}",
                accent = StudioColors.Cyan,
                modifier = Modifier.weight(1f),
            )
            DevMetricTile(
                label = if (isEn) "Failures" else "الإخفاقات",
                value = "${engine.failuresCount}",
                accent = if (engine.failuresCount > 0) StudioColors.Rose else StudioColors.Emerald,
                modifier = Modifier.weight(1f),
            )
            DevMetricTile(
                label = if (isEn) "Sample Rate" else "معدل العينة",
                value = engine.lastSampleRate?.let { "${it}Hz" } ?: "—",
                accent = StudioColors.Violet,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = StudioColors.Border)
        Text(
            text = if (isEn) "Last Recorded Test:" else "آخر اختبار مسجل:",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = StudioColors.TextPrimary,
        )
        if (latestTest == null) {
            Text(
                text = if (isEn) "No real test executed yet for this engine." else "لم يُنفّذ أي اختبار فعلي لهذا المحرك بعد.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextSecondary,
            )
        } else {
            val testAccent = if (latestTest.passed) StudioColors.Emerald else StudioColors.Rose
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StudioColors.ElevatedSurface,
                border = BorderStroke(1.dp, testAccent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (isEn) latestTest.testType.titleEn else latestTest.testType.titleAr,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = testAccent,
                        )
                        Text(
                            text = "${latestTest.latencyMs} ms • ${formatDevDateTime(latestTest.createdAtMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextMuted,
                        )
                    }
                    Text(latestTest.summary, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextPrimary)
                    if (latestTest.errorDetail.isNotBlank()) {
                        Text(latestTest.errorDetail, style = MaterialTheme.typography.labelSmall, color = StudioColors.RoseBright)
                    }
                }
            }
        }
    }
}

// =========================================================================
// Section 2: Server Telemetry Tab
// =========================================================================

@Composable
internal fun ServerTelemetryTab(
    server: ServerTelemetrySnapshot,
    isEn: Boolean,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val connColor = if (server.connected) StudioColors.Emerald else StudioColors.Rose
        DevSectionCard(
            title = if (isEn) "GPU Backend Server Telemetry" else "حالة خادم الذكاء الاصطناعي والـ GPU",
            subtitle = if (server.connected) {
                if (isEn) "Connected • HTTP ${server.httpCode} • Ping ${server.pingMs} ms" else "متصل بالخادم • HTTP ${server.httpCode} • زمن الاستجابة ${server.pingMs} ms"
            } else {
                server.errorDetail ?: (if (isEn) "Server offline or URL not configured" else "الخادم غير متصل أو لم يتم ضبط الرابط")
            },
            accent = connColor,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (server.connected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = connColor,
                )
                DevStatusPill(
                    text = if (server.connected) {
                        if (isEn) "LIVE CONNECTED" else "متصل فعلياً"
                    } else {
                        if (isEn) "OFFLINE" else "غير متصل"
                    },
                    accent = connColor,
                )
                DevStatusPill(
                    text = if (server.cudaAvailable) "CUDA Active" else "CUDA Inactive",
                    accent = if (server.cudaAvailable) StudioColors.Emerald else StudioColors.Amber,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = "GPU",
                    value = server.gpuName,
                    accent = StudioColors.Cyan,
                    modifier = Modifier.weight(1.4f),
                )
                DevMetricTile(
                    label = if (isEn) "Uptime" else "مدة التشغيل",
                    value = if (server.connected) "${server.uptimeSeconds / 60}m" else "—",
                    accent = StudioColors.Violet,
                    modifier = Modifier.weight(1f),
                )
            }

            // VRAM & RAM usage bars
            UsageProgressRow(
                label = if (isEn) "GPU VRAM" else "ذاكرة GPU (VRAM)",
                usedMb = server.vramUsedMb,
                totalMb = server.vramTotalMb,
                accent = StudioColors.Cyan,
            )
            UsageProgressRow(
                label = if (isEn) "System RAM" else "ذاكرة النظام (RAM)",
                usedMb = server.ramUsedMb,
                totalMb = server.ramTotalMb,
                accent = StudioColors.Violet,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(
                    label = if (isEn) "CPU Load" else "استهلاك المعالج",
                    value = if (server.connected) String.format(Locale.US, "%.1f%%", server.cpuPercent) else "—",
                    accent = StudioColors.Amber,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Generations" else "عمليات التوليد",
                    value = "${server.totalGenerations}",
                    accent = StudioColors.Emerald,
                    modifier = Modifier.weight(1f),
                )
                DevMetricTile(
                    label = if (isEn) "Est. Cost" else "التكلفة التقديرية",
                    value = formatDevUsd(server.estimatedComputeCostUsd),
                    accent = StudioColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = if (isEn) {
                    "Compute time: ${String.format(Locale.US, "%.1f", server.totalComputeSeconds)}s • Hourly GPU rate: ${formatDevUsd(server.gpuHourlyRateUsd)}/hr • HF Token configured: ${if (server.hfTokenConfigured) "Yes" else "No"}"
                } else {
                    "إجمالي وقت المعالجة الفعلي: ${String.format(Locale.US, "%.1f", server.totalComputeSeconds)} ثانية • تعرفة الساعة: ${formatDevUsd(server.gpuHourlyRateUsd)}/ساعة • توكن HF على السيرفر: ${if (server.hfTokenConfigured) "مهيأ" else "غير مهيأ"}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
            )

            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("dev_server_ping_button"),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isEn) "Re-check Server Telemetry" else "إعادة فحص الخادم الآن")
            }
        }
    }
}

@Composable
private fun UsageProgressRow(
    label: String,
    usedMb: Double,
    totalMb: Double,
    accent: Color,
) {
    val ratio = if (totalMb > 0.0) (usedMb / totalMb).toFloat().coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = StudioColors.TextPrimary)
            Text(
                text = if (totalMb > 0.0) {
                    String.format(Locale.US, "%.0f / %.0f MB (%.0f%%)", usedMb, totalMb, ratio * 100f)
                } else {
                    "—"
                },
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            color = accent,
            trackColor = StudioColors.ElevatedSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// =========================================================================
// Section 5: Development Changelog Tab
// =========================================================================

@Composable
internal fun DevChangelogTab(
    devState: DeveloperPanelUiState,
    diagnosticEntries: List<AppDiagnosticEntry>,
    isEn: Boolean,
    onAddChangelog: (String, DevChangelogCategory, DevChangelogStatus, String, String) -> Unit,
    onDeleteChangelog: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var changeDate by rememberSaveable { mutableStateOf(todayDevDateString()) }
    var category by remember { mutableStateOf(DevChangelogCategory.IMPLEMENTED) }
    var status by remember { mutableStateOf(DevChangelogStatus.DONE) }
    var filterCategory by remember { mutableStateOf<DevChangelogCategory?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Add Changelog Card
        DevSectionCard(
            title = if (isEn) "Add Development Log Entry" else "إضافة بند إلى سجل التطوير",
            accent = StudioColors.Cyan,
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(if (isEn) "Title" else "عنوان الإضافة أو المشكلة") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                modifier = Modifier.fillMaxWidth().testTag("dev_changelog_title_input"),
            )

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DevChangelogCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(changelogCategoryLabel(cat, isEn)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = StudioColors.Cyan,
                        ),
                    )
                }
            }

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DevChangelogStatus.entries.forEach { st ->
                    FilterChip(
                        selected = status == st,
                        onClick = { status = st },
                        label = { Text(st.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StudioColors.Violet.copy(alpha = 0.22f),
                            selectedLabelColor = StudioColors.VioletBright,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = changeDate,
                onValueChange = { changeDate = it },
                label = { Text(if (isEn) "Date (YYYY-MM-DD)" else "التاريخ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text(if (isEn) "Technical details" else "التفاصيل التقنية") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onAddChangelog(title.trim(), category, status, details.trim(), changeDate.trim())
                        title = ""
                        details = ""
                    }
                },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Cyan, contentColor = StudioColors.BgTop),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("dev_changelog_add_btn"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isEn) "Save Entry" else "حفظ في سجل التطوير")
            }
        }

        // Filter row + Firestore changelog entries
        DevSectionCard(
            title = if (isEn) "Development Changelog (${devState.changelogEntries.size})" else "سجل التغييرات والحالة (${devState.changelogEntries.size})",
            accent = StudioColors.Violet,
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filterCategory == null,
                    onClick = { filterCategory = null },
                    label = { Text(if (isEn) "All" else "الكل") },
                )
                DevChangelogCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = filterCategory == cat,
                        onClick = { filterCategory = cat },
                        label = { Text(changelogCategoryLabel(cat, isEn)) },
                    )
                }
            }

            val visible = devState.changelogEntries.filter { filterCategory == null || it.category == filterCategory }
            if (visible.isEmpty()) {
                Text(
                    text = if (isEn) "No recorded changelog entries yet." else "لا توجد سجلات تطوير محفوظة بعد في قاعدة البيانات.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            } else {
                visible.forEach { item ->
                    val catAccent = when (item.category) {
                        DevChangelogCategory.IMPLEMENTED,
                        DevChangelogCategory.TEST_PASSED -> StudioColors.Emerald
                        DevChangelogCategory.UNCOMPLETED -> StudioColors.Amber
                        DevChangelogCategory.ISSUE,
                        DevChangelogCategory.TEST_FAILED -> StudioColors.Rose
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, catAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, color = StudioColors.TextPrimary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DevStatusPill(changelogCategoryLabel(item.category, isEn), catAccent)
                                    DevStatusPill(item.status.name, StudioColors.Cyan)
                                }
                                if (item.details.isNotBlank()) {
                                    Text(item.details, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                                }
                                Text(
                                    text = "${if (isEn) "Date" else "التاريخ"}: ${item.changeDate.ifBlank { formatDevDateTime(item.createdAtMs) }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextMuted,
                                )
                            }
                            IconButton(
                                onClick = { onDeleteChangelog(item.docId) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = StudioColors.Rose)
                            }
                        }
                    }
                }
            }
        }

        // Real Live Summary of Executed Tests & Local Issues
        val passedRuns = devState.testRuns.count { it.passed }
        val failedRuns = devState.testRuns.count { !it.passed }
        DevSectionCard(
            title = if (isEn) "Live Automated Tests & Captured Issues" else "ملخص الاختبارات الحية والمشكلات المفتوحة",
            accent = StudioColors.Emerald,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevMetricTile(if (isEn) "Passed Tests" else "اختبارات ناجحة", "$passedRuns", StudioColors.Emerald, Modifier.weight(1f))
                DevMetricTile(if (isEn) "Failed Tests" else "اختبارات فاشلة", "$failedRuns", StudioColors.Rose, Modifier.weight(1f))
                DevMetricTile(if (isEn) "Open Diagnostics" else "مشكلات مسجلة", "${diagnosticEntries.size}", StudioColors.Amber, Modifier.weight(1f))
            }
            diagnosticEntries.take(5).forEach { diag ->
                Text(
                    text = "• [${AppDiagnosticLog.formatTimestamp(diag.timestampMs)}] ${if (isEn) diag.titleEn else diag.titleAr}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun changelogCategoryLabel(cat: DevChangelogCategory, isEn: Boolean): String = when (cat) {
    DevChangelogCategory.IMPLEMENTED -> if (isEn) "Implemented" else "مُنفّذ"
    DevChangelogCategory.UNCOMPLETED -> if (isEn) "Uncompleted" else "غير مكتمل"
    DevChangelogCategory.ISSUE -> if (isEn) "Issue" else "مشكلة تحتاج إصلاح"
    DevChangelogCategory.TEST_PASSED -> if (isEn) "Test Passed" else "اختبار ناجح"
    DevChangelogCategory.TEST_FAILED -> if (isEn) "Test Failed" else "اختبار فاشل"
}

// =========================================================================
// Section 6: Live Diagnostic Tests Runner Tab
// =========================================================================

@Composable
internal fun TestRunnerTab(
    devState: DeveloperPanelUiState,
    isEn: Boolean,
    onSelectEngine: (String) -> Unit,
    onRunTest: (AdminTestType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DevSectionCard(
            title = if (isEn) "Target Engine for Live Tests" else "المحرك المستهدف للاختبارات الحية",
            subtitle = if (isEn) {
                "Each test executes real synthesis, export, or server checks and persists the honest outcome."
            } else {
                "كل زر ينفذ فحصاً حقيقياً (توليد أو تصدير أو اتصال) ويحفظ النتيجة الصادقة بالزمن الفعلي."
            },
            accent = StudioColors.Cyan,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = devState.selectedTestEngineId == "voxcpm2",
                    onClick = { onSelectEngine("voxcpm2") },
                    label = { Text("VoxCPM2", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("dev_test_engine_voxcpm2"),
                )
                FilterChip(
                    selected = devState.selectedTestEngineId == "audar_tts_flash",
                    onClick = { onSelectEngine("audar_tts_flash") },
                    label = { Text("Audar-Flash", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("dev_test_engine_audar_flash"),
                )
                FilterChip(
                    selected = devState.selectedTestEngineId == "edge_neural_free",
                    onClick = { onSelectEngine("edge_neural_free") },
                    label = { Text("Edge Free", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).testTag("dev_test_engine_edge_neural"),
                )
            }

            AdminTestType.entries.forEach { testType ->
                val isRunning = devState.activeTestRunning == testType
                OutlinedButton(
                    onClick = { onRunTest(testType) },
                    enabled = devState.activeTestRunning == null,
                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("dev_run_test_${testType.name.lowercase()}"),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(color = StudioColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isEn) "Executing..." else "جاري تنفيذ الاختبار...")
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = StudioColors.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isEn) testType.titleEn else testType.titleAr,
                            color = StudioColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        DevSectionCard(
            title = if (isEn) "Executed Test History (${devState.testRuns.size})" else "نتائج الاختبارات الحية المسجلة (${devState.testRuns.size})",
            accent = StudioColors.Emerald,
        ) {
            if (devState.testRuns.isEmpty()) {
                Text(
                    text = if (isEn) "No test runs executed yet." else "لم يتم تشغيل أي اختبار فعلي بعد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )
            } else {
                devState.testRuns.forEach { run ->
                    val accent = if (run.passed) StudioColors.Emerald else StudioColors.Rose
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (run.passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = accent,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "${if (isEn) run.testType.titleEn else run.testType.titleAr} • ${run.engineId}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = accent,
                                )
                                Text(run.summary, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextPrimary)
                                if (run.errorDetail.isNotBlank()) {
                                    Text(run.errorDetail, style = MaterialTheme.typography.labelSmall, color = StudioColors.RoseBright)
                                }
                                Text(
                                    text = "${run.latencyMs} ms • ${formatDevDateTime(run.createdAtMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
