package dev.mntl.exbloattv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mntl.exbloattv.data.RememberedTv
import dev.mntl.exbloattv.discovery.FoundTv
import dev.mntl.exbloattv.discovery.TvReadiness
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One TV on the Find list. [rememberedSerial] is set when it is a TV this phone has worked with; [movedFrom] is its
 * old address when it was recognised by serial at a new one.
 */
data class FindRow(
    val host: String,
    val name: String?,
    val readiness: TvReadiness,
    val rememberedSerial: String? = null,
    val movedFrom: String? = null,
)

data class FindUiState(
    val wifi: Boolean = true,
    val scanning: Boolean = false,
    val rows: List<FindRow> = emptyList(),
    /** The scan finished with nothing: explain guest networks and offer the address. */
    val nothingFound: Boolean = false,
)

/**
 * "Find your TV": scans the Wi-Fi, shows TVs as they answer, and recognises remembered TVs by serial. A remembered
 * TV not found at its last address is looked for among the ready TVs ([serialAt] asks a TV for its serial, which
 * only works without a prompt on TVs that already allowed this phone); when found, [onMoved] updates its address.
 */
class FindViewModel(
    private val remembered: Flow<List<RememberedTv>>,
    private val wifiAvailable: () -> Boolean,
    private val scan: () -> Flow<FoundTv>,
    private val serialAt: suspend (host: String) -> String?,
    private val onMoved: suspend (serial: String, host: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(FindUiState())
    val state: StateFlow<FindUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        rescan()
    }

    fun rescan() {
        job?.cancel()
        if (!wifiAvailable()) {
            _state.value = FindUiState(wifi = false)
            return
        }
        _state.value = FindUiState(scanning = true)
        job = viewModelScope.launch {
            val known = remembered.first()
            val found = linkedMapOf<String, FoundTv>()
            scan().collect { tv ->
                found[tv.host] = tv
                publish(found, known, emptyMap(), scanning = true)
            }
            val moved = findMoved(found, known)
            publish(found, known, moved, scanning = false)
            for ((serial, host) in moved) onMoved(serial, host)
        }
    }

    /** Ready TVs at unknown addresses are asked for their serial, until every missing remembered TV is placed. */
    private suspend fun findMoved(found: Map<String, FoundTv>, known: List<RememberedTv>): Map<String, String> {
        val missing = known.filter { it.lastHost !in found }.associateBy { it.serial }.toMutableMap()
        if (missing.isEmpty()) return emptyMap()
        val knownHosts = known.map { it.lastHost }.toSet()
        val moved = linkedMapOf<String, String>()
        for (tv in found.values) {
            if (missing.isEmpty()) break
            if (tv.readiness != TvReadiness.READY || tv.host in knownHosts) continue
            val serial = try {
                serialAt(tv.host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            if (missing.remove(serial) != null) moved[serial] = tv.host
        }
        return moved
    }

    private fun publish(found: Map<String, FoundTv>, known: List<RememberedTv>, moved: Map<String, String>, scanning: Boolean) {
        val byHost = known.associateBy { it.lastHost }
        val bySerial = known.associateBy { it.serial }
        val movedTo = moved.entries.associate { (serial, host) -> host to bySerial.getValue(serial) }
        val rows = found.values.map { tv ->
            val atOld = byHost[tv.host]?.takeIf { movedTo[tv.host] == null }
            val atNew = movedTo[tv.host]
            val rem = atNew ?: atOld
            FindRow(
                host = tv.host,
                name = rem?.name ?: tv.name,
                readiness = tv.readiness,
                rememberedSerial = rem?.serial,
                movedFrom = atNew?.lastHost,
            )
        }.sortedWith(compareBy<FindRow> { it.rememberedSerial == null }.thenBy { it.readiness != TvReadiness.READY })
        _state.update { it.copy(scanning = scanning, rows = rows, nothingFound = !scanning && rows.isEmpty()) }
    }
}
