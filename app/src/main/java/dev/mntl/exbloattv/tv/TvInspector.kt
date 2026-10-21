package dev.mntl.exbloattv.tv

import dev.mntl.exbloattv.adb.AdbClient

/** Reads everything the app needs to know about a TV using read-only shell commands. */
class TvInspector(private val adb: AdbClient) {
    private fun query(category: String) =
        "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.$category"

    suspend fun read(): Inventory {
        val props = Parsers.getprop(adb.shell("getprop").output)
        val rows = Parsers.pmListLines(adb.shell("pm list packages -f -i -U").output)
        check(rows.isNotEmpty()) { "The TV returned no app list" }
        val system = Parsers.packageNames(adb.shell("pm list packages -s").output)
        val disabled = Parsers.packageNames(adb.shell("pm list packages -d").output)
        val packages = rows.map { (name, path, inst) ->
            InstalledPackage(name, path, inst.first, inst.second, name in system, name !in disabled)
        }
        val launchable = (Parsers.queryActivitiesPackages(adb.shell(query("LEANBACK_LAUNCHER")).output) +
            Parsers.queryActivitiesPackages(adb.shell(query("LAUNCHER")).output)).toSet()
        val homePackages = Parsers.queryActivitiesPackages(adb.shell(query("HOME")).output)
        val resolvedHome = Parsers.resolveActivityBrief(
            adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").output,
        )
        val memory = runCatching { Parsers.meminfo(adb.shell("dumpsys meminfo").output) }
            .getOrElse { MemoryStats(0, 0, 0) }
        val storage = runCatching { Parsers.df(adb.shell("df /data").output) }
            .getOrElse { StorageStats(0, 0, 0) }
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
        return Inventory(info, packages, launchable, homePackages, resolvedHome, memory, storage, animations, backgroundOps)
    }

    /** `settings get` prints "null" when the value was never set, which means the default 1.0. */
    private suspend fun scale(key: String): Float =
        adb.shell("settings get global $key").output.trim().toFloatOrNull() ?: 1.0f
}
