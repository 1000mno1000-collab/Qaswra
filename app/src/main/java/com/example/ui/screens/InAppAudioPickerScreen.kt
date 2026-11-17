package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.formatDurationMs
import com.example.data.MediaStoreAudioRepository
import com.example.i18n.t
import com.example.model.AudioPickerTarget
import com.example.model.DeviceAudioItem
import com.example.studio.ui.lit
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Composable
fun InAppAudioPickerScreen(
    target: AudioPickerTarget,
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    showBackButton: Boolean,
    onPickedComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    val context = LocalContext.current
    val requiredPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasAudioPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPerm = granted
        if (granted) {
            viewModel.refreshDeviceAudios()
        }
    }

    val externalDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            when (target) {
                AudioPickerTarget.IMPORT_MAIN,
                AudioPickerTarget.LIBRARY_BROWSE,
                -> viewModel.importAudioUri(uri)
                AudioPickerTarget.APPEND_CLIP -> viewModel.appendAudioUriToCurrent(uri)
                AudioPickerTarget.MIX_BACKGROUND -> viewModel.loadMixBackgroundUri(uri)
            }
            onPickedComplete()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = All on device, 1 = Folders
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("ALL") }

    var previewingUri by remember { mutableStateOf<String?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        runCatching { previewPlayer?.stop() }
        runCatching { previewPlayer?.release() }
        previewPlayer = null
        previewingUri = null
    }

    fun togglePreview(item: DeviceAudioItem) {
        if (previewingUri == item.uriString) {
            stopPreview()
            return
        }
        stopPreview()
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setDataSource(context, Uri.parse(item.uriString))
            mp.setOnPreparedListener { prepared ->
                runCatching { prepared.start() }
            }
            mp.setOnCompletionListener {
                stopPreview()
            }
            mp.setOnErrorListener { _, _, _ ->
                stopPreview()
                true
            }
            previewPlayer = mp
            previewingUri = item.uriString
            mp.prepareAsync()
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { mp.release() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { previewPlayer?.stop() }
            runCatching { previewPlayer?.release() }
        }
    }

    LaunchedEffect(hasAudioPerm) {
        if (hasAudioPerm) {
            viewModel.refreshDeviceAudios()
        }
    }

    val formatOptions = listOf("ALL", "MP3", "WAV", "M4A", "AAC", "FLAC")
    val filteredItems = remember(uiState.deviceAudios, searchQuery, selectedFormat, selectedFolder, selectedTab) {
        uiState.deviceAudios.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
                item.folderName.contains(searchQuery.trim(), ignoreCase = true)
            val matchesFormat = selectedFormat == "ALL" || item.extension.equals(selectedFormat, ignoreCase = true)
            val matchesFolder = selectedTab != 1 || selectedFolder == null || item.folderName == selectedFolder
            matchesQuery && matchesFormat && matchesFolder
        }
    }

    val foldersSummary = remember(uiState.deviceAudios, searchQuery, selectedFormat) {
        uiState.deviceAudios
            .filter { item ->
                val matchesQuery = searchQuery.isBlank() ||
                    item.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
                    item.folderName.contains(searchQuery.trim(), ignoreCase = true)
                val matchesFormat = selectedFormat == "ALL" || item.extension.equals(selectedFormat, ignoreCase = true)
                matchesQuery && matchesFormat
            }
            .groupBy { it.folderName }
            .entries
            .sortedByDescending { it.value.size }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "picker_header", contentType = "header") {
                if (showBackButton) {
                    StudioFocusTopBar(
                        title = t(lang, "picker_title"),
                        subtitle = "${uiState.deviceAudios.size} • ${t(lang, "app_name")}",
                        language = lang,
                        onBack = {
                            stopPreview()
                            onBack()
                        },
                        trailing = {
                            val refreshInteraction = remember { MutableInteractionSource() }
                            IconButton(
                                onClick = { viewModel.refreshDeviceAudios() },
                                interactionSource = refreshInteraction,
                                modifier = Modifier
                                    .size(48.dp)
                                    .lit(refreshInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
                                    .testTag("picker_refresh_button"),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = StudioColors.Cyan)
                            }
                        },
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = StudioColors.CardSurface,
                        border = BorderStroke(1.dp, StudioColors.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = StudioColors.Cyan)
                                Column {
                                    Text(
                                        text = t(lang, "picker_title"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioColors.TextPrimary,
                                    )
                                    Text(
                                        text = "${uiState.deviceAudios.size} ملف صوتي",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.TextSecondary,
                                    )
                                }
                            }
                            val refreshBrowseInteraction = remember { MutableInteractionSource() }
                            IconButton(
                                onClick = { viewModel.refreshDeviceAudios() },
                                interactionSource = refreshBrowseInteraction,
                                modifier = Modifier
                                    .size(48.dp)
                                    .lit(refreshBrowseInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = StudioColors.Cyan)
                            }
                        }
                    }
                }
            }

            item(key = "external_drive_import_button", contentType = "external_import_button") {
                val externalInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        stopPreview()
                        externalDocLauncher.launch(arrayOf("audio/*"))
                    },
                    interactionSource = externalInteraction,
                    border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Violet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .lit(externalInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                        .testTag("picker_external_drive_button"),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "picker_import_drive_files"), style = MaterialTheme.typography.labelLarge)
                }
            }

            if (!hasAudioPerm) {
                item(key = "audio_permission_card", contentType = "permission_card") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = t(lang, "picker_grant_audio_perm"),
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioColors.Cyan,
                            )
                            Text(
                                text = t(lang, "picker_perm_desc"),
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextSecondary,
                            )
                            val grantInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = { permLauncher.launch(requiredPermission) },
                                interactionSource = grantInteraction,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioColors.Cyan,
                                    contentColor = StudioColors.BgTop,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(grantInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                    .testTag("picker_grant_permission_button"),
                            ) {
                                Text(t(lang, "grant_permission"), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            } else {
                item(key = "picker_tabs", contentType = "tabs") {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = StudioColors.CardSurface,
                        contentColor = StudioColors.Cyan,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = StudioColors.Cyan,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val deviceTabInteraction = remember { MutableInteractionSource() }
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                selectedFolder = null
                            },
                            interactionSource = deviceTabInteraction,
                            text = { Text(t(lang, "picker_tab_device")) },
                            modifier = Modifier.lit(deviceTabInteraction, MaterialTheme.colorScheme.primary, selected = selectedTab == 0, cornerRadius = 12.dp),
                        )
                        val folderTabInteraction = remember { MutableInteractionSource() }
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            interactionSource = folderTabInteraction,
                            text = { Text(t(lang, "picker_tab_folders")) },
                            modifier = Modifier.lit(folderTabInteraction, MaterialTheme.colorScheme.primary, selected = selectedTab == 1, cornerRadius = 12.dp),
                        )
                    }
                }

                item(key = "picker_search_and_filter", contentType = "search_filter") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = StudioColors.Cyan) },
                            placeholder = { Text(t(lang, "picker_search_hint")) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StudioColors.Cyan,
                                unfocusedBorderColor = StudioColors.Border,
                                focusedTextColor = StudioColors.TextPrimary,
                                unfocusedTextColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("picker_search_input"),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            formatOptions.forEach { fmt ->
                                val chipInteraction = remember { MutableInteractionSource() }
                                val isFmtSelected = selectedFormat == fmt
                                FilterChip(
                                    selected = isFmtSelected,
                                    onClick = { selectedFormat = fmt },
                                    interactionSource = chipInteraction,
                                    label = {
                                        Text(
                                            if (fmt == "ALL") t(lang, "picker_all_formats") else fmt,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StudioColors.Cyan.copy(alpha = 0.2f),
                                        selectedLabelColor = StudioColors.Cyan,
                                    ),
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 40.dp)
                                        .lit(chipInteraction, MaterialTheme.colorScheme.primary, selected = isFmtSelected, cornerRadius = 10.dp),
                                )
                            }
                        }
                    }
                }

                if (uiState.isScanningDeviceAudio && uiState.deviceAudios.isEmpty()) {
                    item(key = "picker_loading", contentType = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = StudioColors.Cyan)
                        }
                    }
                } else if (selectedTab == 1 && selectedFolder == null) {
                    if (foldersSummary.isEmpty()) {
                        item(key = "picker_empty_folders", contentType = "empty") {
                            EmptyLibraryCard(t(lang, "picker_empty"))
                        }
                    } else {
                        itemsIndexed(
                            items = foldersSummary,
                            key = { _, entry -> "folder_${entry.key}" },
                            contentType = { _, _ -> "folder_row" },
                        ) { _, entry ->
                            val folderInteraction = remember { MutableInteractionSource() }
                            Card(
                                onClick = { selectedFolder = entry.key },
                                interactionSource = folderInteraction,
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                                border = BorderStroke(1.dp, StudioColors.Border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lit(folderInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 16.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Folder,
                                        contentDescription = entry.key,
                                        tint = StudioColors.Amber,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.key,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${entry.value.size} ملف صوتي",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (selectedTab == 1 && selectedFolder != null) {
                        item(key = "folder_back_chip", contentType = "folder_back") {
                            val folderBackInteraction = remember { MutableInteractionSource() }
                            OutlinedButton(
                                onClick = { selectedFolder = null },
                                interactionSource = folderBackInteraction,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .lit(folderBackInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 12.dp),
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("${selectedFolder ?: ""} • ${t(lang, "back")}")
                            }
                        }
                    }

                    if (filteredItems.isEmpty()) {
                        item(key = "picker_empty_items", contentType = "empty") {
                            EmptyLibraryCard(t(lang, "picker_empty"))
                        }
                    } else {
                        itemsIndexed(
                            items = filteredItems,
                            key = { _, item -> "audio_${item.id}" },
                            contentType = { _, _ -> "audio_row" },
                        ) { index, item ->
                            val isPlayingThis = previewingUri == item.uriString
                            DeviceAudioRowCard(
                                serialNumber = index + 1,
                                item = item,
                                isPlayingPreview = isPlayingThis,
                                selectLabel = t(lang, "picker_select_file"),
                                onTogglePreview = { togglePreview(item) },
                                onSelect = {
                                    stopPreview()
                                    when (target) {
                                        AudioPickerTarget.IMPORT_MAIN,
                                        AudioPickerTarget.LIBRARY_BROWSE,
                                        -> viewModel.importDeviceAudioItem(item)
                                        AudioPickerTarget.APPEND_CLIP -> viewModel.appendDeviceAudioItem(item)
                                        AudioPickerTarget.MIX_BACKGROUND -> viewModel.loadMixBackgroundDeviceItem(item)
                                    }
                                    onPickedComplete()
                                },
                            )
                        }
                    }
                }
            }

            item(key = "picker_bottom_spacer", contentType = "spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun EmptyLibraryCard(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioColors.CardSurface,
        border = BorderStroke(1.dp, StudioColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = StudioColors.TextMuted)
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextSecondary)
        }
    }
}

@Composable
private fun DeviceAudioRowCard(
    serialNumber: Int,
    item: DeviceAudioItem,
    isPlayingPreview: Boolean,
    selectLabel: String,
    onTogglePreview: () -> Unit,
    onSelect: () -> Unit,
) {
    val cardInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onSelect,
        interactionSource = cardInteraction,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
        border = BorderStroke(
            1.dp,
            if (isPlayingPreview) StudioColors.Cyan else StudioColors.Border,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .lit(cardInteraction, MaterialTheme.colorScheme.primary, selected = isPlayingPreview, cornerRadius = 16.dp)
            .testTag("device_audio_card_${item.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StudioColors.ElevatedSurface,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$serialNumber",
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = StudioColors.Cyan,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatDurationMs(item.durationMs),
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = StudioColors.Cyan,
                    )
                    Text(
                        text = MediaStoreAudioRepository.formatSize(item.sizeBytes),
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = StudioColors.TextSecondary,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StudioColors.Violet.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = item.extension,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = StudioColors.Violet,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            val previewInteraction = remember { MutableInteractionSource() }
            IconButton(
                onClick = onTogglePreview,
                interactionSource = previewInteraction,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isPlayingPreview) StudioColors.Cyan else StudioColors.ElevatedSurface,
                    contentColor = if (isPlayingPreview) StudioColors.BgTop else StudioColors.Cyan,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .lit(previewInteraction, MaterialTheme.colorScheme.primary, selected = isPlayingPreview, cornerRadius = 24.dp)
                    .testTag("device_audio_preview_${item.id}"),
            ) {
                Icon(
                    imageVector = if (isPlayingPreview) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Preview",
                )
            }

            val selectInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = onSelect,
                interactionSource = selectInteraction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Cyan.copy(alpha = 0.18f),
                    contentColor = StudioColors.Cyan,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .lit(selectInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 10.dp),
            ) {
                Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(selectLabel, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
