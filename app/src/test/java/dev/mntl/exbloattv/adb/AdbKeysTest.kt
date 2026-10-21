package dev.mntl.exbloattv.adb

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
}
