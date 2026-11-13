package com.dualscreen.launcher

import android.app.Application
import com.dualscreen.launcher.switch.EdenSwitchBackend

class DualScreenLauncherApplication : Application() {
    val settings by lazy { LauncherSettingsStore(this) }
    internal val payloads by lazy { PayloadBootstrapper(this) }
    internal val updates by lazy { LauncherUpdateChecker(this) }
    val gpuDrivers by lazy { LauncherGpuDrivers(this) }
    val registry by lazy { InstallRegistry(this) }
    val installer by lazy { GameInstaller(this, registry) }
    val saveImporter by lazy { SaveImporter(this) }
    val launchStatus by lazy { LaunchStatusStore(this) }
    val switchBackend by lazy { EdenSwitchBackend(this) }
}
