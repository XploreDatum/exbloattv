package dev.xpdt.exbloattv.about

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Checks the repository's LICENSE and THIRD_PARTY_NOTICES.md, which the build copies into the app as its licence
 * screen. Fails when a dependency version changes without its notice being updated.
 */
class NoticesFileTest {
    private val notices = Notices.parse(File("../THIRD_PARTY_NOTICES.md").readText())
    private val versions: Map<String, String> = Regex("""(?m)^(\w+)\s*=\s*"([^"]+)"""")
        .findAll(File("../gradle/libs.versions.toml").readText().substringAfter("[versions]").substringBefore("[libraries]"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun component(name: String) = notices.components.firstOrNull { it.name == name }
        ?: throw AssertionError("THIRD_PARTY_NOTICES.md has no \"$name\" entry")

    private fun section(title: String) = notices.sections.firstOrNull { it.title == title }
        ?: throw AssertionError("THIRD_PARTY_NOTICES.md has no \"## $title\" section")

    @Test
    fun `project licence is MIT with the right holder`() {
        val license = File("../LICENSE").readText()
        assertThat(license).startsWith("MIT License\n\nCopyright (c) 2026 XploreDatum and contributors\n")
        assertThat(license).contains(notices.texts.getValue("MIT"))
    }

    @Test
    fun `every runtime and test dependency version matches the version catalog`() {
        val expected = mapOf(
            "AndroidX and Jetpack Compose" to listOf("composeBom", "material3", "activityCompose", "coreKtx", "lifecycle", "navigationCompose", "datastore"),
            "Kotlin standard library" to listOf("kotlin"),
            "kotlinx.coroutines" to listOf("coroutines"),
            "kotlinx.serialization" to listOf("serialization"),
            "OkHttp" to listOf("okhttp"),
            "OkHttp MockWebServer" to listOf("okhttp"),
            "dadb" to listOf("dadb"),
            "JUnit 4" to listOf("junit"),
            "Truth" to listOf("truth"),
            "kotlinx-coroutines-test" to listOf("coroutines"),
        )
        for ((name, keys) in expected) {
            for (key in keys) {
                val v = versions[key] ?: throw AssertionError("libs.versions.toml has no version \"$key\"")
                assertWithMessage("$name should list $key $v").that(component(name).version).contains(v)
            }
        }
    }

    @Test
    fun `every library in the version catalog is covered by the test above`() {
        val covered = setOf(
            "agp", "composeBom", "material3", "activityCompose", "coreKtx", "lifecycle", "navigationCompose", "datastore",
            "kotlin", "coroutines", "serialization", "okhttp", "dadb", "junit", "truth",
        )
        assertWithMessage("new entries in libs.versions.toml need a THIRD_PARTY_NOTICES.md entry and a line in this test")
            .that(versions.keys - covered).isEmpty()
    }

    @Test
    fun `everything in the APK has its full licence text in the file`() {
        for (title in listOf("Shipped in the app", "Derived from")) {
            for (c in section(title).components) {
                assertWithMessage("${c.name} (${c.licence}) needs its licence text under ## Licence texts").that(notices.textFor(c)).isNotNull()
            }
        }
    }

    @Test
    fun `projects not in the APK have their licence text or a link to it`() {
        for (title in listOf("Downloaded at runtime, not bundled", "Build and test only (not shipped)")) {
            for (c in section(title).components) {
                if (notices.textFor(c) == null) {
                    assertWithMessage("${c.name} needs a Licence URL").that(c.licenceUrl).startsWith("https://")
                }
            }
        }
    }

    @Test
    fun `every component links to its source over https`() {
        for (c in notices.components) assertWithMessage(c.name).that(c.source).startsWith("https://")
    }

    @Test
    fun `android-tv-tune notice matches the licence kept with the seed lists`() {
        val c = component("android-tv-tune")
        assertThat(c.licence).isEqualTo("MIT")
        assertThat(c.source).isEqualTo("https://github.com/ohernandezdev/android-tv-tune")
        val upstream = File("../catalog-seed/android-tv-tune.LICENSE").readText()
        assertThat(upstream).contains(c.copyright + "\n")
        assertThat(upstream).contains(notices.texts.getValue("MIT"))
    }

    @Test
    fun `FLauncher entry names the exact download the app makes`() {
        val launcherSwap = File("src/main/java/dev/xpdt/exbloattv/cleanup/LauncherSwap.kt").readText()
        val url = Regex(""""(https://gitlab\.com/[^"]+\.apk)"""").find(launcherSwap)?.groupValues?.get(1)
            ?: throw AssertionError("no FLauncher download URL in LauncherSwap.kt")
        val c = component("FLauncher")
        assertThat(c.notes).contains(url)
        assertThat(url).contains("/${c.version}/")
    }

    @Test
    fun `the build copies the notices into the app assets`() {
        val gradle = File("build.gradle.kts").readText()
        assertThat(gradle).contains("rootProject.file(\"LICENSE\"), rootProject.file(\"THIRD_PARTY_NOTICES.md\")")
        assertThat(Notices.NOTICES_ASSET).isEqualTo("licenses/THIRD_PARTY_NOTICES.md")
        assertThat(Notices.LICENSE_ASSET).isEqualTo("licenses/LICENSE")
    }
}
