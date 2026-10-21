package dev.xpdt.exbloattv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.xpdt.exbloattv.BuildConfig
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.about.Component
import dev.xpdt.exbloattv.about.Notices
import dev.xpdt.exbloattv.about.reflowLicence
import dev.xpdt.exbloattv.ui.components.QuietButton
import dev.xpdt.exbloattv.ui.components.SegmentedGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val REPO_URL = "https://github.com/XploreDatum/exbloattv"
const val ISSUES_URL = "$REPO_URL/issues"

/** Id of the app's own licence on the licence screen; components use their position in the notices file. */
const val APP_LICENCE_ID = "app"

/** The "i" action at the end of a top bar that opens About. */
@Composable
fun AboutAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(end = 4.dp)) {
        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.about_cd))
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicences: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val open = rememberUrlOpener(snackbar)
    ExScreen(onBack = onBack, snackbar = snackbar) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            AppIcon()
        }
        ScreenTitle(stringResource(R.string.app_name), center = true)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Supporting(stringResource(R.string.about_description), center = true)
        SegmentedGroup(
            listOf(
                {
                    LinkRow(Icons.Rounded.Code, stringResource(R.string.about_source), stringResource(R.string.about_source_desc), external = true) {
                        open(REPO_URL)
                    }
                },
                {
                    LinkRow(Icons.Rounded.BugReport, stringResource(R.string.about_report), stringResource(R.string.about_report_desc), external = true) {
                        open(ISSUES_URL)
                    }
                },
                {
                    LinkRow(Icons.Rounded.Gavel, stringResource(R.string.about_licences), stringResource(R.string.about_licences_desc), external = false, onClick = onOpenLicences)
                },
            ),
        )
        VSpace(16)
        Text(
            stringResource(R.string.about_licence_line),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Supporting(stringResource(R.string.about_copyright), modifier = Modifier.padding(horizontal = 4.dp))
        Supporting(stringResource(R.string.about_flauncher_note), modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun LicencesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val (load, retry) = rememberNotices()
    ExScreen(onBack = onBack) {
        ScreenTitle(stringResource(R.string.about_licences))
        when (load) {
            NoticesLoad.Loading -> Busy(stringResource(R.string.licences_loading))
            NoticesLoad.Failed -> LoadFailed(retry)
            is NoticesLoad.Ready -> {
                Supporting(stringResource(R.string.licences_subtitle))
                SectionLabel(stringResource(R.string.licences_this_app))
                SegmentedGroup(
                    listOf({
                        ComponentRow(stringResource(R.string.app_name), stringResource(R.string.licence_row, BuildConfig.VERSION_NAME, "MIT"), REPO_URL) {
                            onOpen(APP_LICENCE_ID)
                        }
                    }),
                )
                var index = 0
                load.notices.sections.forEach { section ->
                    SectionLabel(section.title)
                    SegmentedGroup(
                        section.components.map { c ->
                            val id = (index++).toString()
                            ({ ComponentRow(c.name, stringResource(R.string.licence_row, c.version, c.licence), c.source) { onOpen(id) } })
                        },
                    )
                }
                VSpace(12)
            }
        }
    }
}

@Composable
fun LicenceScreen(id: String, onBack: () -> Unit) {
    val (load, retry) = rememberNotices()
    val snackbar = remember { SnackbarHostState() }
    val open = rememberUrlOpener(snackbar)
    ExScreen(onBack = onBack, snackbar = snackbar) {
        when (load) {
            NoticesLoad.Loading -> Busy(stringResource(R.string.licences_loading))
            NoticesLoad.Failed -> LoadFailed(retry)
            is NoticesLoad.Ready -> {
                if (id == APP_LICENCE_ID) {
                    ScreenTitle(stringResource(R.string.app_name))
                    Facts(BuildConfig.VERSION_NAME, "MIT")
                    LinkButton(stringResource(R.string.licence_source)) { open(REPO_URL) }
                    LicenceText(reflowLicence(load.appLicence))
                    return@ExScreen
                }
                val c = id.toIntOrNull()?.let { load.notices.components.getOrNull(it) }
                if (c == null) {
                    ScreenTitle(stringResource(R.string.licence_not_found))
                    return@ExScreen
                }
                ComponentDetails(c, load.notices.textFor(c), open)
            }
        }
    }
}

@Composable
private fun ComponentDetails(c: Component, text: String?, open: (String) -> Unit) {
    ScreenTitle(c.name)
    Facts(c.version, c.licence)
    LinkButton(stringResource(R.string.licence_source)) { open(c.source) }
    Text(c.copyright, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
    c.notes?.let { Supporting(it, modifier = Modifier.padding(horizontal = 4.dp)) }
    if (text != null) {
        LicenceText(reflowLicence(text))
    } else {
        c.licenceUrl?.let { url -> LinkButton(stringResource(R.string.licence_read_online)) { open(url) } }
    }
}

@Composable
private fun Facts(version: String, licence: String) {
    listOf(stringResource(R.string.licence_version, version), stringResource(R.string.licence_licence, licence)).forEach {
        Text(it, modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    VSpace(8)
}

@Composable
private fun LicenceText(text: String) {
    SectionLabel(stringResource(R.string.licence_text_title))
    SelectionContainer {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    VSpace(12)
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** A list row with a round leading icon; [external] rows open the browser and say so to TalkBack. */
@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, external: Boolean, onClick: () -> Unit) {
    val cd = if (external) stringResource(R.string.about_opens_browser_cd, title) else title
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 72.dp)
            .padding(14.dp)
            .clearAndSetSemantics { contentDescription = cd; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComponentRow(name: String, detail: String, url: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The launcher icon, drawn from its two adaptive-icon layers (Compose can't paint an adaptive-icon resource). */
@Composable
private fun AppIcon() {
    Box(Modifier.size(112.dp).clip(MaterialShapes.Cookie9Sided.toShape())) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun LoadFailed(retry: () -> Unit) {
    Supporting(stringResource(R.string.licences_failed))
    QuietButton(stringResource(R.string.try_again), onClick = retry)
}

sealed interface NoticesLoad {
    data object Loading : NoticesLoad
    data object Failed : NoticesLoad
    data class Ready(val notices: Notices, val appLicence: String) : NoticesLoad
}

/** Reads and parses the licence assets off the main thread; the second value retries after a failure. */
@Composable
private fun rememberNotices(): Pair<NoticesLoad, () -> Unit> {
    val context = LocalContext.current
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val state by produceState<NoticesLoad>(NoticesLoad.Loading, attempt) {
        value = NoticesLoad.Loading
        value = withContext(Dispatchers.IO) {
            runCatching {
                val assets = context.assets
                fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }
                NoticesLoad.Ready(Notices.parse(read(Notices.NOTICES_ASSET)), read(Notices.LICENSE_ASSET))
            }.getOrElse { NoticesLoad.Failed }
        }
    }
    return state to { attempt++ }
}

/** Opens [url] in the browser; when nothing on the phone can, says so in a snackbar instead of failing silently. */
@Composable
private fun rememberUrlOpener(snackbar: SnackbarHostState): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, snackbar) {
        { url ->
            if (!openUrl(context, url)) scope.launch { snackbar.showSnackbar(context.getString(R.string.about_open_failed, url)) }
        }
    }
}

private fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
