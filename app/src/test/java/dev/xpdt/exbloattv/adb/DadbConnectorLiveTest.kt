package dev.xpdt.exbloattv.adb

import com.google.common.truth.Truth.assertThat
import dadb.AdbKeyPair
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opt-in, read-only check against a real TV. Skipped unless EXBLOAT_LIVE_TV=<host> is set.
 * Uses the Mac's already-authorized key (~/.android/adbkey, read only) so no approval prompt appears.
 * Never runs anything that changes the TV.
 */
class DadbConnectorLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun readOnlySessionAgainstRealTv() = runBlocking {
        val host = System.getenv("EXBLOAT_LIVE_TV")
        assumeTrue("EXBLOAT_LIVE_TV not set", !host.isNullOrBlank())
        val keyDir = File(System.getProperty("user.home"), ".android")
        val connector = DadbConnector({ AdbKeyPair.read(File(keyDir, "adbkey"), File(keyDir, "adbkey.pub")) })

        val client = connector.connect(host!!, 5555, 15_000)
        val model = client.shell("getprop ro.product.model")
        assertThat(model.exitCode).isEqualTo(0)
        assertThat(model.output.trim()).isEqualTo("MIBOX4")

        assertThat(client.shell("pm list packages -d").output).contains("package:com.google.android.tvlauncher")

        val out = File(tmp.root, "hosts")
        client.pull("/system/etc/hosts", out)
        assertThat(out.length()).isGreaterThan(0L)

        client.close()
        val err = runCatching { client.shell("getprop ro.product.model") }.exceptionOrNull()
        assertThat(err).isInstanceOf(AdbError::class.java)
    }
}
