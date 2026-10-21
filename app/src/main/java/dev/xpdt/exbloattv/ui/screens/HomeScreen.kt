package dev.xpdt.exbloattv.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.data.ActiveJob
import dev.xpdt.exbloattv.data.RememberedTv
import dev.xpdt.exbloattv.discovery.PortProbe
import dev.xpdt.exbloattv.service.WorkState
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Reach { CHECKING, READY, AWAY }

/** Remembered TVs, each with a quick "is it on the Wi-Fi with debugging on" check. */
class HomeViewModel(tvs: Flow<List<RememberedTv>>, activeJob: Flow<ActiveJob?>, private val probe: PortProbe) : ViewModel() {
    /** A recorded job; when nothing runs in this app, it was cut off (the app was killed mid-run). */
    val activeJob: StateFlow<ActiveJob?> = activeJob.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null until DataStore has answered (so an empty list really means "no TVs yet"). */
    val tvs: StateFlow<List<RememberedTv>?> = tvs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _reach = MutableStateFlow<Map<String, Reach>>(emptyMap())
    val reach: StateFlow<Map<String, Reach>> = _reach.asStateFlow()

    fun check(list: List<RememberedTv>) {
        for (tv in list) {
            if (_reach.value[tv.serial] == Reach.CHECKING) continue
            _reach.update { it + (tv.serial to Reach.CHECKING) }
            viewModelScope.launch {
                val open = probe.isOpen(tv.lastHost, 5555, 1_500)
                _reach.update { it + (tv.serial to if (open) Reach.READY else Reach.AWAY) }
            }
        }
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    work: WorkState,
    onAddTv: () -> Unit,
    onOpenTv: (String) -> Unit,
    onOpenWork: (WorkState) -> Unit,
    onEmpty: () -> Unit,
    onAbout: () -> Unit,
) {
    val tvs by vm.tvs.collectAsStateWithLifecycle()
    val reach by vm.reach.collectAsStateWithLifecycle()
    val activeJob by vm.activeJob.collectAsStateWithLifecycle()
    LaunchedEffect(tvs) {
        val list = tvs ?: return@LaunchedEffect
        if (list.isEmpty() && work is WorkState.Idle) onEmpty() else vm.check(list)
    }
    ExScreen(
        onBack = null,
        topAction = { AboutAction(onAbout) },
        actions = { PrimaryButton(stringResource(R.string.home_add_tv), onClick = onAddTv) },
    ) {
        ScreenTitle(stringResource(R.string.home_title))
        Supporting(stringResource(R.string.home_subtitle))
        val cutOff = activeJob?.takeIf { work is WorkState.Idle }
        if (cutOff != null) {
            Banner(stringResource(R.string.home_interrupted, cutOff.tvName)) { onOpenTv(cutOff.serial) }
        } else {
            WorkBanner(work, onOpenWork)
        }
        tvs.orEmpty().sortedByDescending { it.lastCleanedAt ?: 0 }.forEach { tv ->
            TvCard(tv, reach[tv.serial] ?: Reach.CHECKING, onClick = { onOpenTv(tv.serial) })
        }
    }
}

@Composable
private fun WorkBanner(work: WorkState, onOpen: (WorkState) -> Unit) {
    val text = when (work) {
        is WorkState.Cleaning -> stringResource(R.string.home_busy_cleaning, work.tvName)
        is WorkState.Undoing -> stringResource(R.string.home_busy_undoing, work.tvName)
        is WorkState.Cleaned -> stringResource(R.string.home_result_ready, work.tvName)
        is WorkState.CleanupStopped -> stringResource(R.string.home_result_ready, work.tvName)
        else -> return
    }
    Banner(text) { onOpen(work) }
}

@Composable
private fun Banner(text: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        TextButton(onClick = onOpen, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(stringResource(R.string.home_see))
        }
    }
}

@Composable
private fun TvCard(tv: RememberedTv, reach: Reach, onClick: () -> Unit) {
    val cleaned = tv.lastCleanedAt?.let {
        stringResource(R.string.home_last_cleaned, DateUtils.getRelativeTimeSpanString(it).toString())
    } ?: stringResource(R.string.home_never_cleaned)
    val status = stringResource(
        when (reach) {
            Reach.CHECKING -> R.string.home_status_checking
            Reach.READY -> R.string.home_status_ready
            Reach.AWAY -> R.string.home_status_away
        },
    )
    val cd = stringResource(R.string.home_tv_cd, tv.name, tv.lastHost, cleaned, status)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 72.dp)
            .padding(14.dp)
            .clearAndSetSemantics { contentDescription = cd; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(tv.lastHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cleaned, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusDot(reach, status)
    }
}

@Composable
fun StatusDot(reach: Reach, label: String) {
    val color = when (reach) {
        Reach.READY -> MaterialTheme.colorScheme.primary
        Reach.AWAY -> MaterialTheme.colorScheme.outline
        Reach.CHECKING -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
