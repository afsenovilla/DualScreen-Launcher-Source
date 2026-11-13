package com.dualscreen.launcher

import android.app.Application
import android.content.Context
import android.net.Uri
import com.thor.core.common.log.ThorLog
import com.wemu.engine.contents.AdrenotoolsManager
import com.wemu.engine.contents.BundledContents
import com.wemu.engine.core.DefaultVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A Vulkan driver the Launcher can safely place in its tested container preset. */
data class LauncherGpuDriver(
    val id: String,
    val label: String,
    val source: Source,
) {
    enum class Source { SYSTEM, BUNDLED, UPLOADED }
}

/** User-visible state for the document-picker driver import. */
sealed interface GpuDriverImportState {
    data object Idle : GpuDriverImportState
    data object Importing : GpuDriverImportState
    data class Installed(val driver: LauncherGpuDriver) : GpuDriverImportState
    data class Failed(val message: String) : GpuDriverImportState
}

/** Progress for a PC driver downloaded from the shared AdrenoTools catalog. */
data class PcGpuDriverOperationState(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val detail: String = "",
    val error: String? = null,
)

/**
 * The Launcher's small, safe view over the engine's AdrenoTools driver manager.
 *
 * Archive validation and extraction deliberately stay in [AdrenotoolsManager]:
 * it rejects path traversal, links, missing metadata/libraries, excessive entry
 * counts and packages that expand beyond its limit. The launcher never opens or
 * unpacks an uploaded archive itself.
 */
class LauncherGpuDrivers(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val manager = AdrenotoolsManager(appContext)
    private val repository = GpuDriverRepository(appContext as Application)
    private val repositoryPreferences =
        appContext.getSharedPreferences("pc_gpu_driver_catalog", Context.MODE_PRIVATE)
    private val repositoryLock = Mutex()

    private val _drivers = MutableStateFlow<List<LauncherGpuDriver>>(emptyList())
    val drivers: StateFlow<List<LauncherGpuDriver>> = _drivers.asStateFlow()

    private val _importState = MutableStateFlow<GpuDriverImportState>(GpuDriverImportState.Idle)
    val importState: StateFlow<GpuDriverImportState> = _importState.asStateFlow()

    private val _operation = MutableStateFlow(PcGpuDriverOperationState())
    val operation: StateFlow<PcGpuDriverOperationState> = _operation.asStateFlow()

    suspend fun refresh() {
        _drivers.value = withContext(io) { readInstalled() }
    }

    /** Installs a selected AdrenoTools ZIP and returns its validated driver id. */
    suspend fun install(uri: Uri): String? {
        _importState.value = GpuDriverImportState.Importing
        val id = withContext(io) {
            runCatching { manager.installDriver(uri) }
                .onFailure { ThorLog.e(TAG, "Could not import GPU driver $uri", it) }
                .getOrDefault("")
        }
        refresh()

        val installed = _drivers.value.firstOrNull { it.id == id }
        return if (id.isNotBlank() && installed != null) {
            _importState.value = GpuDriverImportState.Installed(installed)
            id
        } else {
            _importState.value = GpuDriverImportState.Failed(
                manager.lastInstallError.ifBlank {
                    "That file is not a valid AdrenoTools driver ZIP."
                },
            )
            null
        }
    }

    fun clearImportMessage() {
        if (_importState.value !is GpuDriverImportState.Importing) {
            _importState.value = GpuDriverImportState.Idle
        }
    }

    suspend fun fetchReleases(): Result<List<GpuDriverRelease>> = runCatching {
        repositoryLock.withLock {
            repository.fetch(installedRepositoryArtifacts())
        }
    }

    /** Downloads, validates, installs, and returns the selected PC driver id. */
    suspend fun downloadAndInstall(artifact: GpuDriverArtifact): Result<String> =
        repositoryLock.withLock {
            _operation.value = PcGpuDriverOperationState(
                running = true,
                detail = "Downloading ${artifact.name}",
            )
            var installedId = ""
            runCatching {
                repository.downloadAndInstall(
                    artifact = artifact,
                    onProgress = { progress ->
                        _operation.value = _operation.value.copy(
                            running = true,
                            fraction = (progress * 0.85f).coerceIn(0f, 0.85f),
                            detail = "Downloading ${artifact.name}",
                            error = null,
                        )
                    },
                    installer = { uri ->
                        _operation.value = _operation.value.copy(
                            fraction = 0.9f,
                            detail = "Validating PC GPU driver",
                        )
                        installedId = install(uri).orEmpty()
                        require(installedId.isNotBlank()) {
                            (importState.value as? GpuDriverImportState.Failed)?.message
                                ?: "The downloaded ZIP is not a valid PC GPU driver."
                        }
                    },
                )
                val artifacts = installedRepositoryArtifacts() + artifact.name.lowercase()
                check(
                    repositoryPreferences.edit()
                        .putStringSet(KEY_INSTALLED_ARTIFACTS, artifacts)
                        .commit(),
                ) { "The installed driver could not be recorded." }
                refresh()
                _operation.value = PcGpuDriverOperationState(
                    running = false,
                    fraction = 1f,
                    detail = "PC GPU driver installed",
                )
                installedId
            }.onFailure { failure ->
                _operation.value = PcGpuDriverOperationState(
                    running = false,
                    detail = "Driver installation failed",
                    error = failure.message ?: "Could not install the PC GPU driver.",
                )
            }
        }

    private fun installedRepositoryArtifacts(): Set<String> =
        repositoryPreferences.getStringSet(KEY_INSTALLED_ARTIFACTS, emptySet())
            ?.map(String::lowercase)
            ?.toSet()
            .orEmpty()

    private fun readInstalled(): List<LauncherGpuDriver> {
        val bundled = runCatching { BundledContents.drivers(appContext) }
            .onFailure { ThorLog.w(TAG, "Could not read bundled GPU drivers", it) }
            .getOrDefault(listOf(DefaultVersion.WRAPPER))
        val uploaded = runCatching { manager.enumarateInstalledDrivers().toList() }
            .onFailure { ThorLog.w(TAG, "Could not enumerate uploaded GPU drivers", it) }
            .getOrDefault(emptyList())

        return (bundled + uploaded)
            .distinct()
            .map { id ->
                val source = when {
                    id.equals(DefaultVersion.WRAPPER, ignoreCase = true) ->
                        LauncherGpuDriver.Source.SYSTEM
                    id in uploaded -> LauncherGpuDriver.Source.UPLOADED
                    else -> LauncherGpuDriver.Source.BUNDLED
                }
                LauncherGpuDriver(id = id, label = labelOf(id), source = source)
            }
    }

    private fun labelOf(id: String): String {
        if (id.equals(DefaultVersion.WRAPPER, ignoreCase = true)) return "System driver"
        val metadataName = manager.getDriverName(id)
        val version = manager.getDriverVersion(id)
        return when {
            metadataName.isNotBlank() && version.isNotBlank() &&
                !metadataName.contains(version, ignoreCase = true) -> "$metadataName · $version"
            metadataName.isNotBlank() -> metadataName
            id.startsWith("turnip", ignoreCase = true) ->
                "Turnip ${id.removePrefix("turnip")}".trim()
            else -> id
        }
    }

    private companion object {
        const val TAG = "LauncherGpuDrivers"
        const val KEY_INSTALLED_ARTIFACTS = "installedArtifacts"
    }
}
