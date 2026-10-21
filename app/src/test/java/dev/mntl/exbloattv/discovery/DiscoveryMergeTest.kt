package dev.mntl.exbloattv.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiscoveryMergeTest {
    @Test fun mdnsHitWithoutPort5555IsNeedsSetup() = runTest {
        val tv = classifyMdnsHit("10.0.0.5", "Living room") { _, _, _ -> false }
        assertThat(tv).isEqualTo(FoundTv("10.0.0.5", TvReadiness.NEEDS_SETUP, "Living room"))
    }

    @Test fun mdnsHitWithPort5555IsReady() = runTest {
        val tv = classifyMdnsHit("10.0.0.5", null) { _, port, _ -> port == 5555 }
        assertThat(tv.readiness).isEqualTo(TvReadiness.READY)
    }

    @Test fun mergeNewHostEmits() {
        val tv = FoundTv("h", TvReadiness.NEEDS_SETUP)
        assertThat(mergeFound(null, tv)).isEqualTo(tv)
    }

    @Test fun readyIsNeverDowngraded() {
        val old = FoundTv("h", TvReadiness.READY)
        assertThat(mergeFound(old, FoundTv("h", TvReadiness.NEEDS_SETUP))).isNull()
    }

    @Test fun setupUpgradesToReadyKeepingName() {
        val old = FoundTv("h", TvReadiness.NEEDS_SETUP, "Den")
        assertThat(mergeFound(old, FoundTv("h", TvReadiness.READY))).isEqualTo(FoundTv("h", TvReadiness.READY, "Den"))
    }

    @Test fun newNameIsFilledIn() {
        val old = FoundTv("h", TvReadiness.READY)
        assertThat(mergeFound(old, FoundTv("h", TvReadiness.NEEDS_SETUP, "Den"))).isEqualTo(FoundTv("h", TvReadiness.READY, "Den"))
    }

    @Test fun duplicateIsDropped() {
        val tv = FoundTv("h", TvReadiness.READY, "Den")
        assertThat(mergeFound(tv, tv)).isNull()
    }
}
