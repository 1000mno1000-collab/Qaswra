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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.audio.ai.OnlineSoundItem
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.AssistantChatMessage
import com.example.model.OnlineSearchCategory
import com.example.model.SavedAssistantChatSession
import com.example.studio.ui.lit
import com.example.ui.components.InfoBadgeButton
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.components.ToolInfoDialog
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmartAssistantScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onNavigateToTool: (String) -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = AI Engineer Chat, 1 = Online Archive Library
    var chatInput by remember { mutableStateOf("") }
    var soundQuery by remember { mutableStateOf("") }
    var infoOpen by remember { mutableStateOf(false) }
    var chatHistoryOpen by remember { mutableStateOf(false) }
    var favHistoryOpen by remember { mutableStateOf(false) }

    val favoriteIds = remember(uiState.favoriteOnlineSounds) {
        uiState.favoriteOnlineSounds.map { it.identifier }.toSet()
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        viewModel.showStatusBanner(t(lang, "assistant_copied_banner"))
    }

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
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (selectedTab == 0) {
                        val histInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { chatHistoryOpen = true },
                            interactionSource = histInt,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(histInt, StudioColors.Violet, cornerRadius = 24.dp)
                                .testTag("assistant_chat_history_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = t(lang, "assistant_chat_history_btn"),
                                tint = StudioColors.Violet,
                            )
                        }

                        if (uiState.assistantChatMessages.isNotEmpty()) {
                            val newChatInt = remember { MutableInteractionSource() }
                            IconButton(
                                onClick = { viewModel.startNewAssistantChat() },
                                interactionSource = newChatInt,
                                modifier = Modifier
                                    .size(48.dp)
                                    .lit(newChatInt, StudioColors.Cyan, cornerRadius = 24.dp)
                                    .testTag("assistant_clear_chat_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddComment,
                                    contentDescription = t(lang, "assistant_new_chat_btn"),
                                    tint = StudioColors.Cyan,
                                )
                            }
                        }
                    } else {
                        val favInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { favHistoryOpen = true },
                            interactionSource = favInt,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(favInt, StudioColors.RoseBright, cornerRadius = 24.dp)
                                .testTag("online_fav_history_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = t(lang, "online_fav_and_history_btn"),
                                tint = StudioColors.RoseBright,
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
            // Multi-Turn Smart AI Audio Engineer Chat (4-Model Parallel Racing + Zero-Latency Greetings + History)
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

                    if (uiState.assistantChatMessages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val copyAllInt = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(viewModel.formatAssistantChatForCopy())
                                },
                                interactionSource = copyAllInt,
                                border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.55f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Cyan),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(copyAllInt, StudioColors.Cyan, cornerRadius = 12.dp)
                                    .testTag("assistant_copy_full_chat_button"),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t(lang, "assistant_copy_full_chat"),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            val openHistInt = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { chatHistoryOpen = true },
                                interactionSource = openHistInt,
                                border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.55f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Violet),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(openHistInt, StudioColors.Violet, cornerRadius = 12.dp),
                            ) {
                                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${t(lang, "assistant_chat_history_btn")} (${uiState.savedAssistantChats.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
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
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = if (lang == AppLanguage.EN) {
                                            "Ask me anything about audio engineering, dialect FX, noise cleanup, or finding the exact Sonix ✦ studio tool."
                                        } else {
                                            "اسألني بأي لهجة (عراقي، خليجي، مصري، فصحى) عن أي مشكلة بالصوت أو التأثير اللي تريده، وراح أجاوبك بسرعة فائقة وأعطيك اختصار مباشر للأداة المناسبة!"
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
                                    onCopyReply = { copyToClipboard(msg.text) },
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        color = StudioColors.Cyan,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (lang == AppLanguage.EN) "Racing 4 AI servers..." else "جاري البحث السريع عبر السيرفرات...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.Cyan,
                                    )
                                }

                                val cancelGuideInt = remember { MutableInteractionSource() }
                                OutlinedButton(
                                    onClick = { viewModel.cancelActiveOnlineRequest() },
                                    interactionSource = cancelGuideInt,
                                    border = BorderStroke(1.dp, StudioColors.RoseBright),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .lit(cancelGuideInt, StudioColors.RoseBright, cornerRadius = 10.dp)
                                        .testTag("assistant_guide_cancel_button"),
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(t(lang, "cancel_request"), style = MaterialTheme.typography.labelMedium)
                                }
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
            // 2-Tab Categorized Online Audio & SFX Library (Context-Aware Bilingual Archive.org Search + Favorites + History)
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
                                .defaultMinSize(minHeight = 48.dp)
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
                                .defaultMinSize(minHeight = 48.dp)
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

                    if (uiState.isSearchingOnlineSounds || uiState.loadingOnlineSoundId != null) {
                        val cancelSearchInt = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { viewModel.cancelActiveOnlineRequest() },
                            interactionSource = cancelSearchInt,
                            border = BorderStroke(1.dp, StudioColors.RoseBright),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.RoseBright),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(cancelSearchInt, StudioColors.RoseBright, cornerRadius = 12.dp)
                                .testTag("archive_search_cancel_button"),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t(lang, "cancel_request"))
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
                                OnlineSoundResultCard(
                                    item = item,
                                    isPreviewing = uiState.previewingOnlineUrl == item.streamUrl,
                                    isLoadingItem = uiState.loadingOnlineSoundId == item.identifier,
                                    isFavorite = item.identifier in favoriteIds,
                                    isBusy = uiState.isBusy,
                                    language = lang,
                                    onTogglePreview = { viewModel.toggleOnlineSoundPreview(item) },
                                    onToggleFavorite = { viewModel.toggleOnlineSoundFavorite(item) },
                                    onImportToStudio = { viewModel.importOnlineSoundToStudio(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (chatHistoryOpen) {
        ChatHistoryDialog(
            sessions = uiState.savedAssistantChats,
            language = lang,
            onOpenSession = { sessionId ->
                viewModel.openSavedAssistantChat(sessionId)
                selectedTab = 0
                chatHistoryOpen = false
            },
            onCopyFullSession = { session ->
                copyToClipboard(viewModel.formatAssistantChatForCopy(session.messages))
            },
            onDeleteSession = { sessionId ->
                viewModel.deleteSavedAssistantChat(sessionId)
            },
            onDismiss = { chatHistoryOpen = false },
        )
    }

    if (favHistoryOpen) {
        FavoritesAndHistoryDialog(
            favorites = uiState.favoriteOnlineSounds,
            history = uiState.onlineListeningHistory,
            previewingUrl = uiState.previewingOnlineUrl,
            loadingId = uiState.loadingOnlineSoundId,
            isBusy = uiState.isBusy,
            language = lang,
            onTogglePreview = { viewModel.toggleOnlineSoundPreview(it) },
            onToggleFavorite = { viewModel.toggleOnlineSoundFavorite(it) },
            onImportToStudio = {
                favHistoryOpen = false
                viewModel.importOnlineSoundToStudio(it)
            },
            onClearHistory = { viewModel.clearOnlineListeningHistory() },
            onDismiss = { favHistoryOpen = false },
        )
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
private fun OnlineSoundResultCard(
    item: OnlineSoundItem,
    isPreviewing: Boolean,
    isLoadingItem: Boolean,
    isFavorite: Boolean,
    isBusy: Boolean,
    language: AppLanguage,
    subtitleOverride: String? = null,
    onTogglePreview: () -> Unit,
    onToggleFavorite: () -> Unit,
    onImportToStudio: () -> Unit,
) {
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
                    onClick = onTogglePreview,
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
                        text = subtitleOverride ?: "${item.source} • ${item.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val favInt = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onToggleFavorite,
                    interactionSource = favInt,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isFavorite) StudioColors.RoseBright.copy(alpha = 0.16f) else StudioColors.ElevatedSurface,
                        contentColor = if (isFavorite) StudioColors.RoseBright else StudioColors.TextSecondary,
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .lit(favInt, StudioColors.RoseBright, selected = isFavorite, cornerRadius = 24.dp)
                        .testTag("online_sound_fav_${item.identifier}"),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = t(language, "online_tab_favorites"),
                    )
                }
            }

            val addInt = remember { MutableInteractionSource() }
            Button(
                onClick = onImportToStudio,
                enabled = !isBusy,
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
                Text(t(language, "assistant_add_studio"))
            }
        }
    }
}

@Composable
private fun ChatBubbleRow(
    message: AssistantChatMessage,
    language: AppLanguage,
    onCopyReply: () -> Unit,
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
                if (!message.isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (!toolId.isNullOrBlank()) {
                            val chipInteraction = remember { MutableInteractionSource() }
                            Surface(
                                onClick = { onNavigateToTool(toolId) },
                                interactionSource = chipInteraction,
                                shape = RoundedCornerShape(999.dp),
                                color = StudioColors.Cyan.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .weight(1f, fill = false)
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
                        } else {
                            Spacer(Modifier.width(4.dp))
                        }

                        val copyInt = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = onCopyReply,
                            interactionSource = copyInt,
                            modifier = Modifier
                                .size(48.dp)
                                .lit(copyInt, StudioColors.Violet, cornerRadius = 24.dp)
                                .testTag("assistant_copy_reply_${message.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = t(language, "assistant_copy_message"),
                                tint = StudioColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryDialog(
    sessions: List<SavedAssistantChatSession>,
    language: AppLanguage,
    onOpenSession: (String) -> Unit,
    onCopyFullSession: (SavedAssistantChatSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFmt = remember(language) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = StudioColors.CardSurface,
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = StudioColors.Violet)
                        Text(
                            text = t(language, "assistant_chat_history_btn"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.TextPrimary,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = t(language, "back"), tint = StudioColors.TextSecondary)
                    }
                }

                HorizontalDivider(color = StudioColors.Border)

                if (sessions.isEmpty()) {
                    Text(
                        text = t(language, "assistant_history_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        sessions.forEach { session ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = StudioColors.BgMid,
                                border = BorderStroke(1.dp, StudioColors.Border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = session.title.ifBlank { t(language, "assistant_title") },
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = StudioColors.TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${dateFmt.format(Date(session.updatedAtMs))} • ${session.messages.size} msg",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioColors.TextSecondary,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Button(
                                            onClick = { onOpenSession(session.id) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = StudioColors.Cyan,
                                                contentColor = StudioColors.BgTop,
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 48.dp),
                                        ) {
                                            Text(
                                                text = t(language, "assistant_resume_chat"),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onCopyFullSession(session) },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentCopy,
                                                contentDescription = t(language, "assistant_copy_full_chat"),
                                                tint = StudioColors.Violet,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteSession(session.id) },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete",
                                                tint = StudioColors.RoseBright,
                                            )
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
}

@Composable
private fun FavoritesAndHistoryDialog(
    favorites: List<OnlineSoundItem>,
    history: List<com.example.audio.ai.OnlineSoundHistoryItem>,
    previewingUrl: String?,
    loadingId: String?,
    isBusy: Boolean,
    language: AppLanguage,
    onTogglePreview: (OnlineSoundItem) -> Unit,
    onToggleFavorite: (OnlineSoundItem) -> Unit,
    onImportToStudio: (OnlineSoundItem) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    var activeSubTab by remember { mutableIntStateOf(0) } // 0 = Favorites, 1 = History
    val dateFmt = remember(language) { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val favIds = remember(favorites) { favorites.map { it.identifier }.toSet() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = StudioColors.CardSurface,
            border = BorderStroke(1.dp, StudioColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = t(language, "online_fav_and_history_btn"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = t(language, "back"), tint = StudioColors.TextSecondary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        0 to "${t(language, "online_tab_favorites")} (${favorites.size})",
                        1 to "${t(language, "online_tab_history")} (${history.size})",
                    ).forEach { (idx, label) ->
                        val selected = activeSubTab == idx
                        Surface(
                            onClick = { activeSubTab = idx },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                            border = BorderStroke(1.dp, if (selected) StudioColors.RoseBright else StudioColors.Border),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (selected) StudioColors.RoseBright else StudioColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (activeSubTab == 1 && history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onClearHistory) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = StudioColors.RoseBright)
                            Spacer(Modifier.width(4.dp))
                            Text(t(language, "online_clear_history_btn"), color = StudioColors.RoseBright)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (activeSubTab == 0) {
                        if (favorites.isEmpty()) {
                            Text(
                                text = t(language, "online_favorites_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextSecondary,
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        } else {
                            favorites.forEach { item ->
                                OnlineSoundResultCard(
                                    item = item,
                                    isPreviewing = previewingUrl == item.streamUrl,
                                    isLoadingItem = loadingId == item.identifier,
                                    isFavorite = true,
                                    isBusy = isBusy,
                                    language = language,
                                    onTogglePreview = { onTogglePreview(item) },
                                    onToggleFavorite = { onToggleFavorite(item) },
                                    onImportToStudio = { onImportToStudio(item) },
                                )
                            }
                        }
                    } else {
                        if (history.isEmpty()) {
                            Text(
                                text = t(language, "online_history_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioColors.TextSecondary,
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        } else {
                            history.forEach { entry ->
                                val item = entry.sound
                                OnlineSoundResultCard(
                                    item = item,
                                    isPreviewing = previewingUrl == item.streamUrl,
                                    isLoadingItem = loadingId == item.identifier,
                                    isFavorite = item.identifier in favIds,
                                    isBusy = isBusy,
                                    language = language,
                                    subtitleOverride = "${dateFmt.format(Date(entry.listenedAtMs))} • ${item.category}",
                                    onTogglePreview = { onTogglePreview(item) },
                                    onToggleFavorite = { onToggleFavorite(item) },
                                    onImportToStudio = { onImportToStudio(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
