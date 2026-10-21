package dev.xpdt.exbloattv.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {

    private val specLiterals = listOf(
        "android", "com.android.systemui", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.shell", "com.android.bluetooth",
        "com.android.se", "com.android.keychain", "com.android.certinstaller", "com.android.vending",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.webview",
        "com.google.android.permissioncontroller", "com.google.android.tv.remote.service",
        "mitv.service", "com.xiaomi.mitv.res", "com.android.dreams.basic",
    )

    private val specGlobSamples = listOf(
        "com.android.settings", "com.android.settings.intelligence",
        "com.android.tv.settings", "com.android.tv.settings.x",
        "com.android.providers.media.module", "com.android.providers.settings",
        "com.android.networkstack.tethering", "com.android.wifi.resources",
        "com.google.android.ext.services", "com.google.android.ext.shared",
        "com.google.android.inputmethod.latin", "com.google.android.tv.inputmethod.x",
        "com.foo.globalkeyoverlay", "com.foo.autopair", "com.tcl.tvinput",
        "com.foo.systemserver", "com.droidlogic.ble", "com.droidlogic", "com.droidlogic.x",
        "com.amlogic.tvservice", "com.mediatek.x", "com.realtek.y",
    )

    // From android-tv-tune never-disable.list, beyond spec section 7.
    private val seedExtras = listOf(
        "com.android.tethering", "com.android.tethering.x", "com.google.android.modulemetadata",
        "com.google.android.overlay.x", "com.foo.providers.config", "com.tvos",
    )

    private val notProtected = listOf(
        // Google extras
        "com.google.android.videos", "com.google.android.play.games", "com.google.android.feedback",
        "com.google.android.tv.bugreportsender", "com.google.android.partnersetup",
        "com.google.android.syncadapters.calendar", "com.google.android.syncadapters.contacts",
        "com.android.printspooler", "com.android.camera2", "com.google.android.tvrecommendations",
        // Manufacturer extras
        "com.mitv.tvhome.atv", "com.mitv.tvhome.michannel", "com.xm.webcontent",
        "com.mitv.milinkservice", "com.mitv.download.service", "com.mitv.videoplayer",
        "com.xiaomi.android.tvsetup.partnercustomizer", "com.xiaomi.mitv.updateservice",
        "android.autoinstalls.config.xioami.mibox3",
        // Ads & tracking
        "tv.alphonso.alphonso_eula", "com.miui.tv.analytics",
        // Sideload tools
        "com.esaba.downloader", "com.esaba.downloader.browserplugin", "com.lonelycatgames.Xplore",
        // Streaming
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
        "in.startv.hotstar", "com.sonyliv", "com.graymatrix.did", "com.apple.atve.androidtv.appletv",
        "com.plexapp.android", "org.videolan.vlc", "org.xbmc.kodi", "com.mxtech.videoplayer.pro",
        "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.television", "com.oumi.utility.media.hub",
        "com.jio.media.stb.ondemand", "io.put.putio", "com.stevenschoen.putionew",
        "tv.twitch.android.app",
        // Music
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.google.android.music",
        // Features
        "com.google.android.katniss", "com.google.android.speech.pumpkin",
        "com.google.android.apps.mediashell", "com.google.android.backdrop",
        "com.google.android.marvin.talkback", "com.google.android.tts",
        // Setup wizard (HOME thief)
        "com.google.android.tungsten.setupwraith",
    )

    private val remoteInput = listOf(
        "com.google.android.tv.remote.service", "com.droidlogic.ble",
        "com.google.android.inputmethod.latin", "com.foo.inputmethod.bar",
        "com.foo.autopair", "com.foo.globalkeyoverlay",
    )

    @Test fun everySpecLiteralIsProtected() =
        specLiterals.forEach { assertTrue(it, SafetyGuard.isProtected(it)) }

    @Test fun specGlobsProtectRealisticPackages() =
        specGlobSamples.forEach { assertTrue(it, SafetyGuard.isProtected(it)) }

    @Test fun seedListExtrasAreProtected() =
        seedExtras.forEach { assertTrue(it, SafetyGuard.isProtected(it)) }

    @Test fun ordinaryAndBloatPackagesAreNotProtected() =
        notProtected.forEach { assertFalse(it, SafetyGuard.isProtected(it)) }

    @Test fun matchIsAnchoredAndDotsAreLiteral() {
        assertFalse(SafetyGuard.isProtected("xandroid"))
        assertFalse(SafetyGuard.isProtected("androidx"))
        assertFalse(SafetyGuard.isProtected("com.android.systemuix"))
        assertFalse(SafetyGuard.isProtected("comXandroid.systemui"))
        assertFalse(SafetyGuard.isProtected("com.android.setting"))
        assertTrue(SafetyGuard.isProtected("com.android.settingsx"))
        assertFalse(SafetyGuard.isProtected("com.droidlogi"))
        assertFalse(SafetyGuard.isProtected(""))
    }

    @Test fun everyRemoteInputPackageIsRemoteInputAndProtected() = remoteInput.forEach {
        assertTrue(it, SafetyGuard.isRemoteInput(it))
        assertTrue(it, SafetyGuard.isProtected(it))
    }

    @Test fun nonRemotePackagesAreNotRemoteInput() {
        listOf("com.android.systemui", "com.netflix.ninja", "com.tcl.tvinput", "com.google.android.tvlauncher")
            .forEach { assertFalse(it, SafetyGuard.isRemoteInput(it)) }
    }

    @Test fun remoteInputStaysProtectedEvenWhenReplacementVerified() {
        assertTrue(SafetyGuard.isProtected("com.droidlogic.ble", "com.droidlogic.ble", true))
    }

    @Test fun activeLauncherProtectedUntilReplacementVerified() {
        val l = "com.google.android.tvlauncher"
        assertFalse(SafetyGuard.isProtected(l))
        assertTrue(SafetyGuard.isProtected(l, activeLauncher = l, replacementVerified = false))
        assertFalse(SafetyGuard.isProtected(l, activeLauncher = l, replacementVerified = true))
        assertFalse(SafetyGuard.isProtected("com.netflix.ninja", activeLauncher = l))
    }

    @Test fun launchersAreOnlyProtectedWhileActiveAndUnreplaced() {
        listOf("me.efesser.flauncher", "com.spocky.projengmenu", "com.google.android.tvlauncher").forEach { l ->
            assertFalse(l, SafetyGuard.isProtected(l))
            assertTrue(l, SafetyGuard.isProtected(l, activeLauncher = l, replacementVerified = false))
            assertFalse(l, SafetyGuard.isProtected(l, activeLauncher = l, replacementVerified = true))
        }
    }

    @Test fun neverListHasNoBlankEntries() {
        assertTrue(SafetyGuard.NEVER.none { it.isBlank() })
        assertEquals(SafetyGuard.NEVER.size, SafetyGuard.NEVER.toSet().size)
    }
}
