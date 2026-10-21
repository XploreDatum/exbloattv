package dev.xpdt.exbloattv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Rounded Google Sans Flex / Roboto Flex where the device ships them, plain sans-serif otherwise. */
val ExBloatFontFamily: FontFamily = FontFamily(
    Font(DeviceFontFamilyName("google-sans-flex")),
    Font(DeviceFontFamilyName("roboto-flex")),
    Font(DeviceFontFamilyName("sans-serif")),
)

/** Baseline M3 type scale with the rounded family and the ~34 sp expressive headline. */
val ExBloatTypography: Typography = Typography().let { base ->
    fun TextStyle.rounded() = copy(fontFamily = ExBloatFontFamily)
    base.copy(
        displayLarge = base.displayLarge.rounded(),
        displayMedium = base.displayMedium.rounded(),
        displaySmall = base.displaySmall.rounded(),
        headlineLarge = base.headlineLarge.rounded().copy(
            fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight(560), letterSpacing = (-0.34).sp,
        ),
        headlineMedium = base.headlineMedium.rounded(),
        headlineSmall = base.headlineSmall.rounded(),
        titleLarge = base.titleLarge.rounded(),
        titleMedium = base.titleMedium.rounded().copy(fontWeight = FontWeight.W600),
        titleSmall = base.titleSmall.rounded(),
        bodyLarge = base.bodyLarge.rounded(),
        bodyMedium = base.bodyMedium.rounded(),
        bodySmall = base.bodySmall.rounded(),
        labelLarge = base.labelLarge.rounded().copy(fontWeight = FontWeight.W600),
        labelMedium = base.labelMedium.rounded().copy(fontWeight = FontWeight.W600),
        labelSmall = base.labelSmall.rounded(),
    )
}
