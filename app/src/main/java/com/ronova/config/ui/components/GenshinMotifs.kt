package com.ronova.config.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ronova.config.data.ModuleId
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// ───────────────────────── Core drawing helpers ─────────────────────────

/** Four-pointed star with concave, curved sides (the classic "sparkle" silhouette). */
internal fun DrawScope.sparkle(
    center: Offset,
    outer: Float,
    inner: Float,
    brush: Brush,
    alpha: Float = 1f,
    rotationDeg: Float = 0f
) {
    val pts = List(4) { i ->
        val a = Math.toRadians(i * 90.0 - 90.0 + rotationDeg)
        Offset(center.x + (cos(a) * outer).toFloat(), center.y + (sin(a) * outer).toFloat())
    }
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 0 until 4) {
        val next = pts[(i + 1) % 4]
        val mid = Math.toRadians(i * 90.0 - 45.0 + rotationDeg)
        val cx = center.x + (cos(mid) * inner).toFloat()
        val cy = center.y + (sin(mid) * inner).toFloat()
        path.quadraticBezierTo(cx, cy, next.x, next.y)
    }
    path.close()
    drawPath(path, brush, alpha = alpha)
}

// ───────────────────────── Emblems ─────────────────────────

/** Celestial crest: large sparkle, a rotated small sparkle and a satellite star, with a soft glow. */
@Composable
fun CelestialStar(
    size: Dp,
    modifier: Modifier = Modifier,
    brush: Brush = LocalRonova.current.brush(),
    glow: Boolean = true
) {
    val c = LocalRonova.current
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val ctr = center
        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.primary.copy(alpha = 0.32f), Color.Transparent),
                    center = ctr,
                    radius = r
                ),
                radius = r,
                center = ctr
            )
        }
        sparkle(ctr, r * 0.92f, r * 0.15f, brush)
        sparkle(ctr, r * 0.5f, r * 0.09f, brush, alpha = 0.55f, rotationDeg = 45f)
        sparkle(Offset(ctr.x + r * 0.62f, ctr.y - r * 0.62f), r * 0.22f, r * 0.05f, brush, alpha = 0.9f)
        sparkle(Offset(ctr.x - r * 0.66f, ctr.y + r * 0.6f), r * 0.14f, r * 0.03f, brush, alpha = 0.7f)
    }
}

/** Primogem-style faceted diamond (status badge). */
@Composable
fun PrimogemDiamond(
    size: Dp,
    modifier: Modifier = Modifier,
    brush: Brush = LocalRonova.current.brush()
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val top = Offset(w * 0.5f, h * 0.04f)
        val right = Offset(w * 0.9f, h * 0.5f)
        val bottom = Offset(w * 0.5f, h * 0.96f)
        val left = Offset(w * 0.1f, h * 0.5f)
        val mid = Offset(w * 0.5f, h * 0.5f)
        fun facet(a: Offset, b: Offset, cc: Offset, alpha: Float) {
            val p = Path()
            p.moveTo(a.x, a.y)
            p.lineTo(b.x, b.y)
            p.lineTo(cc.x, cc.y)
            p.close()
            drawPath(p, brush, alpha = alpha)
        }
        facet(top, left, mid, 1f)
        facet(top, right, mid, 0.78f)
        facet(bottom, left, mid, 0.55f)
        facet(bottom, right, mid, 0.38f)
        val outline = Path()
        outline.moveTo(top.x, top.y)
        outline.lineTo(right.x, right.y)
        outline.lineTo(bottom.x, bottom.y)
        outline.lineTo(left.x, left.y)
        outline.close()
        drawPath(outline, Color.White.copy(alpha = 0.35f), style = Stroke(w * 0.03f, join = StrokeJoin.Round))
        sparkle(Offset(w * 0.36f, h * 0.33f), w * 0.1f, w * 0.02f, SolidColor(Color.White), 0.9f)
    }
}

/** Thin constellation line with nodes and a centre sparkle. */
@Composable
fun ConstellationDivider(modifier: Modifier = Modifier) {
    val c = LocalRonova.current
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val w = this.size.width
        val h = this.size.height
        val xs = listOf(0.03f, 0.18f, 0.35f, 0.5f, 0.65f, 0.82f, 0.97f)
        val ys = listOf(0.6f, 0.3f, 0.7f, 0.5f, 0.7f, 0.3f, 0.6f)
        val points = xs.indices.map { Offset(w * xs[it], h * ys[it]) }
        val line = c.borderBrush(0.55f)
        for (i in 0 until points.size - 1) {
            drawLine(line, points[i], points[i + 1], strokeWidth = 1.dp.toPx())
        }
        points.forEachIndexed { i, p ->
            if (i != 3) {
                drawCircle(c.primary.copy(alpha = 0.85f), 2.2.dp.toPx(), p)
                drawCircle(c.primary.copy(alpha = 0.25f), 4.6.dp.toPx(), p, style = Stroke(0.8.dp.toPx()))
            }
        }
        sparkle(points[3], 8.dp.toPx(), 1.6.dp.toPx(), c.brush())
    }
}

/**
 * Ornate capsule frame with an orbiting node, cardinal diamonds, a double hairline and an
 * optional progress arc travelling along the perimeter. Wrap the master button with it.
 */
@Composable
fun OrbitalRing(
    progress: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val c = LocalRonova.current
    val moving = active || (progress > 0.001f && progress < 0.999f)
    val transition = rememberInfiniteTransition(label = "orbit")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (moving) 2600 else 12000, easing = LinearEasing)),
        label = "orbitPhase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbitPulse"
    )
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val inset = 3.dp.toPx()
            val rect = Rect(inset, inset, w - inset, h - inset)
            val corner = minOf(rect.height / 2f, 32.dp.toPx())
            val path = Path()
            path.addRoundRect(RoundRect(rect, CornerRadius(corner, corner)))

            val inner = rect.deflate(5.dp.toPx())
            val innerCorner = max(corner - 5.dp.toPx(), 0f)
            val innerPath = Path()
            innerPath.addRoundRect(RoundRect(inner, CornerRadius(innerCorner, innerCorner)))

            drawPath(path, c.borderBrush(if (active) 0.75f else 0.4f), style = Stroke(1.3.dp.toPx()))
            drawPath(innerPath, c.borderBrush(0.22f), style = Stroke(0.8.dp.toPx()))

            val measure = PathMeasure()
            measure.setPath(path, false)
            val length = measure.length

            if (progress > 0.001f) {
                val seg = Path()
                measure.getSegment(0f, length * progress.coerceAtMost(1f), seg, true)
                if (progress >= 0.999f) {
                    drawPath(seg, c.horizontalBrush(), alpha = 0.22f * pulse, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                }
                drawPath(seg, c.horizontalBrush(), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
            }

            val d = 4.5.dp.toPx()
            val nodes = listOf(
                Offset(w / 2f, inset),
                Offset(w / 2f, h - inset),
                Offset(inset, h / 2f),
                Offset(w - inset, h / 2f)
            )
            nodes.forEach { p ->
                val diamond = Path()
                diamond.moveTo(p.x, p.y - d)
                diamond.lineTo(p.x + d, p.y)
                diamond.lineTo(p.x, p.y + d)
                diamond.lineTo(p.x - d, p.y)
                diamond.close()
                drawPath(diamond, RonovaPalette.Background)
                drawPath(diamond, c.brush(), style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round))
            }

            if (length > 0f) {
                for (k in 4 downTo 1) {
                    val t = (phase - k * 0.012f + 1f) % 1f
                    val tp = measure.getPosition(length * t)
                    drawCircle(c.primary.copy(alpha = 0.18f * (5 - k)), (3.2f - k * 0.45f).dp.toPx(), tp)
                }
                val pos = measure.getPosition(length * phase)
                drawCircle(c.primary.copy(alpha = 0.35f * pulse), 7.dp.toPx(), pos)
                drawCircle(c.secondary, 3.2.dp.toPx(), pos)
                sparkle(pos, 6.dp.toPx(), 1.2.dp.toPx(), SolidColor(Color.White), 0.9f)
            }
        }
        content()
    }
}

// ───────────────────────── Module icons ─────────────────────────

@Composable
private fun IconCanvas(
    size: Dp,
    active: Boolean,
    modifier: Modifier,
    draw: DrawScope.(Brush, Stroke) -> Unit
) {
    val c = LocalRonova.current
    val brush: Brush = if (active) c.brush() else SolidColor(RonovaPalette.TextSecondary)
    Canvas(modifier.size(size)) {
        val stroke = Stroke(
            width = this.size.minDimension * 0.07f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        draw(brush, stroke)
    }
}

@Composable
fun RenderScalerIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val m = w * 0.12f
        val l = w * 0.24f
        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            val p = Path()
            p.moveTo(x + dx * l, y)
            p.lineTo(x, y)
            p.lineTo(x, y + dy * l)
            drawPath(p, brush, style = stroke)
        }
        bracket(m, m, 1f, 1f)
        bracket(w - m, m, -1f, 1f)
        bracket(m, w - m, 1f, -1f)
        bracket(w - m, w - m, -1f, -1f)
        val d = w * 0.2f
        val cx = w / 2f
        val diamond = Path()
        diamond.moveTo(cx, cx - d)
        diamond.lineTo(cx + d, cx)
        diamond.lineTo(cx, cx + d)
        diamond.lineTo(cx - d, cx)
        diamond.close()
        drawPath(diamond, brush, alpha = 0.85f, style = stroke)
        sparkle(Offset(cx, cx), d * 0.6f, d * 0.1f, brush)
    }

@Composable
fun CutoutGuardIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val shield = Path()
        shield.moveTo(w * 0.5f, w * 0.08f)
        shield.lineTo(w * 0.86f, w * 0.22f)
        shield.lineTo(w * 0.86f, w * 0.5f)
        shield.quadraticBezierTo(w * 0.86f, w * 0.8f, w * 0.5f, w * 0.94f)
        shield.quadraticBezierTo(w * 0.14f, w * 0.8f, w * 0.14f, w * 0.5f)
        shield.lineTo(w * 0.14f, w * 0.22f)
        shield.close()
        drawPath(shield, brush, style = stroke)
        drawCircle(brush, radius = w * 0.075f, center = Offset(w * 0.5f, w * 0.3f))
        drawLine(brush, Offset(w * 0.5f, w * 0.43f), Offset(w * 0.5f, w * 0.66f), stroke.width, StrokeCap.Round)
        sparkle(Offset(w * 0.5f, w * 0.62f), w * 0.1f, w * 0.02f, brush, 0.8f)
    }

@Composable
fun FramePacingIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val c = Offset(w * 0.5f, w * 0.57f)
        val r = w * 0.34f
        drawCircle(brush, r, c, style = stroke)
        drawLine(brush, Offset(w * 0.5f, w * 0.13f), Offset(w * 0.5f, w * 0.2f), stroke.width, StrokeCap.Round)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val long = i % 3 == 0
            val r1 = r * (if (long) 0.7f else 0.82f)
            val r2 = r * 0.92f
            drawLine(
                brush,
                Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                stroke.width * 0.7f,
                StrokeCap.Round
            )
        }
        val na = Math.toRadians(-55.0)
        drawLine(
            brush, c,
            Offset(c.x + (cos(na) * r * 0.6f).toFloat(), c.y + (sin(na) * r * 0.6f).toFloat()),
            stroke.width, StrokeCap.Round
        )
        drawCircle(brush, w * 0.04f, c)
        sparkle(Offset(w * 0.84f, w * 0.16f), w * 0.1f, w * 0.02f, brush)
    }

@Composable
fun NetworkShieldIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val cx = w * 0.5f
        val cy = w * 0.74f
        val radii = listOf(0.2f, 0.36f, 0.52f)
        radii.forEachIndexed { i, rr ->
            val r = w * rr
            drawArc(
                brush, -135f, 90f, false,
                Offset(cx - r, cy - r), Size(r * 2f, r * 2f),
                alpha = 1f - i * 0.15f, style = stroke
            )
        }
        drawCircle(brush, w * 0.06f, Offset(cx, cy))
        sparkle(Offset(w * 0.86f, w * 0.16f), w * 0.09f, w * 0.02f, brush)
    }

@Composable
fun BluetoothIsolatorIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        drawCircle(brush, w * 0.46f, Offset(w * 0.5f, w * 0.5f), alpha = 0.45f, style = Stroke(stroke.width * 0.6f))
        val rune = Path()
        rune.moveTo(w * 0.34f, w * 0.36f)
        rune.lineTo(w * 0.68f, w * 0.64f)
        rune.lineTo(w * 0.5f, w * 0.8f)
        rune.lineTo(w * 0.5f, w * 0.2f)
        rune.lineTo(w * 0.68f, w * 0.36f)
        rune.lineTo(w * 0.34f, w * 0.64f)
        drawPath(rune, brush, style = stroke)
    }

@Composable
fun FpsCounterIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val c = Offset(w * 0.5f, w * 0.58f)
        val r = w * 0.38f
        drawArc(brush, 150f, 240f, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = stroke)
        for (i in 0..6) {
            val a = Math.toRadians(150.0 + i * 40.0)
            drawLine(
                brush,
                Offset(c.x + (cos(a) * r * 0.78f).toFloat(), c.y + (sin(a) * r * 0.78f).toFloat()),
                Offset(c.x + (cos(a) * r * 0.9f).toFloat(), c.y + (sin(a) * r * 0.9f).toFloat()),
                stroke.width * 0.7f, StrokeCap.Round
            )
        }
        val na = Math.toRadians(150.0 + 0.72 * 240.0)
        drawLine(
            brush, c,
            Offset(c.x + (cos(na) * r * 0.68f).toFloat(), c.y + (sin(na) * r * 0.68f).toFloat()),
            stroke.width, StrokeCap.Round
        )
        drawCircle(brush, w * 0.05f, c)
        sparkle(Offset(w * 0.5f, w * 0.12f), w * 0.09f, w * 0.02f, brush)
    }

@Composable
fun ModuleGlyph(id: ModuleId, size: Dp, active: Boolean, modifier: Modifier = Modifier) {
    when (id) {
        ModuleId.RENDER_SCALER -> RenderScalerIcon(size, active, modifier)
        ModuleId.CUTOUT_CRASH_GUARD -> CutoutGuardIcon(size, active, modifier)
        ModuleId.FRAME_PACING -> FramePacingIcon(size, active, modifier)
        ModuleId.NETWORK_SHIELD -> NetworkShieldIcon(size, active, modifier)
        ModuleId.BLUETOOTH_ISOLATOR -> BluetoothIsolatorIcon(size, active, modifier)
        ModuleId.FPS_OVERLAY -> FpsCounterIcon(size, active, modifier)
        ModuleId.TOUCH_LATENCY -> TouchLatencyIcon(size, active, modifier)
        ModuleId.DISPLAY_REFRESH -> RefreshRateIcon(size, active, modifier)
        ModuleId.THERMAL_GUARD -> ThermalGuardIcon(size, active, modifier)
    }
}

@Composable
fun TouchLatencyIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val c = Offset(w * 0.5f, w * 0.5f)
        drawCircle(brush, w * 0.07f, c)
        listOf(0.26f, 0.4f).forEach { rr ->
            drawCircle(brush, w * rr, c, alpha = 1f - rr, style = Stroke(stroke.width * 0.7f))
        }
        sparkle(Offset(w * 0.82f, w * 0.18f), w * 0.09f, w * 0.02f, brush)
    }

@Composable
fun RefreshRateIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val c = Offset(w * 0.5f, w * 0.5f)
        val r = w * 0.32f
        drawArc(brush, -40f, 260f, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = stroke)
        val ah = Math.toRadians(-40.0)
        val tip = Offset(c.x + (cos(ah) * r).toFloat(), c.y + (sin(ah) * r).toFloat())
        val arrow = Path()
        arrow.moveTo(tip.x - w * 0.1f, tip.y - w * 0.03f)
        arrow.lineTo(tip.x, tip.y)
        arrow.lineTo(tip.x - w * 0.03f, tip.y + w * 0.1f)
        drawPath(arrow, brush, style = stroke)
        sparkle(Offset(w * 0.5f, w * 0.5f), w * 0.08f, w * 0.015f, brush)
    }

@Composable
fun ThermalGuardIcon(size: Dp = 28.dp, active: Boolean = true, modifier: Modifier = Modifier) =
    IconCanvas(size, active, modifier) { brush, stroke ->
        val w = this.size.minDimension
        val stemX = w * 0.5f
        drawLine(brush, Offset(stemX, w * 0.16f), Offset(stemX, w * 0.6f), stroke.width, StrokeCap.Round)
        drawCircle(brush, w * 0.13f, Offset(stemX, w * 0.72f), style = stroke)
        drawCircle(brush, w * 0.05f, Offset(stemX, w * 0.72f))
        sparkle(Offset(w * 0.82f, w * 0.18f), w * 0.09f, w * 0.02f, brush)
    }

// ───────────────────────── Silky spring interaction ─────────────────────────

/** Press-scale + glow with spring(dampingRatio = 0.72f, stiffness = 380f). */
fun Modifier.springPress(
    enabled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    onClick: () -> Unit
): Modifier = composed {
    val c = LocalRonova.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "pressScale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "pressGlow"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawBehind {
            if (glow > 0.01f) {
                val rad = cornerRadius.toPx()
                for (i in 1..3) {
                    val grow = i * 4.dp.toPx() * glow
                    drawRoundRect(
                        color = c.primary.copy(alpha = 0.16f * glow / i),
                        topLeft = Offset(-grow, -grow),
                        size = Size(this.size.width + grow * 2f, this.size.height + grow * 2f),
                        cornerRadius = CornerRadius(rad + grow, rad + grow)
                    )
                }
            }
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun SilkySpringButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val c = LocalRonova.current
    val shape = RoundedCornerShape(cornerRadius)
    val contentColor = if (filled) c.onAccent else c.textPrimary
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .springPress(enabled, cornerRadius, onClick)
            .clip(shape)
            .background(if (filled) c.horizontalBrush() else SolidColor(c.surface))
            .border(1.dp, if (filled) SolidColor(Color.Transparent) else c.borderBrush(0.7f), shape)
            .padding(contentPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
fun SilkySpringCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 18.dp,
    ornate: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalRonova.current
    val shape = RoundedCornerShape(cornerRadius)
    val base = if (onClick != null) modifier.springPress(true, cornerRadius, onClick) else modifier
    Box(
        base
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
    ) {
        if (ornate) {
            Canvas(Modifier.matchParentSize()) {
                val brush = c.brush()
                sparkle(Offset(11.dp.toPx(), 11.dp.toPx()), 5.dp.toPx(), 1.1.dp.toPx(), brush, 0.85f)
                sparkle(Offset(this.size.width - 11.dp.toPx(), this.size.height - 11.dp.toPx()), 5.dp.toPx(), 1.1.dp.toPx(), brush, 0.85f)
            }
        }
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Switch with a sparkle thumb that glides with the same spring. */
@Composable
fun RonovaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = LocalRonova.current
    val pos by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "switchPos"
    )
    Canvas(
        modifier
            .size(48.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
    ) {
        val w = this.size.width
        val h = this.size.height
        val radius = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(color = RonovaPalette.Border, size = this.size, cornerRadius = radius)
        if (pos > 0.01f) {
            drawRoundRect(brush = c.horizontalBrush(), size = this.size, cornerRadius = radius, alpha = pos)
        }
        if (pos < 0.99f) {
            drawRoundRect(
                color = Color(0xFF2A2E3E).copy(alpha = 1f - pos),
                size = this.size,
                cornerRadius = radius,
                style = Stroke(1.dp.toPx())
            )
        }
        val r = h * 0.32f
        val x = h / 2f + (w - h) * pos
        val thumb = if (checked) RonovaPalette.TextPrimary else Color(0xFF6D7186)
        drawCircle(Color.Black.copy(alpha = 0.3f), r * 1.2f, Offset(x, h / 2f + 1.dp.toPx()))
        drawCircle(thumb, r, Offset(x, h / 2f))
        sparkle(Offset(x, h / 2f), r * 0.85f, r * 0.2f, SolidColor(RonovaPalette.Background), 0.75f)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalRonova.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CelestialStar(14.dp, glow = false)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
        Spacer(Modifier.width(10.dp))
        Spacer(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(c.horizontalBrush(), alpha = 0.4f)
        )
    }
}

/** Obsidian night sky: accent halo at the top, slowly twinkling sparkles, faint constellation lines. */
@Composable
fun StarfieldBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val c = LocalRonova.current
    val transition = rememberInfiniteTransition(label = "stars")
    val twinkle by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "twinkle"
    )
    val stars = remember {
        val rnd = Random(20260101)
        List(36) { Triple(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
    }
    Box(modifier.background(RonovaPalette.Background)) {
        Canvas(Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            drawRect(
                brush = Brush.radialGradient(
                    listOf(c.primary.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.12f),
                    radius = w * 1.1f
                )
            )
            val pts = stars.map { Offset(it.first * w, it.second * h) }
            for (i in 0 until 9) {
                drawLine(
                    RonovaPalette.TextSecondary.copy(alpha = 0.10f),
                    pts[i], pts[i + 1], strokeWidth = 0.8.dp.toPx()
                )
            }
            stars.forEachIndexed { i, s ->
                val p = pts[i]
                val size = (1.6f + s.third * 3.6f).dp.toPx()
                val a = (0.25f + 0.45f * s.third) * (if (i % 2 == 0) twinkle else 1.5f - twinkle).coerceIn(0.2f, 1f)
                sparkle(p, size, size * 0.2f, SolidColor(RonovaPalette.TextPrimary), a.coerceIn(0f, 1f))
            }
        }
        content()
    }
}
