package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceGroupFilter
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.audio.aivoice.countScriptWords
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CompactDropdownField(
    label: String,
    selectedText: String,
    buttonTag: String,
    modifier: Modifier = Modifier,
    searchable: Boolean = false,
    searchPlaceholder: String = "",
    menuContent: @Composable (searchQuery: String, dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                searchQuery = ""
                expanded = true
            },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, StudioColors.BorderBright),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag(buttonTag),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 1,
                )
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = label,
                tint = StudioColors.Cyan,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                searchQuery = ""
                expanded = false
            },
            containerColor = StudioColors.ElevatedSurface,
        ) {
            Column(
                modifier = Modifier.widthIn(min = 210.dp, max = 320.dp),
            ) {
                if (searchable) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = searchPlaceholder.ifBlank { "بحث..." },
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioColors.TextMuted,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.Cyan,
                            unfocusedBorderColor = StudioColors.Border,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 216.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    menuContent(searchQuery) {
                        searchQuery = ""
                        expanded = false
                    }
                }
            }
        }
    }
}

// =========================================================================
// Script Text Input Card
// =========================================================================
@Composable
internal fun AiVoiceGroup1TextCard(
    scriptText: String,
    isEnglish: Boolean,
    onScriptChange: (String) -> Unit,
) {
    val words = remember(scriptText) { countScriptWords(scriptText) }
    val chars = scriptText.length

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.BorderBright),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_group_text"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isEnglish) "Script Text" else "النص",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StudioColors.ElevatedSurface,
                        border = BorderStroke(1.dp, StudioColors.Border),
                    ) {
                        Text(
                            text = if (isEnglish) "$words words • $chars chars" else "$words كلمة • $chars حرف",
                            style = TelemetryTextStyle,
                            color = StudioColors.Cyan,
                            modifier = Modifier
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("ai_voice_text_counters"),
                        )
                    }
                    if (scriptText.isNotEmpty()) {
                        IconButton(
                            onClick = { onScriptChange("") },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_voice_clear_script_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = if (isEnglish) "Clear Text" else "مسح النص",
                                tint = StudioColors.RoseBright,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = scriptText,
                onValueChange = onScriptChange,
                placeholder = {
                    Text(
                        text = if (isEnglish) {
                            "Write the script to transform into expressive voice..."
                        } else {
                            "اكتب النص المراد تحويله إلى صوت استوديو واضح..."
                        },
                        color = StudioColors.TextMuted,
                    )
                },
                minLines = 3,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_script_input"),
            )
        }
    }
}

// =========================================================================
// Card 1: المحرك الصوتي (Two Clean Direct Buttons — NO Dropdown, NO Audar/Edge)
// =========================================================================
@Composable
internal fun AiVoiceEngineSelectionCard(
    selectedEngine: AiVoiceEngineKind,
    isEnglish: Boolean,
    onSelectEngine: (AiVoiceEngineKind) -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_card_engine"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = StudioColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (isEnglish) "1. Voice Engine" else "1. المحرك الصوتي",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.TextPrimary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiVoiceEngineKind.entries.forEach { engine ->
                    val selected = selectedEngine == engine
                    val icon = if (engine == AiVoiceEngineKind.VOXCPM_2) {
                        Icons.Rounded.CloudQueue
                    } else {
                        Icons.Rounded.Memory
                    }
                    Surface(
                        onClick = {
                            focusManager.clearFocus()
                            onSelectEngine(engine)
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) StudioColors.Cyan.copy(alpha = 0.16f) else StudioColors.ElevatedSurface,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) StudioColors.Cyan else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 64.dp)
                            .testTag("ai_voice_engine_${engine.id}"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) StudioColors.Cyan else StudioColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isEnglish) engine.shortTitleEn else engine.shortTitleAr,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (selected) StudioColors.Cyan else StudioColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (engine == AiVoiceEngineKind.VOXCPM_2) {
                                    "VoxCPM2 • 48kHz"
                                } else {
                                    if (isEnglish) "No Server" else "بدون سيرفر"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
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

// =========================================================================
// Card 2: اختيار الصوت (رجالي / نسائي / المفضلة — 6 Fixed Professional Roles)
// =========================================================================
@Composable
internal fun AiVoiceGroup2VoiceSelectionCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSelectLanguage: (AiVoiceLanguage) -> Unit,
    onSelectCharacter: (AiVoiceCharacter) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    var activeFilter by rememberSaveable { mutableStateOf(state.selectedCategory.toGroupFilter()) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.selectedCharacterId, state.selectedCategory) {
        if (activeFilter != AiVoiceGroupFilter.FAVORITES) {
            activeFilter = state.selectedCategory.toGroupFilter()
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_group_voice"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isEnglish) "2. Voice Selection" else "2. اختيار الصوت",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                    )
                }

                // Compact language dropdown (Arabic / Global languages) without dialect clutter
                Box(modifier = Modifier.widthIn(min = 120.dp, max = 160.dp)) {
                    CompactDropdownField(
                        label = if (isEnglish) "Language" else "اللغة",
                        selectedText = state.selectedLanguage.nativeName,
                        buttonTag = "ai_voice_language_dropdown",
                        searchable = true,
                        searchPlaceholder = if (isEnglish) "Search..." else "بحث...",
                    ) { query, dismiss ->
                        val q = query.trim()
                        val langs = state.selectedEngine.supportedLanguages().filter { langOpt ->
                            q.isEmpty() ||
                                langOpt.titleAr.contains(q, ignoreCase = true) ||
                                langOpt.titleEn.contains(q, ignoreCase = true) ||
                                langOpt.nativeName.contains(q, ignoreCase = true) ||
                                langOpt.code.contains(q, ignoreCase = true)
                        }
                        langs.forEach { langOpt ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isEnglish) langOpt.titleEn else langOpt.titleAr,
                                        color = if (langOpt == state.selectedLanguage) StudioColors.Cyan else StudioColors.TextPrimary,
                                    )
                                },
                                onClick = {
                                    focusManager.clearFocus()
                                    onSelectLanguage(langOpt)
                                    dismiss()
                                },
                                modifier = Modifier.testTag("ai_voice_language_${langOpt.code}"),
                            )
                        }
                    }
                }
            }

            // Clean Tabs: رجالي / نسائي (+ المفضلة) — NO "شخصيات" tab!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiVoiceGroupFilter.entries.forEach { filter ->
                    val isSelected = activeFilter == filter
                    Surface(
                        onClick = {
                            focusManager.clearFocus()
                            activeFilter = filter
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) StudioColors.Cyan.copy(alpha = 0.18f) else StudioColors.ElevatedSurface,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) StudioColors.Cyan else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_filter_${filter.id}"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (isEnglish) filter.titleEn else filter.titleAr,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isSelected) StudioColors.Cyan else StudioColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val matchingCharacters = remember(state.allCharacters, state.favoriteCharacterIds, activeFilter) {
                when (activeFilter) {
                    AiVoiceGroupFilter.FAVORITES -> state.allCharacters.filter { state.isCharacterFavorite(it.id) }
                    else -> state.allCharacters.filter { it.category.toGroupFilter() == activeFilter }
                }
            }

            if (matchingCharacters.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioColors.ElevatedSurface,
                    border = BorderStroke(1.dp, StudioColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (isEnglish) {
                            "Tap ★ next to any voice to save it in Favorites."
                        } else {
                            "اضغط على أيقونة النجمة ☆ بجانب أي صوت لإضافته إلى المفضلة."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    matchingCharacters.forEach { ch ->
                        val isCharSelected = state.selectedCharacterId == ch.id
                        val isFav = state.isCharacterFavorite(ch.id)
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                onSelectCharacter(ch)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCharSelected) StudioColors.Cyan.copy(alpha = 0.14f) else StudioColors.ElevatedSurface,
                            border = BorderStroke(
                                width = if (isCharSelected) 1.5.dp else 1.dp,
                                color = if (isCharSelected) StudioColors.Cyan else StudioColors.Border,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp)
                                .testTag("ai_voice_character_option_${ch.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = ch.displayName(isEnglish),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCharSelected) StudioColors.Cyan else StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = ch.displayDescription(isEnglish),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onToggleFavorite(ch.id)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("ai_voice_fav_toggle_${ch.id}"),
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        contentDescription = if (isEnglish) "Favorite" else "تفضيل",
                                        tint = if (isFav) StudioColors.Amber else StudioColors.TextMuted,
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

// =========================================================================
// Card 3: النبرة والمشاعر (6 Core Impactful Emotions in a Neat 2x3 Grid)
// =========================================================================
@Composable
internal fun AiVoiceGroup3EmotionCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSelectEmotion: (AiVoiceEmotion) -> Unit,
) {
    val emotions = remember(state.selectedEngine) { state.selectedEngine.supportedEmotions() }
    val rows = remember(emotions) { emotions.chunked(3) }
    val focusManager = LocalFocusManager.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_group_emotion"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mood,
                    contentDescription = null,
                    tint = StudioColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (isEnglish) "3. Tone & Emotion" else "3. النبرة والمشاعر",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = StudioColors.TextPrimary,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rows.forEach { rowEmotions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowEmotions.forEach { emo ->
                            val isSelected = state.selectedEmotion == emo
                            Surface(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSelectEmotion(emo)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) StudioColors.Cyan.copy(alpha = 0.18f) else StudioColors.ElevatedSurface,
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) StudioColors.Cyan else StudioColors.Border,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 52.dp)
                                    .testTag("ai_voice_emotion_${emo.id}"),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = if (isEnglish) emo.titleEn else emo.titleAr,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        ),
                                        color = if (isSelected) StudioColors.Cyan else StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        repeat(3 - rowEmotions.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// Card 4: الضبط الدقيق (2 Clean Sliders: Speech Rate + Emotion Intensity % + Reset Button)
// =========================================================================
@Composable
internal fun AiVoiceGroup4ControlsCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSpeechRateChange: (Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onResetTuning: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("studio_group_controls"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isEnglish) "4. Fine Tuning" else "4. الضبط الدقيق",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                    )
                }

                TextButton(
                    onClick = onResetTuning,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_reset_tuning_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isEnglish) "Reset" else "إعادة الضبط الافتراضي",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioColors.Cyan,
                    )
                }
            }

            HorizontalDivider(color = StudioColors.Border)

            // Slider 1: Speech Rate (سرعة الكلام)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isEnglish) "Speech Rate" else "سرعة الكلام",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.2f", state.speechRate)}x",
                        style = TelemetryTextStyle,
                        color = StudioColors.Cyan,
                    )
                }
                Slider(
                    value = state.speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.60f..1.50f,
                    colors = SliderDefaults.colors(
                        thumbColor = StudioColors.Cyan,
                        activeTrackColor = StudioColors.Cyan,
                        inactiveTrackColor = StudioColors.BgTop,
                    ),
                    modifier = Modifier.testTag("ai_voice_speed_slider"),
                )
            }

            // Slider 2: Emotion Intensity % (قوة التعبير %)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isEnglish) "Emotion Intensity" else "قوة التعبير %",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioColors.TextPrimary,
                    )
                    Text(
                        text = "${(state.emotionIntensity * 100).roundToInt()}%",
                        style = TelemetryTextStyle,
                        color = StudioColors.Cyan,
                    )
                }
                Slider(
                    value = state.emotionIntensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0.10f..1.00f,
                    colors = SliderDefaults.colors(
                        thumbColor = StudioColors.Cyan,
                        activeTrackColor = StudioColors.Cyan,
                        inactiveTrackColor = StudioColors.BgTop,
                    ),
                    modifier = Modifier.testTag("ai_voice_intensity_slider"),
                )
            }
        }
    }
}

// =========================================================================
// Sticky Bottom Action Bar (Always Pinned Above Keyboard / Nav Bar)
// =========================================================================
@Composable
internal fun AiVoiceStickyBottomActionBar(
    state: AiVoiceStudioUiState,
    hasStudioAudio: Boolean,
    isEnglish: Boolean,
    onPreviewClick: () -> Unit,
    onCancelSynthesis: () -> Unit,
    onApproveClick: () -> Unit,
) {
    val hasOutput = !state.lastOutputWavPath.isNullOrBlank()
    val focusManager = LocalFocusManager.current
    val insetModifier = if (!hasStudioAudio) {
        Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    } else {
        Modifier.imePadding()
    }

    Surface(
        color = StudioColors.CardSurface,
        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.35f)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(insetModifier)
            .testTag("ai_voice_sticky_bottom_bar"),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 700.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isSynthesizing) {
                    LinearProgressIndicator(
                        progress = { state.synthesisProgress.coerceIn(0.02f, 1f) },
                        color = StudioColors.Cyan,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    if (state.synthesisStatusLabel.isNotBlank()) {
                        Text(
                            text = state.synthesisStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.Cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            if (state.isSynthesizing) onCancelSynthesis() else onPreviewClick()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (state.isSynthesizing) StudioColors.RoseBright else StudioColors.Cyan,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (state.isSynthesizing) StudioColors.RoseBright else StudioColors.Cyan,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_sticky_preview_button"),
                    ) {
                        Icon(
                            imageVector = when {
                                state.isSynthesizing -> Icons.Rounded.Close
                                state.isPlayingOutput -> Icons.Rounded.Pause
                                hasOutput -> Icons.Rounded.PlayArrow
                                else -> Icons.Rounded.GraphicEq
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                state.isSynthesizing -> if (isEnglish) "Cancel" else "إلغاء"
                                state.isPlayingOutput -> if (isEnglish) "Pause" else "إيقاف المعاينة"
                                else -> if (isEnglish) "Preview Voice" else "معاينة الصوت"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onApproveClick()
                        },
                        enabled = !state.isSynthesizing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasOutput) StudioColors.Emerald else StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                            disabledContainerColor = StudioColors.ElevatedSurface,
                            disabledContentColor = StudioColors.TextMuted,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("ai_voice_sticky_approve_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isEnglish) "Approve Voice" else "موافقة / اعتماد",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
