package dev.mntl.exbloattv.adb

import dadb.AdbKeyPair
import java.io.File

object AdbKeys {
    /**
     * Returns the key pair stored as `adbkey` / `adbkey.pub` in [dir], creating it on first use.
     * [name] becomes the comment of the public key, which is what the TV shows in its approval dialog.
     * Reusing the same key keeps the TV's "always allow" approval valid across app launches.
     */
    fun loadOrCreate(dir: File, name: String): AdbKeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.isFile || !pub.isFile) {
            dir.mkdirs()
            AdbKeyPair.generate(priv, pub)
            val encoded = pub.readText().trim().substringBefore(' ')
            pub.writeText("$encoded $name\n")
        }
        return AdbKeyPair.read(priv, pub)
    }
}
