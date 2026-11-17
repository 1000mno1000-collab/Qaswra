package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.formatDurationMs
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.BUILT_IN_PRESETS
import com.example.model.ExportAudioFormat
import com.example.model.StudioPreset
import com.example.ui.components.ExportFormatDialog
import com.example.ui.components.StudioFocusTopBar
import com.example.ui.components.StudioScrollColumn
import com.example.ui.theme.StudioColors
import com.example.ui.theme.TelemetryTextStyle
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel

@Composable
fun PresetsScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var newPresetName by remember { mutableStateOf("") }
    var newPresetDesc by remember { mutableStateOf("") }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_presets_title"),
            subtitle = t(lang, "section_presets_subtitle"),
            language = lang,
            onBack = onBack,
        )

        // Save Custom Preset Form
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
                Text(
                    text = t(lang, "save_custom_preset"),
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Cyan,
                    unfocusedBorderColor = StudioColors.Border,
                    focusedTextColor = StudioColors.TextPrimary,
                    unfocusedTextColor = StudioColors.TextPrimary,
                )

                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    singleLine = true,
                    label = { Text(t(lang, "preset_name_hint")) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_name_input"),
                )

                OutlinedTextField(
                    value = newPresetDesc,
                    onValueChange = { newPresetDesc = it },
                    singleLine = true,
                    label = {
                        Text(if (lang == AppLanguage.EN) "Description (optional)" else "وصف مختصر (اختياري)")
                    },
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_desc_input"),
                )

                Button(
                    onClick = {
                        viewModel.saveCustomPreset(
                            name = newPresetName.trim(),
                            description = newPresetDesc.trim().ifEmpty {
                                if (lang == AppLanguage.EN) "Custom DSP Rack" else "إعدادات استوديو مخصصة"
                            },
                        )
                        newPresetName = ""
                        newPresetDesc = ""
                    },
                    enabled = newPresetName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.Cyan,
                        contentColor = StudioColors.BgTop,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("preset_save_custom_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t(lang, "save_custom_preset"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (uiState.customPresets.isNotEmpty()) {
            Text(
                text = t(lang, "custom_presets"),
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.Violet,
            )
            uiState.customPresets.forEach { preset ->
                PresetRowCard(
                    preset = preset,
                    isActive = uiState.activePresetId == preset.id,
                    lang = lang,
                    onApply = { viewModel.applyPreset(preset) },
                    onDelete = { viewModel.deleteCustomPreset(preset.id) },
                )
            }
        }

        Text(
            text = t(lang, "built_in_presets"),
            style = MaterialTheme.typography.titleMedium,
            color = StudioColors.Cyan,
        )
        BUILT_IN_PRESETS.forEach { preset ->
            PresetRowCard(
                preset = preset,
                isActive = uiState.activePresetId == preset.id,
                lang = lang,
                onApply = { viewModel.applyPreset(preset) },
                onDelete = null,
            )
        }
    }
}

@Composable
private fun PresetRowCard(
    preset: StudioPreset,
    isActive: Boolean,
    lang: AppLanguage,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val activeCount = preset.config.chain.count { it.enabled }
    Card(
        onClick = onApply,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) StudioColors.ElevatedSurface else StudioColors.CardSurface,
        ),
        border = BorderStroke(1.dp, if (isActive) StudioColors.Cyan else StudioColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preset_card_${preset.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = preset.name,
                tint = if (isActive) StudioColors.Cyan else StudioColors.Violet,
                modifier = Modifier.size(26.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (lang == AppLanguage.EN) "$activeCount effects" else "$activeCount مؤثر",
                    style = TelemetryTextStyle.copy(fontSize = 11.sp),
                    color = StudioColors.Cyan,
                )
            }
            if (isActive) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = StudioColors.Cyan,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = StudioColors.RoseBright),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("preset_delete_${preset.id}"),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = t(lang, "delete"))
                }
            }
        }
    }
}

@Composable
fun ProjectsExportScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
) {
    val lang = uiState.language
    var exportDialogOpen by remember { mutableStateOf(false) }

    StudioScrollColumn {
        StudioFocusTopBar(
            title = t(lang, "section_projects_title"),
            subtitle = t(lang, "section_projects_subtitle"),
            language = lang,
            onBack = onBack,
        )

        // Active Project Name + Save / New Project
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
                Text(
                    text = t(lang, "active_project"),
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                OutlinedTextField(
                    value = uiState.activeProjectName,
                    onValueChange = { viewModel.setActiveProjectName(it) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_name_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.saveCurrentProject() },
                        enabled = !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Emerald,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("project_save_button"),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "save_project"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = { viewModel.newProject() },
                        border = BorderStroke(1.dp, StudioColors.BorderBright),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("project_new_button"),
                    ) {
                        Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "new_project"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Mastered Export Card
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
                Text(
                    text = if (lang == AppLanguage.EN) "Mastered Audio Export" else "التصدير الاحترافي بالمعالجة الكاملة",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                uiState.exportProgress?.let { prog ->
                    Text(
                        text = t(lang, "exporting_progress"),
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.Cyan,
                    )
                    LinearProgressIndicator(
                        progress = { prog.coerceIn(0f, 1f) },
                        color = StudioColors.Cyan,
                        trackColor = StudioColors.BgTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            viewModel.exportRenderedAudio(
                                ExportAudioFormat.M4A,
                                uiState.activeProjectName.ifBlank { "VoicePro_Master" },
                                shareAfter = false,
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("export_quick_m4a_button"),
                    ) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "export_mp3"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.exportRenderedAudio(
                                ExportAudioFormat.WAV,
                                uiState.activeProjectName.ifBlank { "VoicePro_Master" },
                                shareAfter = false,
                            )
                        },
                        enabled = uiState.hasAudio && !uiState.isBusy,
                        border = BorderStroke(1.dp, StudioColors.Emerald),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("export_quick_wav_button"),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t(lang, "export_wav"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                OutlinedButton(
                    onClick = { exportDialogOpen = true },
                    enabled = uiState.hasAudio && !uiState.isBusy,
                    border = BorderStroke(1.dp, StudioColors.Violet),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Violet),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("projects_open_export_dialog_button"),
                ) {
                    Text(t(lang, "choose_export_format_title"))
                }
            }
        }

        // Saved Projects List
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
                Text(
                    text = t(lang, "saved_projects"),
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                )

                if (uiState.projects.isEmpty()) {
                    Text(
                        text = if (lang == AppLanguage.EN) "No saved projects yet." else "لا توجد مشاريع محفوظة بعد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                    )
                } else {
                    uiState.projects.forEach { proj ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (proj.id == uiState.activeProjectId) StudioColors.ElevatedSurface else StudioColors.BgMid,
                            border = BorderStroke(
                                1.dp,
                                if (proj.id == uiState.activeProjectId) StudioColors.Cyan else StudioColors.Border,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = proj.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${formatDurationMs(proj.durationMs)} • ${proj.sampleRate} Hz",
                                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                                        color = StudioColors.TextSecondary,
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.loadProject(proj) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = StudioColors.ElevatedSurface,
                                        contentColor = StudioColors.Cyan,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("project_open_${proj.id}"),
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = t(lang, "load"))
                                }

                                IconButton(
                                    onClick = { viewModel.deleteProject(proj) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = StudioColors.ElevatedSurface,
                                        contentColor = StudioColors.RoseBright,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("project_delete_${proj.id}"),
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = t(lang, "delete"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ExportFormatDialog(
        open = exportDialogOpen,
        language = lang,
        initialFileName = uiState.activeProjectName.ifBlank { "VoicePro_Master" },
        onDismiss = { exportDialogOpen = false },
        onSaveToDevice = { fmt, name -> viewModel.exportRenderedAudio(fmt, name, shareAfter = false) },
        onShareAudio = { fmt, name -> viewModel.exportRenderedAudio(fmt, name, shareAfter = true) },
    )
}
