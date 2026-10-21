package dev.mntl.exbloattv.tv

import com.google.common.truth.Truth.assertThat
import dev.mntl.exbloattv.adb.FakeAdbClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class TvInspectorTest {
    private val leanback = "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
    private val launcher = "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    private val home = "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"

    private fun fake(getprop: String = Fixtures.text("getprop.txt")) = FakeAdbClient().apply {
        responses["getprop"] = getprop
        responses["pm list packages -f -i -U"] = Fixtures.text("pm_list_all.txt")
        responses["pm list packages -s"] = Fixtures.text("pm_list_system.txt")
        responses["pm list packages -d"] = Fixtures.text("pm_list_disabled.txt")
        responses[leanback] = Fixtures.text("query_leanback.txt")
        responses[launcher] = Fixtures.text("query_launcher.txt")
        responses[home] = Fixtures.text("query_home.txt")
        responses["cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"] =
            Fixtures.text("resolve_home.txt")
        responses["dumpsys meminfo"] = Fixtures.text("dumpsys_meminfo.txt")
        responses["df /data"] = Fixtures.text("df_data.txt")
        responses["settings get global window_animation_scale"] = "0.5\n"
        responses["settings get global transition_animation_scale"] = "null\n"
        responses["settings get global animator_duration_scale"] = "1.0\n"
    }

    private fun read(adb: FakeAdbClient) = runBlocking { TvInspector(adb).read() }

    @Test fun readsInventoryFromFixtures() {
        val inv = read(fake())
        assertThat(inv.packages).hasSize(96)
        assertThat(inv.packages.single { it.name == "mitv.service" }.isSystem).isTrue()
        assertThat(inv.packages.single { it.name == "com.google.android.tvlauncher" }.enabled).isFalse()
        assertThat(inv.packages.single { it.name == "me.efesser.flauncher" }.isSystem).isFalse()
        assertThat(inv.info.model).isEqualTo("MIBOX4")
        assertThat(inv.info.sdkInt).isEqualTo(28)
        assertThat(inv.resolvedHome).isEqualTo("me.efesser.flauncher/.MainActivity")
        assertThat(inv.homePackages).contains("me.efesser.flauncher")
        assertThat(inv.memory).isEqualTo(MemoryStats(2034840, 1287694, 475775))
        assertThat(inv.storage).isEqualTo(StorageStats(5292456, 3052996, 2190308))
        assertThat(inv.animations).isEqualTo(AnimationScales(0.5f, 1.0f, 1.0f))
        assertThat(inv.launchable).containsAtLeast("me.efesser.flauncher", "com.netflix.ninja")
    }

    @Test fun serialFallsBackToBootSerialThenModel() {
        val base = Fixtures.text("getprop.txt")
        assertThat(read(fake()).info.serial).isEqualTo("unknown-MIBOX4")
        assertThat(read(fake(base + "\n[ro.boot.serialno]: [ABC123]\n")).info.serial).isEqualTo("ABC123")
        assertThat(read(fake(base + "\n[ro.serialno]: [XYZ]\n[ro.boot.serialno]: [ABC123]\n")).info.serial).isEqualTo("XYZ")
    }

    @Test fun appOpsAskedOnlyForLaunchableUserPackages() {
        val adb = fake()
        val inv = read(adb)
        val askedPkgs = adb.log.filter { it.startsWith("cmd appops get ") }.map { it.split(" ")[3] }
        val systemNames = inv.packages.filter { it.isSystem }.map { it.name }.toSet()
        assertThat(askedPkgs).isNotEmpty()
        assertThat(askedPkgs.toSet()).containsNoneIn(systemNames)
        assertThat(inv.launchable).containsAtLeastElementsIn(askedPkgs)
        assertThat(askedPkgs).containsNoDuplicates()
    }

    @Test fun backgroundOpsRecordsAnswers() {
        val adb = fake()
        adb.responses["cmd appops get me.efesser.flauncher RUN_ANY_IN_BACKGROUND"] = Fixtures.text("appops_netflix.txt")
        val inv = read(adb)
        assertThat(inv.backgroundOps["me.efesser.flauncher"]).isEqualTo("ignore")
    }

    @Test fun emptyPackageListIsAnErrorNotAnEmptyTv() {
        val adb = fake()
        adb.responses["pm list packages -f -i -U"] = ""
        assertThrows(IllegalStateException::class.java) { read(adb) }
    }

    @Test fun unreadableMemoryOrStorageDoesNotFailInventory() {
        val adb = fake()
        adb.responses["dumpsys meminfo"] = "Can't find service: meminfo"
        adb.responses["df /data"] = ""
        val inv = read(adb)
        assertThat(inv.memory).isEqualTo(MemoryStats(0, 0, 0))
        assertThat(inv.storage).isEqualTo(StorageStats(0, 0, 0))
    }

    @Test fun inventoryRoundTripsThroughJson() {
        val inv = read(fake())
        val json = Json.encodeToString(Inventory.serializer(), inv)
        assertThat(Json.decodeFromString(Inventory.serializer(), json)).isEqualTo(inv)
    }
}
