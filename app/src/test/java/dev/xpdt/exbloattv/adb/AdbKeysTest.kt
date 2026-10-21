package dev.xpdt.exbloattv.adb

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createsKeyFilesWithNameAsPublicKeyComment() {
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "exbloattv@Pixel 9")
        assertThat(File(dir, "adbkey").isFile).isTrue()
        val pub = File(dir, "adbkey.pub").readText()
        assertThat(pub.trim()).endsWith(" exbloattv@Pixel 9")
    }

    @Test fun secondCallReusesTheSameKey() {
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "exbloattv@Pixel 9")
        val priv1 = File(dir, "adbkey").readText()
        val pub1 = File(dir, "adbkey.pub").readText()
        val second = AdbKeys.loadOrCreate(dir, "exbloattv@Pixel 9")
        assertThat(second).isNotNull()
        assertThat(File(dir, "adbkey").readText()).isEqualTo(priv1)
        assertThat(File(dir, "adbkey.pub").readText()).isEqualTo(pub1)
    }

    @Test fun incompletePairIsRegeneratedTogether() {
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "n")
        File(dir, "adbkey.pub").delete()
        AdbKeys.loadOrCreate(dir, "n")
        assertThat(File(dir, "adbkey.pub").isFile).isTrue()
    }

    @Test fun corruptKeyFilesAreKeptAsideAndANewPairIsMade() {
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "n")
        File(dir, "adbkey").writeText("-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n")
        File(dir, "adbkey.pub").writeText("garbage n\n")

        val pair = AdbKeys.loadOrCreate(dir, "n")

        assertThat(pair).isNotNull()
        assertThat(File(dir, "adbkey.pub").readText().trim()).endsWith(" n")
        val kept = dir.listFiles()!!.map { it.name }.filter { ".bad-" in it }
        assertThat(kept).hasSize(2) // both old files, for diagnosis; the TV will ask to approve the new key
        assertThat(kept.single { it.startsWith("adbkey.pub.bad-") }.let { File(dir, it).readText() }).isEqualTo("garbage n\n")
        // The new pair is good: loading again keeps it and sets nothing more aside.
        val priv = File(dir, "adbkey").readText()
        AdbKeys.loadOrCreate(dir, "n")
        assertThat(File(dir, "adbkey").readText()).isEqualTo(priv)
        assertThat(dir.listFiles()!!.count { ".bad-" in it.name }).isEqualTo(2)
    }

    @Test fun anEmptyKeyFileIsTreatedAsCorrupt() {
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "n")
        File(dir, "adbkey").writeText("")
        assertThat(AdbKeys.loadOrCreate(dir, "n")).isNotNull()
    }

    @Test fun aCorruptPublicKeyAloneIsAlsoReplaced() {
        // The private key still reads, but the TV would be sent garbage and could never show the approval prompt.
        val dir = File(tmp.root, "adb")
        AdbKeys.loadOrCreate(dir, "n")
        File(dir, "adbkey.pub").writeText("QUJD n\n")
        AdbKeys.loadOrCreate(dir, "n")
        assertThat(File(dir, "adbkey.pub").readText()).isNotEqualTo("QUJD n\n")
        assertThat(dir.listFiles()!!.count { ".bad-" in it.name }).isEqualTo(2)
    }
}
