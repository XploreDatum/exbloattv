package dev.mntl.exbloattv

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The ADB private key and install id must never leave the phone: no cloud backup, no device-to-device transfer. */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    @Test fun manifestTurnsOffBackupAndPointsAtTheRules() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
    }

    @Test fun cloudBackupAndDeviceTransferExcludeEverything() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as Element?
            assertTrue("$section missing", node != null)
            val excludes = node!!.getElementsByTagName("exclude")
            val excluded = (0 until excludes.length).map { excludes.item(it) as Element }
                .filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            assertEquals(section, domains, excluded)
            assertEquals("$section must not include anything", 0, node.getElementsByTagName("include").length)
        }
    }
}
