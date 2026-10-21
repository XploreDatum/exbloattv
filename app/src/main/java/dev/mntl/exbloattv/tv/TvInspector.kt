package dev.mntl.exbloattv.tv

import dev.mntl.exbloattv.adb.AdbClient
import kotlin.coroutines.cancellation.CancellationException

/** A read from the TV failed or returned something unusable. [what] names the read for the UI. */
class TvReadException(val what: String, message: String, cause: Throwable? = null) :
    Exception("$what: $message", cause)

/** Reads everything the app needs to know about a TV using read-only shell commands. */
class TvInspector(private val adb: AdbClient) {
    private fun query(category: String) =
        "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.$category"

    /** Runs [cmd]; a non-zero exit code is a failed read. Connection errors ([dev.mntl.exbloattv.adb.AdbError]) pass through. */
    private suspend fun run(what: String, cmd: String, pmOutput: Boolean = false): String {
        val r = adb.shell(cmd)
        if (r.exitCode != 0) throw TvReadException(what, "exit code ${r.exitCode}")
        if (pmOutput && Parsers.isPmError(r.output)) throw TvReadException(what, "TV reported an error")
        return r.output
    }

    suspend fun read(): Inventory {
        val props = Parsers.getprop(run("device info", "getprop"))
        val rows = Parsers.pmListLines(run("app list", "pm list packages -f -i -U", pmOutput = true))
        if (rows.isEmpty()) throw TvReadException("app list", "no apps returned")
        val system = Parsers.packageNames(run("system app list", "pm list packages -s", pmOutput = true))
        if (system.isEmpty()) throw TvReadException("system app list", "no apps returned")
        // Empty is valid here: a fresh TV has nothing switched off.
        val disabled = Parsers.packageNames(run("switched-off app list", "pm list packages -d", pmOutput = true))
        val packages = rows.map { (name, path, inst) ->
            InstalledPackage(name, path, inst.first, inst.second, name in system, name !in disabled)
        }
        // `-u` also lists apps removed for the TV's user; they can be brought back with install-existing.
        val removedForUser = Parsers.packageNames(run("removed app list", "pm list packages -u", pmOutput = true)) -
            packages.map { it.name }.toSet()
        val launchable = (Parsers.queryActivitiesPackages(run("launchable apps", query("LEANBACK_LAUNCHER"))) +
            Parsers.queryActivitiesPackages(run("launchable apps", query("LAUNCHER")))).toSet()
        if (launchable.isEmpty()) throw TvReadException("launchable apps", "none found")
        val homePackages = Parsers.queryActivitiesPackages(run("home screen apps", query("HOME")))
        val resolvedHome = Parsers.resolveActivityBrief(
            run("current home screen", "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
        )
        val memory = optional { Parsers.meminfo(run("memory", "dumpsys meminfo")) }
        val storage = optional { Parsers.df(run("storage", "df /data")) }
        val animations = AnimationScales(
            scale("window_animation_scale"), scale("transition_animation_scale"), scale("animator_duration_scale"),
        )
        val backgroundOps = linkedMapOf<String, String>()
        for (p in packages) {
            if (p.isSystem || p.name !in launchable) continue
            Parsers.appop(adb.shell("cmd appops get ${p.name} RUN_ANY_IN_BACKGROUND").output, "RUN_ANY_IN_BACKGROUND")
                ?.let { backgroundOps[p.name] = it }
        }
        val model = props["ro.product.model"].orEmpty()
        val serial = props["ro.serialno"]?.takeIf { it.isNotBlank() }
            ?: props["ro.boot.serialno"]?.takeIf { it.isNotBlank() }
            ?: "unknown-$model"
        val info = TvInfo(
            serial = serial,
            model = model,
            brand = props["ro.product.brand"].orEmpty(),
            manufacturer = props["ro.product.manufacturer"].orEmpty(),
            androidRelease = props["ro.build.version.release"].orEmpty(),
            sdkInt = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
        )
        return Inventory(info, packages, launchable, homePackages, resolvedHome, memory, storage, animations, backgroundOps, removedForUser)
    }

    /** Memory and storage are informational: an unreadable one becomes null. Cancellation and connection errors still propagate. */
    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TvReadException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

    /**
     * Only the literal "null" (never set) means the default 1.0. Anything else that is not a number is a failed
     * read, because undo later restores these values.
     */
    private suspend fun scale(key: String): Float {
        val text = run("animation settings", "settings get global $key").trim()
        if (text == "null") return 1.0f
        return text.toFloatOrNull() ?: throw TvReadException("animation settings", "unexpected value '$text' for $key")
    }
}
