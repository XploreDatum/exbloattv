package dev.mntl.exbloattv.apps

import dev.mntl.exbloattv.adb.AdbClient
import dev.mntl.exbloattv.adb.AdbError
import dev.mntl.exbloattv.catalog.CategorizedApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppLabel(val name: String, val iconPng: File?)

/**
 * Gives each TV app a readable name and, when cheap enough, its real icon.
 *
 * [parseApk] reads a pulled APK on the phone and returns its label plus an icon PNG file (the real
 * implementation uses PackageManager.getPackageArchiveInfo and lives in the UI wiring).
 */
class AppLabelResolver(
    private val cacheDir: File,
    private val parseApk: (File) -> AppLabel?,
) {

    suspend fun resolve(adb: AdbClient, app: CategorizedApp, apkSizeBytes: Long?): AppLabel {
        val pkg = app.pkg.name
        val fallbackName = app.name.ifBlank { prettify(pkg) }
        // A name equal to the bare last package segment came from the fallback, not the catalog.
        val catalogNamed = app.name != pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return try {
            val version = versionCode(adb, pkg)
            val icon = File(File(cacheDir, "icons"), "$pkg-$version.png")
            if (icon.isFile && icon.length() > 0) return AppLabel(fallbackName, icon)

            val size = apkSizeBytes ?: statSize(adb, app.pkg.apkPath)
            if (size == null || size > MAX_APK_BYTES) return AppLabel(fallbackName, null)

            val parsed = pullAndParse(adb, app.pkg.apkPath, pkg, icon) ?: return AppLabel(fallbackName, null)
            AppLabel(if (catalogNamed) fallbackName else parsed.name.ifBlank { fallbackName }, parsed.iconPng)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbError) {
            AppLabel(fallbackName, null)
        }
    }

    private suspend fun versionCode(adb: AdbClient, pkg: String): Long =
        Regex("versionCode=(\\d+)")
            .find(adb.shell("dumpsys package $pkg | grep -m1 versionCode").output)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private suspend fun statSize(adb: AdbClient, apkPath: String): Long? =
        adb.shell("stat -c %s $apkPath").output.trim().toLongOrNull()

    private suspend fun pullAndParse(adb: AdbClient, remote: String, pkg: String, target: File): AppLabel? {
        cacheDir.mkdirs()
        val local = File.createTempFile("apk-$pkg-", ".apk", cacheDir)
        try {
            adb.pull(remote, local)
            return withContext(Dispatchers.Default) {
                val parsed = try { parseApk(local) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    ?: return@withContext null
                val png = parsed.iconPng ?: return@withContext AppLabel(parsed.name, null)
                target.parentFile!!.mkdirs()
                if (png != target) { png.copyTo(target, overwrite = true); png.delete() }
                AppLabel(parsed.name, target)
            }
        } catch (e: java.io.IOException) {
            return null
        } finally {
            local.delete()
        }
    }

    companion object {
        const val MAX_APK_BYTES = 40L * 1024 * 1024

        /** "com.mxtech.videoplayer.pro" -> "Videoplayer Pro" (drops the vendor prefix). */
        fun prettify(pkg: String): String {
            val parts = pkg.split('.').filter { it.isNotBlank() }
            val words = if (parts.size > 2) parts.drop(2) else parts.takeLast(1)
            return words.flatMap { it.split('_') }.filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                .ifBlank { pkg }
        }
    }
}
