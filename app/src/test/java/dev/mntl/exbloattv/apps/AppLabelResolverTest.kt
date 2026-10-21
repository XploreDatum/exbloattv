package dev.mntl.exbloattv.apps

import com.google.common.truth.Truth.assertThat
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.adb.FakeAdbClient
import dev.mntl.exbloattv.catalog.CategorizedApp
import dev.mntl.exbloattv.catalog.Category
import dev.mntl.exbloattv.tv.InstalledPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLabelResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apk = "/data/app/com.mxtech.videoplayer.pro-1/base.apk"
    private val app = CategorizedApp(
        InstalledPackage("com.mxtech.videoplayer.pro", apk, null, 10001, false, true),
        Category.OTHER, "Videoplayer Pro", "", true, false, false, true,
    )

    private fun tv() = FakeAdbClient().apply {
        responses["dumpsys package 'com.mxtech.videoplayer.pro' | grep -m1 versionCode"] = "    versionCode=42 minSdk=21 targetSdk=33"
        pullContent[apk] = ByteArray(10) { 1 }
    }

    private fun resolver(cache: File = tmp.newFolder(), parse: (File) -> AppLabel? = { f ->
        AppLabel("Real Name", File(cache, "tmp.png").also { it.writeBytes(byteArrayOf(9)) })
    }) = AppLabelResolver(cache, parse)

    @Test fun prettify() {
        assertThat(AppLabelResolver.prettify("com.mxtech.videoplayer.pro")).isEqualTo("Videoplayer Pro")
        assertThat(AppLabelResolver.prettify("org.videolan.vlc")).isEqualTo("Vlc")
    }

    @Test fun savesIconByPackageAndVersionAndDeletesApk() = runTest {
        val cache = tmp.newFolder()
        val adb = tv()
        var pulledTo: File? = null
        val r = AppLabelResolver(cache) { f -> pulledTo = f; AppLabel("Real", File(cache, "x.png").apply { writeBytes(byteArrayOf(7)) }) }
        val label = r.resolve(adb, app, 1_000L)
        assertThat(label.name).isEqualTo("Videoplayer Pro")
        assertThat(label.iconPng).isEqualTo(File(cache, "icons/com.mxtech.videoplayer.pro-42.png"))
        assertThat(label.iconPng!!.readBytes()).isEqualTo(byteArrayOf(7))
        assertThat(pulledTo!!.exists()).isFalse()
    }

    @Test fun cachedIconShortCircuitsPull() = runTest {
        val r = resolver()
        val adb = tv()
        r.resolve(adb, app, 1_000L)
        r.resolve(adb, app, 1_000L)
        assertThat(adb.log.count { it.startsWith("pull:") }).isEqualTo(1)
    }

    @Test fun over40MbIsNeverPulled() = runTest {
        val adb = tv()
        val label = resolver().resolve(adb, app, 40L * 1024 * 1024 + 1)
        assertThat(adb.log.any { it.startsWith("pull:") }).isFalse()
        assertThat(label.iconPng).isNull()
        assertThat(label.name).isEqualTo("Videoplayer Pro")
    }

    @Test fun unknownSizeIsStatted() = runTest {
        val adb = tv().apply { responses["stat -c %s '$apk'"] = "99999999999" }
        val label = resolver().resolve(adb, app, null)
        assertThat(adb.log).contains("stat -c %s '$apk'")
        assertThat(adb.log.any { it.startsWith("pull:") }).isFalse()
        assertThat(label.iconPng).isNull()
    }

    @Test fun parseFailureGivesPrettifiedNameAndDeletesApk() = runTest {
        var pulledTo: File? = null
        val r = resolver(parse = { f -> pulledTo = f; null })
        val label = r.resolve(tv(), app.copy(name = "Videoplayer Pro"), 1_000L)
        assertThat(label).isEqualTo(AppLabel("Videoplayer Pro", null))
        assertThat(pulledTo!!.exists()).isFalse()
    }

    @Test fun parserThrowingStillDeletesApk() = runTest {
        var pulledTo: File? = null
        val r = resolver(parse = { f -> pulledTo = f; error("bad apk") })
        assertThat(r.resolve(tv(), app, 1_000L).iconPng).isNull()
        assertThat(pulledTo!!.exists()).isFalse()
    }

    @Test fun adbErrorOnPullMeansNoIcon() = runTest {
        val adb = tv().apply { pullContent.clear() } // fake throws FileNotFoundException
        assertThat(resolver().resolve(adb, app, 1_000L).iconPng).isNull()
        val adb2 = object : dev.mntl.exbloattv.adb.AdbClient by tv() {
            override suspend fun pull(remote: String, local: File) { throw AdbError.Timeout() }
        }
        assertThat(resolver().resolve(adb2, app, 1_000L).iconPng).isNull()
    }

    @Test fun cancellationIsRethrown() = runTest {
        val adb = tv().also { it.failNext("stat -c %s '$apk'", CancellationException("stop")) }
        try {
            resolver().resolve(adb, app, null)
            error("should have thrown")
        } catch (e: CancellationException) {
            assertThat(e.message).isEqualTo("stop")
        }
    }

    @Test fun nonCatalogNameUsesRealLabel() = runTest {
        val plain = app.copy(name = "Pro")
        assertThat(resolver().resolve(tv(), plain, 1_000L).name).isEqualTo("Real Name")
    }

    @Test fun parseFailureWithBareFallbackNameUsesPrettify() = runTest {
        val bare = app.copy(name = "Pro")
        assertThat(resolver(parse = { null }).resolve(tv(), bare, 1_000L).name).isEqualTo("Videoplayer Pro")
        val adb = tv().also { it.failNext("stat -c %s '$apk'", AdbError.Timeout()) }
        assertThat(resolver().resolve(adb, bare, null).name).isEqualTo("Videoplayer Pro")
    }

    @Test fun uncataloguedNameIsStableAcrossCacheHit() = runTest {
        val r = resolver()
        val adb = tv()
        val bare = app.copy(name = "Pro")
        val first = r.resolve(adb, bare, 1_000L)
        val second = r.resolve(adb, bare, 1_000L)
        assertThat(first.name).isEqualTo("Real Name")
        assertThat(second.name).isEqualTo("Real Name")
        assertThat(adb.log.count { it.startsWith("pull:") }).isEqualTo(1)
    }

    @Test fun shellArgumentsAreQuoted() = runTest {
        val odd = app.copy(pkg = app.pkg.copy(name = "a.b.c'd", apkPath = "/x y/b.apk"))
        val adb = FakeAdbClient()
        resolver().resolve(adb, odd, null)
        assertThat(adb.log).contains("dumpsys package 'a.b.c'\\''d' | grep -m1 versionCode")
        assertThat(adb.log).contains("stat -c %s '/x y/b.apk'")
    }
}
