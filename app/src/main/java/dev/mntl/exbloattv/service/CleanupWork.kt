package dev.mntl.exbloattv.service

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbConnector
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.cleanup.CleanupRunner
import dev.mntl.exbloattv.cleanup.LauncherSwap
import dev.mntl.exbloattv.cleanup.RunProgress
import dev.mntl.exbloattv.cleanup.Step
import dev.mntl.exbloattv.cleanup.StepStatus
import dev.mntl.exbloattv.cleanup.Undo
import dev.mntl.exbloattv.state.Change
import dev.mntl.exbloattv.state.TvLockedException
import dev.mntl.exbloattv.state.TvStateStore
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.TvInspector
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CleanupRequest(
    val serial: String,
    val host: String,
    val tvName: String,
    val plan: List<Step>,
    val before: Inventory,
    /** Package -> friendly name, for the checklist and the runner's messages. */
    val appNames: Map<String, String>,
)

data class UndoRequest(val serial: String, val host: String, val tvName: String)

/**
 * The runner stops for good when the TV's lock or change log changed under it (another phone worked on the TV). Its
 * step message starts with this text; the Cleaning screen then offers "Check the TV again".
 */
internal const val CHANGED_ELSEWHERE_PREFIX = "This TV was changed from another phone"

fun isChangedElsewhere(message: String?): Boolean = message?.startsWith(CHANGED_ELSEWHERE_PREFIX) == true

enum class UndoProblem { LOCKED, NOT_ALLOWED, UNREACHABLE, UNEXPECTED }

/** What the TV work is doing right now. Exactly one cleanup or undo runs at a time. */
sealed interface WorkState {
    data object Idle : WorkState

    data class Cleaning(
        val serial: String,
        val tvName: String,
        val progress: RunProgress?,
        val appNames: Map<String, String>,
        /** Seconds left of the settle wait after the restart, before the "after" numbers are read. */
        val settleSecondsLeft: Int? = null,
        val measuring: Boolean = false,
    ) : WorkState

    data class Cleaned(val serial: String, val tvName: String, val host: String, val summary: DoneSummary, val appNames: Map<String, String>) : WorkState

    /** The run ended without finishing (unexpected error or the system stopped it). [progress] is the last checklist. */
    data class CleanupStopped(val serial: String, val tvName: String, val progress: RunProgress?, val appNames: Map<String, String>) : WorkState

    data class Undoing(val serial: String, val tvName: String) : WorkState

    data class Undone(val serial: String, val tvName: String, val outcome: UndoOutcome) : WorkState

    data class UndoFailed(val serial: String, val tvName: String, val problem: UndoProblem, val lockedBy: String? = null) : WorkState

    val busy: Boolean get() = this is Cleaning || this is Undoing
    val tvSerial: String?
        get() = when (this) {
            Idle -> null
            is Cleaning -> serial
            is Cleaned -> serial
            is CleanupStopped -> serial
            is Undoing -> serial
            is Undone -> serial
            is UndoFailed -> serial
        }
}

/**
 * Process-scoped owner of cleanup and undo runs. Screens start work here and observe [state]; the work itself is
 * executed by [CleanupService] (a foreground service, so locking the phone doesn't stop it), which takes it with
 * [claimPending] and calls [execute]. If the service can't be started the work runs in [appScope] instead.
 */
class CleanupWork(
    private val connector: AdbConnector,
    private val phoneName: suspend () -> String,
    private val swapFor: (TvStateStore, String) -> LauncherSwap,
    private val appScope: CoroutineScope,
    private val onCleaned: suspend (serial: String, at: Long) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val settleSeconds: Int = 60,
) {
    sealed interface Job {
        data class Clean(val request: CleanupRequest) : Job
        data class UndoAll(val request: UndoRequest) : Job
    }

    /** Starts [CleanupService]; false if it could not be started. Set by the application. */
    @Volatile var startService: () -> Boolean = { false }

    private val _state = MutableStateFlow<WorkState>(WorkState.Idle)
    val state: StateFlow<WorkState> = _state.asStateFlow()

    private val pending = AtomicReference<Job?>(null)
    @Volatile private var runner: CleanupRunner? = null
    @Volatile private var running: kotlinx.coroutines.Job? = null
    @Volatile private var abandoned = false

    /** False if other work is still running. */
    fun startCleanup(request: CleanupRequest): Boolean =
        start(Job.Clean(request), WorkState.Cleaning(request.serial, request.tvName, null, request.appNames))

    fun startUndo(request: UndoRequest): Boolean = start(Job.UndoAll(request), WorkState.Undoing(request.serial, request.tvName))

    @Synchronized
    private fun start(job: Job, initial: WorkState): Boolean {
        if (_state.value.busy || !pending.compareAndSet(null, job)) return false
        _state.value = initial
        if (!startService()) {
            // No foreground service (very unlikely from a visible screen): run anyway, without the lock-screen guarantee.
            pending.getAndSet(null)?.let { appScope.launch { execute(it) } }
        }
        return true
    }

    fun claimPending(): Job? = pending.getAndSet(null)

    suspend fun retry(index: Int) = runner?.retry(index)

    suspend fun skip(index: Int) = runner?.skip(index)

    /** Skips every failed step except the restore point, which nothing may run without. */
    suspend fun finishAnyway() {
        val cleaning = _state.value as? WorkState.Cleaning ?: return
        val steps = cleaning.progress?.steps ?: return
        steps.forEachIndexed { i, s -> if (i != 0 && s.status == StepStatus.FAILED) runner?.skip(i) }
    }

    /** Retries every failed step (the "Can't reach the TV" banner). */
    suspend fun retryAll() {
        val steps = (_state.value as? WorkState.Cleaning)?.progress?.steps ?: return
        steps.forEachIndexed { i, s -> if (s.status == StepStatus.FAILED) runner?.retry(i) }
    }

    /** The person has seen the result; forget it. */
    fun acknowledge(serial: String) {
        val s = _state.value
        if (!s.busy && s.tvSerial == serial) _state.compareAndSet(s, WorkState.Idle)
    }

    /**
     * Ends the current cleanup without a result (the runner releases the TV lock as it is cancelled), e.g. when the
     * TV was changed from another phone and must be read again. The state goes back to [WorkState.Idle].
     */
    suspend fun abandonCleanup() {
        if (_state.value !is WorkState.Cleaning) return
        abandoned = true
        running?.let {
            it.cancel()
            it.join()
        }
        _state.value = WorkState.Idle
    }

    suspend fun execute(job: Job) {
        running = currentCoroutineContext()[kotlinx.coroutines.Job]
        abandoned = false
        when (job) {
            is Job.Clean -> clean(job.request)
            is Job.UndoAll -> undo(job.request)
        }
    }

    private suspend fun clean(req: CleanupRequest) {
        val phone = phoneName()
        val since = now()
        val r = CleanupRunner(
            connect = { connector.connect(req.host) },
            storeFor = { TvStateStore(it) },
            swapFor = { store -> swapFor(store, phone) },
            phone = phone,
            serial = req.serial,
            appNames = req.appNames,
        )
        var last: RunProgress? = null
        var cleaning = WorkState.Cleaning(req.serial, req.tvName, null, req.appNames)
        runner = r
        try {
            r.run(req.plan, req.before).collect {
                last = it
                cleaning = cleaning.copy(progress = it)
                _state.value = cleaning
            }
            runner = null
            val restarted = last?.steps?.any { it.step == Step.RebootAndVerify && it.status == StepStatus.DONE } == true
            if (restarted) {
                for (s in settleSeconds downTo 1) {
                    _state.value = cleaning.copy(settleSecondsLeft = s)
                    delay(1_000)
                }
            }
            _state.value = cleaning.copy(measuring = true)
            val (after, changes) = measure(req)
            val summary = DoneSummary.compute(req.before, after, changes, phone, since, last?.steps.orEmpty())
            onCleaned(req.serial, now())
            _state.value = WorkState.Cleaned(req.serial, req.tvName, req.host, summary, req.appNames)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                _state.value = if (abandoned) WorkState.Idle else WorkState.CleanupStopped(req.serial, req.tvName, last, req.appNames)
            }
            throw e
        } catch (e: Exception) {
            _state.value = WorkState.CleanupStopped(req.serial, req.tvName, last, req.appNames)
        } finally {
            runner = null
        }
    }

    /** The "after" inventory and the change log; either is null if the TV can't be read after a few tries. */
    private suspend fun measure(req: CleanupRequest): Pair<Inventory?, List<Change>?> {
        repeat(MEASURE_ATTEMPTS) { attempt ->
            var adb: AdbClient? = null
            try {
                adb = connector.connect(req.host)
                val after = try { TvInspector(adb).read() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                val changes = try { TvStateStore(adb).load(req.serial).changes } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (after != null || changes != null || attempt == MEASURE_ATTEMPTS - 1) return after to changes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Try again below.
            } finally {
                adb?.let { runCatching { it.close() } }
            }
            delay(MEASURE_BACKOFF_MS)
        }
        return null to null
    }

    private suspend fun undo(req: UndoRequest) {
        _state.value = WorkState.Undoing(req.serial, req.tvName)
        var adb: AdbClient? = null
        try {
            val phone = phoneName()
            adb = connector.connect(req.host)
            val report = Undo(TvStateStore(adb), phone).undoAll(adb, req.serial)
            _state.value = WorkState.Undone(req.serial, req.tvName, report.toOutcome())
        } catch (e: CancellationException) {
            withContext(NonCancellable) { _state.value = WorkState.UndoFailed(req.serial, req.tvName, UndoProblem.UNEXPECTED) }
            throw e
        } catch (e: TvLockedException) {
            _state.value = WorkState.UndoFailed(req.serial, req.tvName, UndoProblem.LOCKED, e.byPhone)
        } catch (e: AdbError.Unauthorized) {
            _state.value = WorkState.UndoFailed(req.serial, req.tvName, UndoProblem.NOT_ALLOWED)
        } catch (e: AdbError) {
            _state.value = WorkState.UndoFailed(req.serial, req.tvName, UndoProblem.UNREACHABLE)
        } catch (e: Exception) {
            _state.value = WorkState.UndoFailed(req.serial, req.tvName, UndoProblem.UNEXPECTED)
        } finally {
            adb?.let { runCatching { it.close() } }
        }
    }

    /** Opens a deleted app's Play Store page on the TV. Quick, so it runs here rather than in the service. */
    suspend fun openInPlayStore(host: String, pkg: String): Boolean {
        var adb: AdbClient? = null
        return try {
            adb = connector.connect(host, authTimeoutMs = PLAY_STORE_AUTH_MS)
            Undo(TvStateStore(adb), phoneName()).openInPlayStore(adb, pkg)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            adb?.let { runCatching { it.close() } }
        }
    }

    private companion object {
        const val MEASURE_ATTEMPTS = 3
        const val MEASURE_BACKOFF_MS = 5_000L
        const val PLAY_STORE_AUTH_MS = 15_000L
    }
}
