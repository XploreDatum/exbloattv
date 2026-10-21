package dev.mntl.exbloattv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TvRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(scope: CoroutineScope, model: String = "Pixel 9", file: File = File(tmp.root, "tvs.preferences_pb")) =
        TvRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, model)

    @Test fun aRememberedTvAtANewAddressIsMergedBySerial() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val r = repo(scope)
        r.remember("S1", "Mi Box 4", "192.168.10.3")
        r.markCleaned("S1", 42L)
        // Picked at a new address under another name; after connecting the serial says it's the same TV.
        r.remember("S1", "Xiaomi MIBOX4", "192.168.10.40")
        val tvs = r.tvs.first()
        assertEquals(listOf(RememberedTv("S1", "Mi Box 4", "192.168.10.40", 42L)), tvs)
        scope.cancel()
    }

    @Test fun phoneNameIsModelPlusAStableInstallId() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val r = repo(scope)
        val a = r.phoneName()
        assertTrue(a.matches(Regex("Pixel 9 · [0-9a-f]{4}")))
        assertEquals(a, r.phoneName()) // persisted, not regenerated
        val other = repo(scope, file = File(tmp.root, "other.preferences_pb")).phoneName()
        assertNotEquals(a, other) // another install of the same model is told apart (1 in 65536 clash)
        scope.cancel()
    }
}
