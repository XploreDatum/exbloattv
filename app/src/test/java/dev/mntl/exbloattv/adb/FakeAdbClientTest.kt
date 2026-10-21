package dev.mntl.exbloattv.adb

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeAdbClientTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun mappedAndUnmappedCommandsAreBothLogged() = runTest {
        val fake = FakeAdbClient(mutableMapOf("getprop a" to "b"))
        assertThat(fake.shell("getprop a")).isEqualTo(ShellResult("b", 0))
        assertThat(fake.shell("whatever")).isEqualTo(ShellResult("", 0))
        assertThat(fake.log).containsExactly("getprop a", "whatever").inOrder()
    }

    @Test fun failNextFailsOnceThenRecovers() = runTest {
        val fake = FakeAdbClient(mutableMapOf("x" to "ok"))
        fake.failNext("x", AdbError.Disconnected())
        var thrown: AdbError? = null
        try { fake.shell("x") } catch (e: AdbError) { thrown = e }
        assertThat(thrown).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(fake.shell("x").output).isEqualTo("ok")
        assertThat(fake.log).containsExactly("x", "x").inOrder()
    }

    @Test fun onCommandCanChangeStateForTheSameCall() = runTest {
        val fake = FakeAdbClient(mutableMapOf("pm list" to "a b"))
        fake.onCommand = { if (it == "pm disable b") fake.responses["pm list"] = "a" }
        fake.shell("pm disable b")
        assertThat(fake.shell("pm list").output).isEqualTo("a")
    }

    @Test fun installPushPullRebootAreLogged() = runTest {
        val apk = tmp.newFile("f.apk").apply { writeText("data") }
        val fake = FakeAdbClient()
        fake.install(apk)
        fake.push(apk, "/data/local/tmp/f.apk")
        fake.pullContent["/r"] = "z".toByteArray()
        val out = File(tmp.root, "out.bin")
        fake.pull("/r", out)
        fake.reboot()
        assertThat(fake.log).containsExactly(
            "install:f.apk", "push:f.apk->/data/local/tmp/f.apk", "pull:/r->out.bin", "reboot",
        ).inOrder()
        assertThat(out.readText()).isEqualTo("z")
        assertThrows(java.io.FileNotFoundException::class.java) {
            kotlinx.coroutines.runBlocking { fake.pull("/missing", out) }
        }
    }
}
