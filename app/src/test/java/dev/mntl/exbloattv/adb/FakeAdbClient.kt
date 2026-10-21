package dev.mntl.exbloattv.adb

import java.io.File

/**
 * In-memory [AdbClient] shared by tests across the app.
 *
 * - `responses[cmd]` is the exact output returned for a shell command. Unmapped commands return
 *   `ShellResult("", 0)` and are still recorded, so tests can assert on unscripted commands.
 * - `exitCodes[cmd]` optionally overrides the exit code for a command (default 0).
 * - `log` records every call in order: `shell` commands verbatim, `install:<file name>`,
 *   `push:<local name>-><remote>`, `pull:<remote>-><local name>`, `reboot`.
 * - `failNext(cmd, error)` makes the next shell call of exactly `cmd` throw once.
 * - `onCommand` runs after a shell command is logged and before its response is read, so a test can
 *   mutate `responses` to simulate TV state changing (the command's own response then reflects the change).
 * - `installResult`, `pushFiles`/`pullContent` let tests script install/push/pull; `reboot` just logs.
 */
class FakeAdbClient(
    val responses: MutableMap<String, String> = mutableMapOf(),
    val log: MutableList<String> = mutableListOf(),
    override val host: String = "fake-tv",
) : AdbClient {
    val exitCodes: MutableMap<String, Int> = mutableMapOf()
    var onCommand: (String) -> Unit = {}
    var installResult: ShellResult = ShellResult("Success", 0)
    /** Remote path -> bytes written by [push]. */
    val pushed: MutableMap<String, ByteArray> = mutableMapOf()
    /** Remote path -> bytes served by [pull]. */
    val pullContent: MutableMap<String, ByteArray> = mutableMapOf()
    var closed = false
        private set

    private val failures = mutableMapOf<String, ArrayDeque<Throwable>>()

    fun failNext(cmd: String, error: Throwable) {
        failures.getOrPut(cmd) { ArrayDeque() }.addLast(error)
    }

    override suspend fun shell(cmd: String): ShellResult {
        log += cmd
        failures[cmd]?.removeFirstOrNull()?.let { throw it }
        onCommand(cmd)
        return ShellResult(responses[cmd] ?: "", exitCodes[cmd] ?: 0)
    }

    override suspend fun install(apk: File): ShellResult {
        log += "install:${apk.name}"
        return installResult
    }

    override suspend fun push(local: File, remote: String) {
        log += "push:${local.name}->$remote"
        pushed[remote] = local.readBytes()
    }

    override suspend fun pull(remote: String, local: File) {
        log += "pull:$remote->${local.name}"
        local.writeBytes(pullContent[remote] ?: throw java.io.FileNotFoundException(remote))
    }

    override suspend fun reboot() {
        log += "reboot"
    }

    override fun close() {
        closed = true
    }
}
