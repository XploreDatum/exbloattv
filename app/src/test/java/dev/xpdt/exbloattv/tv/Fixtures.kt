package dev.xpdt.exbloattv.tv

object Fixtures {
    fun text(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/mibox4-android9/$name")
            ?: error("missing fixture $name")).bufferedReader().use { it.readText() }
}
