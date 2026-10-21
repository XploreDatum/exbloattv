package dev.xpdt.exbloattv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.adb.AdbClient
import dev.xpdt.exbloattv.adb.AdbError
import dev.xpdt.exbloattv.data.LoadedTv
import dev.xpdt.exbloattv.ui.components.CookieBadge
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.QuietButton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ApproveState {
    /** Connecting; the TV may be showing "Allow USB debugging?". */
    data object Waiting : ApproveState
    data object Reading : ApproveState
    data class Loaded(val serial: String) : ApproveState
    /** Debugging is off (connection refused): back to the steps. */
    data object NeedsSetup : ApproveState
    /** Nobody pressed OK within 60 s. */
    data object TimedOut : ApproveState
    data object Unreachable : ApproveState
    data object ReadFailed : ApproveState
}

/**
 * "Allow on the TV": connects and waits up to [windowMs] for the person to press OK on the TV. A not-yet-approved
 * answer keeps waiting, a refusal means debugging is off, and the time running out offers Retry. Once in, the TV is
 * read ([load], which then owns the connection).
 */
class ApproveViewModel(
    private val host: String,
    private val connect: suspend (host: String, authTimeoutMs: Long) -> AdbClient,
    private val load: suspend (host: String, client: AdbClient) -> LoadedTv,
    private val windowMs: Long = 60_000,
) : ViewModel() {
    private val _state = MutableStateFlow<ApproveState>(ApproveState.Waiting)
    val state: StateFlow<ApproveState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        retry()
    }

    fun retry() {
        job?.cancel()
        _state.value = ApproveState.Waiting
        job = viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            var client: AdbClient? = null
            while (client == null) {
                val left = windowMs - started.elapsedNow().inWholeMilliseconds
                if (left <= 0) {
                    _state.value = ApproveState.TimedOut
                    return@launch
                }
                try {
                    client = connect(host, left)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AdbError.Unauthorized) {
                    delay(RETRY_MS) // still waiting for OK on the TV
                } catch (e: AdbError.Refused) {
                    _state.value = ApproveState.NeedsSetup
                    return@launch
                } catch (e: AdbError.Timeout) {
                    // The last try only gets what is left of the window; running out then is the window ending.
                    val over = started.elapsedNow().inWholeMilliseconds >= windowMs
                    _state.value = if (over) ApproveState.TimedOut else ApproveState.Unreachable
                    return@launch
                } catch (e: AdbError) {
                    _state.value = ApproveState.Unreachable
                    return@launch
                } catch (e: Exception) {
                    _state.value = ApproveState.Unreachable
                    return@launch
                }
            }
            _state.value = ApproveState.Reading
            try {
                _state.value = ApproveState.Loaded(load(host, client).serial)
            } catch (e: CancellationException) {
                runCatching { client.close() }
                throw e
            } catch (e: AdbError) {
                runCatching { client.close() }
                _state.value = ApproveState.Unreachable
            } catch (e: Exception) {
                runCatching { client.close() }
                _state.value = ApproveState.ReadFailed
            }
        }
    }

    private companion object {
        const val RETRY_MS = 1_000L
    }
}

@Composable
fun ApproveScreen(vm: ApproveViewModel, onBack: () -> Unit, onNeedsSetup: () -> Unit, onLoaded: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        when (val s = state) {
            is ApproveState.Loaded -> onLoaded(s.serial)
            ApproveState.NeedsSetup -> onNeedsSetup()
            else -> Unit
        }
    }
    val failed = state is ApproveState.TimedOut || state is ApproveState.Unreachable || state is ApproveState.ReadFailed
    ExScreen(
        onBack = onBack,
        actions = if (failed) {
            {
                PrimaryButton(stringResource(R.string.try_again), onClick = vm::retry)
                QuietButton(stringResource(R.string.approve_show_steps), onClick = onNeedsSetup)
            }
        } else null,
    ) {
        when (state) {
            ApproveState.Reading, is ApproveState.Loaded -> {
                ScreenTitle(stringResource(R.string.approve_reading_title))
                Supporting(stringResource(R.string.approve_reading))
                Hero(Icons.Rounded.Apps)
                Busy(stringResource(R.string.keep_loading))
            }
            ApproveState.TimedOut -> {
                ScreenTitle(stringResource(R.string.approve_timeout_title))
                Supporting(stringResource(R.string.approve_timeout_body))
                Supporting(stringResource(R.string.approve_two_phones))
            }
            ApproveState.Unreachable -> {
                ScreenTitle(stringResource(R.string.approve_timeout_title))
                Supporting(stringResource(R.string.approve_unreachable_body))
            }
            ApproveState.ReadFailed -> {
                ScreenTitle(stringResource(R.string.approve_read_failed_title))
                Supporting(stringResource(R.string.approve_read_failed_body))
            }
            ApproveState.Waiting, ApproveState.NeedsSetup -> {
                ScreenTitle(stringResource(R.string.approve_title))
                Supporting(stringResource(R.string.approve_subtitle))
                Hero(Icons.Rounded.Tv)
                Busy(stringResource(R.string.approve_waiting))
                Supporting(stringResource(R.string.approve_two_phones))
            }
        }
    }
}

@Composable
private fun Hero(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CookieBadge(MaterialShapes.Cookie9Sided, icon, 120.dp)
    }
}

@Composable
fun Busy(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
