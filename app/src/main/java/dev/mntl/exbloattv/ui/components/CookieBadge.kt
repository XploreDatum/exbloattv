package dev.mntl.exbloattv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.RoundedPolygon

/** Decorative hero badge: an icon on a cookie-shaped primary container. */
@Composable
fun CookieBadge(
    shape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val clip = shape.toShape()
    Box(
        modifier.size(size).clip(clip).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}
