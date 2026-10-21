package dev.xpdt.exbloattv.state

import dev.xpdt.exbloattv.adb.FakeAdbClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The command-line companion (cli/exbloattv) writes the same state file on the TV, so the app's Undo can reverse its
 * changes. These fixtures are written by the script itself (cli/test.sh keeps state-from-cli.json in step with it;
 * the emulator ones are from real runs). The app must load each one as is: never as "too new", never set aside.
 */
class CliStateFixtureTest {
    private fun fixture(name: String): String =
        listOf(File("../cli/test-fixtures/$name"), File("cli/test-fixtures/$name")).first { it.isFile }.readText()

    private fun load(name: String, serial: String): TvState {
        val fake = FakeAdbClient()
        fake.responses["cat ${TvStateStore.FILE}"] = fixture(name)
        val state = runBlocking { TvStateStore(fake) { 0L }.load(serial) }
        assertTrue("the file was set aside: ${fake.log}", fake.log.none { it.startsWith("mv ") })
        return state
    }

    @Test fun everyChangeKindTheScriptWritesLoads() {
        val s = load("state-from-cli.json", "CLI-FIXTURE-1")
        val me = "test-host (exbloattv CLI) · 0000"
        assertEquals(
            listOf(
                ChangeKind.DISABLE, ChangeKind.REMOVE_FOR_USER, ChangeKind.ENABLE, ChangeKind.INSTALL_EXISTING,
                ChangeKind.APPOPS, ChangeKind.ANIMATIONS, ChangeKind.UNINSTALL, ChangeKind.INSTALL_APK,
                ChangeKind.DISABLE, ChangeKind.LAUNCHER_SWAP,
            ),
            s.changes.map { it.kind },
        )
        assertTrue(s.changes.all { it.byPhone == me })
        val anim = s.changes.single { it.kind == ChangeKind.ANIMATIONS }
        assertEquals(null, anim.pkg)
        assertEquals("1.0,null,1.0", anim.from)
        assertEquals(false, anim.verified)
        assertEquals("RUN_ANY_IN_BACKGROUND=allow", s.changes.single { it.kind == ChangeKind.APPOPS }.from)
        assertEquals("swap-1700000007000-0000abcd:home", s.changes.last().id)
        assertEquals(Lock(me, 1_700_000_000_000), s.lock)
        assertNotNull(s.original)
        val rp = s.original!!
        assertEquals(rp, s.latest)
        assertEquals(listOf("com.netflix.ninja"), rp.enabled)
        assertEquals(listOf("com.google.android.feedback"), rp.disabled)
        assertEquals(listOf("com.netflix.ninja"), rp.userInstalled)
        assertEquals("com.google.android.tvlauncher/.MainActivity", rp.resolvedHome)
        assertEquals(1.0f, rp.animations.transition)
        assertEquals(mapOf("com.netflix.ninja" to "allow"), rp.backgroundOps)
    }

    @Test fun aRealEmulatorRunLoads() {
        val s = load("state-emulator-run.json", "EMULATOR36X5X11X0")
        assertEquals(listOf(ChangeKind.DISABLE, ChangeKind.DISABLE, ChangeKind.DISABLE, ChangeKind.ANIMATIONS), s.changes.map { it.kind })
        assertTrue(s.changes.all { it.verified })
        assertEquals(null, s.lock)
        assertTrue(s.original!!.enabled.size > 100)
    }

    @Test fun aRealEmulatorHomeScreenSwapLoadsInLauncherSwapsLayout() {
        val s = load("state-emulator-swap.json", "EMULATOR36X5X11X0")
        val swap = s.changes.filter { it.id.startsWith("swap-") }
        val id = swap.first().id.substringBefore(':')
        assertEquals(
            listOf("$id:install", "$id:disable:com.google.android.apps.nexuslauncher", "$id:home"),
            swap.map { it.id },
        )
        assertEquals(listOf(ChangeKind.INSTALL_APK, ChangeKind.DISABLE, ChangeKind.LAUNCHER_SWAP), swap.map { it.kind })
        assertTrue(swap.all { it.verified })
        assertEquals("com.google.android.apps.nexuslauncher", swap.last().from)
    }
}
