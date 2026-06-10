package net.mhanak.yama.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Compact HSV seed-colour picker: a live preview swatch plus hue / saturation / brightness sliders.
 *
 * Owns its HSV state (seeded once from [color]) and reports the resulting colour through [onColorChange]
 * as the user drags — the stored colour is downstream of these sliders, not the other way around, which
 * avoids float round-trip jitter while dragging.
 */
@Composable
fun SeedColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember { color.toHsv() }
    var hue by remember { mutableFloatStateOf(initial.first) }
    var saturation by remember { mutableFloatStateOf(initial.second) }
    var brightness by remember { mutableFloatStateOf(initial.third) }

    fun emit() = onColorChange(Color.hsv(hue, saturation, brightness))

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.hsv(hue, saturation, brightness)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            LabeledSlider("Hue", hue, 0f..360f) { hue = it; emit() }
            LabeledSlider("Saturation", saturation, 0f..1f) { saturation = it; emit() }
            LabeledSlider("Brightness", brightness, 0f..1f) { brightness = it; emit() }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

/** Decompose an opaque colour into hue (0..360), saturation (0..1) and value/brightness (0..1). */
private fun Color.toHsv(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val delta = mx - mn
    var hue = when {
        delta == 0f -> 0f
        mx == r -> 60f * (((g - b) / delta) % 6f)
        mx == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    if (hue < 0f) hue += 360f
    val saturation = if (mx == 0f) 0f else delta / mx
    return Triple(hue, saturation, mx)
}
