package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.formatDurationMs
import com.example.data.RecentEditEntry
import com.example.diagnostics.AppDiagnosticLog
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AudioPickerTarget
import com.example.model.AuthSession
import com.example.model.EffectType
import com.example.model.StudioRoute
import com.example.studio.ui.lit
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.components.MiniAiRobotCompanion
import com.example.ui.components.RealTimeWaveformCanvas
import com.example.ui.components.ToolInfoDialog
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Immutable
private data class HomeToolTile(
    val id: String,
    val titleKey: String,
    val icon: ImageVector,
    val accent: Color,
    val route: StudioRoute,
    val infoKey: String? = null,
)

@Immutable
private data class HomeToolFamily(
    val id: String,
    val titleKey: String,
    val icon: ImageVector,
    val accent: Color,
    val tools: List<HomeToolTile>,
)

@Composable
fun HomeScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    listState: LazyListState,
    onNavigate: (StudioRoute) -> Unit,
    onOpenNotifications: () -> Unit = { onNavigate(StudioRoute.Notifications) },
    onRestoreRecentEdit: (RecentEditEntry) -> Unit = { entry ->
        viewModel.restoreRecentEditSession(entry) { route -> onNavigate(route) }
    },
) {
    val lang = uiState.language
    var langDialogOpen by remember { mutableStateOf(false) }
    var activeToolInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

    val activeEffectsCount = uiState.dspConfig.chain.count { it.enabled }
    val userLabel = when (val auth = uiState.authSession) {
        is AuthSession.SignedIn -> auth.displayName
        is AuthSession.Guest -> if (lang == AppLanguage.EN) "Guest" else "ضيف"
        is AuthSession.SignedOut -> ""
    }

    val families = remember {
        listOf(
            HomeToolFamily(
                id = "ai_voice",
                titleKey = "family_ai_voice",
                icon = Icons.Rounded.RecordVoiceOver,
                accent = StudioColors.Cyan,
                tools = listOf(
                    HomeToolTile(
                        id = "tts_human",
                        titleKey = "tool_tts_human",
                        icon = Icons.Rounded.RecordVoiceOver,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.TtsStudio,
                    ),
                    HomeToolTile(
                        id = "voice_morph",
                        titleKey = "tool_voice_morph",
                        icon = Icons.Rounded.SpatialAudioOff,
                        accent = StudioColors.Violet,
                        route = StudioRoute.VoiceTransformer,
                    ),
                    HomeToolTile(
                        id = "smart_engineer",
                        titleKey = "tool_smart_engineer",
                        icon = Icons.Rounded.AutoFixHigh,
                        accent = StudioColors.Emerald,
                        route = StudioRoute.VoicePurifier,
                        infoKey = "tool_smart_engineer_info",
                    ),
                    HomeToolTile(
                        id = "speech_to_text",
                        titleKey = "tool_speech_to_text",
                        icon = Icons.Rounded.ClosedCaption,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.SpeechToText,
                        infoKey = "speech_to_text_info",
                    ),
                    HomeToolTile(
                        id = "smart_assistant",
                        titleKey = "tool_smart_assistant",
                        icon = Icons.Rounded.AutoAwesome,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.SmartAssistant,
                        infoKey = "tool_smart_assistant_info",
                    ),
                ),
            ),
            HomeToolFamily(
                id = "editing",
                titleKey = "family_editing",
                icon = Icons.Rounded.ContentCut,
                accent = StudioColors.Amber,
                tools = listOf(
                    HomeToolTile(
                        id = "trim_edit",
                        titleKey = "tool_trim_edit",
                        icon = Icons.Rounded.ContentCut,
                        accent = StudioColors.Amber,
                        route = StudioRoute.Editor,
                    ),
                    HomeToolTile(
                        id = "smart_music_mixer",
                        titleKey = "tool_smart_music_mixer",
                        icon = Icons.Rounded.MusicNote,
                        accent = StudioColors.Amber,
                        route = StudioRoute.SmartMusicMixer,
                        infoKey = "smart_mixer_info",
                    ),
                    HomeToolTile(
                        id = "merge_audio",
                        titleKey = "tool_merge_audio",
                        icon = Icons.Rounded.Layers,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.AudioMerge,
                    ),
                    HomeToolTile(
                        id = "split_tracks",
                        titleKey = "tool_split_tracks",
                        icon = Icons.AutoMirrored.Rounded.CallSplit,
                        accent = StudioColors.Violet,
                        route = StudioRoute.AudioSplit,
                    ),
                ),
            ),
            HomeToolFamily(
                id = "effects",
                titleKey = "family_effects",
                icon = Icons.Rounded.Waves,
                accent = StudioColors.Violet,
                tools = listOf(
                    HomeToolTile(
                        id = "pro_presets_echo",
                        titleKey = "tool_pro_presets_echo",
                        icon = Icons.Rounded.Waves,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.Presets,
                        infoKey = "tool_pro_presets_echo_info",
                    ),
                    HomeToolTile(
                        id = "equalizer",
                        titleKey = "tool_equalizer",
                        icon = Icons.Rounded.Tune,
                        accent = StudioColors.Violet,
                        route = StudioRoute.EffectDetail(EffectType.EQUALIZER),
                    ),
                    HomeToolTile(
                        id = "pitch_speed",
                        titleKey = "tool_pitch_speed",
                        icon = Icons.Rounded.Speed,
                        accent = StudioColors.Amber,
                        route = StudioRoute.PitchAndSpeed,
                    ),
                    HomeToolTile(
                        id = "reverse_audio",
                        titleKey = "tool_reverse_audio",
                        icon = Icons.Rounded.Undo,
                        accent = StudioColors.RoseBright,
                        route = StudioRoute.ReverseAudio,
                    ),
                    HomeToolTile(
                        id = "stereo_surround",
                        titleKey = "tool_stereo_surround",
                        icon = Icons.Rounded.SurroundSound,
                        accent = StudioColors.Emerald,
                        route = StudioRoute.SurroundStereo,
                    ),
                    HomeToolTile(
                        id = "generate_silence",
                        titleKey = "tool_generate_silence",
                        icon = Icons.Rounded.HourglassEmpty,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.GenerateSilence,
                    ),
                ),
            ),
            HomeToolFamily(
                id = "quick_tools",
                titleKey = "family_quick_tools",
                icon = Icons.Rounded.GraphicEq,
                accent = StudioColors.Emerald,
                tools = listOf(
                    HomeToolTile(
                        id = "format_convert",
                        titleKey = "tool_format_convert",
                        icon = Icons.Rounded.SwapHoriz,
                        accent = StudioColors.Cyan,
                        route = StudioRoute.FormatConverter,
                    ),
                    HomeToolTile(
                        id = "extract_video",
                        titleKey = "tool_extract_video",
                        icon = Icons.Rounded.Movie,
                        accent = StudioColors.Amber,
                        route = StudioRoute.ExtractVideoAudio,
                    ),
                    HomeToolTile(
                        id = "reels_audio_templates",
                        titleKey = "tool_reels_audio_templates",
                        icon = Icons.Rounded.Podcasts,
                        accent = StudioColors.Amber,
                        route = StudioRoute.ReelsAudioTemplates,
                        infoKey = "tool_reels_audio_templates_info",
                    ),
                    HomeToolTile(
                        id = "compress_audio",
                        titleKey = "tool_compress_audio",
                        icon = Icons.Rounded.Compress,
                        accent = StudioColors.Emerald,
                        route = StudioRoute.CompressAudio,
                    ),
                    HomeToolTile(
                        id = "live_record",
                        titleKey = "tool_live_record",
                        icon = Icons.Rounded.Mic,
                        accent = StudioColors.RoseBright,
                        route = StudioRoute.Record,
                    ),
                    HomeToolTile(
                        id = "ringtone_maker",
                        titleKey = "tool_ringtone_maker",
                        icon = Icons.Rounded.NotificationsActive,
                        accent = StudioColors.Amber,
                        route = StudioRoute.RingtoneMaker,
                        infoKey = "tool_ringtone_maker_info",
                    ),
                ),
            ),
        )
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "hero_banner", contentType = "hero_banner") {
                val heroBgBrush = remember {
                    Brush.linearGradient(
                        colors = listOf(
                            StudioColors.BgTop,
                            StudioColors.CardSurface,
                            Color(0xFF151E3B),
                        ),
                    )
                }
                val heroRadialBrush = remember {
                    Brush.radialGradient(
                        colors = listOf(
                            StudioColors.Cyan.copy(alpha = 0.16f),
                            StudioColors.Violet.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 168.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(heroBgBrush),
                ) {
                    // Ambient glowing cyan-violet studio backdrop + watermark of the primary logo silhouette
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(heroRadialBrush),
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_splash_vector),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(StudioColors.Cyan.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 18.dp)
                            .size(132.dp)
                            .alpha(0.85f),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Row 1: Unconstrained Identity (Logo + single-line unclipped LTR "Sonix ✦" + full tagline + Notification Bell)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val logoBorderBrush = remember {
                                Brush.linearGradient(
                                    colors = listOf(StudioColors.Cyan, StudioColors.Violet),
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFFFFFFE),
                                border = BorderStroke(
                                    width = 1.5.dp,
                                    brush = logoBorderBrush,
                                ),
                                shadowElevation = 8.dp,
                                modifier = Modifier.size(54.dp),
                            ) {
                                Image(
                                    bitmap = ImageBitmap.imageResource(R.drawable.sonix_primary_logo),
                                    contentDescription = t(lang, "app_name"),
                                    contentScale = ContentScale.Crop,
                                    filterQuality = FilterQuality.High,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(15.dp)),
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "Sonix ✦",
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            textDirection = TextDirection.Ltr,
                                            letterSpacing = 0.4.sp,
                                        ),
                                        color = StudioColors.TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Text(
                                    text = t(lang, "studio_tagline"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.Cyan,
                                )
                            }

                            val bellInteraction = remember { MutableInteractionSource() }
                            val badgeCount = uiState.unreadDiagnosticCount
                            val totalCount = uiState.diagnosticEntries.size
                            IconButton(
                                onClick = onOpenNotifications,
                                interactionSource = bellInteraction,
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = StudioColors.ElevatedSurface.copy(alpha = 0.92f),
                                    contentColor = if (badgeCount > 0) StudioColors.Amber else StudioColors.Cyan,
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .lit(bellInteraction, StudioColors.Cyan, cornerRadius = 24.dp)
                                    .testTag("home_notifications_button"),
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (badgeCount > 0 || totalCount > 0) {
                                            Badge(
                                                containerColor = if (badgeCount > 0) StudioColors.RoseBright else StudioColors.Amber,
                                                contentColor = StudioColors.BgTop,
                                            ) {
                                                Text(
                                                    text = (if (badgeCount > 0) badgeCount else totalCount).coerceAtMost(99).toString(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 10.sp,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Notifications,
                                        contentDescription = t(lang, "notifications_title"),
                                    )
                                }
                            }
                        }

                        // Row 2: Balanced utility bar (Guest / Sign-In chip + Language badge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val authChipInteraction = remember { MutableInteractionSource() }
                            Surface(
                                onClick = {
                                    if (uiState.authSession is AuthSession.Guest) {
                                        viewModel.requestSignIn()
                                    } else {
                                        viewModel.signOut()
                                    }
                                },
                                interactionSource = authChipInteraction,
                                shape = RoundedCornerShape(999.dp),
                                color = StudioColors.ElevatedSurface.copy(alpha = 0.92f),
                                border = BorderStroke(1.dp, StudioColors.BorderBright),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(authChipInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
                                    .testTag("home_auth_chip"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = StudioColors.Cyan,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (uiState.authSession is AuthSession.Guest) {
                                            "$userLabel • ${t(lang, "auth_sign_in")}"
                                        } else {
                                            "$userLabel • ${t(lang, "auth_sign_out")}"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            LanguageBadgeButton(
                                currentLanguage = lang,
                                onClick = { langDialogOpen = true },
                            )
                        }

                        // Row 3: Live Audio Waveform Visualizer
                        RealTimeWaveformCanvas(
                            bars = if (uiState.recorder.isRecording) uiState.recorder.waveformBars else uiState.playback.waveformBars,
                            isActive = uiState.playback.isPlaying || uiState.recorder.isRecording,
                            accentColor = if (uiState.recorder.isRecording) StudioColors.Rose else StudioColors.Cyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        )
                    }
                }
            }

            item(key = "featured_assistant_robot", contentType = "featured_assistant_robot") {
                val assistantCardInteraction = remember { MutableInteractionSource() }
                val signedInName = (uiState.authSession as? AuthSession.SignedIn)?.displayName
                Box(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        onClick = { onNavigate(StudioRoute.SmartAssistant) },
                        interactionSource = assistantCardInteraction,
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioColors.ElevatedSurface),
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 46.dp)
                            .lit(assistantCardInteraction, StudioColors.Cyan, cornerRadius = 20.dp)
                            .testTag("home_smart_assistant_card"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioColors.Cyan.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = t(lang, "tool_smart_assistant"),
                                        tint = StudioColors.Cyan,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = t(lang, "tool_smart_assistant"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = t(lang, "assistant_smart_card_subtitle"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = StudioColors.Cyan.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
                            ) {
                                Text(
                                    text = "AI ✦",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = StudioColors.Cyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    MiniAiRobotCompanion(
                        userDisplayName = signedInName,
                        language = lang,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }

            if (uiState.recentEdits.isNotEmpty()) {
                item(key = "recent_edits_strip", contentType = "recent_edits_strip") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = t(lang, "recent_edits_title"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.TextPrimary,
                            )
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(
                                items = uiState.recentEdits,
                                key = { it.id },
                            ) { entry ->
                                val recentInteraction = remember { MutableInteractionSource() }
                                Surface(
                                    onClick = { onRestoreRecentEdit(entry) },
                                    interactionSource = recentInteraction,
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.CardSurface,
                                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .widthIn(min = 168.dp, max = 232.dp)
                                        .lit(recentInteraction, StudioColors.Cyan, cornerRadius = 14.dp)
                                        .testTag("recent_edit_${entry.id}"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = if (lang == AppLanguage.EN) entry.titleEn else entry.titleAr,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = StudioColors.Cyan,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = entry.audioName,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = StudioColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${if (lang == AppLanguage.EN) entry.summaryEn else entry.summaryAr} • ${AppDiagnosticLog.formatTimestamp(entry.timestampMs).takeLast(8)}",
                                            style = TelemetryTextStyle.copy(fontSize = 10.sp),
                                            color = StudioColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "active_project_summary", contentType = "active_project_summary") {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                    border = BorderStroke(1.dp, StudioColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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

                            Spacer(Modifier.width(8.dp))

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = formatDurationMs(uiState.durationMs),
                                    style = TelemetryTextStyle,
                                    color = StudioColors.Cyan,
                                )
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
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val importInteraction = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { onNavigate(StudioRoute.AudioPicker(AudioPickerTarget.IMPORT_MAIN)) },
                                interactionSource = importInteraction,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(importInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                                    .testTag("home_import_library_button"),
                            ) {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t(lang, "import_audio"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val recordInteraction = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { onNavigate(StudioRoute.Record) },
                                interactionSource = recordInteraction,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(recordInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                                    .testTag("home_card_record"),
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null, tint = StudioColors.RoseBright, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t(lang, "tool_live_record"),
                                    color = StudioColors.RoseBright,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 4 Families ("كل أب وأبناؤه - شبكة مربعات 3 أعمدة")
            families.forEach { family ->
                item(key = "family_header_${family.id}", contentType = "family_header") {
                    FamilyParentHeader(
                        title = t(lang, family.titleKey),
                        icon = family.icon,
                        accent = family.accent,
                        toolCount = family.tools.size,
                    )
                }

                val rows = family.tools.chunked(3)
                rows.forEachIndexed { rowIdx, rowTools ->
                    item(key = "family_${family.id}_row_$rowIdx", contentType = "family_tools_row") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowTools.forEach { tile ->
                                ToolSquareTile(
                                    title = t(lang, tile.titleKey),
                                    icon = tile.icon,
                                    accent = tile.accent,
                                    testTag = "home_tile_${tile.id}",
                                    hasInfo = tile.infoKey != null,
                                    onInfoClick = {
                                        tile.infoKey?.let { key ->
                                            activeToolInfo = t(lang, tile.titleKey) to t(lang, key)
                                        }
                                    },
                                    onClick = { onNavigate(tile.route) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowTools.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Secondary studio utilities strip (AI Tools, 3D Echo Pad, Full Effects Chain, Settings)
            item(key = "studio_extras_strip", contentType = "extras_strip") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExtraQuickChip(
                            label = t(lang, "section_ai_title"),
                            icon = Icons.Rounded.AutoAwesome,
                            accent = StudioColors.Violet,
                            testTag = "home_card_ai",
                            onClick = { onNavigate(StudioRoute.AiStudio) },
                            modifier = Modifier.weight(1f),
                        )
                        ExtraQuickChip(
                            label = t(lang, "section_echo_title"),
                            icon = Icons.Rounded.Waves,
                            accent = StudioColors.Cyan,
                            testTag = "home_card_echo",
                            onClick = { onNavigate(StudioRoute.EchoSpace) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExtraQuickChip(
                            label = t(lang, "section_chain_title"),
                            icon = Icons.Rounded.Tune,
                            accent = StudioColors.Emerald,
                            testTag = "home_card_chain",
                            onClick = { onNavigate(StudioRoute.EffectsChain) },
                            modifier = Modifier.weight(1f),
                        )
                        ExtraQuickChip(
                            label = t(lang, "section_settings_title"),
                            icon = Icons.Rounded.Settings,
                            accent = StudioColors.Amber,
                            testTag = "home_card_settings",
                            onClick = { onNavigate(StudioRoute.Settings) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item(key = "home_bottom_spacer", contentType = "spacer") {
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )

    activeToolInfo?.let { (infoTitle, infoDesc) ->
        ToolInfoDialog(
            open = true,
            title = infoTitle,
            description = infoDesc,
            language = lang,
            onDismiss = { activeToolInfo = null },
        )
    }
}

@Composable
private fun FamilyParentHeader(
    title: String,
    icon: ImageVector,
    accent: Color,
    toolCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.16f),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = accent.copy(alpha = 0.14f),
            ) {
                Text(
                    text = "$toolCount",
                    style = TelemetryTextStyle.copy(fontSize = 11.sp),
                    color = accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = accent.copy(alpha = 0.25f), thickness = 1.dp)
    }
}

@Composable
private fun ToolSquareTile(
    title: String,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    hasInfo: Boolean = false,
    onInfoClick: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileInteraction = remember { MutableInteractionSource() }
    val tileGradient = remember(accent) {
        Brush.verticalGradient(
            colors = listOf(
                accent.copy(alpha = 0.14f),
                StudioColors.CardSurface,
                StudioColors.ElevatedSurface.copy(alpha = 0.85f),
            ),
        )
    }
    Surface(
        onClick = onClick,
        interactionSource = tileInteraction,
        shape = RoundedCornerShape(16.dp),
        color = StudioColors.CardSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        modifier = modifier
            .aspectRatio(1f)
            .defaultMinSize(minHeight = 48.dp)
            .lit(tileInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp)
            .testTag(testTag),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tileGradient)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 14.sp,
                    ),
                    color = StudioColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hasInfo) {
                val infoInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = onInfoClick,
                    interactionSource = infoInteraction,
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .lit(infoInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 13.dp)
                        .testTag("${testTag}_info"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "!",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraQuickChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipInteraction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = chipInteraction,
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.ElevatedSurface,
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .lit(chipInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = StudioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
