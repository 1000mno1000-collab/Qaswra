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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.ui.components.StudioParamSlider
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import java.util.Locale

@Composable
fun EffectsChainScreen(
    dspConfig: DspConfig,
    onToggleEffect: (EffectType) -> Unit,
    onMoveEffect: (EffectType, Boolean) -> Unit,
    onOpenEffectDetail: (EffectType) -> Unit,
    onUpdateMasterGain: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.chain_order_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            StudioParamSlider(
                label = "الشدة الرئيسية للمخرج (Master Gain)",
                value = dspConfig.masterGainDb,
                valueRange = -12f..12f,
                formattedValue = String.format(Locale.US, "%+.1f dB", dspConfig.masterGainDb),
                onValueChange = onUpdateMasterGain,
                accentColor = EmeraldActive,
                testTag = "master_gain_slider"
            )
            Spacer(Modifier.height(6.dp))
        }

        itemsIndexed(dspConfig.chain, key = { _, slot -> slot.type.name }) { index, slot ->
            val accent = if (slot.enabled) CyanGlow else TextMuted
            Surface(
                onClick = { onOpenEffectDetail(slot.type) },
                shape = RoundedCornerShape(20.dp),
                color = if (slot.enabled) StudioSurface else StudioSurface.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    if (slot.enabled) CyanGlow.copy(alpha = 0.45f) else StudioBorder.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chain_item_${slot.type.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Signal chain index badge
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (slot.enabled) CyanGlow.copy(alpha = 0.18f) else StudioSurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(slot.type.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (slot.enabled) TextPrimary else TextSecondary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = slot.type.category.arabicLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (slot.enabled) VioletGlow else TextMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "تعديل",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }

                    // Reorder buttons
                    IconButton(
                        onClick = { onMoveEffect(slot.type, true) },
                        enabled = index > 0,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = stringResource(R.string.move_up),
                            tint = if (index > 0) TextSecondary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { onMoveEffect(slot.type, false) },
                        enabled = index < dspConfig.chain.lastIndex,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = stringResource(R.string.move_down),
                            tint = if (index < dspConfig.chain.lastIndex) TextSecondary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Switch(
                        checked = slot.enabled,
                        onCheckedChange = { onToggleEffect(slot.type) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioObsidian,
                            checkedTrackColor = CyanGlow,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = StudioSurfaceElevated
                        ),
                        modifier = Modifier.testTag("chain_switch_${slot.type.name.lowercase()}")
                    )
                }
            }
        }
    }
}
