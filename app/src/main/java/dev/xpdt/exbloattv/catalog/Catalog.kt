package dev.xpdt.exbloattv.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Category { STREAMING, MUSIC, LIVE_TV, FEATURES, GOOGLE_EXTRAS, MANUFACTURER, ADS_TRACKING, SIDELOAD_TOOLS, OTHER, SYSTEM, LAUNCHER }

@Serializable
data class CatalogEntry(
    val pattern: String,
    val category: Category,
    val name: String,
    val description: String = "",
    val defaultKeep: Boolean,
    val locked: Boolean = false,
    val musicApp: Boolean = false,
    val brand: String? = null,
)

@Serializable
private data class CatalogFile(val entries: List<CatalogEntry>)

/** Package-name catalog. Patterns are exact names or globs where `*` matches any run of characters. */
class Catalog(val entries: List<CatalogEntry>) {
    private val exact: Map<String, CatalogEntry>
    private val globs: List<Pair<Regex, CatalogEntry>>

    init {
        val dupes = entries.groupBy { it.pattern }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "Duplicate catalog patterns: $dupes" }
        exact = entries.filter { '*' !in it.pattern }.associateBy { it.pattern }
        globs = entries.filter { '*' in it.pattern }
            .sortedByDescending { it.pattern.length }
            .map { e -> Regex(e.pattern.split('*').joinToString(".*") { Regex.escape(it) }) to e }
    }

    /** Exact match beats glob; among globs the longest pattern wins. */
    fun match(pkg: String): CatalogEntry? =
        exact[pkg] ?: globs.firstOrNull { it.first.matches(pkg) }?.second

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(json: String): Catalog = Catalog(this.json.decodeFromString<CatalogFile>(json).entries)
    }
}
