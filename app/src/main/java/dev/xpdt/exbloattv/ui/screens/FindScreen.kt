package dev.xpdt.exbloattv.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.discovery.TvReadiness
import dev.xpdt.exbloattv.ui.components.ChipKind
import dev.xpdt.exbloattv.ui.components.CookieBadge
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.QuietButton
import dev.xpdt.exbloattv.ui.components.StatusChip

/** Android 16 (API 36): local network protection may require "Nearby devices" for mDNS. */
private const val NEARBY_FOR_NAMES_SDK = 36

/** Four numbers 0-255 with dots. */
fun isIpv4(text: String): Boolean {
    val parts = text.trim().split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

@Composable
fun FindScreen(
    vm: FindViewModel,
    onBack: (() -> Unit)?,
    /** Shown only when this is the first screen (no TVs yet), where the home screen's About action isn't reachable. */
    onAbout: () -> Unit,
    onPick: (FindRow) -> Unit,
    onManualHost: (String) -> Unit,
    /** null while unknown; true once "Nearby devices" was asked for (it is never asked again). */
    nearbyAsked: Boolean?,
    onNearbyAsked: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 16+ may hold back the network-name lookup (mDNS) without "Nearby devices". The address scan works
    // either way, so this is asked once, only when the person taps Allow on the one-line explanation.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.rescan()
    }
    val needsNearby = Build.VERSION.SDK_INT >= NEARBY_FOR_NAMES_SDK &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
    LifecycleResumeEffect(state.wifi) {
        if (!state.wifi) vm.rescan()
        onPauseOrDispose { }
    }

    val aboutAction: (@Composable RowScope.() -> Unit)? = if (onBack == null) ({ AboutAction(onAbout) }) else null

    var showIp by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = state.rows.firstOrNull { it.host == selected } ?: state.rows.firstOrNull()

    if (!state.wifi) {
        ExScreen(
            onBack = onBack,
            topAction = aboutAction,
            actions = {
                PrimaryButton(stringResource(R.string.find_open_wifi), onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                    runCatching { context.startActivity(intent) }
                })
                QuietButton(stringResource(R.string.find_check_again), onClick = vm::rescan)
            },
        ) {
            ScreenTitle(stringResource(R.string.find_no_wifi_title))
            Supporting(stringResource(R.string.find_no_wifi_body))
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CookieBadge(MaterialShapes.Cookie9Sided, Icons.Rounded.WifiOff, 120.dp)
            }
        }
        return
    }

    ExScreen(
        onBack = onBack,
        topAction = aboutAction,
        actions = {
            PrimaryButton(stringResource(R.string.find_continue), enabled = pick != null, onClick = { pick?.let(onPick) })
            QuietButton(stringResource(R.string.find_enter_ip), onClick = { showIp = true })
        },
    ) {
        ScreenTitle(stringResource(R.string.find_title))
        Supporting(stringResource(R.string.find_subtitle))
        ScanHero(state.scanning)
        if (needsNearby && nearbyAsked == false) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.find_nearby_rationale),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    onNearbyAsked()
                    permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.find_nearby_allow)) }
            }
        }
        if (state.scanning) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.find_scanning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VSpace(8)
        }
        state.rows.forEach { row -> FoundCard(row, selected = row == pick, onClick = { selected = row.host }) }
        if (state.nothingFound) {
            Text(stringResource(R.string.find_nothing_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.find_nothing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (!state.scanning) QuietButton(stringResource(R.string.find_scan_again), onClick = vm::rescan)
    }

    if (showIp) IpDialog(onDismiss = { showIp = false }, onConnect = { showIp = false; onManualHost(it) })
}

@Composable
private fun ScanHero(scanning: Boolean) {
    val spin = rememberInfiniteTransition(label = "scan")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(150.dp)
                .rotate(if (scanning) angle else 0f)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        CookieBadge(MaterialShapes.Cookie9Sided, Icons.Rounded.Wifi, 96.dp)
    }
    VSpace(10)
}

@Composable
private fun FoundCard(row: FindRow, selected: Boolean, onClick: () -> Unit) {
    val name = row.name ?: stringResource(R.string.find_unnamed)
    val chip = stringResource(if (row.readiness == TvReadiness.READY) R.string.find_ready else R.string.find_setup)
    val address = if (row.movedFrom != null) stringResource(R.string.find_moved, row.host) else row.host
    val cd = stringResource(R.string.find_tv_cd, name, address, chip)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(14.dp)
            .clearAndSetSemantics { contentDescription = cd; this.selected = selected; role = Role.RadioButton },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(chip, if (row.readiness == TvReadiness.READY) ChipKind.READY else ChipKind.SETUP)
    }
}

@Composable
private fun IpDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val bad = tried && !isIpv4(text)
    fun submit() {
        tried = true
        if (isIpv4(text)) onConnect(text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ip_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ip_dialog_body), style = MaterialTheme.typography.bodyMedium)
                VSpace(12)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    label = { Text(stringResource(R.string.ip_dialog_label)) },
                    singleLine = true,
                    isError = bad,
                    supportingText = if (bad) { { Text(stringResource(R.string.ip_dialog_error)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.ip_dialog_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
