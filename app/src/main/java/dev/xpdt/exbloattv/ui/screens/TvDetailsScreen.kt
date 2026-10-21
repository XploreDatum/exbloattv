package dev.xpdt.exbloattv.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.data.ActiveJob
import dev.xpdt.exbloattv.data.RememberedTv
import dev.xpdt.exbloattv.state.Lock
import dev.xpdt.exbloattv.service.UndoFailureInfo
import dev.xpdt.exbloattv.service.UndoProblem
import dev.xpdt.exbloattv.service.WorkState
import dev.xpdt.exbloattv.state.Change
import dev.xpdt.exbloattv.state.ChangeKind
import dev.xpdt.exbloattv.state.TvState
import dev.xpdt.exbloattv.state.TvStateStore
import dev.xpdt.exbloattv.state.TvStateTooNewException
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.QuietButton
import dev.xpdt.exbloattv.ui.components.SegmentedGroup
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LogState {
    data object Loading : LogState
    data class Loaded(val state: TvState) : LogState
    data object Unreachable : LogState
    /** A newer version of the app set this TV up: this one can't read its change log (and must not change it). */
    data object TooNew : LogState
}

enum class LockView { NONE, OTHER_PHONE, THIS_PHONE_INTERRUPTED }

/**
 * What the TV's run lock means for the person. A fresh lock held by this phone while this phone runs nothing is a run
 * that was cut off (the app was killed): this phone can take its own lock again right away, so it is not "another
 * phone". [runningHere]: this phone is working on this TV right now.
 */
fun lockView(lock: Lock?, me: String?, now: Long, runningHere: Boolean): LockView = when {
    lock == null || now - lock.at >= TvStateStore.LOCK_EXPIRY_MS -> LockView.NONE
    me != null && lock.byPhone == me -> if (runningHere) LockView.NONE else LockView.THIS_PHONE_INTERRUPTED
    else -> LockView.OTHER_PHONE
}

/** The TV page: its record on this phone plus the change log read from the TV itself. */
class TvDetailsViewModel(
    private val serial: String,
    tv: Flow<RememberedTv?>,
    activeJob: Flow<ActiveJob?>,
    private val phoneName: suspend () -> String,
    private val readState: suspend (host: String, serial: String) -> TvState,
    /** Called when the person leaves the page: a finished Undo result is then forgotten. */
    private val onLeave: () -> Unit = {},
) : ViewModel() {
    val tv: StateFlow<RememberedTv?> = tv.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** A job recorded for this TV; when this phone isn't running anything, it was cut off. */
    val activeJob: StateFlow<ActiveJob?> =
        activeJob.map { it?.takeIf { j -> j.serial == serial } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _me = MutableStateFlow<String?>(null)
    val me: StateFlow<String?> = _me.asStateFlow()

    init {
        viewModelScope.launch { _me.value = phoneName() }
    }
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
            } catch (e: TvStateTooNewException) {
                LogState.TooNew
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
    val activeJob by vm.activeJob.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
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
    // A newer app version set this TV up: this one must not clean or undo it.
    val tooNew = log == LogState.TooNew
    ExScreen(
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (mine is WorkState.Cleaning) {
                PrimaryButton(stringResource(R.string.home_see), onClick = onSeeCleaning)
            } else {
                PrimaryButton(stringResource(R.string.tv_clean_again), enabled = !busy && !tooNew, onClick = { onCleanAgain(t.lastHost) })
            }
            QuietButton(stringResource(R.string.tv_undo), enabled = !busy && !tooNew, onClick = { confirm = true })
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
            is LogState.Loaded, LogState.TooNew -> Reach.READY
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

        val runningHere = mine?.busy == true
        val ownLock = (log as? LogState.Loaded)?.state?.lock
            ?.let { lockView(it, me, System.currentTimeMillis(), runningHere) == LockView.THIS_PHONE_INTERRUPTED } == true
        val cutOff = !busy && (activeJob != null || ownLock)
        if (cutOff) InterruptedNote(activeJob?.kind ?: ActiveJob.Kind.CLEAN)

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
            LogState.TooNew -> {
                Text(stringResource(R.string.too_new_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
                Supporting(stringResource(R.string.too_new_body))
            }
            is LogState.Loaded -> {
                val lock = l.state.lock
                if (lockView(lock, me, System.currentTimeMillis(), runningHere) == LockView.OTHER_PHONE && lock != null) {
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

/** "A cleanup from this phone was interrupted": both actions below work right away. */
@Composable
fun InterruptedNote(kind: ActiveJob.Kind) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(
            stringResource(if (kind == ActiveJob.Kind.UNDO) R.string.interrupted_undo else R.string.interrupted_clean),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(stringResource(R.string.interrupted_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
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
                    UndoProblem.TOO_NEW -> stringResource(R.string.too_new_body)
                },
            )
            if (mine.problem != UndoProblem.TOO_NEW) {
                FilledTonalButton(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.try_again)) }
            }
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
    f.pkg != null -> nameOf(f.pkg)
    f.kind == ChangeKind.LAUNCHER_SWAP -> stringResource(R.string.home_screen_name)
    else -> stringResource(R.string.undo_failed_animations)
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
