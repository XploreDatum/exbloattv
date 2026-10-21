package dev.xpdt.exbloattv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import dev.xpdt.exbloattv.apps.AppLabel
import java.io.File

/**
 * Reads the label and icon of an APK pulled from the TV, using the phone's own PackageManager. The icon is rendered
 * to a PNG in [outDir] ([dev.xpdt.exbloattv.apps.AppLabelResolver] moves it into its cache).
 */
class ApkArchiveParser(private val context: Context, private val outDir: File) {

    fun parse(apk: File): AppLabel? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        // Without these the label and icon resources can't be found in an APK that isn't installed.
        app.sourceDir = apk.path
        app.publicSourceDir = apk.path
        val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault("")
        val png = runCatching { app.loadIcon(pm) }.getOrNull()?.let { toPng(it) }
        return AppLabel(label, png)
    }

    private fun toPng(d: Drawable): File? = runCatching {
        val size = ICON_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        outDir.mkdirs()
        val f = File.createTempFile("icon-", ".png", outDir)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        f
    }.getOrNull()

    private companion object {
        const val ICON_PX = 128
    }
}
