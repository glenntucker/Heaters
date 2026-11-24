package dev.heatcontrol.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heatcontrol.protocol.ModeType
import kotlinx.coroutines.delay

/** Colours for content drawn on a normal card vs. on the flame-gradient hero card. */
data class OnColors(val text: Color, val subtle: Color, val track: Color, val chip: Color, val chipSelected: Color?)

@Composable
fun cardColors() = OnColors(
    text = MaterialTheme.colorScheme.onSurface,
    subtle = MaterialTheme.colorScheme.onSurfaceVariant,
    track = MaterialTheme.colorScheme.surfaceContainerHighest,
    chip = MaterialTheme.colorScheme.surfaceContainerHigh,
    chipSelected = null,
)

val heroColors = OnColors(
    text = Color.White,
    subtle = Color.White.copy(alpha = 0.78f),
    track = Color.White.copy(alpha = 0.22f),
    chip = Color.White.copy(alpha = 0.16f),
    chipSelected = Color.White,
)

/**
 * 270° dial with the target temperature in the middle and round −/+ buttons either side.
 * Taps are batched and sent 700 ms after the last one.
 */
@Composable
fun TemperatureDial(
    current: Int?,
    range: IntRange,
    caption: String,
    captionColor: Color,
    colors: OnColors,
    busy: Boolean,
    onSet: (Int) -> Unit,
) {
    var pending by remember { mutableStateOf<Int?>(null) }
    val shown = pending ?: current
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(700)
        if (p != current) onSet(p)
        pending = null
    }
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction by animateFloatAsState(
        (((shown ?: range.first) - range.first).toFloat() / span).coerceIn(0f, 1f), label = "dial",
    )

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RoundButton("−", colors, enabled = shown != null && shown > range.first) {
            pending = ((shown ?: 20) - 1).coerceIn(range)
        }
        Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
            val track = colors.track
            val onHero = colors.chipSelected != null
            Canvas(Modifier.size(176.dp)) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                // Draw from angle 0 inside a 135° rotation so the sweep gradient starts where the arc does.
                rotate(135f) {
                    drawArc(track, 0f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (shown != null) {
                        val brush = if (onHero) Brush.linearGradient(listOf(Color.White, Color.White))
                        else Brush.sweepGradient(
                            // Ends on Ice so the rounded start cap (just "before" 0°) stays blue.
                            0f to Heat.Ice, 0.3f to Heat.Amber, 0.55f to Heat.Ember, 0.75f to Heat.EmberDeep,
                            0.95f to Heat.EmberDeep, 1f to Heat.Ice,
                            center = center,
                        )
                        drawArc(
                            brush, 0f, 270f * fraction.coerceAtLeast(0.02f), false,
                            Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (shown != null) "$shown°" else "--°",
                    style = BigTemp,
                    color = if (busy && pending == null) colors.subtle else colors.text,
                )
                Text(caption, style = MaterialTheme.typography.labelLarge, color = captionColor, textAlign = TextAlign.Center)
            }
        }
        RoundButton("+", colors, enabled = shown != null && shown < range.last) {
            pending = ((shown ?: 20) + 1).coerceIn(range)
        }
    }
}

@Composable
private fun RoundButton(label: String, colors: OnColors, enabled: Boolean, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.chip)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = if (enabled) colors.text else colors.subtle.copy(alpha = 0.4f))
    }
}

private val MODES = listOf(
    ModeType.MANUAL to "Manual", ModeType.ECO to "Eco", ModeType.FROST to "Frost",
    ModeType.SCHEDULE to "Schedule", ModeType.STANDBY to "Off",
)

/** Segmented pill: one rounded track, the selected mode filled with its own colour. */
@Composable
fun ModeSelector(current: ModeType?, colors: OnColors, onSelect: (ModeType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(colors.chip).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MODES.forEach { (m, label) ->
            val selected = current == m
            val fill = colors.chipSelected ?: Heat.modeColor(m)
            val bg by animateColorAsState(if (selected) fill else Color.Transparent, label = "mode")
            val fg = when {
                !selected -> colors.subtle
                colors.chipSelected != null -> Heat.EmberDeep
                else -> Color.White
            }
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).background(bg)
                    .clickable { onSelect(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Small rounded pill button. */
@Composable
fun Pill(label: String, colors: OnColors, modifier: Modifier = Modifier, filled: Color? = null, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(50)).background(filled ?: colors.chip)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = if (filled != null) (if (colors.chipSelected != null) Heat.EmberDeep else Color.White) else colors.text,
        )
    }
}

/** Coloured dot + label, e.g. "● Connected". */
@Composable
fun StatusPill(text: String, color: Color, colors: OnColors, busy: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.chip).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pulse by animateFloatAsState(if (busy) 0.35f else 1f, label = "pulse")
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = pulse)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.text)
    }
}

/** Highlighted strip used for the running boost / advance state. */
@Composable
fun Banner(text: String, accent: Color, colors: OnColors, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = if (colors.chipSelected != null) 0.0f else 0.12f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.text, modifier = Modifier.weight(1f))
        Pill(action, colors, filled = accent, onClick = onAction)
    }
}
