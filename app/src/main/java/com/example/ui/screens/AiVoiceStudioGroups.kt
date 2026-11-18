package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.aivoice.AiArabicDialect
import com.example.audio.aivoice.AiDeliveryPace
import com.example.audio.aivoice.AiVoiceCharacter
import com.example.audio.aivoice.AiVoiceEmotion
import com.example.audio.aivoice.AiVoiceEngineKind
import com.example.audio.aivoice.AiVoiceGroupFilter
import com.example.audio.aivoice.AiVoiceLanguage
import com.example.audio.aivoice.AiVoiceStudioUiState
import com.example.audio.aivoice.countScriptWords
import com.example.audio.formatDurationMs
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import kotlin.math.roundToInt

@Composable
internal fun StudioCollapsibleGroupCard(
    title: String,
    summary: String,
    icon: ImageVector,
    cardTag: String,
    toggleTag: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(
            1.dp,
            if (expanded) StudioColors.Cyan.copy(alpha = 0.45f) else StudioColors.Border,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cardTag),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = {
                    focusManager.clearFocus()
                    onToggle()
                },
                color = StudioColors.CardSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp)
                    .testTag(toggleTag),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = StudioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary.isNotBlank()) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = title,
                        tint = StudioColors.Cyan,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = StudioColors.Border)
                    content()
                }
            }
        }
    }
}

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
            shape = RoundedCornerShape(12.dp),
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
// Group 1: Script Text + Word/Character Counters + Clear Button
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
        shape = RoundedCornerShape(16.dp),
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
                        shape = RoundedCornerShape(999.dp),
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
                            "اكتب النص المراد تحويله إلى صوت بشري واقعي..."
                        },
                        color = StudioColors.TextMuted,
                    )
                },
                minLines = 3,
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_script_input"),
            )
        }
    }
}

// =========================================================================
// Group 2: Unified Voice Selection (Engine, Language, Dialect, Type, Favorites)
// =========================================================================
@Composable
internal fun AiVoiceGroup2VoiceSelectionCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSelectEngine: (AiVoiceEngineKind) -> Unit,
    onSelectLanguage: (AiVoiceLanguage) -> Unit,
    onSelectDialect: (AiArabicDialect) -> Unit,
    onSelectCharacter: (AiVoiceCharacter) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var activeFilter by rememberSaveable { mutableStateOf(state.selectedCategory.toGroupFilter()) }
    var voicesDropdownOpen by rememberSaveable { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.selectedCharacterId, state.selectedCategory) {
        activeFilter = state.selectedCategory.toGroupFilter()
    }

    val selectedChar = state.allCharacters.firstOrNull { it.id == state.selectedCharacterId }
    val summary = buildString {
        append(selectedChar?.displayName(isEnglish) ?: state.selectedCategory.let { if (isEnglish) it.titleEn else it.titleAr })
        append(" • ")
        append(if (state.selectedLanguage == AiVoiceLanguage.AR) {
            if (isEnglish) state.selectedDialect.titleEn else state.selectedDialect.titleAr
        } else {
            state.selectedLanguage.nativeName
        })
        append(" • ")
        append(state.selectedEngine.id)
    }

    StudioCollapsibleGroupCard(
        title = if (isEnglish) "Voice & Language Selection" else "اختيار الصوت",
        summary = summary,
        icon = Icons.Rounded.RecordVoiceOver,
        cardTag = "studio_group_voice",
        toggleTag = "studio_group_voice_toggle",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // Engine Dropdown (bounded scrollable, hides unapproved commercial engine)
        CompactDropdownField(
            label = if (isEnglish) "Voice Engine" else "المحرك الصوتي",
            selectedText = if (isEnglish) state.selectedEngine.titleEn else state.selectedEngine.titleAr,
            buttonTag = "ai_voice_engine_dropdown",
        ) { _, dismiss ->
            AiVoiceEngineKind.entries.filter { it.isUserSelectable }.forEach { engine ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = if (isEnglish) engine.titleEn else engine.titleAr,
                                fontWeight = if (engine == state.selectedEngine) FontWeight.Bold else FontWeight.Normal,
                                color = if (engine == state.selectedEngine) StudioColors.Cyan else StudioColors.TextPrimary,
                            )
                            Text(
                                text = if (isEnglish) engine.badgeEn else engine.badgeAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                    },
                    onClick = {
                        focusManager.clearFocus()
                        onSelectEngine(engine)
                        dismiss()
                    },
                    modifier = Modifier.testTag("ai_voice_engine_${engine.id}"),
                )
            }
        }

        // Language & Arabic Dialect compact searchable Dropdowns (4 visible rows max)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactDropdownField(
                label = if (isEnglish) "Language" else "اللغة",
                selectedText = if (isEnglish) state.selectedLanguage.titleEn else state.selectedLanguage.titleAr,
                buttonTag = "ai_voice_language_dropdown",
                modifier = Modifier.weight(1f),
                searchable = true,
                searchPlaceholder = if (isEnglish) "Search language..." else "بحث عن لغة...",
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

            if (state.selectedLanguage == AiVoiceLanguage.AR) {
                CompactDropdownField(
                    label = if (isEnglish) "Arabic Dialect" else "اللهجة العربية",
                    selectedText = if (isEnglish) state.selectedDialect.titleEn else state.selectedDialect.titleAr,
                    buttonTag = "ai_voice_dialect_dropdown",
                    modifier = Modifier.weight(1f),
                    searchable = true,
                    searchPlaceholder = if (isEnglish) "Search dialect..." else "بحث عن لهجة...",
                ) { query, dismiss ->
                    val q = query.trim()
                    val dialects = AiArabicDialect.entries.filter { dialect ->
                        q.isEmpty() ||
                            dialect.titleAr.contains(q, ignoreCase = true) ||
                            dialect.titleEn.contains(q, ignoreCase = true) ||
                            dialect.id.contains(q, ignoreCase = true)
                    }
                    dialects.forEach { dialect ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isEnglish) dialect.titleEn else dialect.titleAr,
                                    color = if (dialect == state.selectedDialect) StudioColors.Cyan else StudioColors.TextPrimary,
                                )
                            },
                            onClick = {
                                focusManager.clearFocus()
                                onSelectDialect(dialect)
                                dismiss()
                            },
                            modifier = Modifier.testTag("ai_voice_dialect_${dialect.id}"),
                        )
                    }
                }
            }
        }

        // Voice Type Categories (رجالي / نسائي / شخصيات / المفضلة)
        Text(
            text = if (isEnglish) "Voice Category" else "نوع الصوت والتصنيف",
            style = MaterialTheme.typography.labelMedium,
            color = StudioColors.Cyan,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiVoiceGroupFilter.entries.forEach { filter ->
                val isSelected = activeFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        focusManager.clearFocus()
                        if (activeFilter == filter) {
                            voicesDropdownOpen = !voicesDropdownOpen
                        } else {
                            activeFilter = filter
                            voicesDropdownOpen = true
                        }
                    },
                    label = { Text(if (isEnglish) filter.titleEn else filter.titleAr, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = if (isSelected && voicesDropdownOpen) {
                                Icons.Rounded.ExpandLess
                            } else {
                                Icons.Rounded.ExpandMore
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                        selectedLabelColor = StudioColors.Cyan,
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_filter_${filter.id}"),
                )
            }
        }

        // Filtered compact voice rows shown ONLY for active category when expanded (~4 rows visible max)
        val matchingCharacters = remember(state.allCharacters, state.favoriteCharacterIds, activeFilter) {
            when (activeFilter) {
                AiVoiceGroupFilter.FAVORITES -> state.allCharacters.filter { state.isCharacterFavorite(it.id) }
                else -> state.allCharacters.filter { it.category.toGroupFilter() == activeFilter }
            }
        }

        AnimatedVisibility(visible = voicesDropdownOpen) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 248.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (matchingCharacters.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioColors.ElevatedSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (isEnglish) {
                                "Tap the ★ icon next to any voice to save it to Favorites."
                            } else {
                                "اضغط على أيقونة النجمة ☆ بجانب أي صوت لإضافته إلى المفضلة."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    matchingCharacters.forEach { ch ->
                        val isCharSelected = state.selectedCharacterId == ch.id
                        val isFav = state.isCharacterFavorite(ch.id)
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                onSelectCharacter(ch)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCharSelected) StudioColors.Cyan.copy(alpha = 0.14f) else StudioColors.ElevatedSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isCharSelected) StudioColors.Cyan else StudioColors.Border,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .testTag("ai_voice_character_option_${ch.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.displayName(isEnglish),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isCharSelected) StudioColors.Cyan else StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${if (isEnglish) ch.dialect.titleEn else ch.dialect.titleAr} • ${ch.preferredEngine.id}",
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
                                        contentDescription = "Favorite",
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
// Group 3: Supported Emotions & Expressions ONLY
// =========================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiVoiceGroup3EmotionCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSelectEmotion: (AiVoiceEmotion) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val supported = remember(state.selectedEngine) { state.selectedEngine.supportedEmotions() }
    val activeLabel = if (isEnglish) state.selectedEmotion.titleEn else state.selectedEmotion.titleAr
    val focusManager = LocalFocusManager.current

    StudioCollapsibleGroupCard(
        title = if (isEnglish) "Emotion & Expression" else "التعبير والمشاعر",
        summary = "$activeLabel • ${supported.size} ${if (isEnglish) "supported" else "مدعومة لهذا المحرك"}",
        icon = Icons.Rounded.Mood,
        cardTag = "studio_group_emotion",
        toggleTag = "studio_group_emotion_toggle",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            supported.forEach { emo ->
                val isSelected = state.selectedEmotion == emo
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        focusManager.clearFocus()
                        onSelectEmotion(emo)
                    },
                    label = { Text(if (isEnglish) emo.titleEn else emo.titleAr, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.22f),
                        selectedLabelColor = StudioColors.Cyan,
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_emotion_${emo.id}"),
                )
            }
        }

        val engineHonestNote = when (state.selectedEngine) {
            AiVoiceEngineKind.AUDAR_TTS_V1_FLASH, AiVoiceEngineKind.AUDAR_TTS_V1_TURBO ->
                if (isEnglish) {
                    "Audar natively supports these 8 expressive tags ([whispers], [laughs], [sighs], [excited], [curious])."
                } else {
                    "يدعم Audar رسمياً هذه التعبيرات الثمانية فقط عبر وسوم النطق الفعلية."
                }
            AiVoiceEngineKind.EDGE_NEURAL_FREE ->
                if (isEnglish) {
                    "Edge Free modulates speed/pitch prosody for these 6 tones (no neural laugh/whisper audio tags)."
                } else {
                    "يضبط Edge المجاني الإيقاع والطبقة لهذه التعبيرات الستة فقط."
                }
            else -> null
        }
        if (engineHonestNote != null) {
            Text(
                text = engineHonestNote,
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextSecondary,
            )
        }
    }
}

// =========================================================================
// Group 4: Supported Acoustic Controls ONLY
// =========================================================================
@Composable
internal fun AiVoiceGroup4ControlsCard(
    state: AiVoiceStudioUiState,
    isEnglish: Boolean,
    onSelectPace: (AiDeliveryPace) -> Unit,
    onPitchOffsetChange: (Float) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDescriptionChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val supportsPitch = state.selectedEngine.supportsPitchShiftControl()
    val supportsIntensity = state.selectedEngine.supportsEmotionIntensity()
    val supportsPrompt = state.selectedEngine.supportsVoiceDesignPrompt()

    val summary = buildString {
        append(if (isEnglish) state.selectedPace.titleEn else state.selectedPace.titleAr)
        if (supportsPitch) {
            append(" • ${"%.1f".format(state.pitchOffsetSemitones)} st")
        }
        if (supportsIntensity) {
            append(" • ${(state.emotionIntensity * 100).roundToInt()}%")
        }
    }

    StudioCollapsibleGroupCard(
        title = if (isEnglish) "Voice Controls" else "التحكم الصوتي",
        summary = summary,
        icon = Icons.Rounded.Tune,
        cardTag = "studio_group_controls",
        toggleTag = "studio_group_controls_toggle",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // 1. Speech Speed / Pace (always supported across all engines)
        CompactDropdownField(
            label = if (isEnglish) "Speech Speed" else "سرعة الكلام",
            selectedText = if (isEnglish) state.selectedPace.titleEn else state.selectedPace.titleAr,
            buttonTag = "ai_voice_pace_dropdown",
        ) { _, dismiss ->
            AiDeliveryPace.entries.forEach { pace ->
                DropdownMenuItem(
                    text = { Text(if (isEnglish) pace.titleEn else pace.titleAr) },
                    onClick = {
                        onSelectPace(pace)
                        dismiss()
                    },
                    modifier = Modifier.testTag("ai_voice_pace_${pace.id}"),
                )
            }
        }

        // 2. Pitch Control (ONLY for engines that actually apply pitch_semitones)
        if (supportsPitch) {
            Column {
                val sign = if (state.pitchOffsetSemitones > 0f) "+" else ""
                Text(
                    text = if (isEnglish) {
                        "Pitch Shift: $sign${"%.1f".format(state.pitchOffsetSemitones)} semitones"
                    } else {
                        "طبقة الصوت: $sign${"%.1f".format(state.pitchOffsetSemitones)} نصف درجة"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Cyan,
                )
                Slider(
                    value = state.pitchOffsetSemitones,
                    onValueChange = onPitchOffsetChange,
                    valueRange = -8f..8f,
                    colors = SliderDefaults.colors(thumbColor = StudioColors.Cyan, activeTrackColor = StudioColors.Cyan),
                    modifier = Modifier.testTag("ai_voice_pitch_slider"),
                )
            }
        }

        // 3. Emotion Intensity (ONLY for GPU neural engines)
        if (supportsIntensity) {
            Column {
                Text(
                    text = if (isEnglish) {
                        "Emotion Intensity: ${(state.emotionIntensity * 100).roundToInt()}%"
                    } else {
                        "مستوى التعبير الصوتي: ${(state.emotionIntensity * 100).roundToInt()}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.Cyan,
                )
                Slider(
                    value = state.emotionIntensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = StudioColors.Cyan, activeTrackColor = StudioColors.Cyan),
                    modifier = Modifier.testTag("ai_voice_intensity_slider"),
                )
            }
        }

        // 4. Voice Tone / Freeform Prompt Design (ONLY for VoxCPM2)
        if (supportsPrompt) {
            OutlinedTextField(
                value = state.voiceDescriptionPrompt,
                onValueChange = onDescriptionChange,
                label = {
                    Text(if (isEnglish) "Voice Tone & Character Prompt" else "النبرة وتفاصيل الصوت (VoxCPM2)")
                },
                minLines = 2,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_prompt_input"),
            )
        }
    }
}

// =========================================================================
// Group 5: Advanced Options (Cloning, Create Character, Dialogue, Export)
// =========================================================================
@Composable
internal fun AiVoiceGroup5AdvancedCard(
    state: AiVoiceStudioUiState,
    hasStudioAudio: Boolean,
    isEnglish: Boolean,
    onPickReferenceFile: () -> Unit,
    onUseStudioBufferAsReference: () -> Unit,
    onClearReference: () -> Unit,
    onReferenceTranscriptChange: (String) -> Unit,
    onRightsConfirmedChange: (Boolean) -> Unit,
    onOpenSaveCharacterDialog: () -> Unit,
    onOpenDialogueTab: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenServerTab: () -> Unit = {},
    onSaveOutputWav: () -> Unit,
    onShareOutput: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    StudioCollapsibleGroupCard(
        title = if (isEnglish) "Advanced Options" else "خيارات متقدمة",
        summary = if (isEnglish) {
            "Voice Cloning • Custom Character • Dialogue • Export"
        } else {
            "استنساخ الصوت • إنشاء شخصية • الحوارات • التصدير"
        },
        icon = Icons.Rounded.SettingsSuggest,
        cardTag = "studio_group_advanced",
        toggleTag = "studio_group_advanced_toggle",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // Voice Cloning section (honest capability gate)
        if (state.selectedEngine.supportsVoiceCloning()) {
            Text(
                text = if (isEnglish) "Voice Cloning (Reference Audio)" else "استنساخ الصوت (عينة مرجعية)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = StudioColors.Cyan,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPickReferenceFile,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_pick_ref_button"),
                ) {
                    Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEnglish) "Upload Audio" else "رفع عينة", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUseStudioBufferAsReference,
                    enabled = hasStudioAudio,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_voice_use_studio_ref_button"),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEnglish) "From Studio" else "من الاستوديو", maxLines = 1)
                }
            }

            if (!state.referenceAudioName.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioColors.ElevatedSurface,
                    border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.referenceAudioName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = StudioColors.Emerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatDurationMs(state.referenceAudioDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                        }
                        IconButton(onClick = onClearReference, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear Ref", tint = StudioColors.RoseBright)
                        }
                    }
                }
                OutlinedTextField(
                    value = state.referenceTranscript,
                    onValueChange = onReferenceTranscriptChange,
                    label = {
                        Text(if (isEnglish) "Reference spoken words" else "نص العينة المرجعية (لتحسين المطابقة)")
                    },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.referenceRightsConfirmed,
                        onCheckedChange = onRightsConfirmedChange,
                        colors = CheckboxDefaults.colors(checkedColor = StudioColors.Cyan),
                    )
                    Text(
                        text = if (isEnglish) {
                            "I confirm legal rights/consent to use this reference voice."
                        } else {
                            "أؤكد امتلاك الإذن القانوني لاستخدام هذا الصوت."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                }
            }
        } else {
            Text(
                text = if (isEnglish) {
                    "Voice Cloning requires VoxCPM2 or Audar-TTS GPU engine."
                } else {
                    "استنساخ الصوت متاح عند اختيار محرك VoxCPM2 أو Audar-TTS."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextSecondary,
            )
        }

        HorizontalDivider(color = StudioColors.Border)

        // Create Character + Multi-Character Dialogue shortcuts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSaveCharacterDialog,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("ai_voice_save_character_quick_button"),
            ) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isEnglish) "Save Character" else "إنشاء شخصية", maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenDialogueTab,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("ai_voice_goto_dialogue_button"),
            ) {
                Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isEnglish) "Dialogue" else "الحوارات", maxLines = 1)
            }
        }

        // Export shortcuts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSaveOutputWav,
                enabled = !state.lastOutputWavPath.isNullOrBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("ai_voice_adv_save_wav_button"),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isEnglish) "Export WAV" else "تصدير WAV", maxLines = 1)
            }
            IconButton(
                onClick = onShareOutput,
                enabled = !state.lastOutputWavPath.isNullOrBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ai_voice_adv_share_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = if (isEnglish) "Share" else "مشاركة",
                    tint = if (!state.lastOutputWavPath.isNullOrBlank()) StudioColors.Cyan else StudioColors.TextMuted,
                )
            }
        }
    }
}

// =========================================================================
// MANDATORY Sticky Bottom Action Bar (Always Pinned Directly Above Keyboard/Nav Bar)
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
                // Button 1: معاينة الصوت (Preview / Play / Pause / Cancel)
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (state.isSynthesizing) onCancelSynthesis() else onPreviewClick()
                    },
                    shape = RoundedCornerShape(14.dp),
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

                // Button 2: موافقة / اعتماد الصوت (Approve & Insert into Studio)
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
                    shape = RoundedCornerShape(14.dp),
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
