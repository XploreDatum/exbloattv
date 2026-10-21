package dev.mntl.exbloattv.tv

object Parsers {
    private val numberK = Regex("""([\d,]+)K""")

    /**
     * Parses `pm list packages -f [-i -U]` lines into (package, apkPath, installer to uid).
     * Lines without `installer=`/`uid:` tokens yield installer = null, uid = -1.
     */
    fun pmListLines(text: String): List<Triple<String, String, Pair<String?, Int>>> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@mapNotNull null
            val body = line.removePrefix("package:")
            val apkIdx = body.lastIndexOf(".apk=")
            val eq = if (apkIdx >= 0) apkIdx + ".apk".length else body.lastIndexOf('=')
            if (eq <= 0) return@mapNotNull null
            val path = body.substring(0, eq)
            val tokens = body.substring(eq + 1).trim().split(Regex("\\s+"))
            val pkg = tokens.firstOrNull().orEmpty()
            if (pkg.isEmpty()) return@mapNotNull null
            val installer = tokens.firstOrNull { it.startsWith("installer=") }
                ?.removePrefix("installer=")?.takeUnless { it.isEmpty() || it == "null" }
            val uid = tokens.firstOrNull { it.startsWith("uid:") }?.removePrefix("uid:")?.toIntOrNull() ?: -1
            Triple(pkg, path, installer to uid)
        }.toList()

    fun packageNames(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

    /** Distinct `packageName=` of each `ActivityInfo:` block, in order of appearance. */
    fun queryActivitiesPackages(text: String): List<String> {
        val out = LinkedHashSet<String>()
        var waiting = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line == "ActivityInfo:") waiting = true
            else if (waiting && line.startsWith("packageName=")) {
                out += line.removePrefix("packageName=").trim()
                waiting = false
            }
        }
        return out.toList()
    }

    fun resolveActivityBrief(text: String): String? =
        text.lineSequence().map { it.trim() }
            .lastOrNull { Regex("""[\w.]+/[\w.$]+""").matches(it) }

    fun meminfo(text: String): MemoryStats {
        fun kb(label: String): Long? =
            text.lineSequence().firstOrNull { it.trim().startsWith("$label:") }
                ?.let { numberK.find(it)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() }
        val used = Regex("""([\d,]+)K used pss""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        return MemoryStats(
            totalKb = kb("Total RAM") ?: error("meminfo: no Total RAM line"),
            freeKb = kb("Free RAM") ?: error("meminfo: no Free RAM line"),
            usedPssKb = used ?: error("meminfo: no used pss"),
        )
    }

    /** Parses `df /data` (1K-blocks): total, used, available. */
    fun df(text: String): StorageStats {
        val row = text.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 4 && it[1].toLongOrNull() != null && it[2].toLongOrNull() != null && it[3].toLongOrNull() != null }
            ?: error("df: no data row")
        return StorageStats(row[1].toLong(), row[2].toLong(), row[3].toLong())
    }

    fun getprop(text: String): Map<String, String> {
        val re = Regex("""^\[(.+?)]: \[(.*)]$""")
        return text.lineSequence().mapNotNull { re.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun resumedPackage(text: String): String? =
        Regex("""[rR]esumedActivity[:=]\s*ActivityRecord\{[^}]*? u\d+ ([^/\s]+)/""").find(text)?.groupValues?.get(1)

    private fun recordPackage(prefix: String) =
        Regex("""^\s*${Regex.escape(prefix)}\s*ActivityRecord\{[^}]*? u\d+ ([^/\s]+)/""", RegexOption.MULTILINE)

    private val topResumed = recordPackage("topResumedActivity=")
    private val focusedResumed = recordPackage("ResumedActivity:")
    private val stackResumed = recordPackage("mResumedActivity:")

    /**
     * The package in front, from `dumpsys activity activities`. Unlike [resumedPackage] this never reads
     * `mLastResumedActivity`/`mLastPausedActivity` or another display: it takes `topResumedActivity=`
     * (Android 10+, the focused display), else the focused `ResumedActivity:` (Android 9 and earlier), else the
     * first `mResumedActivity:` inside the `Display #0` section (or the whole text when there are no sections).
     */
    fun foregroundPackage(text: String): String? {
        topResumed.find(text)?.let { return it.groupValues[1] }
        focusedResumed.find(text)?.let { return it.groupValues[1] }
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Display #0") }
        val display0 = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.trim().startsWith("Display #") }
        return stackResumed.find(display0.joinToString("\n"))?.groupValues?.get(1)
    }

    /** `firstInstallTime=yyyy-MM-dd HH:mm:ss` from `dumpsys package <pkg>`, in the TV's local time; null if absent or odd. */
    fun firstInstallTime(text: String): java.time.LocalDateTime? =
        Regex("""firstInstallTime=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""").find(text)?.groupValues?.get(1)
            ?.let { parseTvTime(it) }

    /** Parses `yyyy-MM-dd HH:mm:ss` (the TV's local time, as `dumpsys package` and `date` print it); null if odd. */
    fun parseTvTime(text: String): java.time.LocalDateTime? =
        try {
            java.time.LocalDateTime.parse(text.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }

    fun appop(text: String, op: String): String? =
        Regex("""^\s*${Regex.escape(op)}:\s*(\w+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    fun isPmError(text: String): Boolean =
        text.contains("Exception occurred") || text.contains("Error:") || text.contains("Failure")
}
