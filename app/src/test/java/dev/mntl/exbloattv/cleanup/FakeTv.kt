package dev.mntl.exbloattv.cleanup

import dev.mntl.exbloattv.adb.FakeAdbClient
import java.util.Base64

/**
 * A small Android TV simulated behind a [FakeAdbClient]: package enable/disable state, HOME resolution by
 * intent-filter priority, the foreground app, and the shared state file. Every command the launcher swap
 * may issue is interpreted; anything else fails the test.
 */
class FakeTv(val adb: FakeAdbClient = FakeAdbClient()) {
    var manufacturer = "Xiaomi"
    val installed = linkedSetOf(STOCK, TV_SETTINGS, SETUPWRAITH, "com.netflix.ninja")
    val disabled = mutableSetOf<String>()

    /** HOME-capable packages and their intent-filter priority (setupwraith's HOME filter is off after setup). */
    val homePriority = linkedMapOf(STOCK to 1, FLAUNCHER_PKG to 0, TV_SETTINGS to -1000)
    var foreground: String? = STOCK
    var monkeyWorks = true
    var amStartWorks = true
    var installWorks = true
    /** Packages whose `pm disable-user` prints success but changes nothing. */
    val stubbornPackages = mutableSetOf<String>()
    /** What pressing HOME brings to the front; by default whatever HOME resolves to. */
    var onHome: () -> String? = { resolvedHome() }
    /** The TV's state file system (path -> content). */
    val fs = mutableMapOf<String, String>()
    /** Makes every state-file write fail (e.g. storage full). */
    var stateWritesFail = false

    init {
        adb.onCommand = { cmd -> adb.exitCodes[cmd] = handle(cmd) }
        adb.onInstall = { if (installWorks) installed += FLAUNCHER_PKG }
    }

    fun homeCandidates() = homePriority.keys.filter { it in installed && it !in disabled }

    /** Highest priority wins; a tie shows the system chooser ("android"). */
    fun resolvedHome(): String? {
        val c = homeCandidates()
        if (c.isEmpty()) return null
        val max = c.maxOf { homePriority.getValue(it) }
        val top = c.filter { homePriority.getValue(it) == max }
        return if (top.size == 1) top.single() else "android"
    }

    private fun component(pkg: String) =
        if (pkg == "android") "android/com.android.internal.app.ResolverActivity" else "$pkg/.MainActivity"

    private fun launchable(pkg: String) = pkg in installed && pkg !in disabled

    private fun handle(cmd: String): Int {
        val out = adb.responses
        when {
            cmd == "getprop ro.product.manufacturer" -> out[cmd] = "$manufacturer\n"
            cmd == RESOLVE_HOME -> out[cmd] = resolvedHome()
                ?.let { "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n${component(it)}\n" }
                ?: "No activity found\n"
            cmd == QUERY_HOME -> out[cmd] = homeCandidates().sortedByDescending { homePriority.getValue(it) }
                .mapIndexed { i, p ->
                    "  Activity #$i:\n    priority=${homePriority.getValue(p)}\n    ActivityInfo:\n" +
                        "      name=$p.MainActivity\n      packageName=$p\n      enabled=true\n"
                }
                .joinToString("", prefix = "${homeCandidates().size} activities found:\n")
            cmd == "dumpsys activity activities" -> out[cmd] = dump()
            cmd == "monkey -p $FLAUNCHER_PKG -c android.intent.category.LAUNCHER 1" -> {
                if (monkeyWorks && launchable(FLAUNCHER_PKG)) foreground = FLAUNCHER_PKG
                out[cmd] = "Events injected: 1\n"
            }
            cmd == "am start -n $FLAUNCHER_PKG/.MainActivity" -> {
                if (amStartWorks && launchable(FLAUNCHER_PKG)) foreground = FLAUNCHER_PKG
                out[cmd] = "Starting: Intent { cmp=$FLAUNCHER_PKG/.MainActivity }\n"
            }
            cmd == "input keyevent KEYCODE_HOME" -> {
                foreground = onHome()
                out[cmd] = ""
            }
            else -> return pm(cmd) ?: stateFile(cmd)
        }
        return 0
    }

    private fun pm(cmd: String): Int? {
        val out = adb.responses
        Regex("""^pm list packages(?: (-e|-d))? (\S+)$""").matchEntire(cmd)?.let { m ->
            val filter = m.groupValues[1]
            out[cmd] = installed.filter { it.contains(m.groupValues[2]) }
                .filter { filter.isEmpty() || (filter == "-d") == (it in disabled) }
                .joinToString("") { "package:$it\n" }
            return 0
        }
        Regex("""^pm disable-user --user 0 (\S+)$""").matchEntire(cmd)?.let { m ->
            val p = m.groupValues[1]
            if (p !in installed) { out[cmd] = "Error: Unknown package: $p\n"; return 1 }
            if (p !in stubbornPackages) disabled += p
            out[cmd] = "Package $p new state: disabled-user\n"
            return 0
        }
        Regex("""^pm uninstall (\S+)$""").matchEntire(cmd)?.let { m ->
            val p = m.groupValues[1]
            if (!installed.remove(p)) { out[cmd] = "Failure [DELETE_FAILED_INTERNAL_ERROR]\n"; return 1 }
            disabled -= p
            if (foreground == p) foreground = null
            out[cmd] = "Success\n"
            return 0
        }
        Regex("""^pm enable (\S+)$""").matchEntire(cmd)?.let { m ->
            val p = m.groupValues[1]
            disabled -= p
            out[cmd] = "Package $p new state: enabled\n"
            return 0
        }
        return null
    }

    private fun dump(): String {
        val fg = foreground ?: return "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\nDisplay #0 (activities from top to bottom):\n"
        return """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              Stack #1: type=home mode=fullscreen
                mResumedActivity: ActivityRecord{cc36645 u0 ${component(fg)} t1690}
                mLastPausedActivity: ActivityRecord{5d1a2b0 u0 com.netflix.ninja/.MainActivity t1689}
             mLastResumedActivity: ActivityRecord{5d1a2b0 u0 com.netflix.ninja/.MainActivity t1689}
             ResumedActivity: ActivityRecord{cc36645 u0 ${component(fg)} t1690}
        """.trimIndent() + "\n"
    }

    /** Interprets exactly the commands TvStateStore issues. */
    private fun stateFile(cmd: String): Int {
        Regex("""^cat (\S+)$""").matchEntire(cmd)?.let { m ->
            val c = fs[m.groupValues[1]]
            adb.responses[cmd] = c ?: "cat: ${m.groupValues[1]}: No such file or directory"
            return if (c == null) 1 else 0
        }
        if (Regex("""^mkdir -p \S+$""").matches(cmd)) return if (stateWritesFail) 1 else 0
        Regex("""^echo (\S*) \| base64 -d (>>?) (\S+)$""").matchEntire(cmd)?.let { m ->
            val text = String(Base64.getDecoder().decode(m.groupValues[1]))
            val path = m.groupValues[3]
            fs[path] = if (m.groupValues[2] == ">") text else (fs[path] ?: return 1) + text
            return 0
        }
        Regex("""^mv (\S+) (\S+)$""").matchEntire(cmd)?.let { m ->
            fs[m.groupValues[2]] = fs.remove(m.groupValues[1]) ?: return 1
            return 0
        }
        Regex("""^rm -f (\S+)$""").matchEntire(cmd)?.let { fs.remove(it.groupValues[1]); return 0 }
        error("unexpected command: $cmd")
    }

    companion object {
        const val STOCK = "com.google.android.tvlauncher"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        const val QUERY_HOME =
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"

        fun isStateFileCommand(cmd: String) =
            cmd.startsWith("cat /data/local/tmp/exbloattv/") || cmd.startsWith("mkdir -p ") ||
                cmd.startsWith("echo ") || cmd.startsWith("mv /data/local/tmp/exbloattv/") || cmd.startsWith("rm -f ")
    }
}
