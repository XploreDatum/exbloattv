package dev.xpdt.exbloattv.discovery

/**
 * Folds a newly seen [incoming] TV into what was already known for that host.
 * Returns the record to emit, or null when nothing changed. READY is never downgraded; a later
 * READY upgrades NEEDS_SETUP; a name is kept once known.
 */
fun mergeFound(old: FoundTv?, incoming: FoundTv): FoundTv? {
    val merged = when {
        old == null -> incoming
        old.readiness == TvReadiness.READY || incoming.readiness == TvReadiness.NEEDS_SETUP ->
            old.copy(name = old.name ?: incoming.name)
        else -> incoming.copy(name = incoming.name ?: old.name)
    }
    return merged.takeIf { it != old }
}

/**
 * An mDNS hit only says "a TV is here". On Android 11+ `_adb._tcp` is wireless debugging (TLS, random
 * port), not the classic port, so a TV is READY only if 5555 actually answers.
 */
suspend fun classifyMdnsHit(host: String, name: String?, probe: PortProbe): FoundTv {
    val ready = probe.isOpen(host, SubnetScanner.ADB_PORT, SubnetScanner.TIMEOUT_MS)
    return FoundTv(host, if (ready) TvReadiness.READY else TvReadiness.NEEDS_SETUP, name)
}
