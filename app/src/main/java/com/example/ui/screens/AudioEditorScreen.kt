package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.StudioProject
import com.example.ui.components.EmptyAudioNotice
import com.example.ui.components.RangeWaveformEditorCanvas
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.formatDurationMs
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.StudioUiState
import java.util.Locale

@Composable
fun AudioEditorScreen(
    uiState: StudioUiState,
    playbackProgress: Float,
    savedProjects: List<StudioProject>,
    onGoToRecording: () -> Unit,
    onRangeChange: (Float, Float) -> Unit,
    onResetSelection: () -> Unit,
    onToggleLoop: () -> Unit,
    onTrimKeep: () -> Unit,
    onCutSelection: () -> Unit,
    onNormalize: () -> Unit,
    onBakeClipGain: (Float) -> Unit,
    onMergeExternalUri: (Uri) -> Unit,
    onMergeSavedProject: (StudioProject) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var clipGainDb by remember { mutableFloatStateOf(0f) }
    var showMergeProjectDialog by remember { mutableStateOf(false) }

    val mergeFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onMergeExternalUri(uri)
        }
    }

    if (!uiState.hasAudio) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            EmptyAudioNotice(
                title = stringResource(R.string.no_audio_loaded),
                actionLabel = stringResource(R.string.section_record_title),
                onAction = onGoToRecording
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Selection Range Telemetry Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selStartMs = (uiState.durationMs * uiState.trimStart).toLong()
            val selEndMs = (uiState.durationMs * uiState.trimEnd).toLong()
            Text(
                text = "التحديد: ${formatDurationMs(selStartMs)} ← ${formatDurationMs(selEndMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = CyanGlow
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.loopSelection,
                    onClick = onToggleLoop,
                    leadingIcon = { Icon(Icons.Rounded.Loop, contentDescription = null) },
                    label = { Text(stringResource(R.string.loop_selection)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanGlow.copy(alpha = 0.2f),
                        selectedLabelColor = CyanGlow
                    ),
                    modifier = Modifier.testTag("editor_loop_chip")
                )
                if (uiState.canUndoEdit) {
                    OutlinedButton(
                        onClick = onUndo,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("editor_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.undo_edit))
                    }
                }
            }
        }

        // Interactive Waveform Trim Range Canvas
        RangeWaveformEditorCanvas(
            peaks = uiState.waveformPeaks,
            trimStart = uiState.trimStart,
            trimEnd = uiState.trimEnd,
            playbackProgress = playbackProgress,
            onRangeChange = onRangeChange,
            modifier = Modifier.testTag("editor_waveform_canvas")
        )

        // Trim & Cut Operations
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onTrimKeep,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("editor_trim_keep_button")
            ) {
                Icon(Icons.Rounded.Crop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.trim_keep))
            }

            Button(
                onClick = onCutSelection,
                colors = ButtonDefaults.buttonColors(containerColor = CoralRecord, contentColor = TextPrimary),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("editor_cut_button")
            ) {
                Icon(Icons.Rounded.ContentCut, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cut_selection))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onResetSelection,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text(stringResource(R.string.reset_selection))
            }
            OutlinedButton(
                onClick = onNormalize,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("editor_normalize_button")
            ) {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = CyanGlow)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.normalize_audio), color = CyanGlow)
            }
        }

        // Merge Clips Card
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.merge_clip),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "ألحق مقطعاً صوتياً من ملف خارجي أو من مشاريعك المحفوظة بنهاية المقطع الحالي بسلاسة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { mergeFileLauncher.launch(arrayOf("audio/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = VioletGlow, contentColor = StudioObsidian),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Rounded.LibraryAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("من ملف صوتي")
                    }
                    if (savedProjects.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showMergeProjectDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("من مشروع محفوظ")
                        }
                    }
                }
            }
        }

        // Clip Gain Bake Card
        StudioParamSlider(
            label = stringResource(R.string.clip_volume),
            value = clipGainDb,
            valueRange = -12f..12f,
            formattedValue = String.format(Locale.US, "%+.1f dB", clipGainDb),
            onValueChange = { clipGainDb = it },
            accentColor = AmberWarm
        )
        Button(
            onClick = {
                onBakeClipGain(clipGainDb)
                clipGainDb = 0f
            },
            enabled = kotlin.math.abs(clipGainDb) > 0.2f,
            colors = ButtonDefaults.buttonColors(containerColor = AmberWarm, contentColor = StudioObsidian),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("تثبيت مستوى الصوت على المقطع")
        }
    }

    if (showMergeProjectDialog) {
        AlertDialog(
            onDismissRequest = { showMergeProjectDialog = false },
            title = { Text("اختر مشروعاً للدمج") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedProjects.forEach { proj ->
                        Surface(
                            onClick = {
                                showMergeProjectDialog = false
                                onMergeSavedProject(proj)
                            },
                            color = StudioSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(proj.name, color = TextPrimary)
                                Text(formatDurationMs(proj.durationMs), color = CyanGlow)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMergeProjectDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
