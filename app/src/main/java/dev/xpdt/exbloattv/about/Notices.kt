package dev.xpdt.exbloattv.about

/** One third-party component from THIRD_PARTY_NOTICES.md. */
data class Component(
    val name: String,
    val version: String,
    /** SPDX identifier, e.g. "Apache-2.0". */
    val licence: String,
    val source: String,
    val copyright: String,
    val notes: String? = null,
    /** Where to read the licence when its full text isn't in the file (projects that don't ship in the APK). */
    val licenceUrl: String? = null,
)

data class NoticeSection(val title: String, val components: List<Component>)

/** The parsed notices: component sections in file order, and the full licence texts keyed by SPDX identifier. */
data class Notices(val sections: List<NoticeSection>, val texts: Map<String, String>) {
    val components: List<Component> get() = sections.flatMap { it.components }

    fun textFor(c: Component): String? = texts[c.licence]

    companion object {
        /** File names inside the app's assets; the build copies them there from the repository root. */
        const val ASSET_DIR = "licenses"
        const val NOTICES_ASSET = "$ASSET_DIR/THIRD_PARTY_NOTICES.md"
        const val LICENSE_ASSET = "$ASSET_DIR/LICENSE"

        private const val TEXTS_SECTION = "Licence texts"
        private val FIELD = Regex("""^- ([A-Za-z ]+): (.+)$""")

        /**
         * Reads the format described at the top of THIRD_PARTY_NOTICES.md: "## Section", then "### Component" with
         * "- Key: value" lines; under "## Licence texts", "### SPDX-id" followed by a fenced block. Anything else
         * (the introduction, blank lines) is ignored. A component missing a required field is an error, so a broken
         * file fails the unit tests instead of showing a half-empty screen.
         */
        fun parse(markdown: String): Notices {
            val sections = mutableListOf<NoticeSection>()
            val texts = linkedMapOf<String, String>()
            var section: String? = null
            var components = mutableListOf<Component>()
            var name: String? = null
            var fields = linkedMapOf<String, String>()
            var textId: String? = null
            var fence: StringBuilder? = null

            fun endComponent() {
                val n = name ?: return
                fun req(key: String) = fields[key] ?: throw IllegalArgumentException("\"$n\" has no \"$key\" line")
                components += Component(
                    name = n,
                    version = req("Version"),
                    licence = req("Licence"),
                    source = req("Source"),
                    copyright = req("Copyright"),
                    notes = fields["Notes"],
                    licenceUrl = fields["Licence URL"],
                )
                name = null
                fields = linkedMapOf()
            }

            fun endSection() {
                endComponent()
                val s = section ?: return
                if (s != TEXTS_SECTION) sections += NoticeSection(s, components)
                components = mutableListOf()
                section = null
            }

            for (raw in markdown.lineSequence()) {
                val line = raw.trimEnd('\r')
                val open = fence
                if (open != null) {
                    if (line.trim() == "```") {
                        texts[textId ?: throw IllegalArgumentException("licence text without a heading")] = open.toString().trimEnd('\n')
                        fence = null
                        textId = null
                    } else {
                        open.append(line).append('\n')
                    }
                    continue
                }
                when {
                    line.startsWith("## ") -> {
                        endSection()
                        section = line.removePrefix("## ").trim()
                    }
                    line.startsWith("### ") && section == TEXTS_SECTION -> textId = line.removePrefix("### ").trim()
                    line.startsWith("### ") && section != null -> {
                        endComponent()
                        name = line.removePrefix("### ").trim()
                    }
                    line.startsWith("```") && section == TEXTS_SECTION -> {
                        require(textId != null) { "licence text without a heading" }
                        fence = StringBuilder()
                    }
                    name != null -> FIELD.matchEntire(line)?.let { m -> fields[m.groupValues[1]] = m.groupValues[2].trim() }
                }
            }
            require(fence == null) { "unclosed licence text for \"$textId\"" }
            endSection()
            return Notices(sections, texts)
        }
    }
}
