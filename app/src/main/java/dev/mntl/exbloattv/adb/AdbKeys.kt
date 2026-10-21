package dev.mntl.exbloattv.adb

import dadb.AdbKeyPair
import java.io.File
import java.util.Base64

object AdbKeys {
    /**
     * Returns the key pair stored as `adbkey` / `adbkey.pub` in [dir], creating it on first use.
     * [name] becomes the comment of the public key, which is what the TV shows in its approval dialog.
     * Reusing the same key keeps the TV's "always allow" approval valid across app launches.
     *
     * A pair that can't be read (a cut-off write, a disk error) is renamed to `*.bad-<time>` and replaced by a new
     * one, so connecting keeps working; the TV then asks once to approve this phone again.
     */
    fun loadOrCreate(dir: File, name: String, now: () -> Long = System::currentTimeMillis): AdbKeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (priv.isFile && pub.isFile) {
            readOrNull(priv, pub)?.let { return it }
            val stamp = now()
            priv.renameTo(File(dir, "adbkey.bad-$stamp"))
            pub.renameTo(File(dir, "adbkey.pub.bad-$stamp"))
        }
        dir.mkdirs()
        AdbKeyPair.generate(priv, pub)
        val encoded = pub.readText().trim().substringBefore(' ')
        pub.writeText("$encoded $name\n")
        return AdbKeyPair.read(priv, pub)
    }

    private fun readOrNull(priv: File, pub: File): AdbKeyPair? = try {
        // dadb sends the public key to the TV as it is; a damaged one would never get an approval prompt.
        val key = Base64.getDecoder().decode(pub.readText().trim().substringBefore(' '))
        if (key.size == ADB_RSA_PUBLIC_KEY_BYTES) AdbKeyPair.read(priv, pub) else null
    } catch (e: Exception) {
        null
    }

    /** Android's RSA-2048 public key format: length, n0inv, modulus, R^2 mod n, exponent. */
    private const val ADB_RSA_PUBLIC_KEY_BYTES = 4 + 4 + 256 + 256 + 4
}
