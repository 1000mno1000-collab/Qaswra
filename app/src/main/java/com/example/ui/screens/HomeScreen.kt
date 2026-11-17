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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AuthSession
import com.example.model.EffectType
import com.example.model.StudioRoute
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.components.RealTimeWaveformCanvas
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Composable
fun HomeScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onNavigate: (StudioRoute) -> Unit,
) {
    val lang = uiState.language
    var langDialogOpen by remember { mutableStateOf(false) }

    val listState = viewModel.homeListState
    var savedIndex by rememberSaveable { mutableIntStateOf(uiState.homeScrollIndex) }
    var savedOffset by rememberSaveable { mutableIntStateOf(uiState.homeScrollOffset) }

    LaunchedEffect(Unit) {
        if (listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0 &&
            (savedIndex > 0 || savedOffset > 0)
        ) {
            listState.scrollToItem(savedIndex, savedOffset)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, off) ->
            savedIndex = idx
            savedOffset = off
            viewModel.updateHomeScrollPosition(idx, off)
        }
    }

    val activeEffectsCount = uiState.dspConfig.chain.count { it.enabled }
    val userLabel = when (val auth = uiState.authSession) {
        is AuthSession.SignedIn -> auth.displayName
        is AuthSession.Guest -> if (lang == AppLanguage.EN) "Guest" else "ضيف"
        is AuthSession.SignedOut -> ""
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero_banner") {
                // Hero Banner Card with studio aura illustration + embedded waveform canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(StudioColors.CardSurface),
                ) {
                    Image(
                        painter = painterResource(R.drawable.img_studio_aura),
                        contentDescription = t(lang, "app_name"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        StudioColors.BgTop.copy(alpha = 0.52f),
                                        StudioColors.BgTop.copy(alpha = 0.88f),
                                    ),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = t(lang, "app_name"),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = t(lang, "studio_tagline"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.Cyan,
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    onClick = {
                                        if (uiState.authSession is AuthSession.Guest) {
                                            viewModel.requestSignIn()
                                        } else {
                                            viewModel.signOut()
                                        }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = StudioColors.ElevatedSurface.copy(alpha = 0.9f),
                                    border = BorderStroke(1.dp, StudioColors.BorderBright),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("home_auth_chip"),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            text = if (uiState.authSession is AuthSession.Guest) {
                                                "$userLabel • ${t(lang, "auth_sign_in")}"
                                            } else {
                                                "$userLabel • ${t(lang, "auth_sign_out")}"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = StudioColors.TextPrimary,
                                            maxLines = 1,
                                        )
                                    }
                                }

                                LanguageBadgeButton(
                                    currentLanguage = lang,
                                    onClick = { langDialogOpen = true },
                                )
                            }
                        }

                        RealTimeWaveformCanvas(
                            bars = if (uiState.recorder.isRecording) uiState.recorder.waveformBars else uiState.playback.waveformBars,
                            isActive = uiState.playback.isPlaying || uiState.recorder.isRecording,
                            accentColor = if (uiState.recorder.isRecording) StudioColors.Rose else StudioColors.Cyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                        )
                    }
                }
            }

            item(key = "active_project_summary") {
                // Active Project Summary Card + Quick Studio Record Entry
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    border = BorderStroke(1.dp, StudioColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = t(lang, "active_project"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextMuted,
                                )
                                Text(
                                    text = uiState.activeProjectName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = formatDurationMs(uiState.durationMs),
                                    style = TelemetryTextStyle,
                                    color = StudioColors.Cyan,
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (uiState.dspConfig.bypassAll) {
                                        StudioColors.Amber.copy(alpha = 0.16f)
                                    } else {
                                        StudioColors.Emerald.copy(alpha = 0.16f)
                                    },
                                ) {
                                    Text(
                                        text = if (uiState.dspConfig.bypassAll) {
                                            t(lang, "bypass_all")
                                        } else if (lang == AppLanguage.EN) {
                                            "$activeEffectsCount DSP Active"
                                        } else {
                                            "$activeEffectsCount مؤثر مفعّل"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (uiState.dspConfig.bypassAll) StudioColors.Amber else StudioColors.Emerald,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { onNavigate(StudioRoute.Record) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Rose.copy(alpha = 0.18f),
                                contentColor = StudioColors.RoseBright,
                            ),
                            border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("home_card_record"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = t(lang, "section_record_title"),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = t(lang, "section_record_title"),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (!uiState.hasAudio) {
                item(key = "empty_audio_hint") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = t(lang, "no_audio_loaded"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextPrimary,
                            )
                        }
                    }
                }
            }

            // 1. التحرير والدمج (Editing & Mixing)
            item(key = "card_editor") {
                StudioSectionCard(
                    title = t(lang, "section_editor_title"),
                    subtitle = t(lang, "section_editor_subtitle"),
                    icon = Icons.Rounded.ContentCut,
                    accentColor = StudioColors.Amber,
                    testTag = "home_card_editor",
                    onClick = { onNavigate(StudioRoute.Editor) },
                )
            }

            // 2. تصفية الصوت وعزل الضوضاء (Noise Reduction & Voice Purifier)
            item(key = "card_purifier") {
                StudioSectionCard(
                    title = t(lang, "section_purifier_title"),
                    subtitle = t(lang, "section_purifier_subtitle"),
                    icon = Icons.Rounded.AutoFixHigh,
                    accentColor = StudioColors.Emerald,
                    badgeText = if (uiState.dspConfig.isEnabled(EffectType.NOISE_REDUCTION)) "ON" else null,
                    testTag = "home_card_purifier",
                    onClick = { onNavigate(StudioRoute.VoicePurifier) },
                )
            }

            // 3. استوديو الذكاء الصوتي (AI Audio Studio)
            item(key = "card_ai") {
                StudioSectionCard(
                    title = t(lang, "section_ai_title"),
                    subtitle = t(lang, "section_ai_subtitle"),
                    icon = Icons.Rounded.AutoAwesome,
                    accentColor = StudioColors.Violet,
                    badgeText = "AI",
                    testTag = "home_card_ai",
                    onClick = { onNavigate(StudioRoute.AiStudio) },
                )
            }

            // 4. مساحة الصدى والعمق (Reverb & Depth Space)
            item(key = "card_echo") {
                StudioSectionCard(
                    title = t(lang, "section_echo_title"),
                    subtitle = t(lang, "section_echo_subtitle"),
                    icon = Icons.Rounded.Waves,
                    accentColor = StudioColors.Cyan,
                    badgeText = if (uiState.dspConfig.isEnabled(EffectType.ECHO_REVERB)) "3D" else null,
                    testTag = "home_card_echo",
                    onClick = { onNavigate(StudioRoute.EchoSpace) },
                )
            }

            // 5. سلسلة المؤثرات (Effects Chain)
            item(key = "card_chain") {
                StudioSectionCard(
                    title = t(lang, "section_chain_title"),
                    subtitle = t(lang, "section_chain_subtitle"),
                    icon = Icons.Rounded.Tune,
                    accentColor = StudioColors.Violet,
                    badgeText = "$activeEffectsCount / ${uiState.dspConfig.chain.size}",
                    testTag = "home_card_chain",
                    onClick = { onNavigate(StudioRoute.EffectsChain) },
                )
            }

            // 6. محوّل الشخصيات الصوتية (Voice Transformer)
            item(key = "card_voice_transformer") {
                StudioSectionCard(
                    title = t(lang, "section_voice_transformer_title"),
                    subtitle = t(lang, "section_voice_transformer_subtitle"),
                    icon = Icons.Rounded.SpatialAudioOff,
                    accentColor = StudioColors.Violet,
                    badgeText = t(lang, "voice_transformer_badge"),
                    testTag = "home_card_voice_transformer",
                    onClick = { onNavigate(StudioRoute.VoiceTransformer) },
                )
            }

            // 7. تحويل النص إلى صوت (TTS)
            item(key = "card_tts") {
                StudioSectionCard(
                    title = t(lang, "section_tts_title"),
                    subtitle = t(lang, "section_tts_subtitle"),
                    icon = Icons.Rounded.RecordVoiceOver,
                    accentColor = StudioColors.Cyan,
                    badgeText = t(lang, "tts_badge_dialects"),
                    testTag = "home_card_tts",
                    onClick = { onNavigate(StudioRoute.TtsStudio) },
                )
            }

            // 8. القوالب الصوتية (Audio Presets)
            item(key = "card_presets") {
                StudioSectionCard(
                    title = t(lang, "section_presets_title"),
                    subtitle = t(lang, "section_presets_subtitle"),
                    icon = Icons.Rounded.GraphicEq,
                    accentColor = StudioColors.Cyan,
                    badgeText = "${uiState.allPresets.size}",
                    testTag = "home_card_presets",
                    onClick = { onNavigate(StudioRoute.Presets) },
                )
            }

            // 9. المشاريع والتصدير (Projects & Export)
            item(key = "card_projects") {
                StudioSectionCard(
                    title = t(lang, "section_projects_title"),
                    subtitle = t(lang, "section_projects_subtitle"),
                    icon = Icons.Rounded.FolderOpen,
                    accentColor = StudioColors.Emerald,
                    badgeText = if (uiState.projects.isNotEmpty()) "${uiState.projects.size}" else null,
                    testTag = "home_card_projects",
                    onClick = { onNavigate(StudioRoute.ProjectsExport) },
                )
            }

            // 10. الإعدادات واللغة (Settings & Language)
            item(key = "card_settings") {
                StudioSectionCard(
                    title = t(lang, "section_settings_title"),
                    subtitle = t(lang, "section_settings_subtitle"),
                    icon = Icons.Rounded.Settings,
                    accentColor = StudioColors.Amber,
                    badgeText = t(lang, "settings_badge"),
                    testTag = "home_card_settings",
                    onClick = { onNavigate(StudioRoute.Settings) },
                )
            }
        }
    }

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )
}
