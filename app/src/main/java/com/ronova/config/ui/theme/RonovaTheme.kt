package com.ronova.config.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ronova.config.data.ConfigState
import com.ronova.config.data.ThemeSettings
import java.util.Locale

/** Fixed obsidian/charcoal base so any accent colour pops cleanly. */
object RonovaPalette {
    val Background = Color(0xFF0A0B0F)
    val Surface = Color(0xFF13151D)
    val Border = Color(0xFF1C1F2B)
    val TextPrimary = Color(0xFFECE5D8)
    val TextSecondary = Color(0xFF9A9CAB)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC857)
    val Success = Color(0xFF6BE3A0)
    val OnAccent = Color(0xFF0A0B0F)
}

fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

fun Color.toHexString(): String = String.format(Locale.US, "#%06X", 0xFFFFFF and this.toArgb())

val ConfigState.primaryAccentHex: String
    get() = hsvColor(theme.hue, theme.saturation, theme.brightness).toHexString()

val ConfigState.secondaryAccentHex: String
    get() = hsvColor(theme.hue2, theme.saturation2, theme.brightness2).toHexString()

val ConfigState.isGradientMode: Boolean
    get() = theme.gradientMode

/**
 * Accent engine: single colour when gradient is off, primary -> secondary blend when on.
 * Only accents change; the dark base never does.
 */
@Immutable
class RonovaColors(
    val primary: Color,
    val secondary: Color,
    val gradient: Boolean
) {
    val background: Color = RonovaPalette.Background
    val surface: Color = RonovaPalette.Surface
    val border: Color = RonovaPalette.Border
    val textPrimary: Color = RonovaPalette.TextPrimary
    val textSecondary: Color = RonovaPalette.TextSecondary
    val onAccent: Color = RonovaPalette.OnAccent

    fun brush(): Brush =
        if (gradient) Brush.linearGradient(listOf(primary, secondary)) else SolidColor(primary)

    fun horizontalBrush(): Brush =
        if (gradient) Brush.horizontalGradient(listOf(primary, secondary)) else SolidColor(primary)

    fun verticalBrush(): Brush =
        if (gradient) Brush.verticalGradient(listOf(primary, secondary)) else SolidColor(primary)

    fun borderBrush(alpha: Float = 0.55f): Brush =
        if (gradient) Brush.linearGradient(listOf(primary.copy(alpha = alpha), secondary.copy(alpha = alpha)))
        else SolidColor(primary.copy(alpha = alpha))

    companion object {
        fun from(theme: ThemeSettings): RonovaColors = RonovaColors(
            hsvColor(theme.hue, theme.saturation, theme.brightness),
            hsvColor(theme.hue2, theme.saturation2, theme.brightness2),
            theme.gradientMode
        )
    }
}

val LocalRonova: ProvidableCompositionLocal<RonovaColors> =
    staticCompositionLocalOf { RonovaColors.from(ThemeSettings()) }

val RonovaTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp)
    )
}

@Composable
fun RonovaTheme(theme: ThemeSettings, content: @Composable () -> Unit) {
    val colors = remember(theme) { RonovaColors.from(theme) }
    val scheme = darkColorScheme(
        primary = colors.primary,
        onPrimary = RonovaPalette.OnAccent,
        secondary = colors.secondary,
        onSecondary = RonovaPalette.OnAccent,
        background = RonovaPalette.Background,
        onBackground = RonovaPalette.TextPrimary,
        surface = RonovaPalette.Surface,
        onSurface = RonovaPalette.TextPrimary,
        surfaceVariant = RonovaPalette.Surface,
        onSurfaceVariant = RonovaPalette.TextSecondary,
        outline = RonovaPalette.Border,
        outlineVariant = RonovaPalette.Border,
        error = RonovaPalette.Danger,
        onError = RonovaPalette.OnAccent
    )
    CompositionLocalProvider(LocalRonova provides colors) {
        MaterialTheme(colorScheme = scheme, typography = RonovaTypography, content = content)
    }
}

@Composable
fun ronovaFieldColors(): TextFieldColors {
    val c = LocalRonova.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = RonovaPalette.TextPrimary,
        unfocusedTextColor = RonovaPalette.TextPrimary,
        focusedBorderColor = c.primary,
        unfocusedBorderColor = RonovaPalette.Border,
        cursorColor = c.primary,
        focusedLabelColor = c.primary,
        unfocusedLabelColor = RonovaPalette.TextSecondary,
        focusedContainerColor = RonovaPalette.Surface,
        unfocusedContainerColor = RonovaPalette.Surface
    )
}
