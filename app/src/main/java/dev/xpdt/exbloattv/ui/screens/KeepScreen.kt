package dev.xpdt.exbloattv.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.state.TvStateTooNewException
import dev.xpdt.exbloattv.catalog.Category
import dev.xpdt.exbloattv.ui.components.AppRow
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.QuietButton
import dev.xpdt.exbloattv.ui.components.SegmentedGroup
import kotlinx.coroutines.launch

/**
 * "Keep what you watch": every app the person can decide about, grouped by category, then the speed switches.
 * [onStart] begins the cleanup and returns false if other work is still running.
 */
@Composable
fun KeepScreen(vm: KeepViewModel, onBack: () -> Unit, onFindAgain: () -> Unit, onStart: (KeepUiState.Ready) -> Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var review by rememberSaveable { mutableStateOf(false) }
    val busyText = stringResource(R.string.keep_busy)

    when (val s = state) {
        KeepUiState.Loading -> ExScreen(onBack = onBack) {
            ScreenTitle(stringResource(R.string.keep_title))
            Busy(stringResource(R.string.keep_loading))
        }
        is KeepUiState.Failed -> {
            val tooNew = s.error is TvStateTooNewException
            ExScreen(
                onBack = onBack,
                actions = {
                    // Trying again can't help until the app is updated.
                    if (!tooNew) PrimaryButton(stringResource(R.string.try_again), onClick = vm::retry)
                    QuietButton(stringResource(R.string.keep_find_again), onClick = onFindAgain)
                },
            ) {
                ScreenTitle(stringResource(if (tooNew) R.string.too_new_title else R.string.keep_failed_title))
                Supporting(stringResource(if (tooNew) R.string.too_new_body else R.string.keep_failed_body))
            }
        }
        is KeepUiState.Ready -> {
            ExScreen(
                onBack = onBack,
                snackbar = snackbar,
                actions = { PrimaryButton(stringResource(R.string.keep_go), onClick = { review = true }) },
            ) {
                ScreenTitle(stringResource(R.string.keep_title))
                Supporting(stringResource(R.string.keep_subtitle), Modifier.padding(bottom = 0.dp))
                for (group in s.groups) {
                    SectionLabel(stringResource(categoryLabel(group.category)))
                    if (group.category == Category.OTHER) {
                        Text(
                            stringResource(R.string.keep_other_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                    }
                    SegmentedGroup(
                        group.rows.map { row ->
                            {
                                val label = labels[row.pkg]
                                AppRow(
                                    name = label?.name ?: row.name,
                                    description = row.description.ifBlank { null } ?: if (!row.toggleable) stringResource(R.string.keep_locked) else null,
                                    icon = rememberIcon(label?.iconPng),
                                    checked = row.keep,
                                    enabled = row.toggleable,
                                    onCheckedChange = { vm.setKeep(row.pkg, it) },
                                )
                            }
                        },
                    )
                }
                if (s.alwaysOff.isNotEmpty()) AdsAndTracking(s.alwaysOff, s.adsUnlocked, onToggle = vm::setKeep)
                SpeedSwitches(s, vm)
                SystemApps(s.system)
                if (s.alwaysOff.isNotEmpty()) Advanced(s.adsUnlocked, vm::setAdsUnlocked)
                VSpace(12)
            }
            if (review) {
                ReviewSheet(
                    review = s.review,
                    deleteUserApps = s.choices.deleteUserApps,
                    onDeleteUserApps = vm::setDeleteUserApps,
                    onDismiss = { review = false },
                    onStart = {
                        if (onStart(s)) {
                            review = false
                        } else {
                            review = false
                            scope.launch { snackbar.showSnackbar(busyText) }
                        }
                    },
                )
            }
        }
    }
}

/** Ads & tracking: collapsed "Always switched off (N)", or switches once unlocked under Advanced. */
@Composable
private fun AdsAndTracking(rows: List<KeepRow>, unlocked: Boolean, onToggle: (String, Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.cat_ads))
    val title = stringResource(if (unlocked) R.string.keep_ads_choose else R.string.keep_always_off, rows.size)
    val body = stringResource(if (unlocked) R.string.keep_ads_choose_body else R.string.keep_always_off_body)
    val stateText = stringResource(if (open) R.string.keep_expanded else R.string.keep_collapsed)
    val items = buildList<@Composable () -> Unit> {
        add {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clearAndSetSemantics { contentDescription = title; stateDescription = stateText; role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
        }
        if (open || unlocked) rows.forEach { row ->
            add {
                AppRow(row.name, row.description.ifBlank { null }, null, checked = row.keep, enabled = row.toggleable, onCheckedChange = { onToggle(row.pkg, it) })
            }
        }
    }
    SegmentedGroup(items)
}

@Composable
private fun Advanced(adsUnlocked: Boolean, onUnlock: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(adsUnlocked) }
    QuietButton(stringResource(if (open) R.string.keep_hide_advanced else R.string.keep_advanced), onClick = { open = !open })
    if (open) {
        SegmentedGroup(
            listOf { SettingRow(stringResource(R.string.keep_unlock_ads), stringResource(R.string.keep_unlock_ads_desc), adsUnlocked, onCheckedChange = onUnlock) },
        )
    }
}

@Composable
private fun SpeedSwitches(s: KeepUiState.Ready, vm: KeepViewModel) {
    SectionLabel(stringResource(R.string.keep_speed_label))
    val c = s.choices
    SegmentedGroup(
        listOf(
            { SettingRow(stringResource(R.string.speed_animations), stringResource(R.string.speed_animations_desc), c.fasterAnimations) { vm.setSpeed(SpeedOption.FASTER_ANIMATIONS, it) } },
            { SettingRow(stringResource(R.string.speed_background), stringResource(R.string.speed_background_desc), c.stopBackground) { vm.setSpeed(SpeedOption.STOP_BACKGROUND, it) } },
            { SettingRow(stringResource(R.string.speed_caches), stringResource(R.string.speed_caches_desc), c.clearCaches) { vm.setSpeed(SpeedOption.CLEAR_CACHES, it) } },
            {
                SettingRow(
                    stringResource(R.string.speed_home),
                    stringResource(if (s.homeScreenSwapAvailable) R.string.speed_home_desc else R.string.speed_home_fire),
                    c.newHomeScreen && s.homeScreenSwapAvailable,
                    enabled = s.homeScreenSwapAvailable,
                ) { vm.setSpeed(SpeedOption.NEW_HOME_SCREEN, it) }
            },
        ),
    )
}

@Composable
private fun SystemApps(rows: List<SystemRow>) {
    if (rows.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    VSpace(8)
    QuietButton(stringResource(if (open) R.string.keep_hide_system else R.string.keep_show_system), onClick = { open = !open })
    if (!open) return
    Text(
        stringResource(R.string.keep_system_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
    SegmentedGroup(
        rows.map { row ->
            {
                Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    if (details) Text(row.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
    Box(Modifier.fillMaxWidth()) {
        QuietButton(stringResource(if (details) R.string.tv_log_hide else R.string.keep_details), onClick = { details = !details })
    }
}
