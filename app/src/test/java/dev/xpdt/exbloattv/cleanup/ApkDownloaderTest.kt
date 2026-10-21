package dev.xpdt.exbloattv.cleanup

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = sha256(bytes)
    private lateinit var cache: File
    private lateinit var downloader: ApkDownloader

    @Before fun setUp() {
        server.start()
        cache = tmp.newFolder("cache")
        downloader = ApkDownloader(OkHttpClient(), cache)
    }

    @After fun tearDown() = server.close()

    private fun serve(body: ByteArray, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(Buffer().write(body)).build())

    private fun url() = server.url("/flauncher.apk").toString()

    @Test fun acceptsMatchingChecksumAndStoresUnderHashName() = runTest {
        serve(bytes)
        val f = downloader.fetch(url(), sha)
        assertThat(f.name).isEqualTo("$sha.apk")
        assertThat(f.parentFile).isEqualTo(cache)
        assertThat(f.readBytes()).isEqualTo(bytes)
        assertThat(cache.list()!!.toList()).containsExactly("$sha.apk")
    }

    @Test fun rejectsWrongChecksumAndLeavesNoFiles() = runTest {
        serve(bytes + byteArrayOf(1))
        val e = thrown<ChecksumMismatch> { downloader.fetch(url(), sha) }
        assertThat(e.expected).isEqualTo(sha)
        assertThat(e.actual).isEqualTo(sha256(bytes + byteArrayOf(1)))
        assertThat(cache.list()!!.toList()).isEmpty()
    }

    @Test fun reusesCachedFileWithoutNetworkWhenHashStillMatches() = runTest {
        serve(bytes)
        downloader.fetch(url(), sha)
        val again = downloader.fetch(url(), sha.uppercase())
        assertThat(again.readBytes()).isEqualTo(bytes)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun redownloadsWhenCachedFileIsCorrupted() = runTest {
        File(cache, "$sha.apk").writeBytes(byteArrayOf(9, 9, 9))
        serve(bytes)
        val f = downloader.fetch(url(), sha)
        assertThat(f.readBytes()).isEqualTo(bytes)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(cache.list()!!.toList()).containsExactly("$sha.apk")
    }

    @Test fun httpErrorThrowsAndLeavesNoFiles() = runTest {
        serve(ByteArray(0), code = 404)
        val e = thrown<IOException> { downloader.fetch(url(), sha) }
        assertThat(e).isNotInstanceOf(ChecksumMismatch::class.java)
        assertThat(cache.list()!!.toList()).isEmpty()
    }

    @Test fun malformedHashIsRejectedBeforeAnyRequest() = runTest {
        thrown<IllegalArgumentException> { downloader.fetch(url(), "abc") }
        assertThat(server.requestCount).isEqualTo(0)
    }

    private suspend inline fun <reified T : Throwable> thrown(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
