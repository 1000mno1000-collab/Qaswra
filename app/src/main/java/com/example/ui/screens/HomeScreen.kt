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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.AppLanguage
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.AuthSessionState
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
    authSession: AuthSessionState,
    currentLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    isPlaying: Boolean,
    outputLevel: Float,
    playbackProgress: Float,
    playbackLiveSamples: FloatArray,
    onOpenSection: (WorkspaceDestination) -> Unit,
    onSignOutOrSwitchAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    val activeEffectsCount = uiState.dspConfig.chain.count { it.enabled }

    val purifierEnabled = uiState.dspConfig.isEffectEnabled(com.example.model.EffectType.NOISE_REDUCTION)

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
            destination = WorkspaceDestination.VoicePurifier,
            titleRes = R.string.section_purifier_title,
            subtitleRes = R.string.section_purifier_subtitle,
            icon = Icons.Rounded.CleaningServices,
            accent = EmeraldActive,
            badgeText = if (purifierEnabled) "نقي ${(uiState.dspConfig.noiseReduction.amount * 100).toInt()}%" else "عزل ذكي",
            tag = "section_voice_purifier"
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
            badgeText = "MP3 / WAV",
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
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.TextToSpeechStudio,
            titleRes = R.string.section_tts_title,
            subtitleRes = R.string.section_tts_subtitle,
            icon = Icons.Rounded.RecordVoiceOver,
            accent = CyanGlow,
            badgeText = stringResource(R.string.tts_badge_dialects),
            tag = "section_tts_studio"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.VoiceTransformer,
            titleRes = R.string.section_voice_transformer_title,
            subtitleRes = R.string.section_voice_transformer_subtitle,
            icon = Icons.Rounded.SpatialAudioOff,
            accent = AmberWarm,
            badgeText = stringResource(R.string.voice_transformer_badge),
            tag = "section_voice_transformer"
        ),
        HomeSectionItem(
            destination = WorkspaceDestination.Settings,
            titleRes = R.string.section_settings_title,
            subtitleRes = R.string.section_settings_subtitle,
            icon = Icons.Rounded.Settings,
            accent = CyanGlow,
            badgeText = stringResource(R.string.settings_badge),
            tag = "section_settings"
        )
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Atmospheric Hero Header + Active Project Strip
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
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
                        // Compact User Profile Badge + Sign Out / Sign In Option
                        val isSignedIn = authSession is AuthSessionState.SignedIn
                        val profileName = when (authSession) {
                            is AuthSessionState.SignedIn -> authSession.displayName
                            AuthSessionState.Guest -> "ضيف (وضع محلي)"
                            AuthSessionState.SignedOut -> "غير مسجل"
                        }
                        val profileSub = when (authSession) {
                            is AuthSessionState.SignedIn -> authSession.email.takeIf { it.isNotBlank() }
                            else -> null
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = StudioSurfaceElevated.copy(alpha = 0.85f),
                            border = BorderStroke(
                                1.dp,
                                if (isSignedIn) EmeraldActive.copy(alpha = 0.45f) else StudioBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_profile_badge")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountCircle,
                                        contentDescription = null,
                                        tint = if (isSignedIn) EmeraldActive else CyanGlow,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profileName,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (profileSub != null) {
                                            Text(
                                                text = profileSub,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                LanguageBadgeButton(
                                    currentLanguage = currentLanguage,
                                    onClick = { showLanguageDialog = true },
                                    modifier = Modifier.testTag("home_language_button")
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    onClick = onSignOutOrSwitchAccount,
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSignedIn) CoralRecord.copy(alpha = 0.16f) else CyanGlow.copy(alpha = 0.16f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSignedIn) CoralRecord.copy(alpha = 0.45f) else CyanGlow.copy(alpha = 0.45f)
                                    ),
                                    modifier = Modifier.testTag(if (isSignedIn) "home_sign_out_button" else "home_sign_in_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isSignedIn) Icons.AutoMirrored.Rounded.Logout else Icons.AutoMirrored.Rounded.Login,
                                            contentDescription = null,
                                            tint = if (isSignedIn) CoralRecord else CyanGlow,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isSignedIn) {
                                                stringResource(R.string.auth_sign_out)
                                            } else {
                                                stringResource(R.string.auth_sign_in)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSignedIn) CoralRecord else CyanGlow,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

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

                        RealTimeWaveformCanvas(
                            liveSamples = playbackLiveSamples,
                            fallbackPeaks = uiState.waveformPeaks,
                            isLive = isPlaying,
                            liveEnergy = outputLevel,
                            progress = playbackProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(70.dp)
                                .testTag("home_realtime_waveform")
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
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (uiState.hasAudio) {
                                    "المدة ${formatDurationMs(uiState.durationMs)}"
                                } else {
                                    stringResource(R.string.no_audio_loaded)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = if (uiState.hasAudio) EmeraldActive else TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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

    if (showLanguageDialog) {
        LanguageSelectorDialog(
            currentLanguage = currentLanguage,
            onSelectLanguage = onSelectLanguage,
            onDismiss = { showLanguageDialog = false }
        )
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
