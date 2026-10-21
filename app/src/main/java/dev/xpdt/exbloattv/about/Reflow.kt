package dev.xpdt.exbloattv.about

/**
 * Licence files are hard-wrapped at ~78 columns for terminals; on a phone that wraps twice and turns ragged.
 * This joins each paragraph's lines back into one, keeping list items, numbered clauses and short title lines on
 * their own lines, and dropping decorative rules ("-----", "=====", "*****") and box borders.
 * Paragraphs stay separated by one blank line. Words are never changed or reordered.
 */
fun reflowLicence(text: String): String {
    val paragraphs = mutableListOf<MutableList<String>>()
    var current: MutableList<String>? = null
    for (raw in text.lines()) {
        val line = unbox(raw.trim())
        if (line.isEmpty() || RULE.matches(line)) {
            current = null
            continue
        }
        val p = current ?: mutableListOf<String>().also { paragraphs += it; current = it }
        if (p.isEmpty() || startsItem(line) || endsLine(p.last())) p += line else p[p.lastIndex] = p.last() + " " + line
    }
    return paragraphs.joinToString("\n\n") { it.joinToString("\n") }
}

/** "*  6. Disclaimer of Warranty   *" → "6. Disclaimer of Warranty"; a bare "*  …  *" border line → "". */
private fun unbox(line: String): String =
    if (line.length >= 2 && line.startsWith("*") && line.endsWith("*") && !RULE.matches(line)) line.trim('*').trim() else line

/** A new bullet, "1.", "1.1.", "(a)", "a." or "(iv)": it starts its own line. */
private fun startsItem(line: String) = ITEM.containsMatchIn(line)

/** A short line ending without punctuation (a title such as "Apache License") is not joined to what follows. */
private fun endsLine(prev: String) = prev.length < TITLE_MAX && prev.last().isLetterOrDigit()

private const val TITLE_MAX = 40
private val RULE = Regex("""^[-=*_]{3,}$""")
private val ITEM = Regex("""^([*\-•]\s|\d+(\.\d+)*\.\s|\([a-z0-9]{1,4}\)\s|[a-z]\.\s)""")
