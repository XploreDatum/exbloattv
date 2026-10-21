package dev.mntl.exbloattv.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.mntl.exbloattv.R
import dev.mntl.exbloattv.data.RememberedTv
import dev.mntl.exbloattv.service.UndoFailureInfo
import dev.mntl.exbloattv.service.UndoProblem
import dev.mntl.exbloattv.service.WorkState
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.ChangeKind
import dev.mntl.exbloattv.state.TvState
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.ui.components.PrimaryButton
import dev.mntl.exbloattv.ui.components.QuietButton
import dev.mntl.exbloattv.ui.components.SegmentedGroup
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LogState {
    data object Loading : LogState
    data class Loaded(val state: TvState) : LogState
    data object Unreachable : LogState
}

/** The TV page: its record on this phone plus the change log read from the TV itself. */
class TvDetailsViewModel(
    private val serial: String,
    tv: Flow<RememberedTv?>,
    private val readState: suspend (host: String, serial: String) -> TvState,
    /** Called when the person leaves the page: a finished Undo result is then forgotten. */
    private val onLeave: () -> Unit = {},
) : ViewModel() {
    val tv: StateFlow<RememberedTv?> = tv.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _log = MutableStateFlow<LogState>(LogState.Loading)
    val log: StateFlow<LogState> = _log.asStateFlow()
    private var job: Job? = null

    override fun onCleared() = onLeave()

    fun refresh(host: String) {
        job?.cancel()
        _log.value = LogState.Loading
        job = viewModelScope.launch {
            _log.value = try {
                LogState.Loaded(readState(host, serial))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogState.Unreachable
            }
        }
    }
}

@Composable
fun TvDetailsScreen(
    vm: TvDetailsViewModel,
    work: WorkState,
    nameOf: (String) -> String,
    onBack: () -> Unit,
    onCleanAgain: (host: String) -> Unit,
    onUndo: (host: String, name: String) -> Unit,
    onOpenInStore: suspend (host: String, pkg: String) -> Boolean,
    onSeeCleaning: () -> Unit,
) {
    val tv by vm.tv.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var technical by rememberSaveable { mutableStateOf(false) }
    val opened = stringResource(R.string.tv_store_opened)
    val openFailed = stringResource(R.string.tv_store_failed)

    val t = tv
    LaunchedEffect(t?.lastHost) { t?.let { vm.refresh(it.lastHost) } }
    val mine = work.takeIf { t != null && it.tvSerial == t.serial }
    LaunchedEffect(mine is WorkState.Undone || mine is WorkState.UndoFailed) {
        if ((mine is WorkState.Undone || mine is WorkState.UndoFailed) && t != null) vm.refresh(t.lastHost)
    }

    if (t == null) {
        ExScreen(onBack = onBack) { ScreenTitle(stringResource(R.string.tv_not_found)) }
        return
    }
    val busy = work.busy
    ExScreen(
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (mine is WorkState.Cleaning) {
                PrimaryButton(stringResource(R.string.home_see), onClick = onSeeCleaning)
            } else {
                PrimaryButton(stringResource(R.string.tv_clean_again), enabled = !busy, onClick = { onCleanAgain(t.lastHost) })
            }
            QuietButton(stringResource(R.string.tv_undo), enabled = !busy, onClick = { confirm = true })
        },
    ) {
        ScreenTitle(t.name)
        Text(stringResource(R.string.tv_at, t.lastHost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            t.lastCleanedAt?.let { stringResource(R.string.home_last_cleaned, DateUtils.getRelativeTimeSpanString(it).toString()) }
                ?: stringResource(R.string.home_never_cleaned),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(6)
        val reach = when (log) {
            LogState.Loading -> Reach.CHECKING
            is LogState.Loaded -> Reach.READY
            LogState.Unreachable -> Reach.AWAY
        }
        StatusDot(
            reach,
            stringResource(
                when (reach) {
                    Reach.CHECKING -> R.string.home_status_checking
                    Reach.READY -> R.string.home_status_ready
                    Reach.AWAY -> R.string.home_status_away
                },
            ),
        )
        VSpace(8)

        UndoSection(mine, nameOf, onRetry = { onUndo(t.lastHost, t.name) }, onOpenInStore = { pkg ->
            scope.launch { snackbar.showSnackbar(if (onOpenInStore(t.lastHost, pkg)) opened else openFailed) }
        })

        SectionLabel(stringResource(R.string.tv_log_title))
        when (val l = log) {
            LogState.Loading -> Busy(stringResource(R.string.tv_log_loading))
            LogState.Unreachable -> {
                Supporting(stringResource(R.string.tv_log_unreachable))
                QuietButton(stringResource(R.string.tv_refresh), onClick = { vm.refresh(t.lastHost) })
            }
            is LogState.Loaded -> {
                val lock = l.state.lock
                if (lock != null && !busy && System.currentTimeMillis() - lock.at < TvStateStore.LOCK_EXPIRY_MS) {
                    Supporting(stringResource(R.string.tv_locked_now, lock.byPhone))
                }
                val changes = l.state.changes
                if (changes.isEmpty()) {
                    Supporting(stringResource(R.string.tv_log_empty))
                } else {
                    Text(
                        pluralStringResource(R.plurals.tv_log_count, changes.size, changes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    SegmentedGroup(changes.asReversed().map { c -> { LogRow(c, nameOf, technical) } })
                    QuietButton(stringResource(if (technical) R.string.tv_log_hide else R.string.tv_log_show), onClick = { technical = !technical })
                }
            }
        }
        VSpace(12)
    }

    if (confirm) {
        UndoConfirmSheet(onDismiss = { confirm = false }, onConfirm = {
            confirm = false
            onUndo(t.lastHost, t.name)
        })
    }
}

@Composable
private fun UndoSection(mine: WorkState?, nameOf: (String) -> String, onRetry: () -> Unit, onOpenInStore: (String) -> Unit) {
    when (mine) {
        is WorkState.Undoing -> {
            SectionLabel(stringResource(R.string.tv_undoing_title))
            Busy(stringResource(R.string.tv_undoing_body))
        }
        is WorkState.UndoFailed -> {
            SectionLabel(stringResource(R.string.tv_undone_title))
            Supporting(
                when (mine.problem) {
                    UndoProblem.LOCKED -> stringResource(R.string.undo_problem_locked, mine.lockedBy.orEmpty())
                    UndoProblem.NOT_ALLOWED -> stringResource(R.string.undo_problem_not_allowed)
                    UndoProblem.UNREACHABLE -> stringResource(R.string.undo_problem_unreachable)
                    UndoProblem.UNEXPECTED -> stringResource(R.string.undo_problem_unexpected)
                },
            )
            FilledTonalButton(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.try_again)) }
        }
        is WorkState.Undone -> {
            val o = mine.outcome
            SectionLabel(stringResource(R.string.tv_undone_title))
            Text(
                if (o.restored > 0) pluralStringResource(R.plurals.tv_undone_restored, o.restored, o.restored) else stringResource(R.string.tv_undone_nothing),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (o.interrupted) {
                Supporting(stringResource(R.string.tv_undo_interrupted))
                FilledTonalButton(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.try_again)) }
            }
            if (o.failed.isNotEmpty()) {
                SectionLabel(stringResource(R.string.tv_undone_failed_title))
                Supporting(stringResource(R.string.tv_undone_failed_body))
                SegmentedGroup(o.failed.map { f -> { PlainRow(failureLabel(f, nameOf)) } })
            }
            if (o.reinstallFromStore.isNotEmpty()) {
                SectionLabel(stringResource(R.string.tv_reinstall_title))
                Supporting(stringResource(R.string.tv_reinstall_body))
                SegmentedGroup(
                    o.reinstallFromStore.map { pkg ->
                        {
                            val name = nameOf(pkg)
                            val cd = stringResource(R.string.tv_open_store_cd, name)
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                FilledTonalButton(
                                    onClick = { onOpenInStore(pkg) },
                                    modifier = Modifier.padding(top = 6.dp).defaultMinSize(minHeight = 48.dp).semantics { contentDescription = cd },
                                ) { Text(stringResource(R.string.tv_open_store)) }
                            }
                        }
                    },
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun failureLabel(f: UndoFailureInfo, nameOf: (String) -> String): String = when {
    f.label != null -> f.label
    f.pkg == null && f.kind == ChangeKind.LAUNCHER_SWAP -> stringResource(R.string.home_screen_name)
    f.pkg != null -> nameOf(f.pkg)
    else -> stringResource(R.string.home_screen_name)
}

@Composable
private fun PlainRow(text: String) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LogRow(c: Change, nameOf: (String) -> String, technical: Boolean) {
    val app = c.pkg?.let(nameOf).orEmpty()
    val title = when (c.kind) {
        ChangeKind.DISABLE -> stringResource(R.string.log_disable, app)
        ChangeKind.ENABLE -> stringResource(R.string.log_enable, app)
        ChangeKind.REMOVE_FOR_USER -> stringResource(R.string.log_remove_for_user, app)
        ChangeKind.INSTALL_EXISTING -> stringResource(R.string.log_install_existing, app)
        ChangeKind.UNINSTALL -> stringResource(R.string.log_uninstall, app)
        ChangeKind.ANIMATIONS -> stringResource(R.string.log_animations)
        ChangeKind.APPOPS -> stringResource(R.string.log_appops, app)
        ChangeKind.INSTALL_APK -> stringResource(R.string.log_install_apk, app)
        ChangeKind.LAUNCHER_SWAP -> stringResource(R.string.log_launcher_swap)
    }
    val whenText = DateUtils.getRelativeTimeSpanString(c.at).toString()
    Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.log_by, c.byPhone, whenText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!c.verified) Text(stringResource(R.string.log_unconfirmed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (technical) {
            Text(
                stringResource(R.string.log_tech, c.kind.name, c.pkg ?: "-", c.from, c.to, c.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
