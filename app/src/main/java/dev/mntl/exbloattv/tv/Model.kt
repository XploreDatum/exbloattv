package dev.mntl.exbloattv.tv

import kotlinx.serialization.Serializable

@Serializable
data class TvInfo(
    val serial: String,
    val model: String,
    val brand: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
)

@Serializable
data class InstalledPackage(
    val name: String,
    val apkPath: String,
    val installer: String?,
    val uid: Int,
    val isSystem: Boolean,
    val enabled: Boolean,
)

@Serializable
data class MemoryStats(val totalKb: Long, val freeKb: Long, val usedPssKb: Long)

@Serializable
data class StorageStats(val totalKb: Long, val usedKb: Long, val availableKb: Long)

@Serializable
data class AnimationScales(val window: Float, val transition: Float, val animator: Float)

@Serializable
data class Inventory(
    val info: TvInfo,
    val packages: List<InstalledPackage>,
    val launchable: Set<String>,
    val homePackages: List<String>,
    val resolvedHome: String?,
    /** null = the TV could not be read; show "—", never zero. */
    val memory: MemoryStats?,
    val storage: StorageStats?,
    val animations: AnimationScales,
    val backgroundOps: Map<String, String>,
)
