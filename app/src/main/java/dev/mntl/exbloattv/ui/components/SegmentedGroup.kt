package dev.mntl.exbloattv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val Outer = 24.dp
private val Inner = 6.dp

/** A list card split into segments: 24 dp outer corners, 6 dp inner corners, 3 dp gaps. */
@Composable
fun SegmentedGroup(items: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { i, item ->
            val top = if (i == 0) Outer else Inner
            val bottom = if (i == items.lastIndex) Outer else Inner
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) { item() }
        }
    }
}
