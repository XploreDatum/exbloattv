package dev.xpdt.exbloattv.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.xpdt.exbloattv.ui.theme.ExBloatTheme

@Preview(name = "light", showBackground = true, widthDp = 360)
@Preview(name = "dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "font 2x", showBackground = true, widthDp = 360, fontScale = 2f)
annotation class ThemePreviews

@ThemePreviews
@Composable
private fun ComponentGalleryPreview() {
    ExBloatTheme {
        Column(
            Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieBadge(icon = Icons.Rounded.Tv, size = 72.dp)
            Text("Pick your TV", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            Row(Modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("Ready", ChipKind.READY)
                StatusChip("Setup", ChipKind.SETUP)
            }
            var netflix by remember { mutableStateOf(true) }
            var ads by remember { mutableStateOf(false) }
            SegmentedGroup(
                listOf(
                    { AppRow("Netflix", "Movies and shows", null, netflix, true, onCheckedChange = { netflix = it }) },
                    { AppRow("Ad services", "Shows ads on the home screen", null, ads, true, onCheckedChange = { ads = it }) },
                    { AppRow("Locked app", "Needed by the TV", null, true, false, onCheckedChange = {}) },
                ),
            )
            PrimaryButton("Clean up my TV", onClick = {})
            QuietButton("Not now", onClick = {})
        }
    }
}
