package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.export.ExportAudioFormat
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class FormatExportDialogState(
    val open: () -> Unit
)

@Composable
fun rememberFormatExportDialog(
    initialFileName: String,
    onExportSelected: (Uri, ExportAudioFormat, String) -> Unit
): FormatExportDialogState {
    var showDialog by remember { mutableStateOf(false) }
    var editableName by remember(initialFileName) {
        mutableStateOf(initialFileName.ifBlank { "VoicePro_Export" })
    }
    var selectedFormat by remember { mutableStateOf(ExportAudioFormat.MP3) }

    val mp3Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportAudioFormat.MP3.mimeType)
    ) { uri -> if (uri != null) onExportSelected(uri, ExportAudioFormat.MP3, editableName) }

    val wavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportAudioFormat.WAV.mimeType)
    ) { uri -> if (uri != null) onExportSelected(uri, ExportAudioFormat.WAV, editableName) }

    val m4aLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportAudioFormat.M4A.mimeType)
    ) { uri -> if (uri != null) onExportSelected(uri, ExportAudioFormat.M4A, editableName) }

    val oggLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportAudioFormat.OGG.mimeType)
    ) { uri -> if (uri != null) onExportSelected(uri, ExportAudioFormat.OGG, editableName) }

    val flacLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportAudioFormat.FLAC.mimeType)
    ) { uri -> if (uri != null) onExportSelected(uri, ExportAudioFormat.FLAC, editableName) }

    if (showDialog) {
        ExportFormatSelectorDialog(
            fileName = editableName,
            onFileNameChange = { editableName = it },
            selectedFormat = selectedFormat,
            onSelectFormat = { selectedFormat = it },
            onDismiss = { showDialog = false },
            onConfirm = {
                showDialog = false
                val cleanBase = editableName
                    .trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "VoicePro_Export" }
                val suggestedName = "$cleanBase.${selectedFormat.extension}"
                when (selectedFormat) {
                    ExportAudioFormat.MP3 -> mp3Launcher.launch(suggestedName)
                    ExportAudioFormat.WAV -> wavLauncher.launch(suggestedName)
                    ExportAudioFormat.M4A -> m4aLauncher.launch(suggestedName)
                    ExportAudioFormat.OGG -> oggLauncher.launch(suggestedName)
                    ExportAudioFormat.FLAC -> flacLauncher.launch(suggestedName)
                }
            }
        )
    }

    return remember {
        FormatExportDialogState(
            open = {
                editableName = initialFileName.ifBlank { "VoicePro_Export" }
                showDialog = true
            }
        )
    }
}

@Composable
fun ExportFormatSelectorDialog(
    fileName: String,
    onFileNameChange: (String) -> Unit,
    selectedFormat: ExportAudioFormat,
    onSelectFormat: (ExportAudioFormat) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = stringResource(R.string.choose_export_format_title),
                style = MaterialTheme.typography.titleLarge,
                color = CyanGlow
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = onFileNameChange,
                    label = { Text("اسم الملف النهائي") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_dialog_filename_input")
                )

                Spacer(Modifier.height(2.dp))

                ExportAudioFormat.entries.forEach { format ->
                    val isSelected = selectedFormat == format
                    Surface(
                        onClick = { onSelectFormat(format) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) CyanGlow.copy(alpha = 0.16f) else StudioObsidian,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) CyanGlow else StudioBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_format_option_${format.extension}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.AudioFile,
                                contentDescription = null,
                                tint = if (isSelected) CyanGlow else TextSecondary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${format.displayName} (.${format.extension})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) CyanGlow else TextPrimary
                                )
                                Text(
                                    text = format.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldActive,
                    contentColor = StudioObsidian
                ),
                modifier = Modifier.testTag("export_format_confirm_button")
            ) {
                Text("اختيار مكان الحفظ", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("export_format_cancel_button")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
