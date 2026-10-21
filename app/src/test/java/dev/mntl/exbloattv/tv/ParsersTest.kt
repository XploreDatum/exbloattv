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

    // Android 9 (Mi Box 4) `dumpsys activity activities`, trimmed. Stale/other lines name other apps on purpose.
    private val android9Dump = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):

  Stack #1: type=home mode=fullscreen
  isSleeping=false
  mBounds=Rect(0, 0 - 0, 0)
    Task id #1690
      * TaskRecord{9e2b1c7 #1690 A=me.efesser.flauncher U=0 StackId=1 sz=1}
        * Hist #0: ActivityRecord{cc36645 u0 me.efesser.flauncher/.MainActivity t1690}
    Running activities (most recent first):
      TaskRecord{9e2b1c7 #1690 A=me.efesser.flauncher U=0 StackId=1 sz=1}
        Run #0: ActivityRecord{cc36645 u0 me.efesser.flauncher/.MainActivity t1690}
    mResumedActivity: ActivityRecord{cc36645 u0 me.efesser.flauncher/.MainActivity t1690}
    mLastPausedActivity: ActivityRecord{5d1a2b0 u0 com.netflix.ninja/.MainActivity t1689}

  Stack #0: type=standard mode=fullscreen
    Running activities (most recent first):
      TaskRecord{1c2d3e4 #1689 A=com.netflix.ninja U=0 StackId=0 sz=1}
        Run #0: ActivityRecord{5d1a2b0 u0 com.netflix.ninja/.MainActivity t1689}

 mLastResumedActivity: ActivityRecord{5d1a2b0 u0 com.netflix.ninja/.MainActivity t1689}
 ResumedActivity: ActivityRecord{cc36645 u0 me.efesser.flauncher/.MainActivity t1690}
 mFocusedStack=ActivityStack{a1b2c3d stackId=1 type=home mode=fullscreen visible=true translucent=false, 1 tasks} mLastFocusedStack=ActivityStack{a1b2c3d stackId=1 type=home mode=fullscreen visible=true translucent=false, 1 tasks}
 mSleepTimeout=false
 mCurTaskIdForUser={0=1690}
 mUserStackInFront={}
""".trimIndent()

    // Android 12-style dump with a second display; the focused one is display 0 (topResumedActivity).
    private val android12MultiDisplayDump = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #2 (activities from top to bottom):
  * Task{77aa11 #12 type=standard A=10101:com.example.cast U=0 visible=true mode=fullscreen translucent=false sz=1}
    mResumedActivity: ActivityRecord{3f3f3f3 u0 com.example.cast/.Presentation t12}
    * Hist #0: ActivityRecord{3f3f3f3 u0 com.example.cast/.Presentation t12}

  Resumed activities in task display areas (from top to bottom):
    Resumed: ActivityRecord{3f3f3f3 u0 com.example.cast/.Presentation t12}

Display #0 (activities from top to bottom):
  * Task{8bb0c1 #1 type=home ?? U=0 visible=true mode=fullscreen translucent=false sz=1}
    mResumedActivity: ActivityRecord{a8e1d2c u0 com.google.android.tvlauncher/.MainActivity t1}
    mLastPausedActivity: ActivityRecord{1b2c3d4 u0 com.netflix.ninja/.MainActivity t40}
    * Hist #0: ActivityRecord{a8e1d2c u0 com.google.android.tvlauncher/.MainActivity t1}

  Resumed activities in task display areas (from top to bottom):
    Resumed: ActivityRecord{a8e1d2c u0 com.google.android.tvlauncher/.MainActivity t1}

  mLastResumedActivity: ActivityRecord{1b2c3d4 u0 com.netflix.ninja/.MainActivity t40}
  ResumedActivity: ActivityRecord{3f3f3f3 u0 com.example.cast/.Presentation t12}
  mFocusedApp=ActivityRecord{a8e1d2c u0 com.google.android.tvlauncher/.MainActivity t1}
  topDisplayFocusedRootTask=Task{8bb0c1 #1 type=home ?? U=0 visible=true mode=fullscreen translucent=false sz=1}
  mLastPausedActivity: ActivityRecord{1b2c3d4 u0 com.netflix.ninja/.MainActivity t40}
  topResumedActivity=ActivityRecord{a8e1d2c u0 com.google.android.tvlauncher/.MainActivity t1}
""".trimIndent()

    @Test fun foregroundPackageAndroid9FixtureAndFullDump() {
        assertThat(Parsers.foregroundPackage(fx("resumed_activity.txt"))).isEqualTo("me.efesser.flauncher")
        assertThat(Parsers.foregroundPackage(android9Dump)).isEqualTo("me.efesser.flauncher")
    }

    @Test fun foregroundPackagePrefersTopResumedActivityOnMultiDisplay() {
        assertThat(Parsers.foregroundPackage(android12MultiDisplayDump)).isEqualTo("com.google.android.tvlauncher")
    }

    @Test fun foregroundPackageUsesDisplay0WhenNoFocusedLine() {
        // Strip the focused-activity lines: the answer must come from display 0, not the first display listed.
        val noFocus = android12MultiDisplayDump.lineSequence()
            .filterNot { it.trim().startsWith("topResumedActivity=") || it.trim().startsWith("ResumedActivity:") }
            .joinToString("\n")
        assertThat(Parsers.foregroundPackage(noFocus)).isEqualTo("com.google.android.tvlauncher")
    }

    @Test fun foregroundPackageIgnoresLastAndPausedLines() {
        val onlyStale = """
            mLastResumedActivity: ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t4}
            mLastPausedActivity: ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t4}
            mTopResumedActivity=ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t4}
            mResumedActivity: null
        """.trimIndent()
        assertThat(Parsers.foregroundPackage(onlyStale)).isNull()
        assertThat(Parsers.foregroundPackage("")).isNull()
        assertThat(Parsers.foregroundPackage("topResumedActivity=null\n  mResumedActivity: ActivityRecord{2 u10 com.a.b/.X t1}"))
            .isEqualTo("com.a.b")
    }

    @Test fun firstInstallTime() {
        val dump = """
            Packages:
              Package [me.efesser.flauncher] (2b1c7a0):
                userId=10091
                firstInstallTime=2026-10-21 18:30:05
                lastUpdateTime=2026-10-22 09:00:00
        """.trimIndent()
        assertThat(Parsers.firstInstallTime(dump)).isEqualTo(java.time.LocalDateTime.of(2026, 10, 21, 18, 30, 5))
        assertThat(Parsers.firstInstallTime("Unable to find package: x")).isNull()
        assertThat(Parsers.firstInstallTime("firstInstallTime=garbage")).isNull()
    }
}
