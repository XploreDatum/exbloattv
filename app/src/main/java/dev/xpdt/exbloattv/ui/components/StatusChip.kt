package dev.xpdt.exbloattv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class ChipKind { READY, SETUP }

/** "Ready" is filled primary; "Setup" is a tertiary container. Colour is never the only cue: the text says it. */
@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        ChipKind.READY -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ChipKind.SETUP -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
    )
}
