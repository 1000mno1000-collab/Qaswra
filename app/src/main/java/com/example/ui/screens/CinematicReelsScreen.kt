package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.formatDurationMs
import com.example.audio.video.CinematicRenderParams
import com.example.audio.video.CinematicReshadePreset
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.studio.ui.lit
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioParamSlider
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.ToolInfoDialog
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlin.math.roundToInt

private data class ReelsColorSwatch(
    val id: String,
    val labelAr: String,
    val labelEn: String,
    val hex: String,
    val color: Color,
)

private val REELS_WAVE_SWATCHES = listOf(
    ReelsColorSwatch("cyan", "نيون سماوي", "Neon Cyan", "0x00E5FF", StudioColors.Cyan),
    ReelsColorSwatch("violet", "بنفسجي فاخر", "Royal Violet", "0xA855F7", StudioColors.Violet),
    ReelsColorSwatch("emerald", "زمردي حيوي", "Emerald", "0x10B981", StudioColors.Emerald),
    ReelsColorSwatch("amber", "ذهبي دافئ", "Warm Gold", "0xF59E0B", StudioColors.Amber),
    ReelsColorSwatch("rose", "قرمزي ناري", "Neon Rose", "0xF43F5E", StudioColors.RoseBright),
)

@Composable
fun CinematicReelsScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onOpenAudioPicker: () -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = ReShade Video, 1 = Reels Audiogram
    var infoOpen by remember { mutableStateOf(false) }

    // ReShade state (Zero-State sliders by default)
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<CinematicReshadePreset?>(null) }
    var contrastBoost by remember { mutableFloatStateOf(0f) }
    var vibranceBoost by remember { mutableFloatStateOf(0f) }
    var sharpnessAmount by remember { mutableFloatStateOf(0f) }
    var warmthShift by remember { mutableFloatStateOf(0f) }
    var vignetteAmount by remember { mutableFloatStateOf(0f) }
    var cinemaOutputName by remember { mutableStateOf("Sonix_Cinema_HDR") }

    // Reels state
    var reelsTitle by remember { mutableStateOf(uiState.activeProjectName.ifBlank { "Sonix ✦ Studio" }) }
    var selectedSwatch by remember { mutableStateOf(REELS_WAVE_SWATCHES.first()) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = resolvePickedVideoName(context, uri)
            cinemaOutputName = selectedVideoName.substringBeforeLast('.').ifBlank { "Sonix_Cinema" } + "_HDR"
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "cinematic_title"),
            subtitle = t(lang, "cinematic_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                InfoBadgeButton(
                    contentDescription = t(lang, "tool_cinematic_video_reels"),
                    testTag = "cinematic_info_button",
                    accentColor = StudioColors.Amber,
                    onClick = { infoOpen = true },
                )
            },
        )

        // Segmented tab selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                0 to t(lang, "cinematic_tab_reshade"),
                1 to t(lang, "cinematic_tab_reels"),
            ).forEach { (idx, label) ->
                val isSel = selectedTab == idx
                val interaction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { selectedTab = idx },
                    interactionSource = interaction,
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel) StudioColors.ElevatedSurface else StudioColors.CardSurface,
                    border = BorderStroke(1.dp, if (isSel) StudioColors.Amber else StudioColors.Border),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(interaction, MaterialTheme.colorScheme.primary, selected = isSel, cornerRadius = 14.dp)
                        .testTag("cinematic_tab_$idx"),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isSel) StudioColors.Amber else StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (selectedTab == 0) {
            // ReShade Cinematic 60/90/120 FPS Renderer
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(1.dp, StudioColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val pickInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { videoPickerLauncher.launch(arrayOf("video/*")) },
                        interactionSource = pickInteraction,
                        border = BorderStroke(1.dp, StudioColors.Amber),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Amber),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(pickInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("cinematic_pick_video_button"),
                    ) {
                        Icon(Icons.Rounded.VideoFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (selectedVideoName.isNotBlank()) selectedVideoName else t(lang, "cinematic_pick_video"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (lang == AppLanguage.EN) "ReShade Presets" else "قوالب الألوان والوضوح (ReShade)",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioColors.TextPrimary,
                        )
                        val resetInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = {
                                selectedPreset = null
                                contrastBoost = 0f
                                vibranceBoost = 0f
                                sharpnessAmount = 0f
                                warmthShift = 0f
                                vignetteAmount = 0f
                            },
                            interactionSource = resetInteraction,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(resetInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 10.dp)
                                .testTag("cinematic_reset_zero"),
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t(lang, "reset_zero"))
                        }
                    }

                    CinematicReshadePreset.entries.forEach { preset ->
                        val isSel = selectedPreset == preset
                        val pInteraction = remember { MutableInteractionSource() }
                        Surface(
                            onClick = {
                                selectedPreset = preset
                                contrastBoost = preset.contrastBoost
                                vibranceBoost = preset.vibranceBoost
                                sharpnessAmount = preset.sharpnessAmount
                                warmthShift = preset.warmthShift
                                vignetteAmount = preset.vignetteAmount
                            },
                            interactionSource = pInteraction,
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSel) StudioColors.ElevatedSurface else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (isSel) StudioColors.Amber else StudioColors.Border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .lit(pInteraction, MaterialTheme.colorScheme.primary, selected = isSel, cornerRadius = 14.dp)
                                .testTag("cinematic_preset_${preset.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = if (lang == AppLanguage.EN) preset.titleEn else preset.titleAr,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSel) StudioColors.Amber else StudioColors.TextPrimary,
                                    )
                                }
                                if (isSel) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "HDR Contrast" else "تباين HDR السينمائي",
                        value = contrastBoost,
                        min = -0.3f,
                        max = 0.6f,
                        formattedValue = "${(contrastBoost * 100f).roundToInt()}%",
                        accentColor = StudioColors.Amber,
                        testTag = "cinematic_contrast_slider",
                        onValueChange = { contrastBoost = it },
                    )
                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "Color Vibrance" else "تشبع وحيوية الألوان",
                        value = vibranceBoost,
                        min = -0.3f,
                        max = 0.7f,
                        formattedValue = "${(vibranceBoost * 100f).roundToInt()}%",
                        accentColor = StudioColors.Cyan,
                        testTag = "cinematic_vibrance_slider",
                        onValueChange = { vibranceBoost = it },
                    )
                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "LumaSharpen Detail" else "وضوح التفاصيل (LumaSharpen)",
                        value = sharpnessAmount,
                        min = 0f,
                        max = 0.9f,
                        formattedValue = "${(sharpnessAmount * 100f).roundToInt()}%",
                        accentColor = StudioColors.Emerald,
                        testTag = "cinematic_sharpness_slider",
                        onValueChange = { sharpnessAmount = it },
                    )
                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "Warmth / Teal-Gold" else "دفء الألوان السينمائي",
                        value = warmthShift,
                        min = -0.4f,
                        max = 0.4f,
                        formattedValue = "${(warmthShift * 100f).roundToInt()}%",
                        accentColor = StudioColors.Violet,
                        testTag = "cinematic_warmth_slider",
                        onValueChange = { warmthShift = it },
                    )
                    StudioParamSlider(
                        label = if (lang == AppLanguage.EN) "Cinema Vignette" else "تظليل الحواف السينمائي",
                        value = vignetteAmount,
                        min = 0f,
                        max = 0.6f,
                        formattedValue = "${(vignetteAmount * 100f).roundToInt()}%",
                        accentColor = StudioColors.RoseBright,
                        testTag = "cinematic_vignette_slider",
                        onValueChange = { vignetteAmount = it },
                    )

                    OutlinedTextField(
                        value = cinemaOutputName,
                        onValueChange = { cinemaOutputName = it },
                        singleLine = true,
                        label = { Text(if (lang == AppLanguage.EN) "Output Video Name" else "اسم الفيديو المصدّر") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Amber,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    uiState.videoRenderProgress?.let { prog ->
                        LinearProgressIndicator(
                            progress = { prog.coerceIn(0f, 1f) },
                            color = StudioColors.Amber,
                            trackColor = StudioColors.BgTop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                        )
                    }

                    val approveCinemaInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            val uri = selectedVideoUri ?: return@Button
                            viewModel.renderCinematicReshadeVideo(
                                sourceUri = uri,
                                params = CinematicRenderParams(
                                    contrastBoost = contrastBoost,
                                    vibranceBoost = vibranceBoost,
                                    sharpnessAmount = sharpnessAmount,
                                    warmthShift = warmthShift,
                                    vignetteAmount = vignetteAmount,
                                ),
                                outputName = cinemaOutputName,
                                shareAfter = false,
                            )
                        },
                        enabled = selectedVideoUri != null && !uiState.isBusy,
                        interactionSource = approveCinemaInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Amber,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .lit(approveCinemaInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("cinematic_approve_button"),
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "approve_and_start_processing"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            // Reels 1080x1920 Animated Waveform Creator
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(1.dp, StudioColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioColors.BgMid,
                        border = BorderStroke(1.dp, StudioColors.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (uiState.hasAudio) uiState.activeProjectName else t(lang, "no_audio_loaded"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "1080x1920 • 60 FPS • 320kbps AAC • ${formatDurationMs(uiState.durationMs)}",
                                    style = TelemetryTextStyle,
                                    color = StudioColors.Cyan,
                                )
                            }
                            if (!uiState.hasAudio) {
                                val pickAudioInt = remember { MutableInteractionSource() }
                                OutlinedButton(
                                    onClick = onOpenAudioPicker,
                                    interactionSource = pickAudioInt,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .lit(pickAudioInt, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp),
                                ) {
                                    Text(t(lang, "import_audio"))
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = reelsTitle,
                        onValueChange = { reelsTitle = it },
                        singleLine = true,
                        label = { Text(t(lang, "reels_title_hint")) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Cyan,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reels_title_input"),
                    )

                    Text(t(lang, "reels_wave_color"), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        REELS_WAVE_SWATCHES.forEach { swatch ->
                            val isSel = selectedSwatch.id == swatch.id
                            val swInt = remember { MutableInteractionSource() }
                            Surface(
                                onClick = { selectedSwatch = swatch },
                                interactionSource = swInt,
                                shape = RoundedCornerShape(12.dp),
                                color = swatch.color.copy(alpha = if (isSel) 0.25f else 0.1f),
                                border = BorderStroke(if (isSel) 2.dp else 1.dp, swatch.color),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(swInt, swatch.color, selected = isSel, cornerRadius = 12.dp)
                                    .testTag("reels_color_${swatch.id}"),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(swatch.color),
                                    )
                                }
                            }
                        }
                    }

                    uiState.videoRenderProgress?.let { prog ->
                        LinearProgressIndicator(
                            progress = { prog.coerceIn(0f, 1f) },
                            color = StudioColors.Cyan,
                            trackColor = StudioColors.BgTop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                        )
                    }

                    val approveReelsInt = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            viewModel.createReelsAudiogramFromStudio(
                                waveColorHex = selectedSwatch.hex,
                                bgColorHex = "0x0D1117",
                                titleText = reelsTitle,
                                shareAfter = false,
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        interactionSource = approveReelsInt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .lit(approveReelsInt, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                            .testTag("reels_approve_button"),
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t(lang, "approve_and_start_processing"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (!uiState.lastRenderedVideoPath.isNullOrBlank()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.ElevatedSurface),
                border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val saveInt = remember { MutableInteractionSource() }
                    Button(
                        onClick = { viewModel.exportLastRenderedVideo(shareAfter = false) },
                        interactionSource = saveInt,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Emerald,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(saveInt, StudioColors.Emerald, cornerRadius = 12.dp),
                    ) {
                        Text(t(lang, "cinematic_save_mp4"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val shareInt = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = { viewModel.exportLastRenderedVideo(shareAfter = true) },
                        interactionSource = shareInt,
                        border = BorderStroke(1.dp, StudioColors.Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .lit(shareInt, StudioColors.Cyan, cornerRadius = 12.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "cinematic_share_mp4"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    ToolInfoDialog(
        open = infoOpen,
        title = t(lang, "tool_cinematic_video_reels"),
        description = t(lang, "tool_cinematic_video_reels_info"),
        language = lang,
        onDismiss = { infoOpen = false },
    )
}

private fun resolvePickedVideoName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "video.mp4"
}
