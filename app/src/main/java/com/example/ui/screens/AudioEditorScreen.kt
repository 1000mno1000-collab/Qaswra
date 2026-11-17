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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.export.ExportAudioFormat
import com.example.audio.tts.TtsDialect
import com.example.data.StudioProject
import com.example.ui.components.EditorDualTrackMixCard
import com.example.ui.components.EditorFadeControlsCard
import com.example.ui.components.EditorTtsGeneratorCard
import com.example.ui.components.EditorVoiceTransformerCard
import com.example.ui.components.EmptyAudioNotice
import com.example.ui.components.RangeWaveformEditorCanvas
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.formatDurationMs
import com.example.ui.components.rememberFormatExportDialog
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
import com.example.viewmodel.TtsStudioUiState
import java.util.Locale

@Composable
fun AudioEditorScreen(
    uiState: StudioUiState,
    ttsState: TtsStudioUiState,
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
    onApplyFadeIn: (Float) -> Unit,
    onApplyFadeOut: (Float) -> Unit,
    onImportMixBackgroundUri: (Uri) -> Unit,
    onClearMixBackgroundTrack: () -> Unit,
    onApplyDualTrackMix: (Float, Float) -> Unit,
    onMergeExternalUri: (Uri) -> Unit,
    onMergeSavedProject: (StudioProject) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveEdits: () -> Unit,
    onExportWithFormat: (Uri, ExportAudioFormat, String) -> Unit,
    onPreviewTtsSpeech: (String, TtsDialect, Float, Float) -> Unit,
    onStopTtsPreview: () -> Unit,
    onGenerateTtsToStudio: (String, TtsDialect, Float, Float, Boolean) -> Unit,
    onApplyVoiceTransform: (Float, Float, Float, Float, Float, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var clipGainDb by remember { mutableFloatStateOf(0f) }
    var showMergeProjectDialog by remember { mutableStateOf(false) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }

    val exportDialog = rememberFormatExportDialog(
        initialFileName = uiState.projectName,
        onExportSelected = onExportWithFormat
    )

    val mergeFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onMergeExternalUri(uri)
        }
    }

    val mixBackgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onImportMixBackgroundUri(uri)
        }
    }

    if (!uiState.hasAudio) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EmptyAudioNotice(
                title = stringResource(R.string.no_audio_loaded),
                actionLabel = stringResource(R.string.section_record_title),
                onAction = onGoToRecording
            )
            EditorTtsGeneratorCard(
                hasAudio = false,
                isBusy = uiState.isBusy,
                ttsState = ttsState,
                onPreviewSpeech = onPreviewTtsSpeech,
                onStopPreview = onStopTtsPreview,
                onGenerateToStudio = onGenerateTtsToStudio
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                color = CyanGlow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (uiState.hasUnsavedChanges) {
                Surface(
                    color = AmberWarm.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, AmberWarm.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "غير محفوظ",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarm,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Editor Action Toolbar: Loop, Undo, Redo, Save Changes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.loopSelection,
                onClick = onToggleLoop,
                leadingIcon = {
                    Icon(Icons.Rounded.Loop, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = {
                    Text(
                        text = stringResource(R.string.loop_selection),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanGlow.copy(alpha = 0.2f),
                    selectedLabelColor = CyanGlow
                ),
                modifier = Modifier.testTag("editor_loop_chip")
            )

            OutlinedButton(
                onClick = onUndo,
                enabled = uiState.canUndoEdit,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("editor_undo_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.undo_edit),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onRedo,
                enabled = uiState.canRedoEdit,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("editor_redo_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.redo_edit),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { showSaveConfirmDialog = true },
                enabled = uiState.hasUnsavedChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldActive,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(42.dp)
                    .testTag("editor_save_button")
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "حفظ",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("editor_trim_keep_button")
            ) {
                Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.trim_keep),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onCutSelection,
                colors = ButtonDefaults.buttonColors(containerColor = CoralRecord, contentColor = TextPrimary),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("editor_cut_button")
            ) {
                Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.cut_selection),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onResetSelection,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.reset_selection),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onNormalize,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("editor_normalize_button")
            ) {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.normalize_audio),
                    color = CyanGlow,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Fade In & Fade Out Card
        EditorFadeControlsCard(
            onApplyFadeIn = onApplyFadeIn,
            onApplyFadeOut = onApplyFadeOut
        )

        // Simultaneous Dual-Track Mixing Card
        EditorDualTrackMixCard(
            backgroundName = uiState.mixBackgroundName,
            backgroundDurationMs = uiState.mixBackgroundDurationMs,
            onPickBackgroundTrack = { mixBackgroundLauncher.launch(arrayOf("audio/*")) },
            onClearBackgroundTrack = onClearMixBackgroundTrack,
            onApplyMix = onApplyDualTrackMix
        )

        // Embedded Offline Formant-Preserving Voice Character Transformer
        EditorVoiceTransformerCard(
            isBusy = uiState.isBusy,
            purifyProgress = uiState.purifyProgress,
            onApplyTransform = onApplyVoiceTransform
        )

        // Embedded Free 10-Dialect Text-to-Speech Generator
        EditorTtsGeneratorCard(
            hasAudio = uiState.hasAudio,
            isBusy = uiState.isBusy,
            ttsState = ttsState,
            onPreviewSpeech = onPreviewTtsSpeech,
            onStopPreview = onStopTtsPreview,
            onGenerateToStudio = onGenerateTtsToStudio
        )

        // Sequential Merge Clips Card
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "من ملف صوتي",
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (savedProjects.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showMergeProjectDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(
                                text = "من مشروع محفوظ",
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(
                text = "تثبيت مستوى الصوت على المقطع",
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Save / Export Final Audio File (Opens Multi-Format Selector Dialog)
        Button(
            onClick = exportDialog.open,
            colors = ButtonDefaults.buttonColors(containerColor = VioletGlow, contentColor = StudioObsidian),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("editor_save_export_audio_button")
        ) {
            Icon(Icons.Rounded.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.save_export_audio),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                        onSaveEdits()
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

    if (showMergeProjectDialog) {
        AlertDialog(
            onDismissRequest = { showMergeProjectDialog = false },
            containerColor = StudioSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("اختر مشروعاً للدمج") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedProjects.forEach { proj ->
                        Surface(
                            onClick = {
                                showMergeProjectDialog = false
                                onMergeSavedProject(proj)
                            },
                            color = StudioObsidian,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(proj.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
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
