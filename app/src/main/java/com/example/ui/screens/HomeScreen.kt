package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.AnimatedAcousticRibbon
import com.example.ui.components.formatDurationMs
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.WorkspaceDestination

private data class HomeSectionItem(
    val destination: WorkspaceDestination,
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
    val accent: Color,
    val badgeText: String?,
    val tag: String
)

@Composable
fun HomeScreen(
    uiState: StudioUiState,
    isPlaying: Boolean,
    outputLevel: Float,
    playbackProgress: Float,
    onOpenSection: (WorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeEffectsCount = uiState.dspConfig.chain.count { it.enabled }

    val sections = listOf(
        HomeSectionItem(
            destination = WorkspaceDestination.RecordingStudio,
            titleRes = R.string.section_record_title,
            subtitleRes = R.string.section_record_subtitle,
            icon = Icons.Rounded.Mic,
            accent = CoralRecord,
            badgeText = if (uiState.hasAudio) formatDurationMs(uiState.durationMs) else "جاهز",
            tag = "section_recording"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.EchoReverbSpace,
            titleRes = R.string.section_echo_title,
            subtitleRes = R.string.section_echo_subtitle,
            icon = Icons.Rounded.BlurOn,
            accent = CyanGlow,
            badgeText = "${(uiState.dspConfig.echoReverb.wetDryMix * 100).toInt()}% مزج",
            tag = "section_echo_reverb"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.EffectsChain,
            titleRes = R.string.section_chain_title,
            subtitleRes = R.string.section_chain_subtitle,
            icon = Icons.Rounded.Tune,
            accent = VioletGlow,
            badgeText = "$activeEffectsCount نشط",
            tag = "section_effects_chain"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.AudioEditor,
            titleRes = R.string.section_editor_title,
            subtitleRes = R.string.section_editor_subtitle,
            icon = Icons.Rounded.ContentCut,
            accent = AmberWarm,
            badgeText = if (uiState.hasAudio) "تعديل" else "فارغ",
            tag = "section_audio_editor"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.Presets,
            titleRes = R.string.section_presets_title,
            subtitleRes = R.string.section_presets_subtitle,
            icon = Icons.Rounded.AutoAwesome,
            accent = EmeraldActive,
            badgeText = "٧+ قوالب",
            tag = "section_presets"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.ProjectsExport,
            titleRes = R.string.section_projects_title,
            subtitleRes = R.string.section_projects_subtitle,
            icon = Icons.Rounded.FolderSpecial,
            accent = CyanGlow,
            badgeText = "WAV",
            tag = "section_projects_export"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.AiStudio,
            titleRes = R.string.section_ai_title,
            subtitleRes = R.string.section_ai_subtitle,
            icon = Icons.Rounded.AutoAwesome,
            accent = VioletGlow,
            badgeText = "AI",
            tag = "section_ai_studio"
        )
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Atmospheric Hero Header + Active Project Strip
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_studio_aura),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.32f)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        StudioObsidian.copy(alpha = 0.4f),
                                        StudioObsidian.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.studio_tagline),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyanGlow
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = StudioSurfaceElevated.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.4f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = CyanGlow,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .size(26.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        AnimatedAcousticRibbon(
                            peaks = uiState.waveformPeaks,
                            isActive = isPlaying,
                            liveEnergy = outputLevel,
                            progress = playbackProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.projectName,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (uiState.hasAudio) {
                                    "المدة ${formatDurationMs(uiState.durationMs)}"
                                } else {
                                    stringResource(R.string.no_audio_loaded)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (uiState.hasAudio) EmeraldActive else TextMuted
                            )
                        }
                    }
                }
            }
        }

        // Main Focus Workspace Section Cards
        items(sections.size) { index ->
            val item = sections[index]
            FocusSectionCard(
                item = item,
                onClick = { onOpenSection(item.destination) }
            )
        }
    }
}

@Composable
private fun FocusSectionCard(
    item: HomeSectionItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(item.tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            item.accent.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(item.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.accent,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(item.subtitleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            if (item.badgeText != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = item.accent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = item.badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = item.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
