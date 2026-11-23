package com.ronova.config.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ronova.config.data.ThemeSettings
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette
import com.ronova.config.ui.theme.hsvColor
import com.ronova.config.ui.theme.toHexString
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radial HSV wheel (angle = hue, radius = saturation) + brightness slider + hex preview.
 * Drag anywhere on the wheel; the puck follows the finger continuously.
 */
@Composable
fun ColorWheelPicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: Dp = 200.dp,
    label: String? = null
) {
    val latestOnChange by rememberUpdatedState(onChange)
    val latestBrightness by rememberUpdatedState(brightness)
    val latestHue by rememberUpdatedState(hue)
    val latestSaturation by rememberUpdatedState(saturation)
    val selected = hsvColor(hue, saturation, brightness)
    val hueColors = androidx.compose.runtime.remember {
        List(7) { Color.hsv((it * 60f).coerceAtMost(360f), 1f, 1f) }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = RonovaPalette.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Canvas(
            Modifier
                .size(wheelSize)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun track(pos: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = minOf(cx, cy)
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val dist = sqrt(dx * dx + dy * dy)
                            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (deg < 0f) deg += 360f
                            latestOnChange(deg, (dist / radius).coerceIn(0f, 1f), latestBrightness)
                        }
                        track(down.position)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    track(change.position)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val r = this.size.minDimension / 2f
            val c = center
            drawCircle(brush = Brush.sweepGradient(hueColors, center = c), radius = r, center = c)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = c, radius = r),
                radius = r,
                center = c
            )
            drawCircle(color = Color.Black.copy(alpha = 1f - latestBrightness), radius = r, center = c)
            drawCircle(color = RonovaPalette.TextPrimary.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(2.dp.toPx()))
            drawCircle(color = RonovaPalette.Border, radius = r - 6.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))

            val a = Math.toRadians(latestHue.toDouble())
            val px = c.x + (cos(a) * r * latestSaturation).toFloat()
            val py = c.y + (sin(a) * r * latestSaturation).toFloat()
            drawCircle(Color.Black.copy(alpha = 0.35f), 13.dp.toPx(), Offset(px, py))
            drawCircle(Color.White, 11.dp.toPx(), Offset(px, py), style = Stroke(3.dp.toPx()))
            drawCircle(selected, 8.dp.toPx(), Offset(px, py))
        }
        Spacer(Modifier.height(12.dp))
        BrightnessSlider(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onBrightness = { v -> latestOnChange(latestHue, latestSaturation, v) },
            modifier = Modifier.width(wheelSize)
        )
        Spacer(Modifier.height(8.dp))
        HexPill(selected)
    }
}

@Composable
private fun BrightnessSlider(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onBrightness: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latest by rememberUpdatedState(onBrightness)
    val full = hsvColor(hue, saturation, 1f)
    val current = hsvColor(hue, saturation, brightness)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    latest((down.position.x / w).coerceIn(0f, 1f))
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                latest((change.position.x / w).coerceIn(0f, 1f))
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val h = this.size.height
        val trackH = 12.dp.toPx()
        val top = (h - trackH) / 2f
        val radius = CornerRadius(trackH / 2f)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, full)),
            topLeft = Offset(0f, top),
            size = Size(this.size.width, trackH),
            cornerRadius = radius
        )
        drawRoundRect(
            color = RonovaPalette.TextPrimary.copy(alpha = 0.35f),
            topLeft = Offset(0f, top),
            size = Size(this.size.width, trackH),
            cornerRadius = radius,
            style = Stroke(1.dp.toPx())
        )
        val pad = 12.dp.toPx()
        val x = (brightness * this.size.width).coerceIn(pad, this.size.width - pad)
        drawCircle(Color.White, 12.dp.toPx(), Offset(x, h / 2f), style = Stroke(3.dp.toPx()))
        drawCircle(current, 9.dp.toPx(), Offset(x, h / 2f))
    }
}

@Composable
private fun HexPill(color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(RonovaPalette.Surface)
            .border(1.dp, RonovaPalette.Border, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, RonovaPalette.TextPrimary.copy(alpha = 0.4f), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            color.toHexString(),
            style = MaterialTheme.typography.labelLarge,
            color = RonovaPalette.TextPrimary
        )
    }
}

/**
 * One wheel normally; two wheels (side by side when wide, stacked when narrow) in Gradient Mode,
 * followed by a live preview bar of the resulting accent.
 */
@Composable
fun DualColorWheelPicker(
    theme: ThemeSettings,
    onThemeChange: (ThemeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalRonova.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = theme.gradientMode && maxWidth >= 440.dp
            val wheel: Dp = when {
                sideBySide -> 170.dp
                maxWidth < 224.dp -> maxWidth - 24.dp
                else -> 200.dp
            }
            val primary: @Composable () -> Unit = {
                ColorWheelPicker(
                    hue = theme.hue,
                    saturation = theme.saturation,
                    brightness = theme.brightness,
                    onChange = { h, s, v ->
                        onThemeChange(theme.copy(hue = h, saturation = s, brightness = v))
                    },
                    wheelSize = wheel,
                    label = if (theme.gradientMode) "Primary" else "Accent colour"
                )
            }
            val secondary: @Composable () -> Unit = {
                ColorWheelPicker(
                    hue = theme.hue2,
                    saturation = theme.saturation2,
                    brightness = theme.brightness2,
                    onChange = { h, s, v ->
                        onThemeChange(theme.copy(hue2 = h, saturation2 = s, brightness2 = v))
                    },
                    wheelSize = wheel,
                    label = "Secondary"
                )
            }
            if (!theme.gradientMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { primary() }
            } else if (sideBySide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    primary()
                    secondary()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    primary()
                    secondary()
                }
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(c.horizontalBrush())
        )
    }
}
