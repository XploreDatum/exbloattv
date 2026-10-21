package dev.mntl.exbloattv.adb

import java.io.Closeable
import java.io.File

data class ShellResult(val output: String, val exitCode: Int)

/** One live connection to a TV. All methods are safe to call from any dispatcher. */
interface AdbClient : Closeable {
    val host: String
    suspend fun shell(cmd: String): ShellResult
    suspend fun install(apk: File): ShellResult
    suspend fun push(local: File, remote: String)
    suspend fun pull(remote: String, local: File)
    suspend fun reboot()
}

sealed class AdbError(msg: String) : Exception(msg) {
    /** The TV is showing "Allow USB debugging?" and nobody has accepted yet. */
    class Unauthorized : AdbError("waiting for approval on TV")
    /** The TV refused the connection: debugging is off or the address is wrong. */
    class Refused : AdbError("debugging off")
    class Timeout : AdbError("timeout")
    class Disconnected : AdbError("connection lost")
}

interface AdbConnector {
    suspend fun connect(host: String, port: Int = 5555, authTimeoutMs: Long = 60_000): AdbClient
}
