package dev.mntl.exbloattv.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeedColorSchemeTest {
    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim,
        "inverseSurface" to s.inverseSurface,
        "inverseOnSurface" to s.inverseOnSurface,
        "inversePrimary" to s.inversePrimary,
        "outlineVariant" to s.outlineVariant,
    )

    private fun assertNoBaselineLeak(seed: ColorScheme, baseline: ColorScheme) {
        val base = roles(baseline)
        roles(seed).forEach { (name, color) ->
            assertThat(color).isNotEqualTo(base.getValue(name))
        }
    }

    @Test
    fun lightSeedOverridesBaselineRoles() = assertNoBaselineLeak(SeedLightColorScheme, lightColorScheme())

    @Test
    fun darkSeedOverridesBaselineRoles() = assertNoBaselineLeak(SeedDarkColorScheme, darkColorScheme())
}
