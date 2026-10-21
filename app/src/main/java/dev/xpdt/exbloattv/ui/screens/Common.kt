package dev.xpdt.exbloattv.ui.screens

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.catalog.Category
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The page frame used by every screen: optional back button, a scrolling body with 18 dp gutters, and actions pinned
 * to the bottom (they grow with the font, never clip).
 */
@Composable
fun ExScreen(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    /** Shown at the end of the top bar, e.g. "Stop" while a cleanup runs. */
    topAction: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                    if (topAction != null) {
                        Spacer(Modifier.weight(1f))
                        topAction()
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                    content = content,
                )
                if (actions != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = actions,
                    )
                }
            }
            snackbar?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
        }
    }
}

/** The ~34 sp rounded expressive headline. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
fun Supporting(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(bottom = 14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

/** Small primary-coloured label above a list group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A switch row for a setting (not an app): the whole row toggles; TalkBack reads "<title>, switch on". */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spoken = stringResource(R.string.setting_cd, title, stringResource(if (checked) R.string.switch_on else R.string.switch_off))
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked) {
                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
    }
}

/** A text resource that carries <b> markup (wrapped in CDATA in strings.xml). */
@Composable
fun htmlResource(@StringRes id: Int): AnnotatedString {
    val raw = stringResource(id)
    return remember(raw) { AnnotatedString.fromHtml(raw) }
}

/** Decodes a cached icon PNG off the main thread. */
@Composable
fun rememberIcon(file: File?): Painter? {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = file?.let { f ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    return bitmap?.let { remember(it) { BitmapPainter(it) } }
}

@StringRes
fun categoryLabel(c: Category): Int = when (c) {
    Category.STREAMING -> R.string.cat_streaming
    Category.MUSIC -> R.string.cat_music
    Category.LIVE_TV -> R.string.cat_live_tv
    Category.FEATURES -> R.string.cat_features
    Category.GOOGLE_EXTRAS -> R.string.cat_google
    Category.MANUFACTURER -> R.string.cat_manufacturer
    Category.SIDELOAD_TOOLS -> R.string.cat_sideload
    Category.OTHER -> R.string.cat_other
    Category.ADS_TRACKING -> R.string.cat_ads
    Category.SYSTEM, Category.LAUNCHER -> R.string.keep_show_system
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
