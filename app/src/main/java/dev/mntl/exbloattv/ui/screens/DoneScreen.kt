package dev.mntl.exbloattv.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mntl.exbloattv.R
import dev.mntl.exbloattv.cleanup.appName
import dev.mntl.exbloattv.service.WorkState
import dev.mntl.exbloattv.ui.components.CookieBadge
import dev.mntl.exbloattv.ui.components.PrimaryButton
import dev.mntl.exbloattv.ui.components.QuietButton
import dev.mntl.exbloattv.ui.components.SegmentedGroup

@Composable
fun sizeText(kb: Long?): String {
    val context = LocalContext.current
    return kb?.let { Formatter.formatShortFileSize(context, maxOf(0L, it) * 1024) } ?: stringResource(R.string.dash)
}

/** "All clean": what was freed and changed, honestly (a dash when something couldn't be measured). */
@Composable
fun DoneScreen(serial: String, work: WorkState, onDone: () -> Unit, onUndo: (host: String, tvName: String) -> Unit) {
    val cleaned = work as? WorkState.Cleaned
    if (cleaned == null || cleaned.serial != serial) {
        ExScreen(onBack = onDone, actions = { PrimaryButton(stringResource(R.string.done_done), onClick = onDone) }) {
            ScreenTitle(stringResource(R.string.done_title))
            Supporting(stringResource(R.string.done_missing))
        }
        return
    }
    val s = cleaned.summary
    val partial = s.skipped.isNotEmpty()
    var details by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    ExScreen(
        onBack = onDone,
        actions = {
            PrimaryButton(stringResource(R.string.done_done), onClick = onDone)
            QuietButton(stringResource(R.string.done_undo), onClick = { confirm = true })
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            CookieBadge(MaterialShapes.Cookie9Sided, Icons.Rounded.Check, 132.dp)
        }
        ScreenTitle(stringResource(if (partial) R.string.done_title_partial else R.string.done_title), center = true)
        Supporting(stringResource(if (partial) R.string.done_subtitle_partial else R.string.done_subtitle), center = true)
        val dash = stringResource(R.string.dash)
        val home = when (s.homeIsFlauncher) {
            true -> stringResource(R.string.done_home_flauncher)
            false -> stringResource(R.string.done_home_unchanged)
            null -> dash
        }
        SegmentedGroup(
            listOf(
                { Stat(stringResource(R.string.done_memory), sizeText(s.memoryFreedKb)) },
                { Stat(stringResource(R.string.done_storage), sizeText(s.storageFreedKb)) },
                { Stat(stringResource(R.string.done_switched_off), s.switchedOff?.let { pluralStringResource(R.plurals.done_apps, it, it) } ?: dash) },
                { Stat(stringResource(R.string.done_home), home) },
            ),
        )
        if (s.deleted.isNotEmpty()) {
            SectionLabel(stringResource(R.string.done_deleted_title))
            Text(
                stringResource(
                    R.string.done_deleted_body,
                    s.deleted.joinToString(stringResource(R.string.list_separator)) { appName(it, cleaned.appNames) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (partial) {
            VSpace(10)
            Text(
                stringResource(R.string.done_skipped, s.skipped.map { stepText(it, cleaned.appNames) }.joinToString(stringResource(R.string.list_separator))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        VSpace(4)
        QuietButton(stringResource(R.string.done_details), onClick = { details = true })
    }

    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(stringResource(R.string.done_details), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                Text(stringResource(R.string.done_details_memory, sizeText(s.memoryBeforeKb), sizeText(s.memoryAfterKb)), style = MaterialTheme.typography.bodyLarge)
                VSpace(8)
                Text(stringResource(R.string.done_details_storage, sizeText(s.storageBeforeKb), sizeText(s.storageAfterKb)), style = MaterialTheme.typography.bodyLarge)
                VSpace(8)
                Text(stringResource(R.string.done_details_count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.done_details_unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirm) {
        UndoConfirmSheet(onDismiss = { confirm = false }, onConfirm = {
            confirm = false
            onUndo(cleaned.host, cleaned.tvName)
        })
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val cd = stringResource(R.string.row_with_state, label, value)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 16.dp, vertical = 15.dp).clearAndSetSemantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** "Undo everything?" — shared by the Done screen and the TV page. */
@Composable
fun UndoConfirmSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.undo_confirm_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            Text(stringResource(R.string.undo_confirm_body), style = MaterialTheme.typography.bodyLarge)
            VSpace(18)
            PrimaryButton(stringResource(R.string.undo_confirm_go), onClick = onConfirm)
            QuietButton(stringResource(R.string.undo_confirm_cancel), onClick = onDismiss)
        }
    }
}
