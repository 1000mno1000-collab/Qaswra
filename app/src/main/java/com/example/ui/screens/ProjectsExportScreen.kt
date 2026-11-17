package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.export.ExportAudioFormat
import com.example.audio.export.Mp3ExportState
import com.example.data.AudioRecordingEntity
import com.example.data.StudioProject
import com.example.ui.components.formatDurationMs
import com.example.ui.components.rememberFormatExportDialog
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.StudioUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsExportScreen(
    uiState: StudioUiState,
    mp3ExportState: Mp3ExportState,
    savedProjects: List<StudioProject>,
    onRenameProject: (String) -> Unit,
    onSaveProject: () -> Unit,
    onNewProject: () -> Unit,
    onLoadProject: (StudioProject) -> Unit,
    onDeleteProject: (StudioProject) -> Unit,
    onExportWavUri: (Uri) -> Unit,
    onShareWav: ((Uri) -> Unit) -> Unit,
    onExportMp3Uri: (Uri) -> Unit,
    onShareMp3: () -> Unit,
    onExportWithFormat: (Uri, ExportAudioFormat, String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    savedRecordings: List<AudioRecordingEntity> = emptyList(),
    onLoadRecording: (AudioRecordingEntity) -> Unit = {},
    onDeleteRecording: (AudioRecordingEntity) -> Unit = {},
    onToggleNormalizeBeforeExport: () -> Unit = {}
) {
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }
    var pendingDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val formatExportDialog = rememberFormatExportDialog(
        initialFileName = uiState.projectName,
        onExportSelected = onExportWithFormat
    )

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null) {
            onExportWavUri(uri)
        }
    }

    val exportMp3Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri ->
        if (uri != null) {
            onExportMp3Uri(uri)
        }
    }

    val isMp3Busy = mp3ExportState is Mp3ExportState.Exporting
    val isAnyExportBusy = uiState.exportProgress != null || isMp3Busy

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active Project Save / New Card
        Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.active_project),
                        style = MaterialTheme.typography.titleMedium,
                        color = CyanGlow
                    )
                    OutlinedTextField(
                        value = uiState.projectName,
                        onValueChange = onRenameProject,
                        label = { Text("اسم المشروع") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("project_name_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showSaveConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanGlow, contentColor = StudioObsidian),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("save_project_button")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.save_project),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (uiState.hasUnsavedChanges) {
                                    pendingDiscardAction = onNewProject
                                } else {
                                    onNewProject()
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("new_project_button")
                        ) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.new_project),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

        // Master WAV Export & Share Card
        Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "التصدير والمشاركة الاحترافية",
                        style = MaterialTheme.typography.titleMedium,
                        color = VioletGlow
                    )
                    Text(
                        text = if (uiState.hasAudio) {
                            "يتم تطبيق كامل سلسلة المؤثرات والصدى والماسترينغ قبل حفظ الملف النهائي."
                        } else {
                            "قم بتسجيل أو استيراد مقطع صوتي أولاً لتفعيله للتصدير."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    // Auto-Normalize Before Export Toggle
                    Surface(
                        color = StudioSurfaceElevated.copy(alpha = 0.65f),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.normalizeBeforeExport) EmeraldActive.copy(alpha = 0.55f) else StudioBorder
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = if (uiState.normalizeBeforeExport) EmeraldActive else TextSecondary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "تطبيع الصوت التلقائي قبل التصدير (Normalize)",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "ضبط مستوى الصوت تلقائياً إلى -0.5 dBFS لأفضل توازن دون تشويه",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = uiState.normalizeBeforeExport,
                                onCheckedChange = { onToggleNormalizeBeforeExport() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StudioObsidian,
                                    checkedTrackColor = EmeraldActive
                                ),
                                modifier = Modifier.testTag("normalize_before_export_switch")
                            )
                        }
                    }

                    if (uiState.exportProgress != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.exporting_progress),
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldActive
                            )
                            LinearProgressIndicator(
                                progress = { uiState.exportProgress },
                                color = EmeraldActive,
                                trackColor = StudioSurfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    if (mp3ExportState is Mp3ExportState.Exporting) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${mp3ExportState.stageLabel} (${(mp3ExportState.progress * 100).toInt()}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VioletGlow
                            )
                            LinearProgressIndicator(
                                progress = { mp3ExportState.progress },
                                color = VioletGlow,
                                trackColor = StudioSurfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    // Primary Multi-Format Save / Export Audio button (MP3, WAV, M4A, OGG, FLAC)
                    Button(
                        onClick = formatExportDialog.open,
                        enabled = uiState.hasAudio && !isAnyExportBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanGlow,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("projects_save_export_audio_button")
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${stringResource(R.string.save_export_audio)} (MP3 / WAV / M4A / OGG / FLAC)",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // MP3 Export & Share (Background Service + Foreground Notification)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val safeName = uiState.projectName.ifBlank { "VoicePro_Export" }
                                exportMp3Launcher.launch("$safeName.mp3")
                            },
                            enabled = uiState.hasAudio && !isAnyExportBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VioletGlow,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("export_mp3_button")
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.export_mp3),
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = onShareMp3,
                            enabled = uiState.hasAudio && !isAnyExportBusy,
                            border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VioletGlow),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("share_mp3_button")
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share_mp3))
                        }
                    }

                    // Master WAV Export & Share
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val safeName = uiState.projectName.ifBlank { "VoicePro_Export" }
                                exportWavLauncher.launch("$safeName.wav")
                            },
                            enabled = uiState.hasAudio && !isAnyExportBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldActive,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("export_wav_button")
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.export_wav),
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onShareWav { shareUri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "مشاركة الملف الصوتي")
                                    )
                                }
                            },
                            enabled = uiState.hasAudio && !isAnyExportBusy,
                            border = BorderStroke(1.dp, AmberWarm.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarm),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("share_wav_button")
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share_audio))
                        }
                    }
                }
            }

        // Saved Projects List
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.saved_projects),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        if (savedProjects.isEmpty()) {
            Surface(
                color = StudioSurface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "لا توجد مشاريع محفوظة بعد. اضغط على «حفظ المشروع» لحفظ عملك الحالي.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            savedProjects.forEach { proj ->
                key(proj.id) {
                    val isCurrent = proj.id == uiState.projectId
                    Surface(
                        onClick = {
                            if (uiState.hasUnsavedChanges) {
                                pendingDiscardAction = { onLoadProject(proj) }
                            } else {
                                onLoadProject(proj)
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = StudioSurface,
                        border = BorderStroke(1.dp, if (isCurrent) CyanGlow else StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = if (isCurrent) CyanGlow else TextSecondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(proj.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(
                                    text = if (proj.durationMs > 0) {
                                        "المدة: ${formatDurationMs(proj.durationMs)} • ${proj.config.chain.count { it.enabled }} مؤثرات"
                                    } else {
                                        "إعدادات بدون ملف صوتي"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            IconButton(onClick = { onDeleteProject(proj) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = CoralRecord
                                )
                            }
                        }
                    }
                }
            }
        }

        // Indexed Audio Recordings & Imported Files (Room Metadata)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "التسجيلات والملفات الصوتية المفهرسة",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        if (savedRecordings.isEmpty()) {
            Surface(
                color = StudioSurface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "سيتم فهرسة كل تسجيل جديد أو ملف مستورد هنا تلقائياً لسهولة الوصول.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            savedRecordings.forEach { rec ->
                key("rec_${rec.id}") {
                    Surface(
                        onClick = {
                            if (uiState.hasUnsavedChanges) {
                                pendingDiscardAction = { onLoadRecording(rec) }
                            } else {
                                onLoadRecording(rec)
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = StudioSurface,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (rec.sourceType == "IMPORTED") CyanGlow else CoralRecord
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rec.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${if (rec.sourceType == "IMPORTED") "مستورد" else "تسجيل"} • ${formatDurationMs(rec.durationMs)} • ${dateFmt.format(Date(rec.timestamp))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteRecording(rec) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = CoralRecord
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSaveConfirmDialog = false },
            containerColor = StudioSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(R.string.confirm_save_changes_title)) },
            text = {
                Text("سيتم حفظ التعديلات والمؤثرات الحالية بشكل دائم في المشروع وقاعدة البيانات.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveConfirmDialog = false
                        onSaveProject()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian)
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val discardAction = pendingDiscardAction
    if (discardAction != null) {
        AlertDialog(
            onDismissRequest = { pendingDiscardAction = null },
            containerColor = StudioSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(R.string.confirm_save_changes_title)) },
            text = {
                Text("لديك تعديلات غير محفوظة حالياً. هل تريد حفظها قبل المتابعة أم تجاهلها؟")
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingDiscardAction = null
                            discardAction()
                        }
                    ) {
                        Text("تجاهل", color = CoralRecord)
                    }
                    Button(
                        onClick = {
                            onSaveProject()
                            pendingDiscardAction = null
                            discardAction()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian)
                    ) {
                        Text("حفظ")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscardAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
