package dev.xpdt.exbloattv.state

import dev.xpdt.exbloattv.adb.AdbError
import dev.xpdt.exbloattv.adb.FakeAdbClient
import dev.xpdt.exbloattv.tv.AnimationScales
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class TvStateStoreTest {
    private val file = TvStateStore.FILE
    private val tmp = "$file.tmp"
    private val serial = "SER1"
    private var clock = 1_000_000L
    private val fs = mutableMapOf<String, String>()
    private val fake = FakeAdbClient()
    private val store: TvStateStore

    init {
        // Tiny TV file system interpreting exactly the commands TvStateStore issues.
        fake.onCommand = { cmd -> fake.exitCodes[cmd] = run(cmd) }
        store = TvStateStore(fake) { clock }
    }

    private fun run(cmd: String): Int {
        Regex("""^cat (\S+)$""").matchEntire(cmd)?.let { m ->
            val c = fs[m.groupValues[1]]
            fake.responses[cmd] = c ?: "cat: ${m.groupValues[1]}: No such file or directory"
            return if (c == null) 1 else 0
        }
        if (cmd == "mkdir -p ${TvStateStore.DIR}") return 0
        Regex("""^echo (\S*) \| base64 -d (>>?) (\S+)$""").matchEntire(cmd)?.let { m ->
            val text = String(Base64.getDecoder().decode(m.groupValues[1]))
            val path = m.groupValues[3]
            fs[path] = if (m.groupValues[2] == ">") text else (fs[path] ?: return 1) + text
            return 0
        }
        Regex("""^mv (\S+) (\S+)$""").matchEntire(cmd)?.let { m ->
            val c = fs.remove(m.groupValues[1]) ?: return 1
            fs[m.groupValues[2]] = c
            return 0
        }
        Regex("""^rm -f (\S+)$""").matchEntire(cmd)?.let { fs.remove(it.groupValues[1]); return 0 }
        error("unexpected command: $cmd")
    }

    private fun rp(by: String = "Pixel 8") = RestorePoint(1, by, listOf("a"), listOf("b"), listOf("c"), "home", AnimationScales(1f, 1f, 1f), mapOf("p" to "allow"))
    private fun change(id: String) = Change(id, ChangeKind.DISABLE, "pkg.$id", "enabled", "disabled", "Pixel 8", 5, true)
    private fun <T> r(b: suspend () -> T) = runBlocking { b() }

    @Test fun missingFileLoadsEmptyState() {
        assertEquals(TvState(serial = serial), r { store.load(serial) })
    }

    @Test fun roundTrip() {
        val s = TvState(serial = serial, original = rp(), latest = rp("x"), changes = listOf(change("1")), lock = Lock("Pixel 8", 9))
        r { store.save(s) }
        assertEquals(s, r { store.load(serial) })
        assertTrue(fs.keys.none { it.contains(".tmp") })
    }

    @Test fun commandsAreExact() {
        r { store.save(TvState(serial = serial)) }
        assertEquals("mkdir -p ${TvStateStore.DIR}", fake.log[0])
        assertTrue(fake.log[1].startsWith("echo ") && fake.log[1].contains(" | base64 -d > $tmp."))
        val t = fake.log[1].substringAfter("> ")
        assertEquals("mv $t $file", fake.log[2])
    }

    @Test fun largeStateIsChunkedAndIntact() {
        val s = TvState(serial = serial, changes = (1..600).map { change("c$it") })
        r { store.save(s) }
        assertTrue(fake.log.any { it.contains("base64 -d >> $tmp.") })
        assertTrue(fake.log.filter { it.startsWith("echo ") }.all { it.length < 33_000 })
        assertEquals(s, r { store.load(serial) })
    }

    @Test fun unknownKeysAreIgnored() {
        fs[file] = """{"version":1,"serial":"SER1","future":{"x":1}}"""
        assertEquals(TvState(serial = serial), r { store.load(serial) })
    }

    @Test fun setOriginalNeverOverwrites() {
        r { store.setOriginalIfAbsent(serial, rp("first")) }
        r { store.setOriginalIfAbsent(serial, rp("second")) }
        assertEquals("first", r { store.load(serial) }.original!!.byPhone)
    }

    @Test fun freshLockBlocksOtherPhone() {
        r { store.acquireLock(serial, "Pixel 8") }.getOrThrow()
        clock += 3 * 60_000
        val res = r { store.acquireLock(serial, "Galaxy S24") }
        val e = res.exceptionOrNull() as TvLockedException
        assertEquals("Pixel 8", e.byPhone)
        assertEquals("Pixel 8", r { store.load(serial) }.lock!!.byPhone)
    }

    @Test fun expiredLockIsTakenOver() {
        r { store.acquireLock(serial, "Pixel 8") }.getOrThrow()
        clock += 11 * 60_000
        val s = r { store.acquireLock(serial, "Galaxy S24") }.getOrThrow()
        assertEquals(Lock("Galaxy S24", clock), s.lock)
    }

    @Test fun samePhoneCanReacquire() {
        r { store.acquireLock(serial, "Pixel 8") }.getOrThrow()
        assertTrue(r { store.acquireLock(serial, "Pixel 8") }.isSuccess)
    }

    @Test fun releaseByOtherPhoneIsNoOp() {
        r { store.acquireLock(serial, "Pixel 8") }.getOrThrow()
        fake.log.clear()
        r { store.releaseLock(serial, "Galaxy S24") }
        assertEquals("Pixel 8", r { store.load(serial) }.lock!!.byPhone)
        assertTrue(fake.log.none { it.startsWith("echo ") })
        r { store.releaseLock(serial, "Pixel 8") }
        assertNull(r { store.load(serial) }.lock)
    }

    @Test fun lockLostToConcurrentWriterIsReported() {
        // Another phone overwrites the file between our write and our read-back.
        var armed = true
        val other = """{"version":1,"serial":"SER1","lock":{"byPhone":"Galaxy S24","at":$clock}}"""
        fake.onCommand = { cmd ->
            fake.exitCodes[cmd] = run(cmd)
            if (armed && cmd.startsWith("mv $tmp.")) { armed = false; fs[file] = other }
        }
        val res = r { store.acquireLock(serial, "Pixel 8") }
        assertEquals("Galaxy S24", (res.exceptionOrNull() as TvLockedException).byPhone)
    }

    @Test fun appendChangeKeepsOrder() {
        listOf("1", "2", "3").forEach { r { store.appendChange(serial, change(it)) } }
        assertEquals(listOf("1", "2", "3"), r { store.load(serial) }.changes.map { it.id })
    }

    @Test fun removeChangesDropsOnlyThoseIdsAndKeepsLock() {
        r { store.acquireLock(serial, "Pixel 8") }
        listOf("1", "2", "3").forEach { r { store.appendChange(serial, change(it)) } }
        r { store.removeChanges(serial, setOf("2", "missing")) }
        val s = r { store.load(serial) }
        assertEquals(listOf("1", "3"), s.changes.map { it.id })
        assertEquals("Pixel 8", s.lock?.byPhone)
    }

    @Test fun upsertChangeReplacesInPlaceOrAppends() {
        listOf("1", "2").forEach { r { store.appendChange(serial, change(it)) } }
        r { store.upsertChange(serial, change("1").copy(verified = false)) }
        r { store.upsertChange(serial, change("3")) }
        val changes = r { store.load(serial) }.changes
        assertEquals(listOf("1", "2", "3"), changes.map { it.id })
        assertEquals(listOf(false, true, true), changes.map { it.verified })
    }

    @Test fun removeChangesWithNothingToRemoveWritesNothing() {
        r { store.appendChange(serial, change("1")) }
        fake.log.clear()
        r { store.removeChanges(serial, setOf("nope")) }
        assertEquals(listOf("cat $file"), fake.log)
    }

    @Test fun corruptedFileIsKeptAndStateStartsFresh() {
        fs[file] = "{not json"
        clock = 42
        assertEquals(TvState(serial = serial), r { store.load(serial) })
        assertEquals("{not json", fs["$file.bad-42"])
        assertNull(fs[file])
    }

    // --- a file from a newer version of the app is never read as empty or rewritten ---------------------------

    private fun assertTooNewAndUntouched(text: String, block: suspend () -> Unit) {
        fs[file] = text
        fake.log.clear()
        try { r { block() }; fail("expected TvStateTooNewException") } catch (_: TvStateTooNewException) {}
        assertEquals(text, fs[file])
        assertEquals(setOf(file), fs.keys)
        assertTrue(fake.log.none { it.startsWith("echo ") || it.startsWith("mv ") || it.startsWith("rm ") })
    }

    @Test fun newerVersionThrowsAndLeavesTheFileUntouched() {
        val v2 = """{"version":2,"serial":"SER1","changes":[],"newThing":{"a":1}}"""
        assertTooNewAndUntouched(v2) { store.load(serial) }
        assertTooNewAndUntouched(v2) { store.appendChange(serial, change("1")) }
        assertTooNewAndUntouched(v2) { store.acquireLock(serial, "Pixel 8") }
        assertTooNewAndUntouched(v2) { store.releaseLock(serial, "Pixel 8") }
        assertTooNewAndUntouched(v2) { store.setOriginalIfAbsent(serial, rp()) }
    }

    @Test fun newerVersionForAnotherTvIsAlsoNeverSetAside() {
        assertTooNewAndUntouched("""{"version":3,"serial":"OTHER"}""") { store.load(serial) }
    }

    @Test fun unknownChangeKindThrowsAndLeavesTheFileUntouched() {
        val future = """{"version":1,"serial":"SER1","changes":[{"id":"x","kind":"FREEZE","pkg":"a.b","from":"on","to":"frozen","byPhone":"P","at":1,"verified":true}]}"""
        assertTooNewAndUntouched(future) { store.load(serial) }
        assertTooNewAndUntouched(future) { store.removeChanges(serial, setOf("x")) }
    }

    @Test fun validJsonOfOurShapeThatDoesNotDecodeIsNeverSetAside() {
        // e.g. a field changed type in a later version without a version bump; or a non-numeric version.
        assertTooNewAndUntouched("""{"version":1,"serial":"SER1","lock":"held"}""") { store.load(serial) }
        assertTooNewAndUntouched("""{"version":"two","serial":"SER1"}""") { store.load(serial) }
    }

    @Test fun versionOneAndAMissingVersionStillLoad() {
        fs[file] = """{"serial":"SER1","changes":[]}"""
        assertEquals(TvState(serial = serial), r { store.load(serial) })
        fs[file] = """{"version":1,"serial":"SER1"}"""
        assertEquals(TvState(serial = serial), r { store.load(serial) })
    }

    @Test fun jsonThatIsNotOurFileIsSetAside() {
        for ((i, text) in listOf("""{"hello":"world"}""", "[1,2,3]", "\"text\"", """{"serial":5}""").withIndex()) {
            fs.clear()
            fs[file] = text
            clock = 100L + i
            assertEquals(TvState(serial = serial), r { store.load(serial) })
            assertEquals(text, fs["$file.bad-${100 + i}"])
            assertNull(fs[file])
        }
    }

    @Test fun requireNotTooNewIsReadOnly() {
        fs[file] = """{"version":2,"serial":"SER1"}"""
        try { r { store.requireNotTooNew(serial) }; fail("expected TvStateTooNewException") } catch (_: TvStateTooNewException) {}
        fs[file] = "{not json"
        r { store.requireNotTooNew(serial) } // garbage is not "too new"; nothing is set aside by a check
        assertEquals("{not json", fs[file])
        fs.remove(file)
        r { store.requireNotTooNew(serial) }
        assertTrue(fake.log.none { it.startsWith("echo ") || it.startsWith("mv ") || it.startsWith("rm ") })
    }

    @Test fun otherTvsFileIsKeptAndStateStartsFresh() {
        val foreign = TvState(serial = "OTHER", changes = listOf(change("1")))
        r { store.save(foreign) }
        clock = 77
        assertEquals(TvState(serial = serial), r { store.load(serial) })
        assertNotNull(fs["$file.bad-77"])
        assertNull(fs[file])
    }

    @Test fun failedWriteStepThrows() {
        fake.onCommand = { cmd -> fake.exitCodes[cmd] = if (cmd.startsWith("mv $tmp.")) 1 else run(cmd) }
        try { r { store.save(TvState(serial = serial)) }; fail("expected TvStateWriteException") }
        catch (e: TvStateWriteException) { assertEquals("mv", e.step) }
    }

    @Test fun failedChunkWriteThrows() {
        fake.onCommand = { cmd -> fake.exitCodes[cmd] = if (cmd.startsWith("echo ")) 1 else run(cmd) }
        try { r { store.save(TvState(serial = serial)) }; fail("expected TvStateWriteException") }
        catch (e: TvStateWriteException) { assertEquals("write", e.step) }
    }

    @Test fun adbErrorPropagates() {
        fake.failNext("cat $file", AdbError.Disconnected())
        try { r { store.load(serial) }; fail("expected AdbError") } catch (_: AdbError.Disconnected) {}
    }

    @Test fun permissionDeniedOnReadThrowsAndWritesNothing() {
        fs[file] = "precious"
        fake.onCommand = { cmd -> if (cmd.startsWith("cat ")) { fake.responses[cmd] = "cat: $file: Permission denied"; fake.exitCodes[cmd] = 1 } else fake.exitCodes[cmd] = run(cmd) }
        try { r { store.appendChange(serial, change("1")) }; fail("expected read exception") } catch (_: TvStateReadException) {}
        assertEquals("precious", fs[file])
        assertTrue(fake.log.none { it.startsWith("echo ") || it.startsWith("mv ") })
    }

    @Test fun failedSetAsideThrowsAndLeavesFileUntouched() {
        fs[file] = "{not json"
        fake.onCommand = { cmd -> fake.exitCodes[cmd] = if (cmd.startsWith("mv ")) 1 else run(cmd) }
        try { r { store.load(serial) }; fail("expected write exception") } catch (e: TvStateWriteException) { assertEquals("set-aside", e.step) }
        assertEquals("{not json", fs[file])
    }

    @Test fun interleavedSavesFromTwoPhonesEachProduceValidFile() {
        val other = TvStateStore(fake) { clock }
        val a = TvState(serial = serial, changes = (1..400).map { change("a$it") })
        val b = TvState(serial = serial, changes = listOf(change("b")))
        var fired = false
        fake.onCommand = { cmd ->
            fake.exitCodes[cmd] = run(cmd)
            if (!fired && cmd.startsWith("echo ")) { fired = true; r { other.save(b) } }
        }
        r { store.save(a) }
        assertEquals(a, r { store.load(serial) }) // A's mv came last
        val temps = fake.log.filter { it.startsWith("mv ") && it.endsWith(" $file") }.map { it.split(" ")[1] }
        assertEquals(2, temps.toSet().size)
        assertTrue(fs.keys.none { it.contains(".tmp") })
    }

    @Test fun failedStepCleansUpItsTempFile() {
        fake.onCommand = { cmd -> fake.exitCodes[cmd] = if (cmd.startsWith("mv ")) 1 else run(cmd) }
        try { r { store.save(TvState(serial = serial)) } } catch (_: TvStateWriteException) {}
        assertTrue(fake.log.last().startsWith("rm -f $tmp."))
        assertTrue(fs.keys.none { it.contains(".tmp") })
    }

    @Test fun unicodePhoneNamesRoundTrip() {
        val name = "Mithun's Pixel \u2728"
        r { store.acquireLock(serial, name) }.getOrThrow()
        r { store.appendChange(serial, change("1").copy(byPhone = name)) }
        val s = r { store.load(serial) }
        assertEquals(name, s.lock!!.byPhone)
        assertEquals(name, s.changes.single().byPhone)
    }
}
