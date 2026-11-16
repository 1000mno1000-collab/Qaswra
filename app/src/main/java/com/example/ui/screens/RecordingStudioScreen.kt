package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.io.RecordingState
import com.example.ui.components.RealTimeWaveformCanvas
import com.example.ui.components.formatDurationMs
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.StudioUiState

@Composable
fun RecordingStudioScreen(
    uiState: StudioUiState,
    recordingState: RecordingState,
    recordingLevel: Float,
    recordingDurationMs: Long,
    recordingWaveform: List<Float>,
    recordingLiveSamples: FloatArray,
    isPlaying: Boolean,
    outputLevel: Float,
    playbackProgress: Float,
    playbackLiveSamples: FloatArray,
    onStartOrResumeRecording: () -> Boolean,
    onPauseRecording: () -> Unit,
    onFinishRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onImportAudioUri: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            onStartOrResumeRecording()
        }
    }

    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportAudioUri(uri)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasMicPermission) {
            Surface(
                color = CoralRecord.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mic_permission_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.mic_permission_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Button(
                        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRecord),
                        modifier = Modifier.testTag("grant_mic_permission_button")
                    ) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }
        }

        // Live Acoustic Stage Card
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = StudioSurface,
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (recordingState) {
                        RecordingState.RECORDING -> "جارٍ التسجيل المباشر"
                        RecordingState.PAUSED -> "التسجيل متوقف مؤقتاً"
                        RecordingState.IDLE -> if (uiState.hasAudio) "المقطع الجاهز بالاستوديو" else "جاهز لالتقاط الصوت"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (recordingState) {
                        RecordingState.RECORDING -> CoralRecord
                        RecordingState.PAUSED -> CyanGlow
                        RecordingState.IDLE -> TextSecondary
                    }
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = formatDurationMs(
                        if (recordingState != RecordingState.IDLE) recordingDurationMs else uiState.durationMs
                    ),
                    style = MaterialTheme.typography.displaySmall,
                    color = TextPrimary
                )

                Spacer(Modifier.height(16.dp))

                val activeSamples = if (recordingState == RecordingState.RECORDING) {
                    recordingLiveSamples
                } else {
                    playbackLiveSamples
                }
                RealTimeWaveformCanvas(
                    liveSamples = activeSamples,
                    fallbackPeaks = if (recordingState != RecordingState.IDLE) recordingWaveform else uiState.waveformPeaks,
                    isLive = recordingState == RecordingState.RECORDING || isPlaying,
                    liveEnergy = if (recordingState == RecordingState.RECORDING) recordingLevel else outputLevel,
                    progress = if (recordingState == RecordingState.IDLE) playbackProgress else 1f,
                    accentPrimary = if (recordingState == RecordingState.RECORDING) CoralRecord else CyanGlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(138.dp)
                        .testTag("realtime_waveform_canvas")
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mic_level),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.width(12.dp))
                    LinearProgressIndicator(
                        progress = { (recordingLevel * 1.4f).coerceIn(0f, 1f) },
                        color = if (recordingLevel > 0.78f) CoralRecord else EmeraldActive,
                        trackColor = StudioSurfaceElevated,
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }

        // Massive Acoustic Capture Orb
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .size(156.dp),
            contentAlignment = Alignment.Center
        ) {
            if (recordingState == RecordingState.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(CoralRecord.copy(alpha = 0.22f))
                )
            }

            Surface(
                onClick = {
                    when (recordingState) {
                        RecordingState.IDLE, RecordingState.PAUSED -> {
                            if (hasMicPermission) {
                                onStartOrResumeRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        RecordingState.RECORDING -> onPauseRecording()
                    }
                },
                shape = CircleShape,
                color = StudioObsidian,
                border = BorderStroke(
                    2.5.dp,
                    Brush.linearGradient(
                        if (recordingState == RecordingState.RECORDING) {
                            listOf(CoralRecord, CyanGlow)
                        } else {
                            listOf(CyanGlow, EmeraldActive)
                        }
                    )
                ),
                modifier = Modifier
                    .size(118.dp)
                    .testTag("record_primary_orb")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    (if (recordingState == RecordingState.RECORDING) CoralRecord else CyanGlow)
                                        .copy(alpha = 0.35f),
                                    StudioSurface
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (recordingState) {
                            RecordingState.RECORDING -> Icons.Rounded.Pause
                            RecordingState.PAUSED -> Icons.Rounded.PlayArrow
                            RecordingState.IDLE -> Icons.Rounded.Mic
                        },
                        contentDescription = stringResource(R.string.record_start),
                        tint = TextPrimary,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        Text(
            text = when (recordingState) {
                RecordingState.IDLE -> stringResource(R.string.record_start)
                RecordingState.RECORDING -> stringResource(R.string.record_pause)
                RecordingState.PAUSED -> stringResource(R.string.record_resume)
            },
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        // Finish / Discard Actions when recording is active or paused
        if (recordingState != RecordingState.IDLE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onFinishRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldActive,
                        contentColor = StudioObsidian
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("record_finish_button")
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.record_finish), style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onDiscardRecording,
                    border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRecord),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("record_discard_button")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.record_discard))
                }
            }
        }

        // Zero-permission SAF Audio File Importer
        OutlinedButton(
            onClick = { audioImportLauncher.launch(arrayOf("audio/*")) },
            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("import_audio_button")
        ) {
            Icon(Icons.Rounded.AudioFile, contentDescription = null, tint = CyanGlow)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.import_audio),
                style = MaterialTheme.typography.titleMedium,
                color = CyanGlow
            )
        }
    }
}
