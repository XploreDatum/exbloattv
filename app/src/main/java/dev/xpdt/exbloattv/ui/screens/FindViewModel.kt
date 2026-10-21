package dev.xpdt.exbloattv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.xpdt.exbloattv.data.RememberedTv
import dev.xpdt.exbloattv.discovery.FoundTv
import dev.xpdt.exbloattv.discovery.TvReadiness
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One TV on the Find list. [rememberedSerial] is set when it looks like a TV this phone has worked with; [movedFrom]
 * is its old address when it was recognised by name at a new one.
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
 * "Find your TV": scans the Wi-Fi and shows TVs as they answer. It never connects to a TV just to identify it (on a
 * TV that never allowed this phone that would pop "Allow USB debugging?"). A remembered TV is recognised by its last
 * address, or by the name it announces on the network matching the remembered name; anything else shows as new.
 * The real match is by serial once the person picks a TV and it connects ([dev.xpdt.exbloattv.data.TvSession.load]
 * then updates that record's address).
 */
class FindViewModel(
    private val remembered: Flow<List<RememberedTv>>,
    private val wifiAvailable: () -> Boolean,
    private val scan: () -> Flow<FoundTv>,
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
                publish(found, known, scanning = true)
            }
            publish(found, known, scanning = false)
        }
    }

    private fun publish(found: Map<String, FoundTv>, known: List<RememberedTv>, scanning: Boolean) {
        val rows = match(found.values.toList(), known)
            .sortedWith(compareBy<FindRow> { it.rememberedSerial == null }.thenBy { it.readiness != TvReadiness.READY })
        _state.update { it.copy(scanning = scanning, rows = rows, nothingFound = !scanning && rows.isEmpty()) }
    }

    companion object {
        /** Same address first; then a remembered TV not at its address whose name the found TV announces. */
        fun match(found: List<FoundTv>, known: List<RememberedTv>): List<FindRow> {
            val byHost = known.associateBy { it.lastHost }
            val foundHosts = found.map { it.host }.toSet()
            val unplaced = known.filter { it.lastHost !in foundHosts }.toMutableList()
            return found.map { tv ->
                val atHost = byHost[tv.host]
                val byName = if (atHost == null) {
                    unplaced.firstOrNull { tv.name != null && it.name.trim().equals(tv.name.trim(), ignoreCase = true) }
                        ?.also { unplaced.remove(it) }
                } else null
                val rem = atHost ?: byName
                FindRow(
                    host = tv.host,
                    name = rem?.name ?: tv.name,
                    readiness = tv.readiness,
                    rememberedSerial = rem?.serial,
                    movedFrom = byName?.lastHost,
                )
            }
        }
    }
}
