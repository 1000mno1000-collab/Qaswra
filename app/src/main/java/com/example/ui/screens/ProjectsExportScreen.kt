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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.StudioProject
import com.example.ui.components.formatDurationMs
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

@Composable
fun ProjectsExportScreen(
    uiState: StudioUiState,
    savedProjects: List<StudioProject>,
    onRenameProject: (String) -> Unit,
    onSaveProject: () -> Unit,
    onNewProject: () -> Unit,
    onLoadProject: (StudioProject) -> Unit,
    onDeleteProject: (StudioProject) -> Unit,
    onExportWavUri: (Uri) -> Unit,
    onShareWav: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exportWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null) {
            onExportWavUri(uri)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active Project Save / New Card
        item {
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
                            onClick = onSaveProject,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanGlow, contentColor = StudioObsidian),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("save_project_button")
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.save_project))
                        }
                        OutlinedButton(
                            onClick = onNewProject,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("new_project_button")
                        ) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.new_project))
                        }
                    }
                }
            }
        }

        // Master WAV Export & Share Card
        item {
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val safeName = uiState.projectName.ifBlank { "VoicePro_Export" }
                                exportWavLauncher.launch("$safeName.wav")
                            },
                            enabled = uiState.hasAudio && uiState.exportProgress == null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldActive,
                                contentColor = StudioObsidian
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("export_wav_button")
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_wav))
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
                            enabled = uiState.hasAudio && uiState.exportProgress == null,
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
        }

        // Saved Projects List
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.saved_projects),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }

        if (savedProjects.isEmpty()) {
            item {
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
            }
        } else {
            items(savedProjects, key = { it.id }) { proj ->
                val isCurrent = proj.id == uiState.projectId
                Surface(
                    onClick = { onLoadProject(proj) },
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
}
