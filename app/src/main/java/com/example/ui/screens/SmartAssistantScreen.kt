package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.studio.ui.lit
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.ToolInfoDialog
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Composable
fun SmartAssistantScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onNavigateToTool: (String) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Tool Guide, 1 = Online Archive SFX
    var guideQuery by remember { mutableStateOf("") }
    var soundQuery by remember { mutableStateOf("") }
    var infoOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopOnlineSoundPreview()
        }
    }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "assistant_title"),
            subtitle = t(lang, "assistant_subtitle"),
            language = lang,
            onBack = onBack,
            trailing = {
                InfoBadgeButton(
                    contentDescription = t(lang, "tool_smart_assistant"),
                    testTag = "assistant_info_button",
                    accentColor = StudioColors.Cyan,
                    onClick = { infoOpen = true },
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                0 to t(lang, "assistant_tab_guide"),
                1 to t(lang, "assistant_tab_sounds"),
            ).forEach { (idx, label) ->
                val isSel = selectedTab == idx
                val interaction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { selectedTab = idx },
                    interactionSource = interaction,
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel) StudioColors.ElevatedSurface else StudioColors.CardSurface,
                    border = BorderStroke(1.dp, if (isSel) StudioColors.Cyan else StudioColors.Border),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(interaction, MaterialTheme.colorScheme.primary, selected = isSel, cornerRadius = 14.dp)
                        .testTag("assistant_tab_$idx"),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isSel) StudioColors.Cyan else StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (selectedTab == 0) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = guideQuery,
                        onValueChange = { q ->
                            guideQuery = q
                            viewModel.searchToolGuide(q)
                        },
                        label = { Text(t(lang, "assistant_guide_hint")) },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = StudioColors.Cyan) },
                        singleLine = false,
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Cyan,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assistant_guide_input"),
                    )

                    val quickPrompts = remember(lang) {
                        if (lang == AppLanguage.EN) {
                            listOf("Remove noise", "Dragon voice", "PUBG HDR video", "Web article TTS", "Auto-ducking music")
                        } else {
                            listOf("شلون اشيل الضوضاء", "صوت تنين", "رندر ببجي", "تحويل مقال لبودكاست", "دمج موسيقى")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickPrompts.forEach { sample ->
                            val chipInteraction = remember { MutableInteractionSource() }
                            FilterChip(
                                selected = guideQuery == sample,
                                onClick = {
                                    guideQuery = sample
                                    viewModel.searchToolGuide(sample)
                                },
                                interactionSource = chipInteraction,
                                label = { Text(sample) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(chipInteraction, StudioColors.Cyan, cornerRadius = 10.dp),
                            )
                        }
                    }

                    uiState.assistantGuideResults.forEach { guide ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = StudioColors.BgMid,
                            border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = guide.toolName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioColors.Cyan,
                                )
                                Text(
                                    text = guide.matchReason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                )
                                guide.howToUseSteps.forEach { step ->
                                    Text(
                                        text = step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.TextPrimary,
                                    )
                                }
                                val openInt = remember { MutableInteractionSource() }
                                Button(
                                    onClick = { onNavigateToTool(guide.toolId) },
                                    interactionSource = openInt,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = StudioColors.Cyan,
                                        contentColor = StudioColors.BgTop,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .lit(openInt, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp)
                                        .testTag("assistant_open_${guide.toolId}"),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t(lang, "assistant_open_tool"))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Archive.org categorized internet sound search & import
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = soundQuery,
                            onValueChange = { soundQuery = it },
                            label = { Text(t(lang, "assistant_search_sounds_hint")) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioColors.Violet,
                                unfocusedBorderColor = StudioColors.Border,
                                focusedTextColor = StudioColors.TextPrimary,
                                unfocusedTextColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("archive_search_input"),
                        )

                        val searchInt = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.searchInternetSounds(soundQuery) },
                            enabled = !uiState.isSearchingOnlineSounds,
                            interactionSource = searchInt,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Violet,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 52.dp)
                                .lit(searchInt, StudioColors.Violet, cornerRadius = 14.dp)
                                .testTag("archive_search_button"),
                        ) {
                            if (uiState.isSearchingOnlineSounds) {
                                CircularProgressIndicator(
                                    color = StudioColors.BgTop,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(Icons.Rounded.Search, contentDescription = t(lang, "assistant_search_btn"), modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    val topicChips = remember(lang) {
                        if (lang == AppLanguage.EN) {
                            listOf("Horror", "Rain & Nature", "Cinematic Music", "Gaming Action")
                        } else {
                            listOf("رعب", "مطر وطبيعة", "موسيقى سينمائية", "أكشن وألعاب")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        topicChips.forEach { topic ->
                            val tInt = remember { MutableInteractionSource() }
                            FilterChip(
                                selected = soundQuery == topic,
                                onClick = {
                                    soundQuery = topic
                                    viewModel.searchInternetSounds(topic)
                                },
                                interactionSource = tInt,
                                label = { Text(topic) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(tInt, StudioColors.Violet, cornerRadius = 10.dp),
                            )
                        }
                    }

                    uiState.onlineSoundCategories.forEach { (categoryName, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = StudioColors.Violet, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "$categoryName (${items.size})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = StudioColors.Violet,
                                )
                            }

                            items.forEach { item ->
                                val isPreviewing = uiState.previewingOnlineUrl == item.streamUrl
                                val isLoadingItem = uiState.loadingOnlineSoundId == item.identifier
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.BgMid,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isPreviewing) StudioColors.Cyan else StudioColors.Border,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            val playInt = remember { MutableInteractionSource() }
                                            IconButton(
                                                onClick = { viewModel.toggleOnlineSoundPreview(item) },
                                                interactionSource = playInt,
                                                colors = IconButtonDefaults.iconButtonColors(
                                                    containerColor = if (isPreviewing) StudioColors.Amber.copy(alpha = 0.2f) else StudioColors.ElevatedSurface,
                                                    contentColor = if (isPreviewing) StudioColors.Amber else StudioColors.Cyan,
                                                ),
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .lit(playInt, StudioColors.Cyan, cornerRadius = 24.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (isPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                                    contentDescription = item.title,
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = StudioColors.TextPrimary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = item.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioColors.TextSecondary,
                                                )
                                            }
                                        }

                                        val addInt = remember { MutableInteractionSource() }
                                        Button(
                                            onClick = { viewModel.importOnlineSoundToStudio(item) },
                                            enabled = !uiState.isBusy,
                                            interactionSource = addInt,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = StudioColors.Emerald,
                                                contentColor = StudioColors.BgTop,
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 48.dp)
                                                .lit(addInt, StudioColors.Emerald, cornerRadius = 12.dp),
                                        ) {
                                            if (isLoadingItem) {
                                                CircularProgressIndicator(
                                                    color = StudioColors.BgTop,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            } else {
                                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(t(lang, "assistant_add_studio"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ToolInfoDialog(
        open = infoOpen,
        title = t(lang, "tool_smart_assistant"),
        description = t(lang, "tool_smart_assistant_info"),
        language = lang,
        onDismiss = { infoOpen = false },
    )
}
