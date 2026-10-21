package dev.mntl.exbloattv.cleanup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The downloaded file is not the one we expected; it was deleted and must not be installed. */
class ChecksumMismatch(val expected: String, val actual: String) :
    IOException("downloaded file has SHA-256 $actual, expected $expected")

/**
 * Downloads an APK into [cacheDir] and only ever returns a file whose SHA-256 matches. The file is streamed to a
 * temporary `.part` file, hashed while it is written, and atomically renamed to `<sha256>.apk`; a cached file is
 * reused only if it still hashes correctly. Partial and mismatching files are deleted. This is the app's only
 * network access.
 */
open class ApkDownloader(private val http: OkHttpClient, private val cacheDir: File) {

    open suspend fun fetch(url: String, sha256: String): File {
        val expected = sha256.lowercase()
        require(SHA256_HEX.matches(expected)) { "not a SHA-256 hex string: $sha256" }
        return withContext(Dispatchers.IO) {
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) throw IOException("cannot create ${cacheDir.path}")
            val target = File(cacheDir, "$expected.apk")
            if (target.isFile) {
                if (hashOf(target) == expected) return@withContext target
                target.delete()
            }
            val part = File.createTempFile("download-", ".part", cacheDir)
            try {
                val actual = download(url, part)
                if (actual != expected) throw ChecksumMismatch(expected, actual)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
        }
    }

    /** Streams [url] into [into] and returns the SHA-256 of what was written. */
    private suspend fun download(url: String, into: File): String {
        http.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("download failed: HTTP ${response.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            response.body.byteStream().use { input ->
                into.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            return digest.digest().toHex()
        }
    }

    private fun hashOf(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** Cancelling the coroutine cancels the HTTP call. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, r, _ -> r.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        })
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
