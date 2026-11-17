package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.audio.io.RecordingState
import com.example.data.AudioRecordingEntity
import com.example.ui.components.RealTimeWaveformCanvas
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
import com.example.viewmodel.StudioUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    modifier: Modifier = Modifier,
    savedRecordings: List<AudioRecordingEntity> = emptyList(),
    onLoadRecording: (AudioRecordingEntity) -> Unit = {}
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
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onImportAudioUri(uri)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rawActiveEnergy = if (recordingState == RecordingState.RECORDING) recordingLevel else outputLevel
    val smoothEnergy by animateFloatAsState(
        targetValue = rawActiveEnergy.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "smooth_energy"
    )
    val orbAmplitudeScale by animateFloatAsState(
        targetValue = if (recordingState == RecordingState.RECORDING) {
            1.04f + (recordingLevel * 0.22f).coerceIn(0f, 0.24f)
        } else {
            1f
        },
        animationSpec = tween(durationMillis = 100),
        label = "orb_amp"
    )

    val activeTimerMs = if (recordingState != RecordingState.IDLE) recordingDurationMs else uiState.durationMs
    val tenths = ((activeTimerMs % 1000L) / 100L).toInt().coerceIn(0, 9)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 110.dp),
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

        // Live Acoustic Stage Dashboard Card
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = StudioSurface,
            border = BorderStroke(
                width = 1.dp,
                color = when (recordingState) {
                    RecordingState.RECORDING -> CoralRecord.copy(alpha = 0.7f)
                    RecordingState.PAUSED -> AmberWarm.copy(alpha = 0.6f)
                    RecordingState.IDLE -> StudioBorder
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Pill Badge
                Surface(
                    color = when (recordingState) {
                        RecordingState.RECORDING -> CoralRecord.copy(alpha = 0.16f)
                        RecordingState.PAUSED -> AmberWarm.copy(alpha = 0.16f)
                        RecordingState.IDLE -> CyanGlow.copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        when (recordingState) {
                            RecordingState.RECORDING -> CoralRecord.copy(alpha = 0.6f)
                            RecordingState.PAUSED -> AmberWarm.copy(alpha = 0.55f)
                            RecordingState.IDLE -> CyanGlow.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FiberManualRecord,
                            contentDescription = null,
                            tint = when (recordingState) {
                                RecordingState.RECORDING -> CoralRecord
                                RecordingState.PAUSED -> AmberWarm
                                RecordingState.IDLE -> if (isPlaying) EmeraldActive else CyanGlow
                            },
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = when (recordingState) {
                                RecordingState.RECORDING -> "جارٍ التسجيل المباشر"
                                RecordingState.PAUSED -> "التسجيل متوقف مؤقتاً"
                                RecordingState.IDLE -> when {
                                    isPlaying -> "تشغيل ومراقبة الموجة"
                                    uiState.hasAudio -> "المقطع الجاهز بالاستوديو"
                                    else -> "جاهز لالتقاط الصوت"
                                }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = when (recordingState) {
                                RecordingState.RECORDING -> CoralRecord
                                RecordingState.PAUSED -> AmberWarm
                                RecordingState.IDLE -> if (isPlaying) EmeraldActive else CyanGlow
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Live Digital Timer Display
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.testTag("recording_timer_display")
                ) {
                    Text(
                        text = formatDurationMs(activeTimerMs),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = ".$tenths",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (recordingState == RecordingState.RECORDING) CoralRecord else CyanGlow,
                        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                val activeSamples = if (recordingState == RecordingState.RECORDING) {
                    recordingLiveSamples
                } else {
                    playbackLiveSamples
                }
                RealTimeWaveformCanvas(
                    liveSamples = activeSamples,
                    fallbackPeaks = if (recordingState != RecordingState.IDLE) recordingWaveform else uiState.waveformPeaks,
                    isLive = recordingState == RecordingState.RECORDING || isPlaying,
                    liveEnergy = smoothEnergy,
                    progress = if (recordingState == RecordingState.IDLE) playbackProgress else 1f,
                    accentPrimary = if (recordingState == RecordingState.RECORDING) CoralRecord else CyanGlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(StudioObsidian.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
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
                        progress = { (smoothEnergy * 1.4f).coerceIn(0f, 1f) },
                        color = when {
                            smoothEnergy > 0.78f -> CoralRecord
                            smoothEnergy > 0.45f -> AmberWarm
                            else -> EmeraldActive
                        },
                        trackColor = StudioSurfaceElevated,
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${(smoothEnergy * 100).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        // Massive Acoustic Capture Orb reacting smoothly to live amplitude
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .size(164.dp),
            contentAlignment = Alignment.Center
        ) {
            if (recordingState == RecordingState.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .scale(pulseScale * orbAmplitudeScale)
                        .clip(CircleShape)
                        .background(CoralRecord.copy(alpha = 0.20f))
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
                    .size(120.dp)
                    .scale(orbAmplitudeScale)
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

        // Prominent Start / Stop Recording Action Button
        Button(
            onClick = {
                when (recordingState) {
                    RecordingState.IDLE -> {
                        if (hasMicPermission) {
                            onStartOrResumeRecording()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    RecordingState.RECORDING, RecordingState.PAUSED -> {
                        onFinishRecording()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recordingState == RecordingState.IDLE) CyanGlow else CoralRecord,
                contentColor = StudioObsidian
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("record_start_stop_button")
        ) {
            Icon(
                imageVector = if (recordingState == RecordingState.IDLE) Icons.Rounded.Mic else Icons.Rounded.Stop,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (recordingState == RecordingState.IDLE) {
                    stringResource(R.string.record_start)
                } else {
                    "إيقاف وحفظ التسجيل"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("record_finish_button")
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.record_finish),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onDiscardRecording,
                    border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRecord),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("record_discard_button")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.record_discard),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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

        // Indexed Recordings Quick Access List
        if (savedRecordings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "التسجيلات والملفات المفهرسة (${savedRecordings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.US) }
            savedRecordings.take(5).forEach { rec ->
                Surface(
                    onClick = { onLoadRecording(rec) },
                    shape = RoundedCornerShape(16.dp),
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = if (rec.sourceType == "IMPORTED") CyanGlow else CoralRecord
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rec.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatDurationMs(rec.durationMs)} • ${dateFmt.format(Date(rec.timestamp))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (rec.sourceType == "IMPORTED") "مستورد" else "تسجيل",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanGlow,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
