package dev.xpdt.exbloattv.tv

import com.google.common.truth.Truth.assertThat
import dadb.AdbKeyPair
import dev.xpdt.exbloattv.adb.DadbConnector
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, read-only: skipped unless EXBLOAT_LIVE_TV=<host>. Reads the Mac's adb key, never writes it. */
class TvInspectorLiveTest {
    @Test fun inspectsRealTv() = runBlocking {
        val host = System.getenv("EXBLOAT_LIVE_TV")
        assumeTrue("EXBLOAT_LIVE_TV not set", !host.isNullOrBlank())
        val keyDir = File(System.getProperty("user.home"), ".android")
        val connector = DadbConnector({ AdbKeyPair.read(File(keyDir, "adbkey"), File(keyDir, "adbkey.pub")) })
        val client = connector.connect(host!!, 5555, 15_000)
        try {
            val inv = TvInspector(client).read()
            assertThat(inv.info.model).isEqualTo("MIBOX4")
            assertThat(inv.packages.size).isAtLeast(90)
            assertThat(inv.resolvedHome).startsWith("me.efesser.flauncher")
        } finally {
            client.close()
        }
    }
}
