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

    @Test fun appsRemovedForTheUserAreThoseOnlyListedWithMinusU() {
        val adb = fake()
        // `-u` lists everything the normal list has, plus what was removed for the user.
        adb.responses["pm list packages -u"] = """
            package:mitv.service
            package:com.netflix.ninja
            package:com.example.removed.ads
            package:com.example.removed.extras
        """.trimIndent()
        val inv = read(adb)
        assertThat(inv.removedForUser).containsExactly("com.example.removed.ads", "com.example.removed.extras")
        assertThat(inv.packages.map { it.name }).containsNoneOf("com.example.removed.ads", "com.example.removed.extras")
    }

    @Test fun noAppsRemovedForTheUser() {
        assertThat(read(fake()).removedForUser).isEmpty()
        // Inventories saved before this field existed have no "removedForUser" key and still decode.
        val saved = Json.encodeToString(Inventory.serializer(), read(fake()))
        assertThat(saved).doesNotContain("removedForUser")
        val old = Json.decodeFromString(Inventory.serializer(), saved)
        assertThat(old.removedForUser).isEmpty()
    }

    @Test fun removedListThatReportsAnErrorFailsTheRead() {
        val adb = fake()
        adb.exitCodes["pm list packages -u"] = 1
        assertThrows(TvReadException::class.java) { read(adb) }
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

    private fun failsWith(what: String, setup: FakeAdbClient.() -> Unit) {
        val adb = fake().apply(setup)
        val e = assertThrows(TvReadException::class.java) { read(adb) }
        assertThat(e.what).isEqualTo(what)
    }

    @Test fun emptyOrFailedAppListThrows() {
        failsWith("app list") { responses["pm list packages -f -i -U"] = "" }
        failsWith("app list") { exitCodes["pm list packages -f -i -U"] = 1 }
        failsWith("app list") { responses["pm list packages -f -i -U"] = "Exception occurred while executing:\n..." }
    }

    @Test fun emptyOrFailedSystemListThrows() {
        failsWith("system app list") { responses["pm list packages -s"] = "" }
        failsWith("system app list") { exitCodes["pm list packages -s"] = 255 }
    }

    @Test fun disabledListEmptyIsValidButFailureThrows() {
        val adb = fake().apply { responses["pm list packages -d"] = "" }
        assertThat(read(adb).packages.all { it.enabled }).isTrue()
        failsWith("switched-off app list") { exitCodes["pm list packages -d"] = 1 }
    }

    @Test fun launchableQueryFailuresThrow() {
        failsWith("launchable apps") { exitCodes[leanback] = 1 }
        failsWith("launchable apps") { exitCodes[launcher] = 1 }
        failsWith("launchable apps") { responses[leanback] = ""; responses[launcher] = "" }
    }

    @Test fun launchableUnionOfOneQueryIsEnough() {
        val inv = read(fake().apply { responses[leanback] = "" })
        assertThat(inv.launchable).contains("com.netflix.ninja")
    }

    @Test fun homeQueryFailureThrows() {
        failsWith("home screen apps") { exitCodes[home] = 1 }
    }

    @Test fun unreadableMemoryOrStorageIsNullNotZero() {
        val adb = fake()
        adb.responses["dumpsys meminfo"] = "Can't find service: meminfo"
        adb.exitCodes["df /data"] = 1
        val inv = read(adb)
        assertThat(inv.memory).isNull()
        assertThat(inv.storage).isNull()
        assertThat(inv.packages).hasSize(96)
    }

    @Test fun connectionErrorsPropagateFromMemoryRead() {
        val adb = fake()
        adb.failNext("dumpsys meminfo", dev.mntl.exbloattv.adb.AdbError.Disconnected())
        assertThrows(dev.mntl.exbloattv.adb.AdbError.Disconnected::class.java) { read(adb) }
    }

    @Test fun cancellationIsNotSwallowed() {
        val adb = fake()
        adb.failNext("df /data", kotlinx.coroutines.CancellationException("cancelled"))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { read(adb) }
    }

    @Test fun animationSettingNullMeansOneButGarbageThrows() {
        // fake() already answers "null" for transition scale
        assertThat(read(fake()).animations.transition).isEqualTo(1.0f)
        failsWith("animation settings") { responses["settings get global window_animation_scale"] = "cmd: Can't find service: settings" }
        failsWith("animation settings") { responses["settings get global animator_duration_scale"] = "" }
        failsWith("animation settings") { exitCodes["settings get global window_animation_scale"] = 1 }
    }

    @Test fun inventoryRoundTripsThroughJson() {
        val inv = read(fake())
        val json = Json.encodeToString(Inventory.serializer(), inv)
        assertThat(Json.decodeFromString(Inventory.serializer(), json)).isEqualTo(inv)
    }
}
