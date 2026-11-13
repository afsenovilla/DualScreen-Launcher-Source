package com.dualscreen.launcher.switch

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dualscreen.launcher.DualScreenLauncherApplication
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.ByteSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.LongSetting
import org.yuzu.yuzu_emu.features.settings.model.ShortSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Backend boundary between Dualscreen Launcher's frontend and Eden's emulator runtime.
 * PC code never calls this class; all Eden state changes are serialized through [operationLock].
 */
class EdenSwitchBackend(private val application: Application) {
    private val registry = SwitchInstallRegistry(application)
    private val modPayloads = SwitchModPayloadManager(application)
    private val driverRepository = EdenDriverRepository(application)
    private val operationLock = Mutex()
    private val initializeLock = Mutex()

    private val mutableInstallations = MutableStateFlow(registry.all())
    val installations = mutableInstallations.asStateFlow()

    private val mutableOperation = MutableStateFlow(SwitchOperationState())
    val operation = mutableOperation.asStateFlow()

    private val mutableSystemState = MutableStateFlow(SwitchSystemState())
    val systemState = mutableSystemState.asStateFlow()

    suspend fun prepare(): Result<Unit> = runGlobalOperation("Initializing embedded Eden DS") {
        ensureInitialized()
        synchronizePayloadsLocked()
        refreshSystemState()
        updateOperation(1f, "Switch system ready")
    }

    suspend fun installKeys(uri: Uri): Result<Unit> = runGlobalOperation("Installing encryption keys") {
        ensureInitialized()
        persistReadPermission(uri)
        val ext = extension(uri).ifBlank { "keys" }
        require(NativeLibrary.installKeys(uri.toString(), ext) == 0) {
            "Eden rejected this key file. Select a valid prod.keys or keys archive."
        }
        NativeLibrary.reloadKeys()
        refreshSystemState()
        updateOperation(1f, "Keys installed")
    }

    suspend fun installFirmware(uri: Uri): Result<Unit> = runGlobalOperation("Validating firmware ZIP") {
        ensureInitialized()
        persistReadPermission(uri)
        val staging = File(application.cacheDir, "eden-firmware-${System.nanoTime()}")
        require(staging.mkdirs()) { "Could not create firmware staging." }
        try {
            var ncaCount = 0
            var extractedBytes = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(application.contentResolver.openInputStream(uri)).use { zip ->
                requireNotNull(zip) { "Could not read the firmware ZIP." }
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = entry.name.substringAfterLast('/')
                        require(fileName.endsWith(".nca", ignoreCase = true)) {
                            "Firmware ZIPs may only contain NCA files."
                        }
                        require(ncaCount < MAX_FIRMWARE_FILES) { "The firmware ZIP contains too many files." }
                        require(names.add(fileName.lowercase())) { "The firmware ZIP contains duplicate NCA names." }
                        val output = File(staging, fileName)
                        output.outputStream().use { destination ->
                            extractedBytes += copyLimited(
                                zip,
                                destination,
                                MAX_FIRMWARE_EXPANDED_BYTES - extractedBytes,
                                "Firmware ZIP",
                            )
                        }
                        ncaCount++
                    }
                    updateOperation((ncaCount.coerceAtMost(1000) / 1000f) * 0.7f, "Extracting firmware")
                    entry = zip.nextEntry
                }
            }
            require(ncaCount > 0) { "The firmware ZIP contains no NCA files." }
            val target = File(NativeConfig.getNandDir(), "system/Contents/registered")
            val backup = File(target.parentFile, "registered.dualscreen-backup")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) require(target.renameTo(backup)) { "Could not preserve existing firmware." }
            try {
                require(staging.copyRecursively(target, overwrite = true)) { "Could not install firmware." }
                NativeLibrary.initializeSystem(true)
                backup.deleteRecursively()
            } catch (failure: Throwable) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw failure
            }
        } finally {
            staging.deleteRecursively()
        }
        refreshSystemState()
        updateOperation(1f, "Firmware installed")
    }

    suspend fun installGpuDriver(uri: Uri): Result<Unit> = runGlobalOperation("Validating Switch GPU driver") {
        ensureInitialized()
        persistReadPermission(uri)
        require(extension(uri) == "zip") { "Select an AdrenoTools GPU driver ZIP." }
        validateDriverArchive(uri)
        require(GpuDriverHelper.copyDriverToInternalStorage(uri)) {
            "Eden rejected this GPU driver. The ZIP must contain valid meta.json metadata."
        }
        refreshSystemState()
        updateOperation(1f, "Switch GPU driver installed")
    }

    suspend fun fetchGpuDriverReleases(): Result<List<SwitchDriverRelease>> = runCatching {
        operationLock.withLock {
            ensureInitialized()
            driverRepository.fetch()
        }
    }

    suspend fun downloadGpuDriver(artifact: SwitchDriverArtifact): Result<Unit> =
        runGlobalOperation("Downloading ${artifact.name}") {
            ensureInitialized()
            driverRepository.downloadAndInstall(
                artifact = artifact,
                onProgress = { progress -> updateOperation(progress * 0.85f, "Downloading ${artifact.name}") },
                installer = { downloadedUri ->
                    updateOperation(0.88f, "Verifying downloaded driver")
                    validateDriverArchive(downloadedUri)
                    require(GpuDriverHelper.copyDriverToInternalStorage(downloadedUri)) {
                        "Eden rejected the downloaded driver ZIP."
                    }
                },
            )
            refreshSystemState()
            updateOperation(1f, "GPU driver installed")
        }

    suspend fun addBaseGame(game: SwitchGameDefinition, uri: Uri): Result<Unit> = runOperation(
        game = game,
        kind = SwitchContentKind.BASE,
        initialDetail = "Validating base game NSP",
    ) {
        persistReadPermission(uri)
        ensureInitialized()
        verifyReadableUri(uri)
        require(extension(uri) == "nsp") { "Select the base game NSP file." }
        updateOperation(0.18f, "Reading base game in protected Eden worker")
        val metadata = SwitchContentWorkerService.Client.inspectBase(application, uri)
        val detectedProgramId = metadata.programId.toLongOrNull()
        require(detectedProgramId == game.titleIdValue) {
            val actual = detectedProgramId?.let { "%016X".format(it) } ?: "unknown"
            "This NSP belongs to title $actual, not ${game.titleId}."
        }
        updateOperation(0.45f, "Reading game metadata")
        val title = metadata.title.ifBlank { game.title }
        val version = metadata.version.ifBlank { "Base" }
        val iconPath = persistGameIcon(game, metadata.icon)
        val installed = SwitchGameInstallation(
            gameId = game.id,
            baseUri = uri.toString(),
            displayName = documentName(uri) ?: "${game.title}.nsp",
            detectedTitle = title,
            baseVersion = version,
            iconPath = iconPath,
        )
        registry.put(installed)
        mutableInstallations.value = registry.all()
        modPayloads.synchronize(game, installed)
        refreshPatchesLocked(game)
        updateOperation(1f, "${game.title} is ready")
    }

    suspend fun installContent(
        game: SwitchGameDefinition,
        uri: Uri,
        expectedKind: SwitchContentKind,
    ): Result<Unit> = runOperation(
        game = game,
        kind = expectedKind,
        initialDetail = "Inspecting ${expectedKind.name.lowercase()} NSP",
    ) {
        require(expectedKind == SwitchContentKind.UPDATE || expectedKind == SwitchContentKind.DLC)
        require(registry.get(game.id) != null) { "Add the base game before installing content." }
        persistReadPermission(uri)
        ensureInitialized()
        verifyReadableUri(uri)
        require(extension(uri) == "nsp") { "Select an NSP package." }

        val wantedType = when (expectedKind) {
            SwitchContentKind.UPDATE -> TITLE_TYPE_UPDATE
            SwitchContentKind.DLC -> TITLE_TYPE_DLC
            else -> error("Unsupported content type")
        }
        updateOperation(0.12f, "Validating in protected Eden worker")
        val installed = requireNotNull(registry.get(game.id))
        val patches = SwitchContentWorkerService.Client.installContent(
            context = application,
            uri = uri,
            type = wantedType,
            baseTitleId = game.titleId,
            baseUri = installed.baseUri,
            programId = game.programId,
        ) { progress, detail ->
            updateOperation(0.12f + progress * 0.78f, detail)
        }
        updateOperation(0.93f, "Verifying active content")
        storePatches(installed, patches)
        updateOperation(1f, "${expectedKind.name.lowercase().replaceFirstChar(Char::uppercase)} installed")
    }

    suspend fun discoverModFolders(game: SwitchGameDefinition, treeUri: Uri): Result<List<SwitchModFolder>> = runOperation(
        game, SwitchContentKind.MOD, "Finding mods in selected folder",
    ) {
        persistReadPermission(treeUri)
        SwitchModImporter(application).discover(treeUri, game.titleId)
    }

    suspend fun installMods(game: SwitchGameDefinition, treeUri: Uri, selected: List<SwitchModFolder>): Result<Unit> = runOperation(
        game = game,
        kind = SwitchContentKind.MOD,
        initialDetail = "Validating mod folder",
    ) {
        registry.get(game.id) ?: error("Add the base game before installing mods.")
        persistReadPermission(treeUri)
        ensureInitialized()
        check(!NativeLibrary.isRunning()) { "Close the Switch game before installing mods." }
        val loadPath = NativeConfig.getLoadDir()
        require(loadPath.isNotBlank() && File(loadPath).isAbsolute) { "Eden's mod directory is unavailable." }
        val loadRoot = File(loadPath).canonicalFile
        require(loadRoot.parentFile != null) { "Eden's mod directory is unavailable." }
        SwitchModImporter(application).install(treeUri, game.titleId, selected, loadRoot) { fraction, detail ->
            updateOperation(fraction, detail)
        }
        refreshPatchesLocked(game)
        updateOperation(1f, "${selected.size} mod(s) installed")
    }

    suspend fun setPatchEnabled(
        game: SwitchGameDefinition,
        patchName: String,
        enabled: Boolean,
    ): Result<Unit> = runOperation(game, SwitchContentKind.MOD, "Updating mod state") {
        ensureInitialized()
        val disabled = NativeConfig.getDisabledAddons(game.programId).toMutableSet()
        if (enabled) disabled.remove(patchName) else disabled.add(patchName)
        NativeConfig.setDisabledAddons(game.programId, disabled.toTypedArray())
        NativeConfig.saveGlobalConfig()
        registry.get(game.id)?.let { installed ->
            storePatches(
                installed,
                installed.patches.map { patch ->
                    if (patch.kind == SwitchContentKind.MOD && patch.name == patchName) {
                        patch.copy(enabled = enabled)
                    } else {
                        patch
                    }
                },
            )
        }
        updateOperation(1f, if (enabled) "Mod enabled" else "Mod disabled")
    }

    suspend fun removePatch(game: SwitchGameDefinition, patch: SwitchPatch): Result<Unit> = runOperation(
        game,
        patch.kind,
        "Removing ${patch.name}",
    ) {
        ensureInitialized()
        val installed = registry.get(game.id) ?: error("Add the base game first.")
        val patches = SwitchContentWorkerService.Client.removeContent(
            context = application,
            baseUri = installed.baseUri,
            programId = game.programId,
            patch = patch,
        )
        storePatches(installed, patches)
        updateOperation(1f, "Content removed")
    }

    suspend fun refresh(game: SwitchGameDefinition): Result<Unit> = runOperation(
        game,
        null,
        "Refreshing game content",
    ) {
        ensureInitialized()
        refreshPatchesLocked(game)
        updateOperation(1f, "Content synchronized")
    }

    suspend fun readSettings(game: SwitchGameDefinition): Result<SwitchGameSettings> =
        runCatching {
            operationLock.withLock {
                ensureInitialized()
                withPerGameConfig(game) {
                    SwitchGameSettings(
                        values = readAllSettings(perGame = true),
                        selectedGpuDriverPath = StringSetting.DRIVER_PATH.getString(false),
                    )
                }
            }
        }

    suspend fun saveSettings(game: SwitchGameDefinition, settings: SwitchGameSettings): Result<Unit> =
        runOperation(game, null, "Saving Eden settings") {
            ensureInitialized()
            withPerGameConfig(game) {
                settings.values.forEach { writeSetting(it, perGame = true) }
                StringSetting.DRIVER_PATH.setString(settings.selectedGpuDriverPath)
                NativeConfig.savePerGameConfig()
            }
            updateOperation(1f, "Settings saved")
        }

    suspend fun selectGpuDriver(game: SwitchGameDefinition, driverPath: String): Result<Unit> =
        runOperation(game, null, "Applying GPU driver") {
            ensureInitialized()
            require(
                driverPath.isBlank() || GpuDriverHelper.getDrivers().any { (path, _) -> path == driverPath },
            ) { "That GPU driver is no longer installed." }
            val installed = registry.get(game.id) ?: error("Add the base game first.")
            val previous = withPerGameConfig(game) {
                StringSetting.DRIVER_PATH.getString(false).also {
                    StringSetting.DRIVER_PATH.setString(driverPath)
                    NativeConfig.savePerGameConfig()
                }
            }
            if (previous != driverPath) {
                val externalRoot = application.getExternalFilesDir(null)?.canonicalFile
                val shaderDirectory = externalRoot?.let { root ->
                    File(
                        root,
                        "shader/${Game(installed.detectedTitle, installed.baseUri, game.programId).settingsName.lowercase()}",
                    ).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
                }
                shaderDirectory?.takeIf(File::exists)?.deleteRecursively()
            }
            updateOperation(1f, "GPU driver applied")
        }

    suspend fun readGlobalSettings(): Result<SwitchGameSettings> = runCatching {
        operationLock.withLock {
            ensureInitialized()
            check(!NativeConfig.isPerGameConfigLoaded()) { "A per-game Eden configuration is still active." }
            SwitchGameSettings(
                values = readAllSettings(perGame = false),
                selectedGpuDriverPath = StringSetting.DRIVER_PATH.getString(false),
            )
        }
    }

    suspend fun saveGlobalSettings(settings: SwitchGameSettings): Result<Unit> =
        runGlobalOperation("Saving Eden global settings") {
            ensureInitialized()
            check(!NativeConfig.isPerGameConfigLoaded()) { "A per-game Eden configuration is still active." }
            settings.values.forEach { writeSetting(it, perGame = false) }
            StringSetting.DRIVER_PATH.setString(settings.selectedGpuDriverPath)
            NativeConfig.saveGlobalConfig()
            updateOperation(1f, "Global settings saved")
            refreshSystemState()
        }

    suspend fun selectGlobalGpuDriver(driverPath: String): Result<Unit> =
        runGlobalOperation("Applying Eden global GPU driver") {
            ensureInitialized()
            require(
                driverPath.isBlank() || GpuDriverHelper.getDrivers().any { (path, _) -> path == driverPath },
            ) { "That GPU driver is no longer installed." }
            check(!NativeConfig.isPerGameConfigLoaded()) { "A per-game Eden configuration is still active." }
            StringSetting.DRIVER_PATH.setString(driverPath)
            NativeConfig.saveGlobalConfig()
            updateOperation(1f, "Global GPU driver applied")
            refreshSystemState()
        }

    suspend fun createLaunchIntent(game: SwitchGameDefinition): Result<Intent> = runCatching {
        operationLock.withLock {
            ensureInitialized()
            val installed = registry.get(game.id) ?: error("Add the base game before pressing Play.")
            modPayloads.synchronize(game, installed)
            if (game.id == "pokemon-legends-za" || game.id == "breath-of-the-wild") {
                org.yuzu.yuzu_emu.companion.ExternalCompanionPackages.activate(application, game.titleId)
            }
            val model = Game(
                title = installed.detectedTitle.ifBlank { game.title },
                path = installed.baseUri,
                programId = game.programId,
                version = installed.baseVersion,
            )
            // NSP installation is isolated in :eden_content. Refresh this process's registered
            // NAND cache before launch or PatchManager can silently boot the stale base ExeFS.
            NativeLibrary.clearFilesystemProvider()
            GameHelper.restoreContentForGame(model)
            Log.i(
                TAG,
                "Launch content ${game.titleId} after registered-cache refresh: " +
                    installed.patches.joinToString {
                    "${it.kind}:${it.name}:${it.version}:enabled=${it.enabled}"
                },
            )
            model.launchIntent.apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("dualscreen_launcher_game_id", game.id)
            }
        }
    }

    suspend fun synchronizePayloads(): Result<Unit> = runGlobalOperation("Updating Switch mods") {
        if (registry.all().isNotEmpty()) {
            ensureInitialized()
            synchronizePayloadsLocked()
        }
        updateOperation(1f, "Switch mods are current")
    }

    private suspend fun synchronizePayloadsLocked() {
        registry.all().forEach { (gameId, installation) ->
            SwitchCatalog.byId(gameId)?.let { game ->
                if (modPayloads.synchronize(game, installation)) {
                    refreshPatchesLocked(game)
                }
            }
        }
    }

    suspend fun importSave(game: SwitchGameDefinition, archive: Uri): Result<Unit> = runOperation(
        game,
        null,
        "Validating save archive",
    ) {
        ensureInitialized()
        val installed = registry.get(game.id) ?: error("Add the base game first.")
        val target = File(Game(installed.detectedTitle, installed.baseUri, game.programId).saveDir)
        val staging = File(application.cacheDir, "switch-save-${game.id}-${System.nanoTime()}")
        require(staging.mkdirs()) { "Could not create save import staging." }
        try {
            ZipInputStream(application.contentResolver.openInputStream(archive)).use { zip ->
                requireNotNull(zip) { "Could not read the save ZIP." }
                var count = 0
                var extractedBytes = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    val output = File(staging, entry.name).canonicalFile
                    require(output.path.startsWith(staging.canonicalPath + File.separator)) {
                        "The save ZIP contains an unsafe path."
                    }
                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory) { "Could not create save folder." }
                    } else {
                        require(output.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
                            "Could not create save folder."
                        }
                        output.outputStream().use { destination ->
                            extractedBytes += copyLimited(
                                zip,
                                destination,
                                MAX_SAVE_EXPANDED_BYTES - extractedBytes,
                                "Save ZIP",
                            )
                        }
                        count++
                    }
                    if (count > 100_000) error("The save ZIP contains too many files.")
                    entry = zip.nextEntry
                }
                require(count > 0) { "The save ZIP is empty." }
            }
            val source = staging.listFiles()?.singleOrNull()?.takeIf(File::isDirectory) ?: staging
            val backup = File(target.parentFile, "${target.name}.dualscreen-backup")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) require(target.renameTo(backup)) { "Could not preserve the current save." }
            try {
                require(source.copyRecursively(target, overwrite = true)) { "Could not install the save data." }
                backup.deleteRecursively()
            } catch (failure: Throwable) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw failure
            }
        } finally {
            staging.deleteRecursively()
        }
        updateOperation(1f, "Save data imported")
    }

    suspend fun exportSave(game: SwitchGameDefinition, destination: Uri): Result<Unit> = runOperation(
        game,
        null,
        "Exporting save data",
    ) {
        ensureInitialized()
        val installed = registry.get(game.id) ?: error("Add the base game first.")
        val source = File(Game(installed.detectedTitle, installed.baseUri, game.programId).saveDir)
        require(source.isDirectory && source.walkTopDown().any(File::isFile)) { "No save data exists yet." }
        application.contentResolver.openOutputStream(destination, "w").use { stream ->
            requireNotNull(stream) { "Could not create the save ZIP." }
            ZipOutputStream(stream.buffered()).use { zip ->
                val files = source.walkTopDown().filter(File::isFile).toList()
                files.forEachIndexed { index, file ->
                    val relative = file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    updateOperation(
                        (index + 1f) / files.size.coerceAtLeast(1),
                        "Exporting $relative",
                    )
                }
            }
        }
        updateOperation(1f, "Save data exported")
    }

    private suspend fun <T> runOperation(
        game: SwitchGameDefinition,
        kind: SwitchContentKind?,
        initialDetail: String,
        block: suspend () -> T,
    ): Result<T> = operationLock.withLock {
        mutableOperation.value = SwitchOperationState(
            gameId = game.id,
            running = true,
            kind = kind,
            detail = initialDetail,
        )
        try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (timeout: TimeoutCancellationException) {
            Result.failure<T>(IllegalStateException("Eden took too long to process this content. The launcher stayed open."))
        } catch (cancelled: CancellationException) {
            mutableOperation.value = mutableOperation.value.copy(running = false)
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure<T>(failure)
        }
            .onFailure { failure ->
                mutableOperation.value = mutableOperation.value.copy(
                    running = false,
                    error = failure.message ?: "Switch operation failed",
                    detail = "Operation failed",
                )
            }
            .onSuccess {
                mutableOperation.value = mutableOperation.value.copy(running = false, fraction = 1f)
            }
    }

    private suspend fun <T> runGlobalOperation(
        initialDetail: String,
        block: suspend () -> T,
    ): Result<T> = operationLock.withLock {
        mutableOperation.value = SwitchOperationState(running = true, detail = initialDetail)
        try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (timeout: TimeoutCancellationException) {
            Result.failure<T>(IllegalStateException("Eden took too long to finish this operation. The launcher stayed open."))
        } catch (cancelled: CancellationException) {
            mutableOperation.value = mutableOperation.value.copy(running = false)
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure<T>(failure)
        }
            .onFailure { failure ->
                mutableOperation.value = mutableOperation.value.copy(
                    running = false,
                    error = failure.message ?: "Switch setup failed",
                    detail = "Operation failed",
                )
            }
            .onSuccess {
                mutableOperation.value = mutableOperation.value.copy(running = false, fraction = 1f)
            }
    }

    private suspend fun ensureInitialized() {
        if (YuzuApplication.isInitialized()) return
        initializeLock.withLock {
            if (YuzuApplication.isInitialized()) return
            EdenRuntimeAbi.verify(application.classLoader)
            val root = File(application.getExternalFilesDir(null) ?: application.filesDir, "Switch/Eden")
            migrateLegacyEdenData(root)
            YuzuApplication.initialize(application, root)
            refreshSystemState()
        }
    }

    private fun refreshSystemState() {
        mutableSystemState.value = SwitchSystemState(
            initialized = YuzuApplication.isInitialized(),
            selectedGpuDriverPath = StringSetting.DRIVER_PATH.getString(true),
            keysPresent = NativeLibrary.areKeysPresent(),
            firmwareVersion = NativeLibrary.firmwareVersion().takeIf(String::isNotBlank),
            gpuDrivers = listOf(SwitchGpuDriver.System) + GpuDriverHelper.getDrivers().map { (path, metadata) ->
                SwitchGpuDriver(
                    path = path,
                    name = metadata.name ?: File(path).nameWithoutExtension,
                    version = metadata.version.orEmpty(),
                    author = metadata.author.orEmpty(),
                )
            }.distinctBy(SwitchGpuDriver::path),
        )
    }

    private fun readAllSettings(perGame: Boolean): List<EdenSettingValue> = buildList {
        BooleanSetting.entries.forEach { setting ->
            addSetting(setting.key, setting.name, EdenSettingType.BOOLEAN, perGame) {
                setting.getBoolean(it).toString()
            }
        }
        ByteSetting.entries.forEach { setting ->
            addSetting(setting.key, setting.name, EdenSettingType.BYTE, perGame) {
                setting.getByte(it).toString()
            }
        }
        ShortSetting.entries.forEach { setting ->
            addSetting(setting.key, setting.name, EdenSettingType.SHORT, perGame) {
                setting.getShort(it).toString()
            }
        }
        IntSetting.entries.forEach { setting ->
            addSetting(setting.key, setting.name, EdenSettingType.INTEGER, perGame) {
                setting.getInt(it).toString()
            }
        }
        LongSetting.entries.forEach { setting ->
            addSetting(setting.key, setting.name, EdenSettingType.LONG, perGame) {
                setting.getLong(it).toString()
            }
        }
        StringSetting.entries.forEach { setting ->
            if (setting != StringSetting.DRIVER_PATH) {
                addSetting(setting.key, setting.name, EdenSettingType.STRING, perGame) {
                    setting.getString(it)
                }
            }
        }
    }.sortedWith(
        compareBy<EdenSettingValue>(
            { EDEN_CATEGORY_RANK[it.category] ?: Int.MAX_VALUE },
            EdenSettingValue::edenOrder,
            EdenSettingValue::label,
        ),
    )

    private fun MutableList<EdenSettingValue>.addSetting(
        key: String,
        enumName: String,
        type: EdenSettingType,
        perGame: Boolean,
        read: (Boolean) -> String,
    ) {
        if (key.isBlank()) return
        val switchable = runCatching { NativeConfig.getIsSwitchable(key) }.getOrDefault(false)
        val saveable = runCatching { NativeConfig.getIsSaveable(key) }.getOrDefault(false)
        if (!saveable || (perGame && !switchable)) return
        runCatching {
            add(
                EdenSettingValue(
                    key = key,
                    label = enumName.toSettingLabel(),
                    category = settingCategory(enumName),
                    type = type,
                    value = read(false),
                    globalValue = read(true),
                    defaultValue = NativeConfig.getDefaultToString(key),
                    useGlobal = perGame && NativeConfig.usingGlobal(key),
                    runtimeModifiable = NativeConfig.getIsRuntimeModifiable(key),
                    edenOrder = EDEN_SETTING_RANK[enumName] ?: Int.MAX_VALUE,
                ).withEdenPresentation(application),
            )
        }
    }

    private fun writeSetting(setting: EdenSettingValue, perGame: Boolean) {
        require(
            NativeConfig.getIsSaveable(setting.key) &&
                (!perGame || NativeConfig.getIsSwitchable(setting.key)),
        ) {
            "${setting.label} is not a valid ${if (perGame) "per-game" else "global"} Eden setting."
        }
        if (perGame) {
            NativeConfig.setGlobal(setting.key, setting.useGlobal)
            if (setting.useGlobal) return
        }
        when (setting.type) {
            EdenSettingType.BOOLEAN -> NativeConfig.setBoolean(
                setting.key,
                setting.value.toBooleanStrictOrNull()
                    ?: error("${setting.label} must be true or false."),
            )
            EdenSettingType.BYTE -> NativeConfig.setByte(
                setting.key,
                setting.value.toByteOrNull() ?: error("${setting.label} is outside its valid range."),
            )
            EdenSettingType.SHORT -> NativeConfig.setShort(
                setting.key,
                setting.value.toShortOrNull() ?: error("${setting.label} is outside its valid range."),
            )
            EdenSettingType.INTEGER -> NativeConfig.setInt(
                setting.key,
                setting.value.toIntOrNull() ?: error("${setting.label} must be a whole number."),
            )
            EdenSettingType.LONG -> NativeConfig.setLong(
                setting.key,
                setting.value.toLongOrNull() ?: error("${setting.label} must be a whole number."),
            )
            EdenSettingType.STRING -> NativeConfig.setString(setting.key, setting.value.take(4096))
        }
    }

    private fun String.toSettingLabel(): String = lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun settingCategory(name: String): String = EDEN_SETTING_CATEGORY[name] ?: when {
        name.startsWith("CPU") || name.startsWith("FASTMEM") || name.contains("CPU_") -> "CPU"
        name.startsWith("RENDERER") || name.startsWith("GPU") || name.contains("VRAM") ||
            name.contains("BUFFER") || name.contains("SHADER") -> "Graphics"
        name.startsWith("AUDIO") -> "Audio"
        name.contains("INPUT") || name.contains("CONTROLLER") || name.contains("JOYSTICK") ||
            name.contains("DPAD") || name.contains("HAPTIC") || name == "TOUCHSCREEN" -> "Input"
        name.contains("OVERLAY") || name.startsWith("SHOW_") -> "Overlays"
        name.contains("APPLET") -> "Applets"
        name.startsWith("DEBUG") || name.startsWith("DUMP") -> "Debug"
        name.contains("REGION") || name.contains("LANGUAGE") || name.contains("RTC") ||
            name.contains("MEMORY_LAYOUT") || name.contains("DOCKED") -> "System"
        else -> "Advanced"
    }

    private fun validateDriverArchive(uri: Uri) {
        val staging = File(application.cacheDir, "eden-driver-${System.nanoTime()}.zip")
        try {
            application.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not read the GPU driver ZIP." }
                staging.outputStream().use { output ->
                    copyLimited(input, output, MAX_DRIVER_ARCHIVE_BYTES, "GPU driver ZIP")
                }
            }
            var entries = 0
            var expanded = 0L
            var metadata = false
            var library = false
            ZipFile(staging).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    entries++
                    require(entries <= MAX_DRIVER_ENTRIES) { "The GPU driver ZIP contains too many files." }
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) {
                        "The GPU driver ZIP contains an unsafe path."
                    }
                    if (!entry.isDirectory) {
                        require(entry.size >= 0L) { "The GPU driver ZIP contains an invalid entry." }
                        expanded += entry.size
                        require(expanded <= MAX_DRIVER_EXPANDED_BYTES) {
                            "The GPU driver ZIP expands beyond the safe limit."
                        }
                        metadata = metadata || normalized.substringAfterLast('/').equals("meta.json", true)
                        library = library || normalized.endsWith(".so", true)
                    }
                }
            }
            require(metadata && library) { "The GPU driver ZIP must contain meta.json and a driver library." }
        } finally {
            staging.delete()
        }
    }

    private fun copyLimited(
        input: InputStream,
        output: java.io.OutputStream,
        limit: Long,
        label: String,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "$label is too large." }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun verifyReadableUri(uri: Uri) {
        application.contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor ->
            requireNotNull(descriptor) { "The selected file is no longer readable." }
        }
    }

    private fun migrateLegacyEdenData(target: File) {
        if (File(target, "config/config.ini").isFile || target.listFiles()?.isNotEmpty() == true) return
        val storage = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            "dev.eden.eden_emulator.dualscreen",
            "dev.eden.eden_emulator.dualscreen.debug",
            "dev.eden.eden_emulator",
            "dev.eden.eden_emulator.debug",
        ).map { File(storage, "Android/data/$it/files") }
        val source = candidates.firstOrNull { File(it, "config/config.ini").isFile } ?: return
        updateOperation(0.02f, "Migrating existing Eden DS data")
        require(target.mkdirs() || target.isDirectory) { "Could not create the embedded Eden data directory." }
        source.copyRecursively(target, overwrite = false)
    }

    private suspend fun refreshPatchesLocked(game: SwitchGameDefinition) {
        val installed = registry.get(game.id) ?: return
        val patches = SwitchContentWorkerService.Client.queryPatches(
            context = application,
            baseUri = installed.baseUri,
            programId = game.programId,
        )
        storePatches(installed, patches)
    }

    private fun storePatches(installed: SwitchGameInstallation, patches: List<SwitchPatch>) {
        registry.put(installed.copy(patches = patches.distinctBy { listOf(it.kind, it.name, it.version) }))
        mutableInstallations.value = registry.all()
    }

    private inline fun <T> withPerGameConfig(game: SwitchGameDefinition, block: () -> T): T {
        val installed = registry.get(game.id) ?: error("Add the base game first.")
        NativeConfig.initializePerGameConfig(game.programId, installed.displayName)
        return try {
            block()
        } finally {
            NativeConfig.unloadPerGameConfig()
        }
    }

    private fun persistGameIcon(game: SwitchGameDefinition, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val directory = File(application.filesDir, "switch/icons")
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, "${game.id}.png")
        FileOutputStream(target).use { it.write(bytes) }
        return target.absolutePath
    }

    private fun copyDocument(source: DocumentFile, target: File) {
        if (source.isDirectory) {
            require(target.mkdirs() || target.isDirectory) { "Could not create ${target.name}." }
            source.listFiles().forEach { child ->
                copyDocument(child, File(target, sanitizeFileName(child.name ?: "content")))
            }
        } else {
            val parent = target.parentFile
            require(parent == null || parent.isDirectory || parent.mkdirs()) {
                "Could not create the mod directory."
            }
            application.contentResolver.openInputStream(source.uri).use { input ->
                requireNotNull(input) { "Could not read ${source.name ?: "mod file"}." }
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun updateOperation(fraction: Float, detail: String) {
        mutableOperation.value = mutableOperation.value.copy(
            fraction = fraction.coerceIn(0f, 1f),
            detail = detail,
            error = null,
        )
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun documentName(uri: Uri): String? =
        DocumentFile.fromSingleUri(application, uri)?.name

    private fun extension(uri: Uri): String =
        documentName(uri)?.substringAfterLast('.', "")?.lowercase().orEmpty()

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(96)
        .ifBlank { "Imported Mod" }

    private companion object {
        const val TAG = "EdenSwitchBackend"
        val EDEN_SETTINGS_BY_CATEGORY = linkedMapOf(
            "System" to listOf(
                "DEVICE_NAME", "RENDERER_USE_SPEED_LIMIT", "RENDERER_SPEED_LIMIT",
                "RENDERER_TURBO_SPEED_LIMIT", "RENDERER_SLOW_SPEED_LIMIT", "USE_DOCKED_MODE",
                "REGION_INDEX", "LANGUAGE_INDEX", "USE_CUSTOM_RTC", "CUSTOM_RTC", "FAST_CPU_TIME",
                "FAST_GPU_TIME", "CORE_SYNC_CORE_SPEED", "MEMORY_LAYOUT", "USE_CUSTOM_CPU_TICKS",
                "CPU_TICKS", "WEB_TOKEN", "WEB_USERNAME",
            ),
            "Graphics" to listOf(
                "RENDERER_RESOLUTION", "RENDERER_VSYNC", "RENDERER_SCALING_FILTER",
                "FSR_SHARPENING_SLIDER", "RENDERER_ANTI_ALIASING", "RENDERER_ACCURACY",
                "DMA_ACCURACY", "GPU_FENCE_BEHAVIOR", "MAX_ANISOTROPY", "RENDERER_VRAM_USAGE_MODE",
                "RENDERER_ASTC_DECODE_METHOD", "RENDERER_NVDEC_EMULATION", "SYNC_MEMORY_OPERATIONS",
                "RENDERER_USE_DISK_SHADER_CACHE", "RENDERER_FORCE_MAX_CLOCK",
                "RENDERER_REACTIVE_FLUSHING", "ENABLE_BUFFER_HISTORY", "ENABLE_GPU_BUFFER_READBACK",
                "USE_OPTIMIZED_VERTEX_BUFFERS", "SKIP_CPU_INNER_INVALIDATION", "FIX_BLOOM_EFFECTS",
                "EMULATE_BGR565", "RENDERER_ASYNCHRONOUS_SHADERS", "ANDROID_PIPELINE_WORKERS",
                "RENDERER_ASYNCHRONOUS_GPU_EMULATION", "RENDERER_ASYNC_PRESENTATION",
                "GPU_UNSWIZZLE_ENABLED", "GPU_UNSWIZZLE_TEXTURE_SIZE", "GPU_UNSWIZZLE_STREAM_SIZE",
                "GPU_UNSWIZZLE_CHUNK_SIZE", "RENDERER_DYNA_STATE",
                "RENDERER_VERTEX_INPUT_DYNAMIC_STATE", "RENDERER_SAMPLE_SHADING",
                "RENDERER_SCREEN_LAYOUT", "RENDERER_ASPECT_RATIO", "VERTICAL_ALIGNMENT",
                "PICTURE_IN_PICTURE",
            ),
            "Frame generation" to listOf(
                "RENDERER_FRAME_GEN", "RENDERER_FRAME_GEN_TARGET_RATE",
                "RENDERER_FRAME_GEN_MULTIPLIER", "RENDERER_FRAME_GEN_QUEUE_TARGET",
                "RENDERER_FRAME_GEN_FLOW_SCALE_AUTO", "RENDERER_FRAME_GEN_FLOW_SCALE",
                "RENDERER_FRAME_GEN_FP16",
            ),
            "Performance overlay" to listOf(
                "SHOW_PERFORMANCE_OVERLAY", "PERF_OVERLAY_BACKGROUND", "PERF_OVERLAY_POSITION",
                "SHOW_FPS", "SHOW_FRAMETIME", "SHOW_APP_RAM_USAGE", "SHOW_SYSTEM_RAM_USAGE",
                "SHOW_BAT_TEMPERATURE", "BAT_TEMPERATURE_UNIT", "SHOW_POWER_INFO",
                "SHOW_SHADERS_BUILDING",
            ),
            "SOC overlay" to listOf(
                "SHOW_SOC_OVERLAY", "SOC_OVERLAY_BACKGROUND", "SOC_OVERLAY_POSITION", "SHOW_BUILD_ID",
                "SHOW_DRIVER_VERSION", "SHOW_DEVICE_MODEL", "SHOW_GPU_MODEL", "SHOW_SOC_MODEL",
                "SHOW_FW_VERSION",
            ),
            "Input overlay" to listOf(
                "SHOW_INPUT_OVERLAY", "OVERLAY_SNAP_TO_GRID", "OVERLAY_GRID_SIZE",
                "ENABLE_INPUT_OVERLAY_AUTO_HIDE", "INPUT_OVERLAY_AUTO_HIDE",
                "HIDE_OVERLAY_ON_CONTROLLER_INPUT",
            ),
            "Input" to listOf(
                "JOYSTICK_REL_CENTER", "DPAD_SLIDE", "HAPTIC_FEEDBACK", "TOUCHSCREEN",
            ),
            "Audio" to listOf("AUDIO_OUTPUT_ENGINE", "AUDIO_VOLUME", "AUDIO_MUTED"),
            "Debug" to listOf(
                "RENDERER_BACKEND", "RENDERER_DEBUG", "RENDERER_PATCH_OLD_QCOM_DRIVERS",
                "BUFFER_REORDER_DISABLE", "CPU_BACKEND", "CPU_ACCURACY", "USE_AUTO_STUB",
                "FASTMEM", "FASTMEM_EXCLUSIVES", "CPUOPT_UNSAFE_HOST_MMU", "DEBUG_FLUSH_BY_LINE",
                "DEBUG_KNOBS", "PROGRAM_ARGS", "GPU_LOG_LEVEL", "GPU_LOG_VULKAN_CALLS",
                "DUMP_GUEST_SHADERS", "GPU_LOG_SHADER_DUMPS", "DUMP_MACROS",
                "RENDERER_FRAME_GEN_DUMP_FLOW", "GPU_LOG_MEMORY_TRACKING", "GPU_LOG_DRIVER_DEBUG",
                "GPU_LOG_RING_BUFFER_SIZE",
            ),
            "Applets" to listOf("SWKBD_APPLET", "AIRPLANE_MODE", "ENABLE_OVERLAY"),
            "Application" to listOf(
                "APP_LANGUAGE", "ENABLE_UPDATE_CHECKS", "ENABLE_QUICK_SETTINGS",
                "INVERT_CONFIRM_BACK_CONTROLLER_BUTTONS", "THEME_MODE", "THEME",
                "STATIC_THEME_COLOR", "BLACK_BACKGROUNDS", "ENABLE_FOLDER_BUTTON",
                "ENABLE_QLAUNCH_BUTTON",
            ),
            "Advanced" to emptyList(),
        )
        val EDEN_CATEGORY_RANK = EDEN_SETTINGS_BY_CATEGORY.keys.withIndex().associate { it.value to it.index }
        val EDEN_SETTING_CATEGORY = buildMap {
            EDEN_SETTINGS_BY_CATEGORY.forEach { (category, settings) ->
                settings.forEach { put(it, category) }
            }
        }
        val EDEN_SETTING_RANK = EDEN_SETTINGS_BY_CATEGORY.values
            .flatten()
            .withIndex()
            .associate { it.value to it.index }
        const val TITLE_TYPE_UPDATE = 0x81
        const val TITLE_TYPE_DLC = 0x82
        const val MAX_DRIVER_ENTRIES = 4_096
        const val MAX_DRIVER_ARCHIVE_BYTES = 1_000_000_000L
        const val MAX_DRIVER_EXPANDED_BYTES = 2_000_000_000L
        const val MAX_FIRMWARE_FILES = 100_000
        const val MAX_FIRMWARE_EXPANDED_BYTES = 16_000_000_000L
        const val MAX_SAVE_EXPANDED_BYTES = 8_000_000_000L
    }
}
