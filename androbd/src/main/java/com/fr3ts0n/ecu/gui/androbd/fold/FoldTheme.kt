package com.fr3ts0n.ecu.gui.androbd.fold

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Colour tokens for the split view. */
@Immutable
class FoldPalette(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val grid: Color,
    val live: Color,
    val demo: Color,
    val rec: Color,
    val accents: List<Color>,
) {
    fun accent(order: Int): Color = accents[order.mod(accents.size)]
}

private val DarkPalette = FoldPalette(
    dark = true,
    bgTop = Color(0xFF0C121B),
    bgBottom = Color(0xFF06080D),
    surface = Color(0xFF111924),
    surfaceHigh = Color(0xFF172231),
    outline = Color(0xFF223047),
    text = Color(0xFFE9EFF7),
    textDim = Color(0xFF93A3B9),
    textFaint = Color(0xFF5D6E86),
    grid = Color(0x1FFFFFFF),
    live = Color(0xFF34D399),
    demo = Color(0xFFFBBF24),
    rec = Color(0xFFFB4D6D),
    accents = listOf(
        Color(0xFF22D3EE), // cyan
        Color(0xFFFBBF24), // amber
        Color(0xFFA3E635), // lime
        Color(0xFFF472B6), // pink
        Color(0xFFA78BFA), // violet
        Color(0xFFFB923C), // orange
        Color(0xFF2DD4BF), // teal
        Color(0xFF60A5FA), // blue
        Color(0xFFFB7185), // rose
        Color(0xFF34D399), // emerald
    ),
)

private val LightPalette = FoldPalette(
    dark = false,
    bgTop = Color(0xFFF7F9FC),
    bgBottom = Color(0xFFEAEFF6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF2F5FA),
    outline = Color(0xFFDCE3EE),
    text = Color(0xFF0E1726),
    textDim = Color(0xFF55657C),
    textFaint = Color(0xFF8E9BB0),
    grid = Color(0x14000000),
    live = Color(0xFF059669),
    demo = Color(0xFFD97706),
    rec = Color(0xFFE11D48),
    accents = listOf(
        Color(0xFF0891B2),
        Color(0xFFD97706),
        Color(0xFF65A30D),
        Color(0xFFDB2777),
        Color(0xFF7C3AED),
        Color(0xFFEA580C),
        Color(0xFF0D9488),
        Color(0xFF2563EB),
        Color(0xFFE11D48),
        Color(0xFF059669),
    ),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Tabular figures so live numbers don't jitter sideways. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun FoldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accents[0],
            background = palette.bgBottom,
            surface = palette.surface,
            onSurface = palette.text,
            onBackground = palette.text,
            outline = palette.outline,
        )
    } else {
        lightColorScheme(
            primary = palette.accents[0],
            background = palette.bgBottom,
            surface = palette.surface,
            onSurface = palette.text,
            onBackground = palette.text,
            outline = palette.outline,
        )
    }
    val base = Typography()
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

// ------------------------------------------------------------------ number / time formatting

private val grouping: NumberFormat
    get() = NumberFormat.getNumberInstance(Locale.getDefault())

fun formatNumber(v: Float): String {
    val a = abs(v)
    val decimals = when {
        a >= 100f -> 0
        a >= 10f -> 1
        a >= 1f -> 1
        a == 0f -> 0
        else -> 2
    }
    val f = grouping
    f.maximumFractionDigits = decimals
    f.minimumFractionDigits = 0
    return f.format(v.toDouble())
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, sec)
        else -> "%d:%02d".format(m, sec)
    }
}

fun formatDurationWords(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "$m min"
        else -> "${s} s"
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    b >= 1_000 -> "%.0f KB".format(b / 1e3)
    else -> "$b B"
}

// ------------------------------------------------------------------ icons (24dp, Material-style outlines)

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()

object FoldIcons {
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val History = icon(
        "history",
        "M13,3a9,9 0,0 0,-9 9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42A8.95,8.95 0,0 0,13 21a9,9 0,0 0,0 -18zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z"
    )
    val Share = icon(
        "share",
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11A2.99,2.99 0,0 0,21 5a3,3 0,1 0,-5.91 0.7L8.04,9.81A2.99,2.99 0,0 0,3 12a2.99,2.99 0,0 0,5.04 2.19l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65A2.92,2.92 0,1 0,18 16.08z"
    )
    val Delete = icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM8,9h8v10H8V9zM15.5,4l-1,-1h-5l-1,1H5v2h14V4z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Chart = icon("chart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z")
    val Car = icon(
        "car",
        "M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16C5.67,16 5,15.33 5,14.5S5.67,13 6.5,13 8,13.67 8,14.5 7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11H5z"
    )
}
