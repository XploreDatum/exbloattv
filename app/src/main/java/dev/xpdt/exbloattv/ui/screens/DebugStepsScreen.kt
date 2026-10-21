package dev.xpdt.exbloattv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.discovery.PortProbe
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.QuietButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Turn on debugging": the steps for the person's kind of TV, while the phone checks every 2 s whether the TV now
 * accepts connections and moves on by itself when it does.
 */
@Composable
fun DebugStepsScreen(host: String, tvName: String?, probe: PortProbe, onBack: () -> Unit, onReady: () -> Unit) {
    var brand by rememberSaveable { mutableStateOf(Brand.guess(tvName)) }
    var pickBrand by rememberSaveable { mutableStateOf(false) }
    var pictures by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(host) {
        while (true) {
            if (probe.isOpen(host, 5555, 1_000)) {
                onReady()
                return@LaunchedEffect
            }
            delay(2_000)
        }
    }

    ExScreen(onBack = onBack, actions = { QuietButton(stringResource(R.string.debug_pictures), onClick = { pictures = true }) }) {
        ScreenTitle(stringResource(R.string.debug_title))
        Supporting(stringResource(R.string.debug_subtitle))
        val brandName = stringResource(brand.label)
        val brandCd = stringResource(R.string.debug_brand_cd, brandName)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { pickBrand = true }
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = brandCd; role = Role.DropdownList },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.debug_brand_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(brandName, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Rounded.ExpandMore, null)
        }
        val steps = brand.steps
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            steps.forEachIndexed { i, step ->
                val outer = 24.dp
                val inner = 6.dp
                val shape = RoundedCornerShape(
                    topStart = if (i == 0) outer else inner, topEnd = if (i == 0) outer else inner,
                    bottomStart = if (i == steps.lastIndex) outer else inner, bottomEnd = if (i == steps.lastIndex) outer else inner,
                )
                val text = htmlResource(step.text)
                val cd = stringResource(R.string.debug_step_cd, i + 1, text.text)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 13.dp)
                        .clearAndSetSemantics { contentDescription = cd },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        VSpace(10)
        if (brand.networkTip) Tip(htmlResource(R.string.debug_network_tip))
        if (brand == Brand.FIRE_TV) Tip(androidx.compose.ui.text.AnnotatedString(stringResource(R.string.debug_fire_tip)))
        Tip(htmlResource(R.string.debug_pairing_tip))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Text(stringResource(R.string.debug_waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (pickBrand) BrandSheet(brand, onPick = { brand = it; pickBrand = false }, onDismiss = { pickBrand = false })
    if (pictures) PicturesSheet(brand.steps, onDismiss = { pictures = false })
}

@Composable
private fun Tip(text: androidx.compose.ui.text.AnnotatedString) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun BrandSheet(current: Brand, onPick: (Brand) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.debug_brand_pick), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            Brand.entries.forEach { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = b == current, role = Role.RadioButton, onClick = { onPick(b) })
                        .defaultMinSize(minHeight = 52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = b == current, onClick = null)
                    Text(stringResource(b.label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Simple drawn pictures of each step: a TV screen with the menu item to pick highlighted. */
@Composable
private fun PicturesSheet(steps: List<BrandStep>, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.pictures_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.pictures_step, index + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            val caption = htmlResource(step.text)
            Text(caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
            TvPicture(step.picture, caption.text)
            VSpace(16)
            if (index < steps.lastIndex) {
                PrimaryButton(stringResource(R.string.pictures_next), onClick = { index++ })
            } else {
                PrimaryButton(stringResource(R.string.pictures_close), onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } })
            }
            if (index > 0) QuietButton(stringResource(R.string.pictures_previous), onClick = { index-- })
        }
    }
}

@Composable
private fun TvPicture(picture: StepPicture, caption: String) {
    val (items, pick) = when (picture) {
        is StepPicture.Menu -> picture.items to picture.pick
        is StepPicture.PressOk -> picture.items to picture.pick
        is StepPicture.TurnOn -> picture.items to picture.pick
    }
    val cd = stringResource(R.string.pictures_cd, caption)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = cd }, horizontalAlignment = Alignment.CenterHorizontally) {
        // The TV: a dark rounded screen on a little stand.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(14.dp),
        ) {
            Column(Modifier.fillMaxWidth(0.62f).align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.pic_settings),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                items.forEachIndexed { i, item ->
                    val picked = i == pick
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (picked) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.inverseSurface)
                            .then(if (picked) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.inverseOnSurface), RoundedCornerShape(10.dp)) else Modifier)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(item),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        if (picture is StepPicture.TurnOn && picked) Switch(checked = true, onCheckedChange = null, modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
        Box(Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline))
        VSpace(10)
        val hint = when (picture) {
            is StepPicture.PressOk -> stringResource(R.string.pictures_press_ok, picture.times)
            is StepPicture.TurnOn -> stringResource(R.string.pictures_turn_on)
            is StepPicture.Menu -> null
        }
        if (hint != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.primary)
                Text(hint, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
