package dev.mntl.exbloattv.tv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParsersTest {
    private fun fx(name: String) = Fixtures.text(name)

    @Test fun pmListLinesParsesAllPackagesWithInstallerAndUid() {
        val rows = Parsers.pmListLines(fx("pm_list_all.txt"))
        assertThat(rows).hasSize(96)
        val flauncher = rows.single { it.first == "me.efesser.flauncher" }
        assertThat(flauncher.second)
            .isEqualTo("/data/app/me.efesser.flauncher-tblkRbh5KgNCj6fD9LrAxQ==/base.apk")
        assertThat(flauncher.third).isEqualTo(null to 10091)
    }

    @Test fun pmListLinesKeepsRealInstallerNames() {
        val rows = Parsers.pmListLines(fx("pm_list_all.txt"))
        assertThat(rows.map { it.third.first }.filterNotNull()).isNotEmpty()
    }

    @Test fun pmListLinesAcceptsLinesWithoutInstallerOrUid() {
        val rows = Parsers.pmListLines(fx("pm_list_f_before_cleanup.txt"))
        assertThat(rows).hasSize(108)
        val analytics = rows.single { it.first == "com.miui.tv.analytics" }
        assertThat(analytics.third).isEqualTo(null to -1)
    }

    @Test fun pmListLinesSplitsAtLastApkEquals() {
        val rows = Parsers.pmListLines("package:/data/app/a.apk=b/base.apk=com.x.y  installer=com.android.vending uid:10200\n\nnoise")
        assertThat(rows).containsExactly(
            Triple("com.x.y", "/data/app/a.apk=b/base.apk", "com.android.vending" to 10200),
        )
    }

    @Test fun packageNamesParsesDisabledList() {
        val names = Parsers.packageNames(fx("pm_list_disabled.txt"))
        assertThat(names).hasSize(27)
        assertThat(names).containsAtLeast("com.google.android.tvlauncher", "com.google.android.tungsten.setupwraith")
    }

    @Test fun queryActivitiesPackagesReadsHomeCandidates() {
        val pkgs = Parsers.queryActivitiesPackages(fx("query_home.txt"))
        assertThat(pkgs.first()).isEqualTo("me.efesser.flauncher")
        assertThat(pkgs).contains("com.android.tv.settings")
        assertThat(pkgs).containsNoDuplicates()
    }

    @Test fun queryActivitiesPackagesLauncherFixture() {
        val pkgs = Parsers.queryActivitiesPackages(fx("query_launcher.txt"))
        assertThat(pkgs).hasSize(13)
    }

    @Test fun resolveActivityBrief() {
        assertThat(Parsers.resolveActivityBrief(fx("resolve_home.txt"))).isEqualTo("me.efesser.flauncher/.MainActivity")
        assertThat(Parsers.resolveActivityBrief("")).isNull()
        assertThat(Parsers.resolveActivityBrief("No activity found")).isNull()
    }

    @Test fun meminfo() {
        assertThat(Parsers.meminfo(fx("dumpsys_meminfo.txt"))).isEqualTo(MemoryStats(2034840, 1287694, 475775))
        assertThat(Parsers.meminfo(fx("dumpsys_meminfo_before_cleanup.txt")).usedPssKb).isEqualTo(943414)
    }

    @Test fun df() {
        assertThat(Parsers.df(fx("df_data.txt"))).isEqualTo(StorageStats(5292456, 3052996, 2190308))
    }

    @Test fun getprop() {
        val props = Parsers.getprop(fx("getprop.txt"))
        assertThat(props["ro.product.model"]).isEqualTo("MIBOX4")
        assertThat(props["ro.build.version.sdk"]).isEqualTo("28")
    }

    @Test fun resumedPackage() {
        assertThat(Parsers.resumedPackage(fx("resumed_activity.txt"))).isEqualTo("me.efesser.flauncher")
        assertThat(Parsers.resumedPackage("nothing")).isNull()
    }

    @Test fun resumedPackageHandlesAndroid10To14Forms() {
        val rec = "ActivityRecord{1a2b3c4 u0 com.netflix.ninja/.MainActivity t42}"
        val lines = listOf(
            "  mResumedActivity: $rec",
            "  ResumedActivity: $rec",
            "  topResumedActivity=$rec",
            "  mResumedActivity=$rec",
            "  mTopResumedActivity=$rec",
            "    mResumedActivity: ActivityRecord{1a2b3c4 u10 com.netflix.ninja/com.netflix.Main t42}",
        )
        for (l in lines) assertThat(Parsers.resumedPackage(l)).isEqualTo("com.netflix.ninja")
        assertThat(Parsers.resumedPackage("  mLastPausedActivity: $rec")).isNull()
        assertThat(Parsers.resumedPackage("  mResumedActivity: null")).isNull()
    }

    @Test fun appop() {
        assertThat(Parsers.appop(fx("appops_netflix.txt"), "RUN_ANY_IN_BACKGROUND")).isEqualTo("ignore")
        assertThat(Parsers.appop(fx("appops_netflix.txt"), "CAMERA")).isNull()
        assertThat(Parsers.appop(fx("appops_netflix.txt"), "RUN_IN_BACKGROUND")).isEqualTo("ignore")
    }

    @Test fun isPmError() {
        assertThat(Parsers.isPmError(fx("pm_uninstall_unknown.txt"))).isTrue()
        assertThat(Parsers.isPmError(fx("pm_disable_already_disabled.txt"))).isFalse()
        assertThat(Parsers.isPmError("Failure [DELETE_FAILED_INTERNAL_ERROR]")).isTrue()
        assertThat(Parsers.isPmError("Error: java.lang.SecurityException")).isTrue()
        assertThat(Parsers.isPmError("Success")).isFalse()
    }
}
