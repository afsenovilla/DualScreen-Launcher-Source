package com.dualscreen.launcher

internal enum class GameInstallState {
    NOT_INSTALLED,
    INSTALLING,
    UPDATING_MOD,
    IMPORTING_SAVE,
    READY,
    ERROR,
}

internal fun resolveGameInstallState(
    installed: InstalledGame?,
    progress: InstallProgress?,
    saveProgress: SaveImportProgress?,
): GameInstallState = when {
    installed != null && progress?.running == true && progress.stage in MOD_UPDATE_STAGES ->
        GameInstallState.UPDATING_MOD
    progress?.running == true -> GameInstallState.INSTALLING
    saveProgress?.running == true -> GameInstallState.IMPORTING_SAVE
    progress?.error != null || saveProgress?.error != null -> GameInstallState.ERROR
    installed != null -> GameInstallState.READY
    else -> GameInstallState.NOT_INSTALLED
}

private val MOD_UPDATE_STAGES = setOf(
    InstallStage.PATCHING,
    InstallStage.VERIFYING,
)

/**
 * A payload-only refresh enters the shared installer at PATCHING (82%) rather
 * than at zero. Normalize that narrow stage range so the library card reports
 * useful 0-100% mod-update progress instead of appearing to start at 82%.
 */
internal fun modUpdateFraction(progress: InstallProgress?): Float {
    if (progress == null) return 0f
    return when (progress.stage) {
        InstallStage.PATCHING -> {
            val start = InstallStage.PATCHING.baseProgress
            val end = InstallStage.CONFIGURING.baseProgress
            (((progress.fraction - start) / (end - start)) * 0.9f).coerceIn(0f, 0.9f)
        }
        InstallStage.VERIFYING -> 0.96f
        InstallStage.COMPLETE -> 1f
        else -> progress.fraction.coerceIn(0f, 1f)
    }
}

internal fun gameArtwork(gameId: String): Int = when (gameId) {
    "isaac-repentance" -> R.drawable.art_isaac
    else -> R.drawable.ic_launcher
}

internal fun gameShortTitle(gameId: String): String = when (gameId) {
    "isaac-repentance" -> "Binding of Isaac: Repentance+"
    else -> gameId
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toLong()} ${units[index]}"
    else "%.1f %s".format(value, units[index])
}
