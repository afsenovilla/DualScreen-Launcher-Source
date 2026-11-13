package com.dualscreen.launcher

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_CAPTURED_GPU_DRIVER = "turnip26.2.0"

data class LauncherSettings(
    val keepScreenAwake: Boolean = true,
    val swapFaceButtons: Boolean = true,
    val resolution: GameResolution = GameResolution.HD,
    val gpuDriverId: String = DEFAULT_CAPTURED_GPU_DRIVER,
)

enum class GameResolution(
    val containerValue: String,
    val width: Int,
    val height: Int,
    val label: String,
) {
    HD("1280x720", 1280, 720, "1280 × 720"),
    HD_PLUS("1600x900", 1600, 900, "1600 × 900"),
    FULL_HD("1920x1080", 1920, 1080, "1920 × 1080");

    companion object {
        fun fromStorage(value: String?): GameResolution =
            entries.firstOrNull { it.containerValue == value } ?: HD
    }
}

/** Launcher-only preferences. Container and compatibility settings stay locked to the tested preset. */
class LauncherSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = mutableSettings.asStateFlow()

    fun setKeepScreenAwake(enabled: Boolean) = update(
        mutableSettings.value.copy(keepScreenAwake = enabled),
    )

    fun setSwapFaceButtons(enabled: Boolean) = update(
        mutableSettings.value.copy(swapFaceButtons = enabled),
    )

    fun setResolution(resolution: GameResolution) = update(
        mutableSettings.value.copy(resolution = resolution),
    )

    fun setGpuDriver(driverId: String) = update(
        mutableSettings.value.copy(
            gpuDriverId = driverId.ifBlank { DEFAULT_CAPTURED_GPU_DRIVER },
        ),
    )

    private fun update(value: LauncherSettings) {
        preferences.edit()
            .putBoolean(KEY_KEEP_AWAKE, value.keepScreenAwake)
            .putBoolean(KEY_SWAP_FACE, value.swapFaceButtons)
            .putString(KEY_RESOLUTION, value.resolution.containerValue)
            .putString(KEY_GPU_DRIVER, value.gpuDriverId)
            .apply()
        mutableSettings.value = value
    }

    private fun read() = LauncherSettings(
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_AWAKE, true),
        swapFaceButtons = preferences.getBoolean(KEY_SWAP_FACE, true),
        resolution = GameResolution.fromStorage(preferences.getString(KEY_RESOLUTION, null)),
        gpuDriverId = preferences.getString(KEY_GPU_DRIVER, DEFAULT_CAPTURED_GPU_DRIVER)
            ?.ifBlank { DEFAULT_CAPTURED_GPU_DRIVER }
            ?: DEFAULT_CAPTURED_GPU_DRIVER,
    )

    private companion object {
        const val PREFERENCES_NAME = "launcher-settings"
        const val KEY_KEEP_AWAKE = "keep-screen-awake"
        const val KEY_SWAP_FACE = "swap-face-buttons"
        const val KEY_RESOLUTION = "game-resolution"
        const val KEY_GPU_DRIVER = "gpu-driver"
    }
}
