package dev.mntl.exbloattv.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mntl.exbloattv.R
import dev.mntl.exbloattv.cleanup.CleanupRunner
import dev.mntl.exbloattv.cleanup.RunProgress
import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepState
import dev.mntl.exbloattv.cleanup.StepStatus
import dev.mntl.exbloattv.cleanup.appName
import dev.mntl.exbloattv.data.ActiveJob
import dev.mntl.exbloattv.service.CleanupWork
import dev.mntl.exbloattv.service.WorkState
import dev.mntl.exbloattv.service.isChangedElsewhere
import dev.mntl.exbloattv.ui.components.PrimaryButton
import dev.mntl.exbloattv.ui.components.QuietButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

enum class LineKind(@StringRes val title: Int, @StringRes val sub: Int? = null, val counted: Boolean = false) {
    RESTORE(R.string.group_restore),
    DISABLE(R.string.group_disable, counted = true),
    UNINSTALL(R.string.group_uninstall, counted = true),
    SWITCH_ON(R.string.group_switch_on, counted = true),
    BACKGROUND(R.string.group_background, counted = true),
    ANIMATIONS(R.string.group_animations, R.string.group_animations_sub),
    CACHES(R.string.group_caches),
    SWAP(R.string.group_swap, R.string.group_swap_sub),
    REBOOT(R.string.group_reboot),
}

/** One checklist line: several steps of one kind shown together, like the mockup's "Unused apps off · 17 apps". */
data class ChecklistLine(val kind: LineKind, val indices: List<Int>, val status: StepStatus, val finished: Int)

fun lineKind(step: Step): LineKind = when (step) {
    Step.SaveRestorePoint -> LineKind.RESTORE
    is Step.Disable -> LineKind.DISABLE
    is Step.Uninstall -> LineKind.UNINSTALL
    is Step.Enable, is Step.InstallExisting -> LineKind.SWITCH_ON
    is Step.RestrictBackground -> LineKind.BACKGROUND
    is Step.SetAnimations -> LineKind.ANIMATIONS
    Step.TrimCaches -> LineKind.CACHES
    Step.SwapLauncher -> LineKind.SWAP
    Step.RebootAndVerify -> LineKind.REBOOT
}

/** Groups the run's steps into checklist lines, in the order they run. A line needs attention if any step failed. */
fun checklist(steps: List<StepState>): List<ChecklistLine> {
    val byKind = linkedMapOf<LineKind, MutableList<Int>>()
    steps.forEachIndexed { i, s -> byKind.getOrPut(lineKind(s.step)) { mutableListOf() } += i }
    return byKind.map { (kind, idx) ->
        val st = idx.map { steps[it].status }
        val status = when {
            StepStatus.FAILED in st -> StepStatus.FAILED
            StepStatus.RUNNING in st -> StepStatus.RUNNING
            st.all { it == StepStatus.SKIPPED } -> StepStatus.SKIPPED
            st.all { it == StepStatus.DONE || it == StepStatus.SKIPPED } -> StepStatus.DONE
            st.any { it != StepStatus.PENDING } -> StepStatus.RUNNING
            else -> StepStatus.PENDING
        }
        ChecklistLine(kind, idx, status, st.count { it == StepStatus.DONE || it == StepStatus.SKIPPED })
    }
}

/**
 * What the Cleaning screen offers for the run as it is now. [terminal]: the message of a failure no Retry can fix
 * (changed from another phone, or set up by a newer app version).
 */
data class RunActions(
    val stop: Boolean,
    val topStop: Boolean,
    val finishAnyway: Boolean,
    val checkAgain: Boolean,
    val terminal: String?,
)

fun runActions(work: WorkState.Cleaning): RunActions {
    val p = work.progress
    val failed = p?.steps?.withIndex()?.filter { it.value.status == StepStatus.FAILED }.orEmpty()
    val terminal = failed.firstOrNull { isChangedElsewhere(it.value.message) || isTooNew(it.value.message) }?.value?.message
    // Every step is finished while settling or measuring: the result is moments away, so there is nothing to stop.
    val stepsOver = work.settleSecondsLeft != null || work.measuring
    return RunActions(
        stop = !stepsOver && (failed.isNotEmpty() || p?.waitingForTv == true),
        topStop = !stepsOver,
        finishAnyway = terminal == null && failed.any { it.index != 0 },
        checkAgain = isChangedElsewhere(terminal),
        terminal = terminal,
    )
}

/** The run stopped because a newer version of the app set this TV up. */
fun isTooNew(message: String?): Boolean = message == CleanupRunner.TOO_NEW_MESSAGE

@Composable
fun stepText(step: Step, names: Map<String, String>): String = when (step) {
    is Step.Disable -> stringResource(R.string.step_disable, appName(step.pkg, names))
    is Step.Enable -> stringResource(R.string.step_enable, appName(step.pkg, names))
    is Step.InstallExisting -> stringResource(R.string.step_install_existing, appName(step.pkg, names))
    is Step.Uninstall -> stringResource(R.string.step_uninstall, appName(step.pkg, names))
    is Step.RestrictBackground -> stringResource(R.string.step_background, appName(step.pkg, names))
    else -> stringResource(lineKind(step).title)
}

@Composable
private fun statusText(s: StepStatus) = stringResource(
    when (s) {
        StepStatus.DONE -> R.string.status_done
        StepStatus.RUNNING -> R.string.status_running
        StepStatus.FAILED -> R.string.status_failed
        StepStatus.PENDING -> R.string.status_pending
        StepStatus.SKIPPED -> R.string.status_skipped
    },
)

/** "Cleaning up": wavy progress and the live checklist; failed steps get Retry/Skip inline. */
@Composable
fun CleaningScreen(
    serial: String,
    work: WorkState,
    actions: CleanupWork,
    onDone: () -> Unit,
    onHome: () -> Unit,
    onRecheck: () -> Unit,
    /** A job recorded for this TV that no longer runs (the app was killed mid-run). */
    interrupted: ActiveJob?,
    onOpenTv: () -> Unit,
    onUndo: (host: String, tvName: String) -> Unit,
    onAcknowledge: () -> Unit,
) {
    // This scope outlives the Running screen, so a stop or re-check still navigates once the run has ended.
    val scope = rememberCoroutineScope()
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    // Not saveable: after the screen is recreated the stop is already under way and needs no button state.
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(work) {
        if (work is WorkState.Cleaned && work.serial == serial) onDone()
    }
    when {
        work is WorkState.Cleaning && work.serial == serial -> {
            Running(work, actions, onHome, stopping = stopping, onStop = { confirmStop = true }, onRecheck = {
                scope.launch {
                    actions.abandonCleanup()
                    onRecheck()
                }
            })
            if (confirmStop && !stopping) {
                StopConfirmSheet(onDismiss = { confirmStop = false }, onConfirm = {
                    confirmStop = false
                    stopping = true
                    scope.launch {
                        try {
                            // Waits for the step that is running to finish logging; the lock is then released.
                            actions.abandonCleanup()
                        } finally {
                            stopping = false
                        }
                        onOpenTv() // the TV's page: its record of changes, and Undo
                    }
                })
            }
        }
        work is WorkState.CleanupStopped && work.serial == serial -> {
            ExScreen(
                onBack = { onAcknowledge(); onHome() },
                actions = {
                    PrimaryButton(stringResource(R.string.cleaning_check_again), onClick = { onAcknowledge(); onRecheck() })
                    QuietButton(stringResource(R.string.done_undo), onClick = { confirmUndo = true })
                },
            ) {
                ScreenTitle(stringResource(R.string.cleaning_stopped_title))
                Supporting(stringResource(R.string.cleaning_stopped_body))
                work.progress?.let { Checklist(it, work.appNames, actions = null) }
                VSpace(8)
                QuietButton(stringResource(R.string.cleaning_back_home), onClick = { onAcknowledge(); onHome() })
            }
            if (confirmUndo) {
                UndoConfirmSheet(onDismiss = { confirmUndo = false }, onConfirm = {
                    confirmUndo = false
                    onUndo(work.host, work.tvName)
                })
            }
        }
        else -> ExScreen(
            onBack = onHome,
            actions = {
                if (interrupted != null && !work.busy) PrimaryButton(stringResource(R.string.cleaning_open_tv), onClick = onOpenTv)
                QuietButton(stringResource(R.string.cleaning_back_home), onClick = onHome)
            },
        ) {
            ScreenTitle(stringResource(R.string.cleaning_title))
            if (interrupted != null && !work.busy) {
                InterruptedNote(interrupted.kind)
            } else {
                Supporting(stringResource(R.string.cleaning_not_running))
            }
        }
    }
}

@Composable
private fun Running(
    work: WorkState.Cleaning,
    actions: CleanupWork,
    onHome: () -> Unit,
    stopping: Boolean,
    onStop: () -> Unit,
    onRecheck: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val p = work.progress
    val a = runActions(work)
    val stopCd = stringResource(R.string.cleaning_stop_cd)
    ExScreen(
        onBack = onHome,
        topAction = if (a.topStop && !stopping) {
            {
                TextButton(onClick = onStop, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = stopCd }) {
                    Text(stringResource(R.string.cleaning_stop))
                }
            }
        } else null,
        actions = when {
            stopping -> null
            a.checkAgain -> {
                {
                    PrimaryButton(stringResource(R.string.cleaning_check_again), onClick = onRecheck)
                    Text(
                        stringResource(R.string.cleaning_check_again_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    QuietButton(stringResource(R.string.stop_confirm_go), onClick = onStop)
                }
            }
            a.finishAnyway -> {
                {
                    PrimaryButton(stringResource(R.string.cleaning_finish_anyway), onClick = { scope.launch { actions.finishAnyway() } })
                    Text(
                        stringResource(R.string.cleaning_finish_anyway_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    QuietButton(stringResource(R.string.stop_confirm_go), onClick = onStop)
                }
            }
            // Nothing else gets this run going again (a newer app set the TV up): stopping is the way out.
            a.terminal != null -> {
                { PrimaryButton(stringResource(R.string.stop_confirm_go), onClick = onStop) }
            }
            a.stop -> {
                { QuietButton(stringResource(R.string.stop_confirm_go), onClick = onStop) }
            }
            else -> null
        },
    ) {
        ScreenTitle(stringResource(R.string.cleaning_title))
        Supporting(stringResource(R.string.cleaning_subtitle), Modifier.padding(bottom = 4.dp))
        val fraction by animateFloatAsState(p?.fraction ?: 0f, label = "progress")
        val pct = ((p?.fraction ?: 0f) * 100).toInt()
        val progressCd = stringResource(R.string.cleaning_progress_cd, pct)
        val settling = work.settleSecondsLeft != null || work.measuring
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp).clearAndSetSemantics { contentDescription = progressCd }) {
            if (p == null || settling) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            else LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (stopping) Busy(stringResource(R.string.cleaning_stopping))
        if (p?.waitingForTv == true && !stopping) {
            Banner(stringResource(R.string.cleaning_cant_reach), stringResource(R.string.cleaning_retry)) { scope.launch { actions.retryAll() } }
        }
        when {
            work.settleSecondsLeft != null -> Busy(stringResource(R.string.cleaning_settle, work.settleSecondsLeft))
            work.measuring -> Busy(stringResource(R.string.cleaning_measuring))
            p == null -> Busy(stringResource(R.string.cleaning_starting))
        }
        if (p != null) Checklist(p, work.appNames, actions.takeIf { a.terminal == null && !stopping })
        if (a.terminal != null) {
            VSpace(12)
            Text(a.terminal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        VSpace(14)
        Text(
            stringResource(R.string.cleaning_lock_ok),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        VSpace(12)
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        FilledTonalButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(action) }
    }
}

/** The live checklist. [actions] null = read-only (no Retry/Skip). */
@Composable
private fun Checklist(p: RunProgress, names: Map<String, String>, actions: CleanupWork?) {
    val scope = rememberCoroutineScope()
    val lines = checklist(p.steps)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEachIndexed { li, line ->
            val outer = 24.dp
            val inner = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (li == 0) outer else inner, topEnd = if (li == 0) outer else inner,
                bottomStart = if (li == lines.lastIndex) outer else inner, bottomEnd = if (li == lines.lastIndex) outer else inner,
            )
            Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
                LineRow(line, li + 1)
                for (i in line.indices) {
                    val s = p.steps[i]
                    if (s.status != StepStatus.FAILED || isChangedElsewhere(s.message) || isTooNew(s.message)) continue
                    FailedStep(
                        text = stepText(s.step, names),
                        message = s.message,
                        index = i,
                        onRetry = actions?.let { a -> { scope.launch { a.retry(i) } } },
                        onSkip = actions?.takeIf { i != 0 }?.let { a -> { scope.launch { a.skip(i) } } },
                    )
                }
            }
        }
    }
}

@Composable
private fun LineRow(line: ChecklistLine, number: Int) {
    val title = stringResource(line.kind.title)
    val sub = when {
        line.kind.counted -> stringResource(R.string.group_apps_count, line.finished, line.indices.size)
        line.kind.sub != null -> stringResource(line.kind.sub)
        else -> null
    }
    val cd = stringResource(R.string.row_with_state, listOfNotNull(title, sub).joinToString(", "), statusText(line.status))
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (line.status == StepStatus.PENDING) 0.6f else 1f)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Lead(line.status, number)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Lead(status: StepStatus, number: Int) {
    val (bg, fg) = when (status) {
        StepStatus.DONE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when (status) {
            StepStatus.DONE -> Icon(Icons.Rounded.Check, null, tint = fg)
            StepStatus.FAILED -> Icon(Icons.Rounded.PriorityHigh, null, tint = fg)
            StepStatus.SKIPPED -> Icon(Icons.Rounded.Remove, null, tint = fg)
            StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            StepStatus.PENDING -> Text("$number", style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

@Composable
private fun FailedStep(text: String, message: String?, index: Int, onRetry: (() -> Unit)?, onSkip: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 14.dp, bottom = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (index == 0) {
            Text(stringResource(R.string.cleaning_restore_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRetry != null || onSkip != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val retryCd = stringResource(R.string.cleaning_retry_cd, text)
                val skipCd = stringResource(R.string.cleaning_skip_cd, text)
                onRetry?.let {
                    FilledTonalButton(onClick = it, modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = retryCd }) {
                        Text(stringResource(R.string.cleaning_retry))
                    }
                }
                onSkip?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = skipCd }) {
                        Text(stringResource(R.string.cleaning_skip))
                    }
                }
            }
        }
    }
}

/** "Stop cleaning?": ends the run; what was already changed stays logged on the TV, so Undo can put it back. */
@Composable
private fun StopConfirmSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.stop_confirm_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            Text(stringResource(R.string.stop_confirm_body), style = MaterialTheme.typography.bodyLarge)
            VSpace(18)
            PrimaryButton(stringResource(R.string.stop_confirm_go), onClick = onConfirm)
            QuietButton(stringResource(R.string.stop_confirm_cancel), onClick = onDismiss)
        }
    }
}
