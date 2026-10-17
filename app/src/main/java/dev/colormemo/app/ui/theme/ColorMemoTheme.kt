package dev.colormemo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.colormemo.core.model.NoteColor

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D6953),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0ECDD),
    onPrimaryContainer = Color(0xFF254C38),
    secondary = Color(0xFF776451),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5D8),
    onSecondaryContainer = Color(0xFF51402F),
    tertiary = Color(0xFFA4614E),
    tertiaryContainer = Color(0xFFFADBCD),
    onTertiaryContainer = Color(0xFF6D3929),
    background = Color(0xFFFAFAF6),
    onBackground = Color(0xFF28332B),
    surface = Color(0xFFFAFAF6),
    onSurface = Color(0xFF28332B),
    surfaceVariant = Color(0xFFEBEDE5),
    onSurfaceVariant = Color(0xFF666F64),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4EC),
    surfaceContainer = Color(0xFFEEF0E7),
    surfaceContainerHigh = Color(0xFFE8EBE1),
    surfaceContainerHighest = Color(0xFFE3E7DC),
    outline = Color(0xFF828A7F),
    outlineVariant = Color(0xFFDDE2D6),
    error = Color(0xFFAF3F38),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0D2B6),
    onPrimary = Color(0xFF193725),
    primaryContainer = Color(0xFF34533E),
    onPrimaryContainer = Color(0xFFD9EED9),
    secondary = Color(0xFFD9C1A8),
    onSecondary = Color(0xFF3D2F21),
    secondaryContainer = Color(0xFF564432),
    onSecondaryContainer = Color(0xFFF3DFC8),
    tertiary = Color(0xFFF0BAA5),
    tertiaryContainer = Color(0xFF704638),
    onTertiaryContainer = Color(0xFFFFDBCA),
    background = Color(0xFF161C18),
    onBackground = Color(0xFFE2E8DD),
    surface = Color(0xFF161C18),
    onSurface = Color(0xFFE2E8DD),
    surfaceVariant = Color(0xFF414B40),
    onSurfaceVariant = Color(0xFFB9C3B4),
    surfaceContainerLowest = Color(0xFF101510),
    surfaceContainerLow = Color(0xFF1C231D),
    surfaceContainer = Color(0xFF222A23),
    surfaceContainerHigh = Color(0xFF2B332C),
    surfaceContainerHighest = Color(0xFF353E35),
    outline = Color(0xFF869381),
    outlineVariant = Color(0xFF414B3E),
    error = Color(0xFFFFB4AB),
)

private val MemoTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun ColorMemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MemoTypography,
        content = content,
    )
}

@Composable
fun NoteColor.containerColor(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        NoteColor.DEFAULT -> MaterialTheme.colorScheme.surfaceContainerLowest
        NoteColor.CORAL -> if (dark) Color(0xFF5C3B36) else Color(0xFFF5D4CD)
        NoteColor.PEACH -> if (dark) Color(0xFF57422E) else Color(0xFFF8DFBD)
        NoteColor.SAND -> if (dark) Color(0xFF514B2C) else Color(0xFFF3EAB7)
        NoteColor.MINT -> if (dark) Color(0xFF304F40) else Color(0xFFD9EBD8)
        NoteColor.SAGE -> if (dark) Color(0xFF3F4D36) else Color(0xFFE1E8CD)
        NoteColor.FOG -> if (dark) Color(0xFF2F4D4C) else Color(0xFFD4EAE7)
        NoteColor.STORM -> if (dark) Color(0xFF364956) else Color(0xFFD9E7F2)
        NoteColor.DUSK -> if (dark) Color(0xFF45405A) else Color(0xFFE3DFF1)
        NoteColor.BLOSSOM -> if (dark) Color(0xFF583D50) else Color(0xFFF0DEEB)
        NoteColor.CLAY -> if (dark) Color(0xFF51473D) else Color(0xFFEAE1D5)
    }
}

val NoteColor.displayName: String
    get() = when (this) {
        NoteColor.DEFAULT -> "素白"
        NoteColor.CORAL -> "珊瑚"
        NoteColor.PEACH -> "蜜桃"
        NoteColor.SAND -> "暖沙"
        NoteColor.MINT -> "薄荷"
        NoteColor.SAGE -> "鼠尾草"
        NoteColor.FOG -> "晨雾"
        NoteColor.STORM -> "雨蓝"
        NoteColor.DUSK -> "暮紫"
        NoteColor.BLOSSOM -> "樱花"
        NoteColor.CLAY -> "陶土"
    }
