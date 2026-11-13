package com.dualscreen.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ArchiveReviewState {
    data object Idle : ArchiveReviewState
    data class Inspecting(val gameId: String) : ArchiveReviewState
    data class VersionWarning(
        val gameId: String,
        val inspection: GameArchiveInspection,
    ) : ArchiveReviewState
    data class Rejected(val gameId: String, val message: String) : ArchiveReviewState
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DualScreenLauncherApplication
    val progress = app.installer.progress
    val saveProgress = app.saveImporter.progress
    val settings = app.settings.settings
    val launchStatus = app.launchStatus.status
    val gpuDrivers = app.gpuDrivers.drivers
    val gpuDriverImportState = app.gpuDrivers.importState
    val pcGpuDriverOperation = app.gpuDrivers.operation
    internal val payloadState = app.payloads.state
    internal val updateState = app.updates.state
    private var synchronizedPayloadVersion: String? = null
    private val refreshLock = Mutex()
    private val archiveOperationInFlight = AtomicBoolean(false)
    private var pendingUntestedArchive: Pair<SupportedGame, Uri>? = null
    private val mutableArchiveReview = MutableStateFlow<ArchiveReviewState>(ArchiveReviewState.Idle)
    val archiveReview: StateFlow<ArchiveReviewState> = mutableArchiveReview.asStateFlow()

    private val mutableGpuDriverMessage = MutableStateFlow<String?>(null)
    val gpuDriverMessage: StateFlow<String?> = mutableGpuDriverMessage.asStateFlow()

    private val mutableInstalled = MutableStateFlow(app.registry.all())
    val installed: StateFlow<Map<String, InstalledGame>> = mutableInstalled.asStateFlow()

    init {
        viewModelScope.launch { app.gpuDrivers.refresh() }
    }

    fun refreshUpdates() {
        viewModelScope.launch {
            refreshLock.withLock {
                app.payloads.ensureReady()
                synchronizeInstalledMods()
                // No automatic self-update: upstream releases are a different
                // (supporter-gated, multi-game) build signed with another key.
            }
        }
    }

    fun installLauncherUpdate() {
        viewModelScope.launch { app.updates.downloadAndInstall() }
    }

    fun requestLauncherUpdatePermission() = app.updates.requestInstallerPermission()

    fun retryLauncherUpdate() {
        checkAndInstallLauncherUpdate(force = true)
    }

    private fun checkAndInstallLauncherUpdate(force: Boolean) {
        viewModelScope.launch { checkAndInstallLauncherUpdateNow(force) }
    }

    private suspend fun checkAndInstallLauncherUpdateNow(force: Boolean) {
        app.updates.check(force)
        when (app.updates.state.value) {
            is LauncherUpdateState.Available,
            is LauncherUpdateState.PermissionRequired,
            -> app.updates.downloadAndInstall()
            else -> Unit
        }
    }

    private suspend fun synchronizeInstalledMods() {
        val ready = payloadState.value as? PayloadBootstrapState.Ready ?: return
        if (synchronizedPayloadVersion == ready.version) return
        app.installer.migrateLegacyInstalls()
        app.registry.all().values.forEach { installed ->
            GameCatalog.byId(installed.gameId)?.let { game ->
                app.installer.ensureCurrentPayload(game)
            }
        }
        mutableInstalled.value = app.registry.all()
        synchronizedPayloadVersion = ready.version
    }

    fun install(game: SupportedGame, archive: Uri) {
        if (payloadState.value !is PayloadBootstrapState.Ready) return
        if (progress.value.values.any(InstallProgress::running) ||
            saveProgress.value.values.any(SaveImportProgress::running)
        ) return
        if (!archiveOperationInFlight.compareAndSet(false, true)) return
        app.saveImporter.clear()
        mutableArchiveReview.value = ArchiveReviewState.Inspecting(game.id)
        viewModelScope.launch {
            val inspection = app.installer.inspectArchive(game, archive)
            val checked = inspection.getOrElse { failure ->
                mutableArchiveReview.value = ArchiveReviewState.Rejected(
                    game.id,
                    failure.message ?: "The selected ZIP could not be validated",
                )
                archiveOperationInFlight.set(false)
                return@launch
            }
            if (checked.requiresVersionConfirmation) {
                pendingUntestedArchive = game to archive
                mutableArchiveReview.value = ArchiveReviewState.VersionWarning(game.id, checked)
                return@launch
            }
            installReviewed(game, archive, allowUntestedVersion = false)
        }
    }

    fun continueUntestedInstall() {
        val pending = pendingUntestedArchive ?: return
        pendingUntestedArchive = null
        mutableArchiveReview.value = ArchiveReviewState.Idle
        viewModelScope.launch {
            installReviewed(pending.first, pending.second, allowUntestedVersion = true)
        }
    }

    fun dismissArchiveReview() {
        pendingUntestedArchive = null
        mutableArchiveReview.value = ArchiveReviewState.Idle
        archiveOperationInFlight.set(false)
    }

    private suspend fun installReviewed(
        game: SupportedGame,
        archive: Uri,
        allowUntestedVersion: Boolean,
    ) {
        try {
            mutableArchiveReview.value = ArchiveReviewState.Idle
            app.installer.install(game, archive, allowUntestedVersion)
            mutableInstalled.value = app.registry.all()
        } finally {
            archiveOperationInFlight.set(false)
        }
    }

    fun importSave(game: SupportedGame, installed: InstalledGame, archive: Uri) {
        if (progress.value.values.any(InstallProgress::running) ||
            saveProgress.value.values.any(SaveImportProgress::running)
        ) return
        if (!archiveOperationInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                app.saveImporter.import(game, installed, archive)
            } finally {
                archiveOperationInFlight.set(false)
            }
        }
    }

    fun clearSaveImport() = app.saveImporter.clear()

    fun setKeepScreenAwake(enabled: Boolean) = app.settings.setKeepScreenAwake(enabled)

    fun setResolution(resolution: GameResolution) = app.settings.setResolution(resolution)

    fun setGpuDriver(driverId: String) {
        viewModelScope.launch {
            mutableGpuDriverMessage.value = "Applying GPU driver..."
            val applied = runCatching {
                PresetContainerManager(app).applyGpuDriver(driverId)
            }.getOrDefault(false)
            if (applied) {
                app.settings.setGpuDriver(driverId)
                mutableGpuDriverMessage.value = "GPU driver will be used for the next game launch."
            } else {
                mutableGpuDriverMessage.value = "Could not apply that GPU driver."
            }
        }
    }

    fun importGpuDriver(uri: Uri) {
        viewModelScope.launch {
            val driverId = app.gpuDrivers.install(uri) ?: return@launch
            setGpuDriver(driverId)
        }
    }

    suspend fun fetchPcGpuDrivers(): Result<List<GpuDriverRelease>> =
        app.gpuDrivers.fetchReleases()

    fun downloadPcGpuDriver(artifact: GpuDriverArtifact) {
        viewModelScope.launch {
            app.gpuDrivers.downloadAndInstall(artifact)
                .onSuccess(::setGpuDriver)
        }
    }

    fun retryPayloadBootstrap() {
        viewModelScope.launch {
            refreshLock.withLock {
                app.payloads.ensureReady(force = true)
                synchronizedPayloadVersion = null
                synchronizeInstalledMods()
            }
        }
    }
}
