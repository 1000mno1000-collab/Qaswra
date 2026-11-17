package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.AppLanguage
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.StudioUiState

@Composable
fun SettingsScreen(
    uiState: StudioUiState,
    onSelectLanguage: (AppLanguage) -> Unit,
    onToggleAutoLoop: () -> Unit,
    onToggleNormalizeBeforeExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Language & Layout Direction Card (Persisted in Jetpack DataStore)
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = CyanGlow)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_language_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = CyanGlow
                    )
                }
                Text(
                    text = stringResource(R.string.language_direction_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                AppLanguage.entries.forEach { lang ->
                    val selected = lang == uiState.appLanguage
                    Surface(
                        onClick = {
                            println("DIAG SettingsScreen Surface clicked lang=$lang")
                            onSelectLanguage(lang)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) EmeraldActive.copy(alpha = 0.14f) else StudioObsidian,
                        border = BorderStroke(1.dp, if (selected) EmeraldActive else StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_lang_${lang.tag}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) EmeraldActive else TextPrimary
                                )
                                Text(
                                    text = if (lang.isRtl) "RTL" else "LTR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldActive
                                )
                            }
                        }
                    }
                }
            }
        }

        // Playback & Export Preferences Card
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, VioletGlow.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_playback_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = VioletGlow
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Repeat,
                            contentDescription = null,
                            tint = if (uiState.isAutoLoopEnabled) EmeraldActive else TextSecondary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.loop_repeat),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                    Switch(
                        checked = uiState.isAutoLoopEnabled,
                        onCheckedChange = { onToggleAutoLoop() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioObsidian,
                            checkedTrackColor = EmeraldActive
                        ),
                        modifier = Modifier.testTag("settings_auto_loop_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = if (uiState.normalizeBeforeExport) CyanGlow else TextSecondary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.normalize_audio),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                    Switch(
                        checked = uiState.normalizeBeforeExport,
                        onCheckedChange = { onToggleNormalizeBeforeExport() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioObsidian,
                            checkedTrackColor = CyanGlow
                        ),
                        modifier = Modifier.testTag("settings_normalize_switch")
                    )
                }
            }
        }
    }
}
