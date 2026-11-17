package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.model.ExportAudioFormat
import com.example.ui.theme.StudioColors

@Composable
fun LanguageBadgeButton(
    currentLanguage: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = StudioColors.ElevatedSurface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.45f)),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag("language_badge_button"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = t(currentLanguage, "app_language_title"),
                tint = StudioColors.Cyan,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = currentLanguage.shortBadge,
                style = MaterialTheme.typography.labelLarge,
                color = StudioColors.TextPrimary,
            )
        }
    }
}

@Composable
fun LanguageSelectorDialog(
    open: Boolean,
    currentLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioColors.CardSurface,
            border = BorderStroke(1.dp, StudioColors.BorderBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = t(currentLanguage, "choose_language_title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = StudioColors.TextPrimary,
                )
                Text(
                    text = t(currentLanguage, "language_direction_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                )

                AppLanguage.entries.forEach { lang ->
                    val selected = lang == currentLanguage
                    Card(
                        onClick = {
                            onSelect(lang)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) StudioColors.Cyan else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("lang_option_${lang.tag}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioColors.TextPrimary,
                                )
                                Text(
                                    text = "${lang.shortBadge} • ${if (lang.isRtl) "RTL" else "LTR"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = lang.nativeName,
                                    tint = StudioColors.Cyan,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("lang_dialog_cancel"),
                    ) {
                        Text(t(currentLanguage, "cancel"), color = StudioColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun ExportFormatDialog(
    open: Boolean,
    language: AppLanguage,
    initialFileName: String,
    onDismiss: () -> Unit,
    onSaveToDevice: (ExportAudioFormat, String) -> Unit,
    onShareAudio: (ExportAudioFormat, String) -> Unit,
) {
    if (!open) return

    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.M4A) }
    var fileName by remember(initialFileName, open) { mutableStateOf(initialFileName) }

    LaunchedEffect(initialFileName, open) {
        if (open) {
            fileName = initialFileName
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioColors.CardSurface,
            border = BorderStroke(1.dp, StudioColors.BorderBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = t(language, "choose_export_format_title"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = StudioColors.TextPrimary,
                )

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    label = {
                        Text(if (language == AppLanguage.EN) "File Name" else "اسم الملف")
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                        focusedLabelColor = StudioColors.Cyan,
                        unfocusedLabelColor = StudioColors.TextSecondary,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_filename_input"),
                )

                ExportAudioFormat.entries.forEach { fmt ->
                    val selected = fmt == selectedFormat
                    Card(
                        onClick = { selectedFormat = fmt },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) StudioColors.ElevatedSurface else StudioColors.BgMid,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) StudioColors.Cyan else StudioColors.Border,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("export_format_${fmt.extension}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${fmt.displayName} (.${fmt.extension})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (selected) StudioColors.Cyan else StudioColors.TextPrimary,
                                )
                                Text(
                                    text = fmt.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = fmt.displayName,
                                    tint = StudioColors.Cyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onDismiss()
                            onSaveToDevice(selectedFormat, fileName.ifBlank { initialFileName })
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("export_confirm_save_button"),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t(language, "save_export_audio"), style = MaterialTheme.typography.labelLarge)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onShareAudio(selectedFormat, fileName.ifBlank { initialFileName })
                            },
                            border = BorderStroke(1.dp, StudioColors.Emerald),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("export_confirm_share_button"),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t(language, "share_audio"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("export_dialog_cancel"),
                        ) {
                            Text(t(language, "cancel"), color = StudioColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
