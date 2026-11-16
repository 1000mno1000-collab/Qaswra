package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DspConfig
import com.example.model.EffectType
import com.example.model.EqualizerParams
import com.example.ui.components.StudioParamSlider
import com.example.ui.theme.AmberWarm
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import java.util.Locale

@Composable
fun EffectDetailScreen(
    effectType: EffectType,
    dspConfig: DspConfig,
    gainReductionDb: Float,
    onToggleEffect: (EffectType) -> Unit,
    onUpdateConfig: ((DspConfig) -> DspConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = dspConfig.chain.firstOrNull { it.type == effectType }?.enabled == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Effect Activation Header Card
        Surface(
            color = StudioSurface,
            border = BorderStroke(1.dp, if (enabled) CyanGlow.copy(alpha = 0.6f) else StudioBorder),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(effectType.titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (enabled) "المؤثر مفعّل في السلسلة" else "المؤثر متوقف حالياً",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) EmeraldActive else TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggleEffect(effectType) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StudioObsidian,
                        checkedTrackColor = CyanGlow
                    ),
                    modifier = Modifier.testTag("detail_effect_toggle")
                )
            }
        }

        when (effectType) {
            EffectType.EQUALIZER -> {
                EqResponseCurveCard(dspConfig.equalizer)
                val eq = dspConfig.equalizer
                StudioParamSlider(
                    label = "جهير عميق (60 Hz)",
                    value = eq.band60HzDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", eq.band60HzDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(equalizer = it.equalizer.copy(band60HzDb = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "دفء منخفض (250 Hz)",
                    value = eq.band250HzDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", eq.band250HzDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(equalizer = it.equalizer.copy(band250HzDb = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "وضوح الوسط (1 kHz)",
                    value = eq.band1kHzDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", eq.band1kHzDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(equalizer = it.equalizer.copy(band1kHzDb = v)) } },
                    accentColor = EmeraldActive
                )
                StudioParamSlider(
                    label = "حضور الصوت (4 kHz)",
                    value = eq.band4kHzDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", eq.band4kHzDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(equalizer = it.equalizer.copy(band4kHzDb = v)) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = "بريق وهواء (12 kHz)",
                    value = eq.band12kHzDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", eq.band12kHzDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(equalizer = it.equalizer.copy(band12kHzDb = v)) } },
                    accentColor = CyanGlow
                )
            }

            EffectType.BASS_BOOST -> {
                val bb = dspConfig.bassBoost
                StudioParamSlider(
                    label = "قوة تعزيز الجهير",
                    value = bb.gainDb,
                    valueRange = 0f..12f,
                    formattedValue = String.format(Locale.US, "+%.1f dB", bb.gainDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(bassBoost = it.bassBoost.copy(gainDb = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "تردد القطع السفلي",
                    value = bb.frequencyHz,
                    valueRange = 50f..180f,
                    formattedValue = "${bb.frequencyHz.toInt()} Hz",
                    onValueChange = { v -> onUpdateConfig { it.copy(bassBoost = it.bassBoost.copy(frequencyHz = v)) } },
                    accentColor = CyanGlow
                )
            }

            EffectType.TREBLE_CONTROL -> {
                val tr = dspConfig.treble
                StudioParamSlider(
                    label = "تعديل الحدّة والوضوح",
                    value = tr.gainDb,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f dB", tr.gainDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(treble = it.treble.copy(gainDb = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "تردد البدء",
                    value = tr.frequencyHz,
                    valueRange = 3500f..12000f,
                    formattedValue = "${tr.frequencyHz.toInt()} Hz",
                    onValueChange = { v -> onUpdateConfig { it.copy(treble = it.treble.copy(frequencyHz = v)) } },
                    accentColor = AmberWarm
                )
            }

            EffectType.PITCH_SHIFT -> {
                val ps = dspConfig.pitchShift
                StudioParamSlider(
                    label = "إزاحة طبقة الصوت (Semitones)",
                    value = ps.semitones,
                    valueRange = -12f..12f,
                    formattedValue = String.format(Locale.US, "%+.1f st", ps.semitones),
                    onValueChange = { v -> onUpdateConfig { it.copy(pitchShift = it.pitchShift.copy(semitones = v)) } },
                    accentColor = VioletGlow
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(-7f to "عميق -7", -3f to "خشن -3", 0f to "طبيعي", 4f to "حاد +4", 7f to "مرتفع +7").forEach { (st, title) ->
                        FilterChip(
                            selected = kotlin.math.abs(ps.semitones - st) < 0.2f,
                            onClick = { onUpdateConfig { it.copy(pitchShift = it.pitchShift.copy(semitones = st)) } },
                            label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletGlow.copy(alpha = 0.25f),
                                selectedLabelColor = VioletGlow
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            EffectType.TIME_STRETCH -> {
                val ts = dspConfig.timeStretch
                StudioParamSlider(
                    label = "سرعة التشغيل دون تغيير الطبقة",
                    value = ts.rate,
                    valueRange = 0.5f..2.0f,
                    formattedValue = String.format(Locale.US, "%.2fx", ts.rate),
                    onValueChange = { v -> onUpdateConfig { it.copy(timeStretch = it.timeStretch.copy(rate = v)) } },
                    accentColor = AmberWarm
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.75f to "بطيء 0.75x", 1.0f to "طبيعي 1.0x", 1.25f to "سريع 1.25x", 1.5f to "1.5x").forEach { (r, title) ->
                        FilterChip(
                            selected = kotlin.math.abs(ts.rate - r) < 0.03f,
                            onClick = { onUpdateConfig { it.copy(timeStretch = it.timeStretch.copy(rate = r)) } },
                            label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberWarm.copy(alpha = 0.25f),
                                selectedLabelColor = AmberWarm
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            EffectType.CHORUS -> {
                val ch = dspConfig.chorus
                StudioParamSlider(
                    label = "سرعة التذبذب (Rate)",
                    value = ch.rateHz,
                    valueRange = 0.1f..5.0f,
                    formattedValue = String.format(Locale.US, "%.2f Hz", ch.rateHz),
                    onValueChange = { v -> onUpdateConfig { it.copy(chorus = it.chorus.copy(rateHz = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "عمق الاتساع (Depth)",
                    value = ch.depth,
                    valueRange = 0f..1f,
                    formattedValue = "${(ch.depth * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(chorus = it.chorus.copy(depth = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "مزج التأثير (Mix)",
                    value = ch.mix,
                    valueRange = 0f..1f,
                    formattedValue = "${(ch.mix * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(chorus = it.chorus.copy(mix = v)) } },
                    accentColor = EmeraldActive
                )
            }

            EffectType.FLANGER -> {
                val fl = dspConfig.flanger
                StudioParamSlider(
                    label = "سرعة المسح (Rate)",
                    value = fl.rateHz,
                    valueRange = 0.05f..3.0f,
                    formattedValue = String.format(Locale.US, "%.2f Hz", fl.rateHz),
                    onValueChange = { v -> onUpdateConfig { it.copy(flanger = it.flanger.copy(rateHz = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "العمق (Depth)",
                    value = fl.depth,
                    valueRange = 0f..1f,
                    formattedValue = "${(fl.depth * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(flanger = it.flanger.copy(depth = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "التغذية الراجعة (Resonance)",
                    value = fl.feedback,
                    valueRange = 0f..0.85f,
                    formattedValue = "${(fl.feedback * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(flanger = it.flanger.copy(feedback = v)) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = "مزج التأثير (Mix)",
                    value = fl.mix,
                    valueRange = 0f..1f,
                    formattedValue = "${(fl.mix * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(flanger = it.flanger.copy(mix = v)) } },
                    accentColor = EmeraldActive
                )
            }

            EffectType.PHASER -> {
                val ph = dspConfig.phaser
                StudioParamSlider(
                    label = "سرعة الطور (Rate)",
                    value = ph.rateHz,
                    valueRange = 0.05f..4.0f,
                    formattedValue = String.format(Locale.US, "%.2f Hz", ph.rateHz),
                    onValueChange = { v -> onUpdateConfig { it.copy(phaser = it.phaser.copy(rateHz = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "مدى الانزياح (Depth)",
                    value = ph.depth,
                    valueRange = 0f..1f,
                    formattedValue = "${(ph.depth * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(phaser = it.phaser.copy(depth = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "التغذية الراجعة",
                    value = ph.feedback,
                    valueRange = 0f..0.85f,
                    formattedValue = "${(ph.feedback * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(phaser = it.phaser.copy(feedback = v)) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = "المزج (Mix)",
                    value = ph.mix,
                    valueRange = 0f..1f,
                    formattedValue = "${(ph.mix * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(phaser = it.phaser.copy(mix = v)) } },
                    accentColor = EmeraldActive
                )
            }

            EffectType.DISTORTION -> {
                val ds = dspConfig.distortion
                StudioParamSlider(
                    label = "شدة التشبيع (Drive)",
                    value = ds.drive,
                    valueRange = 0f..1f,
                    formattedValue = "${(ds.drive * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(distortion = it.distortion.copy(drive = v)) } },
                    accentColor = CoralRecord
                )
                StudioParamSlider(
                    label = "دفء النغمة (Tone)",
                    value = ds.tone,
                    valueRange = 0f..1f,
                    formattedValue = "${(ds.tone * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(distortion = it.distortion.copy(tone = v)) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = "المزج (Mix)",
                    value = ds.mix,
                    valueRange = 0f..1f,
                    formattedValue = "${(ds.mix * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(distortion = it.distortion.copy(mix = v)) } },
                    accentColor = CyanGlow
                )
            }

            EffectType.COMPRESSOR -> {
                val cp = dspConfig.compressor
                Surface(
                    color = StudioSurface,
                    border = BorderStroke(1.dp, StudioBorder),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("مقدار الضغط الحي (GR)", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                text = String.format(Locale.US, "-%.1f dB", gainReductionDb),
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberWarm
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (gainReductionDb / 18f).coerceIn(0f, 1f) },
                            color = AmberWarm,
                            trackColor = StudioSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
                StudioParamSlider(
                    label = "عتبة الضغط (Threshold)",
                    value = cp.thresholdDb,
                    valueRange = -40f..0f,
                    formattedValue = "${cp.thresholdDb.toInt()} dB",
                    onValueChange = { v -> onUpdateConfig { it.copy(compressor = it.compressor.copy(thresholdDb = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "نسبة الضغط (Ratio)",
                    value = cp.ratio,
                    valueRange = 1f..20f,
                    formattedValue = String.format(Locale.US, "%.1f : 1", cp.ratio),
                    onValueChange = { v -> onUpdateConfig { it.copy(compressor = it.compressor.copy(ratio = v)) } },
                    accentColor = VioletGlow
                )
                StudioParamSlider(
                    label = "زمن الاستجابة (Attack)",
                    value = cp.attackMs,
                    valueRange = 1f..100f,
                    formattedValue = "${cp.attackMs.toInt()} ms",
                    onValueChange = { v -> onUpdateConfig { it.copy(compressor = it.compressor.copy(attackMs = v)) } },
                    accentColor = EmeraldActive
                )
                StudioParamSlider(
                    label = "زمن التحرير (Release)",
                    value = cp.releaseMs,
                    valueRange = 20f..600f,
                    formattedValue = "${cp.releaseMs.toInt()} ms",
                    onValueChange = { v -> onUpdateConfig { it.copy(compressor = it.compressor.copy(releaseMs = v)) } },
                    accentColor = AmberWarm
                )
                StudioParamSlider(
                    label = "تعويض الشدة (Makeup Gain)",
                    value = cp.makeupDb,
                    valueRange = 0f..18f,
                    formattedValue = String.format(Locale.US, "+%.1f dB", cp.makeupDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(compressor = it.compressor.copy(makeupDb = v)) } },
                    accentColor = CyanGlow
                )
            }

            EffectType.LIMITER -> {
                val lm = dspConfig.limiter
                StudioParamSlider(
                    label = "سقف الحماية (Ceiling)",
                    value = lm.ceilingDb,
                    valueRange = -12f..-0.1f,
                    formattedValue = String.format(Locale.US, "%.1f dB", lm.ceilingDb),
                    onValueChange = { v -> onUpdateConfig { it.copy(limiter = it.limiter.copy(ceilingDb = v)) } },
                    accentColor = EmeraldActive
                )
                StudioParamSlider(
                    label = "زمن التحرير (Release)",
                    value = lm.releaseMs,
                    valueRange = 10f..400f,
                    formattedValue = "${lm.releaseMs.toInt()} ms",
                    onValueChange = { v -> onUpdateConfig { it.copy(limiter = it.limiter.copy(releaseMs = v)) } },
                    accentColor = CyanGlow
                )
            }

            EffectType.NOISE_REDUCTION -> {
                val nr = dspConfig.noiseReduction
                StudioParamSlider(
                    label = "عتبة العزل (Gate Threshold)",
                    value = nr.thresholdDb,
                    valueRange = -65f..-15f,
                    formattedValue = "${nr.thresholdDb.toInt()} dB",
                    onValueChange = { v -> onUpdateConfig { it.copy(noiseReduction = it.noiseReduction.copy(thresholdDb = v)) } },
                    accentColor = CyanGlow
                )
                StudioParamSlider(
                    label = "قوة عزل الضوضاء والهمس",
                    value = nr.amount,
                    valueRange = 0f..1f,
                    formattedValue = "${(nr.amount * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(noiseReduction = it.noiseReduction.copy(amount = v)) } },
                    accentColor = EmeraldActive
                )
            }

            EffectType.ECHO_REVERB -> {
                val er = dspConfig.echoReverb
                StudioParamSlider(
                    label = stringResource(R.string.param_wet_dry),
                    value = er.wetDryMix,
                    valueRange = 0f..1f,
                    formattedValue = "${(er.wetDryMix * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(echoReverb = it.echoReverb.copy(wetDryMix = v)) } }
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_echo_strength),
                    value = er.echoStrength,
                    valueRange = 0f..1f,
                    formattedValue = "${(er.echoStrength * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(echoReverb = it.echoReverb.copy(echoStrength = v)) } }
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_delay_time),
                    value = er.delayTimeMs,
                    valueRange = 20f..1200f,
                    formattedValue = "${er.delayTimeMs.toInt()} ms",
                    onValueChange = { v -> onUpdateConfig { it.copy(echoReverb = it.echoReverb.copy(delayTimeMs = v)) } }
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_reverb_size),
                    value = er.reverbSize,
                    valueRange = 0f..1f,
                    formattedValue = "${(er.reverbSize * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(echoReverb = it.echoReverb.copy(reverbSize = v)) } }
                )
                StudioParamSlider(
                    label = stringResource(R.string.param_reverb_decay),
                    value = er.reverbDecay,
                    valueRange = 0f..0.95f,
                    formattedValue = "${(er.reverbDecay * 100).toInt()}%",
                    onValueChange = { v -> onUpdateConfig { it.copy(echoReverb = it.echoReverb.copy(reverbDecay = v)) } }
                )
            }
        }
    }
}

@Composable
private fun EqResponseCurveCard(eq: EqualizerParams) {
    Surface(
        color = StudioObsidian,
        border = BorderStroke(1.dp, StudioBorder),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            // Zero dB center reference line
            drawLine(
                color = StudioBorder,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1.dp.toPx()
            )

            val bands = listOf(
                eq.band60HzDb,
                eq.band250HzDb,
                eq.band1kHzDb,
                eq.band4kHzDb,
                eq.band12kHzDb
            )
            val points = bands.mapIndexed { idx, db ->
                val x = w * (idx.toFloat() / (bands.size - 1))
                val y = midY - (db / 12f) * (h * 0.42f)
                Offset(x, y)
            }

            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.lastIndex) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val cx = (p0.x + p1.x) / 2f
                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }
            }

            drawPath(
                path = path,
                brush = Brush.horizontalGradient(listOf(VioletGlow, CyanGlow, AmberWarm)),
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            )

            points.forEach { pt ->
                drawCircle(color = CyanGlow, radius = 5.dp.toPx(), center = pt)
            }
        }
    }
}
