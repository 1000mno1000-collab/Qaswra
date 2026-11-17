package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.StudioPreset
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow

@Composable
fun PresetsScreen(
    presets: List<StudioPreset>,
    activePresetId: Long?,
    onApplyPreset: (StudioPreset) -> Unit,
    onSaveCustomPreset: (String, String) -> Unit,
    onDeleteCustomPreset: (StudioPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    val customPresets = presets.filter { !it.isBuiltIn }
    val builtInPresets = presets.filter { it.isBuiltIn }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive, contentColor = StudioObsidian),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_custom_preset_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_custom_preset), style = MaterialTheme.typography.titleMedium)
            }
        }

        if (customPresets.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.custom_presets),
                    style = MaterialTheme.typography.titleMedium,
                    color = VioletGlow
                )
            }
            items(customPresets, key = { "custom_${it.id}" }) { preset ->
                PresetCard(
                    preset = preset,
                    isSelected = activePresetId == preset.id,
                    onApply = { onApplyPreset(preset) },
                    onDelete = { onDeleteCustomPreset(preset) }
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.built_in_presets),
                style = MaterialTheme.typography.titleMedium,
                color = CyanGlow
            )
        }

        items(builtInPresets, key = { "builtin_${it.id}" }) { preset ->
            PresetCard(
                preset = preset,
                isSelected = activePresetId == preset.id,
                onApply = { onApplyPreset(preset) },
                onDelete = null
            )
        }
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_custom_preset)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.preset_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("preset_name_input")
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("وصف مختصر (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onSaveCustomPreset(name, desc)
                            showSaveDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PresetCard(
    preset: StudioPreset,
    isSelected: Boolean,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val activeCount = preset.config.chain.count { it.enabled }
    val accent = if (preset.isBuiltIn) CyanGlow else VioletGlow

    Surface(
        onClick = onApply,
        shape = RoundedCornerShape(20.dp),
        color = StudioSurface,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) EmeraldActive else StudioBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preset_card_${preset.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = if (isSelected) EmeraldActive else accent
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(preset.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$activeCount مؤثرات مفعلة • مزج صدى ${(preset.config.echoReverb.wetDryMix * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = CoralRecord
                    )
                }
            }
        }
    }
}
