package dev.mntl.exbloattv.catalog

import dev.mntl.exbloattv.tv.AnimationScales
import dev.mntl.exbloattv.tv.InstalledPackage
import dev.mntl.exbloattv.tv.Inventory
import dev.mntl.exbloattv.tv.TvInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategorizerTest {
    private val catalogJson = File("src/main/assets/catalog.json").readText()
    private val catalog = Catalog.parse(catalogJson)
    private val categorizer = Categorizer(catalog)

    private fun pkg(name: String, system: Boolean = false) =
        InstalledPackage(name, "/data/app/$name/base.apk", null, 10000, system, true)

    private fun inv(
        vararg pkgs: InstalledPackage,
        launchable: Set<String> = emptySet(),
        home: List<String> = emptyList(),
    ) = Inventory(
        TvInfo("s", "m", "b", "mf", "9", 28), pkgs.toList(), launchable, home, null, null, null,
        AnimationScales(1f, 1f, 1f), emptyMap(),
    )

    private fun one(name: String, system: Boolean = false, home: List<String> = emptyList()) =
        categorizer.categorize(inv(pkg(name, system), home = home)).single()

    private val seeded: Map<Category, List<String>> = mapOf(
        Category.STREAMING to listOf(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
            "in.startv.hotstar", "com.sonyliv", "com.graymatrix.did", "com.apple.atve.androidtv.appletv",
            "com.plexapp.android", "org.videolan.vlc", "org.xbmc.kodi", "com.mxtech.videoplayer.pro",
            "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.television", "com.oumi.utility.media.hub",
            "com.jio.media.stb.ondemand", "io.put.putio", "com.stevenschoen.putionew", "tv.twitch.android.app",
        ),
        Category.MUSIC to listOf("com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.google.android.music"),
        Category.LIVE_TV to listOf("com.google.android.tv", "tv.airtel.xstream.tvapp", "com.mitv.tvhome.mitvplus"),
        Category.FEATURES to listOf(
            "com.google.android.katniss", "com.google.android.speech.pumpkin", "com.google.android.apps.mediashell",
            "com.google.android.backdrop", "com.google.android.marvin.talkback", "com.google.android.tts",
        ),
        Category.GOOGLE_EXTRAS to listOf(
            "com.google.android.videos", "com.google.android.play.games", "com.google.android.feedback",
            "com.google.android.tv.bugreportsender", "com.google.android.partnersetup",
            "com.google.android.syncadapters.calendar", "com.google.android.syncadapters.contacts",
            "com.android.printspooler", "com.android.camera2", "com.google.android.tvrecommendations",
        ),
        Category.MANUFACTURER to listOf(
            "com.mitv.tvhome.atv", "com.mitv.tvhome.michannel", "com.xm.webcontent", "com.mitv.milinkservice",
            "com.mitv.download.service", "com.mitv.videoplayer", "com.xiaomi.android.tvsetup.partnercustomizer",
            "com.xiaomi.mitv.updateservice", "android.autoinstalls.config.xioami.mibox3",
        ),
        Category.ADS_TRACKING to listOf("tv.alphonso.alphonso_eula", "com.miui.tv.analytics"),
        Category.SIDELOAD_TOOLS to listOf("com.esaba.downloader", "com.esaba.downloader.browserplugin", "com.lonelycatgames.Xplore"),
        Category.LAUNCHER to listOf("com.google.android.tvlauncher", "com.google.android.tungsten.setupwraith", "me.efesser.flauncher"),
    )

    @Test fun everySection12PackageLandsInItsCategory() {
        for ((cat, names) in seeded) for (n in names) {
            assertEquals("$n", cat, one(n, system = true).category)
            assertEquals("$n (user)", cat, one(n, system = false).category)
        }
    }

    @Test fun launcherCategoryHasTheRulingPackages() {
        listOf(
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
            "com.google.android.tungsten.setupwraith", "me.efesser.flauncher", "com.spocky.projengmenu", "com.amazon.tv.launcher",
        ).forEach { assertEquals(it, Category.LAUNCHER, one(it).category) }
    }

    @Test fun uncatalogisedHomePackageIsLauncher() {
        assertEquals(Category.LAUNCHER, one("com.vendor.homeapp", system = true, home = listOf("com.vendor.homeapp")).category)
    }

    @Test fun realBrandNames() {
        val names = mapOf(
            "com.netflix.ninja" to "Netflix", "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.tvmusic" to "YouTube Music",
            "in.startv.hotstar" to "JioHotstar", "com.sonyliv" to "Sony LIV", "com.graymatrix.did" to "ZEE5",
            "com.apple.atve.androidtv.appletv" to "Apple TV", "com.plexapp.android" to "Plex", "org.videolan.vlc" to "VLC",
            "org.xbmc.kodi" to "Kodi", "com.mxtech.videoplayer.pro" to "MX Player Pro", "com.oumi.utility.media.hub" to "VidHub",
            "com.jio.media.stb.ondemand" to "JioCinema", "io.put.putio" to "put.io", "com.spotify.tv.android" to "Spotify",
            "com.google.android.tv" to "Live Channels", "tv.airtel.xstream.tvapp" to "Airtel Xstream Play",
            "com.mitv.tvhome.mitvplus" to "Xiaomi TV+", "com.esaba.downloader" to "Downloader", "com.lonelycatgames.Xplore" to "X-plore",
        )
        names.forEach { (p, n) -> assertEquals(p, n, one(p).name) }
    }

    @Test fun flagsAndDefaults() {
        assertTrue(one("com.google.android.youtube.tvmusic").musicApp)
        assertFalse(one("com.netflix.ninja").musicApp)
        val alphonso = one("tv.alphonso.alphonso_eula")
        assertTrue(alphonso.locked); assertFalse(alphonso.defaultKeep)
        assertFalse(one("com.google.android.videos").defaultKeep)
        val katniss = one("com.google.android.katniss")
        assertEquals(Category.FEATURES, katniss.category); assertTrue(katniss.defaultKeep)
        assertEquals("Mic button on the remote stops working", katniss.description)
        assertEquals("You can't cast from your phone to the TV", one("com.google.android.apps.mediashell").description)
    }

    @Test fun unknownPackages() {
        val user = one("com.example.foo")
        assertEquals(Category.OTHER, user.category); assertTrue(user.defaultKeep); assertFalse(user.locked)
        assertEquals(Category.SYSTEM, one("com.vendor.bar", system = true).category)
    }

    @Test fun remoteInputIsSystemEvenIfCatalogGlobMatches() {
        val c = Categorizer(Catalog(listOf(CatalogEntry("com.droidlogic.*", Category.MANUFACTURER, "Droid", "", false))))
        val r = c.categorize(inv(pkg("com.droidlogic.ble", system = true))).single()
        assertEquals(Category.SYSTEM, r.category)
        assertEquals(Category.SYSTEM, one("com.droidlogic.ble", system = true).category)
    }

    @Test fun launchableComesFromInventory() {
        val r = categorizer.categorize(inv(pkg("com.netflix.ninja"), pkg("com.sonyliv"), launchable = setOf("com.netflix.ninja")))
        assertTrue(r.single { it.pkg.name == "com.netflix.ninja" }.launchable)
        assertFalse(r.single { it.pkg.name == "com.sonyliv" }.launchable)
    }

    @Test fun orderedByCategoryThenNameIgnoringCase() {
        val r = categorizer.categorize(inv(pkg("com.example.zed"), pkg("com.sonyliv"), pkg("com.netflix.ninja"), pkg("org.videolan.vlc"), pkg("com.google.android.tvlauncher")))
        assertEquals(listOf("Netflix", "Sony LIV", "VLC", "Zed", "Android TV home"), r.map { it.name })
    }

    @Test fun exactBeatsGlobAndLongestGlobWins() {
        val c = Catalog(listOf(
            CatalogEntry("com.a.*", Category.OTHER, "short", "", true),
            CatalogEntry("com.a.b.*", Category.MUSIC, "long", "", true),
            CatalogEntry("com.a.b.c", Category.LIVE_TV, "exact", "", true),
        ))
        assertEquals("exact", c.match("com.a.b.c")?.name)
        assertEquals("long", c.match("com.a.b.d")?.name)
        assertEquals("short", c.match("com.a.x")?.name)
        assertEquals(null, c.match("org.z"))
    }

    @Test fun duplicatePatternsAreRejected() {
        val e = CatalogEntry("x", Category.OTHER, "n", "", true)
        assertTrue(runCatching { Catalog(listOf(e, e)) }.isFailure)
    }

    @Test fun catalogParsesWithNoDuplicatesAndPlainDescriptions() {
        assertTrue(catalog.entries.size >= 130)
        assertEquals(catalog.entries.size, catalog.entries.map { it.pattern }.toSet().size)
        val banned = listOf("adb", "package", "pss", "stub", "daemon", "apk")
        val generic = setOf("service", "services", "extras", "extra", "app", "apps")
        catalog.entries.forEach { e ->
            assertTrue("${e.pattern} needs a description", e.description.isNotBlank())
            banned.forEach { assertFalse("${e.pattern}: $it", e.description.lowercase().contains(it)) }
            val words = e.description.trim().split(Regex("\\s+"))
            assertTrue("${e.pattern}: description needs at least 3 words", words.size >= 3)
            assertFalse("${e.pattern}: description too generic", words.all { it.lowercase().trim('.', ',') in generic })
            if (e.category == Category.ADS_TRACKING) { assertTrue(e.locked); assertFalse(e.defaultKeep) }
            assertNotNull(e.name)
        }
    }

    @Test fun googleHelpersWithoutCatalogEntryStayHiddenSystemOnMiBox() {
        val system = dev.mntl.exbloattv.tv.Parsers.packageNames(File("src/test/resources/mibox4-android9/pm_list_system.txt").readText())
        val all = dev.mntl.exbloattv.tv.Parsers.pmListLines(File("src/test/resources/mibox4-android9/pm_list_all.txt").readText()).map { it.first }.toSet()
        val apps = categorizer.categorize(inv(*all.map { pkg(it, it in system) }.toTypedArray()))
        for (n in listOf("com.google.android.sss.authbridge", "com.google.android.tv.frameworkpackagestubs")) {
            assertTrue("$n on fixture", n in all)
            assertEquals(n, Category.SYSTEM, apps.single { it.pkg.name == n }.category)
        }
    }

    @Test fun recommendationsDescriptionStatesRealEffect() {
        val r = one("com.google.android.tvrecommendations")
        assertEquals(Category.GOOGLE_EXTRAS, r.category); assertFalse(r.defaultKeep)
        assertEquals("Suggested-show rows on the stock home screen go empty", r.description)
    }

    @Test fun everySeedEntryIsCatalogued() {
        val seedDir = File("../catalog-seed")
        val lists = seedDir.listFiles { f -> f.name.endsWith(".list") && f.name != "never-disable.list" }!!
        assertTrue(lists.isNotEmpty())
        for (f in lists) f.readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .forEach { assertNotNull("${f.name}: $it", catalog.match(it)) }
    }
}
