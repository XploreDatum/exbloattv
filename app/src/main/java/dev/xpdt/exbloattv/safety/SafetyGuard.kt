package dev.xpdt.exbloattv.safety

/**
 * Last line of defence: packages that must never be disabled or uninstalled.
 * Globs: `*` matches any run of characters (dots included), anywhere; the whole
 * package name must match; every other character is literal.
 */
object SafetyGuard {
    val NEVER: List<String> = listOf(
        // Spec section 7
        "android",
        "com.android.systemui",
        "com.android.settings*",
        "com.android.tv.settings*",
        "com.android.providers.*",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.networkstack*",
        "com.android.wifi*",
        "com.android.se",
        "com.android.keychain",
        "com.android.certinstaller",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.google.android.ext.*",
        "com.google.android.permissioncontroller",
        "com.google.android.tv.remote.service",
        "*.inputmethod.*",
        "*.globalkeyoverlay",
        "*.autopair",
        "*.tvinput",
        "*.systemserver",
        "com.droidlogic*",
        "com.amlogic.*",
        "com.mediatek.*",
        "com.realtek.*",
        "mitv.service",
        "com.xiaomi.mitv.res",
        "com.android.dreams.basic",
        // Extra entries from catalog-seed/never-disable.list (android-tv-tune)
        "com.android.tethering*",
        "com.google.android.modulemetadata",
        "com.google.android.overlay.*",
        "*.providers.config",
        "com.tvos",
    )

    private val REMOTE_INPUT: List<String> = listOf(
        "com.google.android.tv.remote.service",
        "com.droidlogic.ble",
        "*.inputmethod.*",
        "*.autopair",
        "*.globalkeyoverlay",
    )

    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun regexFor(glob: String): Regex = regexCache.getOrPut(glob) {
        Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
    }

    private fun matchesAny(globs: List<String>, pkg: String) = globs.any { regexFor(it).matches(pkg) }

    /**
     * True if [pkg] must not be disabled/uninstalled.
     * [activeLauncher] is the package currently resolving HOME; it is protected until
     * [replacementVerified] is true. Launchers are deliberately not in [NEVER], so a stale
     * launcher that is not the active one (e.g. FLauncher during Undo) can be removed.
     */
    fun isProtected(pkg: String, activeLauncher: String? = null, replacementVerified: Boolean = false): Boolean {
        if (matchesAny(NEVER, pkg) || isRemoteInput(pkg)) return true
        return activeLauncher != null && pkg == activeLauncher && !replacementVerified
    }

    /** Remote/keyboard/pairing packages: protected and never shown in the UI. */
    fun isRemoteInput(pkg: String): Boolean = matchesAny(REMOTE_INPUT, pkg)
}
