package dev.mntl.exbloattv.state

import dev.mntl.exbloattv.tv.AnimationScales
import kotlinx.serialization.Serializable

@Serializable
data class RestorePoint(
    val takenAt: Long,
    val byPhone: String,
    val enabled: List<String>,
    val disabled: List<String>,
    val userInstalled: List<String>,
    val resolvedHome: String?,
    val animations: AnimationScales,
    val backgroundOps: Map<String, String>,
)

@Serializable
enum class ChangeKind { DISABLE, ENABLE, REMOVE_FOR_USER, INSTALL_EXISTING, UNINSTALL, ANIMATIONS, APPOPS, INSTALL_APK, LAUNCHER_SWAP }

@Serializable
data class Change(
    val id: String,
    val kind: ChangeKind,
    val pkg: String?,
    val from: String,
    val to: String,
    val byPhone: String,
    val at: Long,
    val verified: Boolean,
)

@Serializable
data class Lock(val byPhone: String, val at: Long)

@Serializable
data class TvState(
    val version: Int = 1,
    val serial: String,
    val original: RestorePoint? = null,
    val latest: RestorePoint? = null,
    val changes: List<Change> = emptyList(),
    val lock: Lock? = null,
)
