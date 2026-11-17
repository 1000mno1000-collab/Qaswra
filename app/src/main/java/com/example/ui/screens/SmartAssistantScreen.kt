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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.example.model.AssistantChatMessage
import com.example.model.OnlineSearchCategory
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = AI Engineer Chat, 1 = Online Archive Library
    var chatInput by remember { mutableStateOf("") }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedTab == 0 && uiState.assistantChatMessages.isNotEmpty()) {
                        val clearInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { viewModel.clearAssistantChat() },
                            interactionSource = clearInt,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(clearInt, StudioColors.Amber, cornerRadius = 24.dp)
                                .testTag("assistant_clear_chat_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = if (lang == AppLanguage.EN) "Clear Chat" else "مسح المحادثة",
                                tint = StudioColors.Amber,
                            )
                        }
                    }
                    InfoBadgeButton(
                        contentDescription = t(lang, "tool_smart_assistant"),
                        testTag = "assistant_info_button",
                        accentColor = StudioColors.Cyan,
                        onClick = { infoOpen = true },
                    )
                }
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
            // Multi-Turn Smart AI Audio Engineer Chat (100% Online Pollinations AI + Compact Action Chip)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = StudioColors.Cyan, modifier = Modifier.size(18.dp))
                            Text(
                                text = t(lang, "assistant_ai_reply_title"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.TextPrimary,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = StudioColors.Cyan.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = t(lang, "assistant_cloud_badge"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.Cyan,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    val quickPrompts = remember(lang) {
                        if (lang == AppLanguage.EN) {
                            listOf("Remove fan noise", "Dragon boss voice", "Reels voice template", "Auto-duck music", "Fix muddy sound")
                        } else {
                            listOf("شلون اشيل الضوضاء", "ابي صوت تنين", "قالب صوت للريلز", "دمج موسيقى ذكي", "الصوت مكتوم شوي")
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
                                selected = false,
                                onClick = {
                                    viewModel.sendAssistantChatMessage(sample)
                                },
                                interactionSource = chipInteraction,
                                label = { Text(sample) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .lit(chipInteraction, StudioColors.Cyan, cornerRadius = 10.dp),
                            )
                        }
                    }

                    uiState.assistantGuideOfflineError?.let { offlineMsg ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.Amber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("assistant_guide_offline_banner"),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = StudioColors.Amber)
                                Text(
                                    text = offlineMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextPrimary,
                                )
                            }
                        }
                    }

                    // Conversation Stream
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (uiState.assistantChatMessages.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = StudioColors.BgMid,
                                border = BorderStroke(1.dp, StudioColors.Border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = if (lang == AppLanguage.EN) {
                                            "Ask me anything about audio engineering, dialect FX, noise cleanup, or finding the exact Sonix ✦ studio tool."
                                        } else {
                                            "اسألني بأي لهجة (عراقي، خليجي، مصري، فصحى) عن أي مشكلة بالصوت أو التأثير اللي تريده، وراح أجاوبك وأعطيك اختصار مباشر للأداة المناسبة!"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioColors.TextSecondary,
                                    )
                                }
                            }
                        } else {
                            uiState.assistantChatMessages.forEach { msg ->
                                ChatBubbleRow(
                                    message = msg,
                                    language = lang,
                                    onNavigateToTool = onNavigateToTool,
                                )
                            }
                        }

                        if (uiState.isLoadingAiGuide) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = StudioColors.Cyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (lang == AppLanguage.EN) "Thinking..." else "جاري التحليل...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.Cyan,
                                )
                            }
                        }
                    }

                    // Bottom Chat Input Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = { Text(t(lang, "assistant_chat_placeholder"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = false,
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioColors.Cyan,
                                unfocusedBorderColor = StudioColors.Border,
                                focusedTextColor = StudioColors.TextPrimary,
                                unfocusedTextColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("assistant_guide_input"),
                        )

                        val sendInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                val textToSend = chatInput.trim()
                                if (textToSend.isNotEmpty() && !uiState.isLoadingAiGuide) {
                                    chatInput = ""
                                    viewModel.sendAssistantChatMessage(textToSend)
                                }
                            },
                            enabled = chatInput.isNotBlank() && !uiState.isLoadingAiGuide,
                            interactionSource = sendInt,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = StudioColors.Cyan,
                                contentColor = StudioColors.BgTop,
                                disabledContainerColor = StudioColors.ElevatedSurface,
                                disabledContentColor = StudioColors.TextMuted,
                            ),
                            modifier = Modifier
                                .size(52.dp)
                                .lit(sendInt, StudioColors.Cyan, cornerRadius = 26.dp)
                                .testTag("assistant_guide_search_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = t(lang, "assistant_ask_ai_btn"),
                            )
                        }
                    }
                }
            }
        } else {
            // 2-Tab Categorized Online Audio & SFX Library (Archive.org)
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
                    // 2 Sub-tabs: Voices & Clips vs Sound Effects (SFX)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val voicesSel = uiState.onlineSearchCategory == OnlineSearchCategory.VOICES_CLIPS
                        val voicesInt = remember { MutableInteractionSource() }
                        Surface(
                            onClick = { viewModel.setOnlineSearchCategory(OnlineSearchCategory.VOICES_CLIPS) },
                            interactionSource = voicesInt,
                            shape = RoundedCornerShape(12.dp),
                            color = if (voicesSel) StudioColors.Cyan.copy(alpha = 0.16f) else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (voicesSel) StudioColors.Cyan else StudioColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp)
                                .lit(voicesInt, StudioColors.Cyan, selected = voicesSel, cornerRadius = 12.dp)
                                .testTag("online_category_voices_tab"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = if (voicesSel) StudioColors.Cyan else StudioColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t(lang, "online_tab_voices_clips"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (voicesSel) StudioColors.Cyan else StudioColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        val sfxSel = uiState.onlineSearchCategory == OnlineSearchCategory.SOUND_EFFECTS
                        val sfxInt = remember { MutableInteractionSource() }
                        Surface(
                            onClick = { viewModel.setOnlineSearchCategory(OnlineSearchCategory.SOUND_EFFECTS) },
                            interactionSource = sfxInt,
                            shape = RoundedCornerShape(12.dp),
                            color = if (sfxSel) StudioColors.Violet.copy(alpha = 0.18f) else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (sfxSel) StudioColors.Violet else StudioColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp)
                                .lit(sfxInt, StudioColors.Violet, selected = sfxSel, cornerRadius = 12.dp)
                                .testTag("online_category_sfx_tab"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = if (sfxSel) StudioColors.Violet else StudioColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t(lang, "online_tab_sfx"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (sfxSel) StudioColors.Violet else StudioColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

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

                    val topicChips = remember(lang, uiState.onlineSearchCategory) {
                        when (uiState.onlineSearchCategory) {
                            OnlineSearchCategory.VOICES_CLIPS -> if (lang == AppLanguage.EN) {
                                listOf("Podcast Voice", "Radio Announcer", "Acoustic Loop", "Dramatic Narration")
                            } else {
                                listOf("أصوات معلّقين", "حوار وبودكاست", "خطابة وإلقاء", "موسيقى حرة")
                            }
                            OnlineSearchCategory.SOUND_EFFECTS -> if (lang == AppLanguage.EN) {
                                listOf("Horror SFX", "Rain & Thunder", "Whoosh Transition", "Gaming Action")
                            } else {
                                listOf("رعب وتشويق", "مطر وطبيعة", "فوش Whoosh", "أكشن وألعاب")
                            }
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
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StudioColors.Violet.copy(alpha = 0.2f),
                                    selectedLabelColor = StudioColors.Violet,
                                ),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 46.dp)
                                    .lit(tInt, StudioColors.Violet, cornerRadius = 10.dp),
                            )
                        }
                    }

                    uiState.soundSearchOfflineError?.let { offlineMsg ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.Amber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StudioColors.Amber.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("archive_search_offline_banner"),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = StudioColors.Amber)
                                Text(
                                    text = offlineMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextPrimary,
                                )
                            }
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

@Composable
private fun ChatBubbleRow(
    message: AssistantChatMessage,
    language: AppLanguage,
    onNavigateToTool: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.isUser) 18.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 18.dp,
            ),
            color = if (message.isUser) StudioColors.Cyan.copy(alpha = 0.18f) else StudioColors.ElevatedSurface,
            border = BorderStroke(
                1.dp,
                if (message.isUser) StudioColors.Cyan.copy(alpha = 0.5f) else StudioColors.Violet.copy(alpha = 0.45f),
            ),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextPrimary,
                )

                val toolId = message.recommendedToolId
                val toolLabel = message.recommendedToolLabel
                if (!message.isUser && !toolId.isNullOrBlank()) {
                    val chipInteraction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = { onNavigateToTool(toolId) },
                        interactionSource = chipInteraction,
                        shape = RoundedCornerShape(999.dp),
                        color = StudioColors.Cyan.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .lit(chipInteraction, StudioColors.Cyan, cornerRadius = 20.dp)
                            .testTag("assistant_compact_chip_$toolId"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = toolLabel,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "${t(language, "assistant_open_recommended")} ${toolLabel ?: toolId}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.Cyan,
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
