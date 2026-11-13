package com.dualscreen.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private sealed interface LauncherPage {
    data object PcGames : LauncherPage
    data class PcGameDetails(val gameId: String) : LauncherPage
    data object PcDriverDownloader : LauncherPage
    data object Guide : LauncherPage
    data object Settings : LauncherPage
}

internal enum class LauncherNavItem { PC_GAMES, GUIDE, SETTINGS }

@Composable
internal fun LauncherScreen(
    model: LauncherViewModel,
    onPlay: (SupportedGame, InstalledGame) -> Unit,
    onConfigure: (SupportedGame, InstalledGame) -> Unit,
) {
    val installed by model.installed.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val saveProgress by model.saveProgress.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val gpuDrivers by model.gpuDrivers.collectAsStateWithLifecycle()
    val gpuDriverImportState by model.gpuDriverImportState.collectAsStateWithLifecycle()
    val gpuDriverMessage by model.gpuDriverMessage.collectAsStateWithLifecycle()
    val pcGpuDriverOperation by model.pcGpuDriverOperation.collectAsStateWithLifecycle()
    val payloadState by model.payloadState.collectAsStateWithLifecycle()
    val updateState by model.updateState.collectAsStateWithLifecycle()
    val archiveReview by model.archiveReview.collectAsStateWithLifecycle()
    val operationBusy = progress.values.any(InstallProgress::running) ||
        saveProgress.values.any(SaveImportProgress::running)
    var pendingGame by remember { mutableStateOf<SupportedGame?>(null) }
    var pendingSaveGame by remember { mutableStateOf<Pair<SupportedGame, InstalledGame>?>(null) }
    val home = LauncherPage.PcGameDetails(GameCatalog.games.single().id)
    var page by remember { mutableStateOf<LauncherPage>(home) }

    val gameArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = pendingGame
        pendingGame = null
        if (game != null && uri != null) model.install(game, uri)
    }
    val saveArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingSaveGame
        pendingSaveGame = null
        if (target != null && uri != null) model.importSave(target.first, target.second, uri)
    }
    val gpuDriverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importGpuDriver(uri)
    }

    fun chooseGameArchive(game: SupportedGame) {
        pendingGame = game
        gameArchivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    fun chooseSaveArchive(game: SupportedGame, ready: InstalledGame) {
        pendingSaveGame = game to ready
        saveArchivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    val selectedNavigation = when (page) {
        LauncherPage.PcGames,
        is LauncherPage.PcGameDetails,
        -> LauncherNavItem.PC_GAMES
        LauncherPage.Guide -> LauncherNavItem.GUIDE
        LauncherPage.Settings,
        LauncherPage.PcDriverDownloader,
        -> LauncherNavItem.SETTINGS
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF181A12), LauncherBackground, Color(0xFF10120E)),
                    ),
                ),
        ) {
            LauncherHeader(
                selected = selectedNavigation,
                onHome = { page = home },
                onSettings = { page = LauncherPage.Settings },
            )
            LauncherUpdateBanner(
                state = updateState,
                onInstall = model::installLauncherUpdate,
                onRetry = model::retryLauncherUpdate,
                onRequestPermission = model::requestLauncherUpdatePermission,
            )
            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = launcherPageRank(targetState) >= launcherPageRank(initialState)
                    val enterDirection = if (forward) 1 else -1
                    val exitDirection = -enterDirection
                    (slideInHorizontally(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                    ) { enterDirection * it / 7 } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        ) { exitDirection * it / 7 } + fadeOut(tween(120))) using
                        SizeTransform(clip = false)
                },
                label = "launcher-page",
            ) { destination ->
                when (destination) {
                    LauncherPage.PcGames -> {
                        if (payloadState !is PayloadBootstrapState.Ready) {
                            PayloadBootstrapPage(
                                state = payloadState,
                                onRetry = model::retryPayloadBootstrap,
                            )
                        } else {
                            LaunchedEffect(Unit) { page = home }
                        }
                    }

                    is LauncherPage.PcGameDetails -> {
                        val game = GameCatalog.byId(destination.gameId)
                        if (payloadState !is PayloadBootstrapState.Ready) {
                            PayloadBootstrapPage(
                                state = payloadState,
                                onRetry = model::retryPayloadBootstrap,
                            )
                        } else if (game == null) {
                            LaunchedEffect(destination.gameId) { page = home }
                        } else {
                            GameDetailsPage(
                                game = game,
                                installed = installed[game.id],
                                progress = progress[game.id],
                                saveProgress = saveProgress[game.id],
                                operationBusy = operationBusy,
                                onBack = null,
                                onInstall = {
                                    if (model.hasBundledGame && installed[game.id] == null) {
                                        model.installBundled(game)
                                    } else {
                                        chooseGameArchive(game)
                                    }
                                },
                                bundledGame = model.hasBundledGame,
                                onImportSave = {
                                    installed[game.id]?.let { chooseSaveArchive(game, it) }
                                },
                                onPlay = { installed[game.id]?.let { onPlay(game, it) } },
                                onConfigure = {
                                    installed[game.id]?.let { onConfigure(game, it) }
                                },
                            )
                        }
                    }

                    LauncherPage.PcDriverDownloader -> PcDriverDownloaderPage(
                        operation = pcGpuDriverOperation,
                        installedCount = gpuDrivers.count {
                            it.source != LauncherGpuDriver.Source.SYSTEM
                        },
                        loader = model::fetchPcGpuDrivers,
                        onInstall = model::downloadPcGpuDriver,
                        onBack = { page = LauncherPage.Settings },
                    )

                    LauncherPage.Guide -> LaunchedEffect(Unit) { page = home }

                    LauncherPage.Settings -> SettingsPage(
                        settings = settings,
                        installed = installed,
                        gpuDrivers = gpuDrivers,
                        gpuDriverImportState = gpuDriverImportState,
                        gpuDriverMessage = gpuDriverMessage,
                        onKeepAwake = model::setKeepScreenAwake,
                        onSwapFaceButtons = model::setSwapFaceButtons,
                        onResolution = model::setResolution,
                        onGpuDriver = model::setGpuDriver,
                        onImportGpuDriver = {
                            gpuDriverPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed"),
                            )
                        },
                        onOpenPcDriverDownloader = {
                            page = LauncherPage.PcDriverDownloader
                        },
                    )
                }
            }
        }
        ArchiveReviewDialog(
            state = archiveReview,
            onContinue = model::continueUntestedInstall,
            onDismiss = model::dismissArchiveReview,
        )
    }
}

private fun launcherPageRank(page: LauncherPage): Int = when (page) {
    LauncherPage.PcGames -> 0
    is LauncherPage.PcGameDetails -> 1
    LauncherPage.PcDriverDownloader -> 2
    LauncherPage.Guide -> 3
    LauncherPage.Settings -> 4
}

@Composable
private fun ArchiveReviewDialog(
    state: ArchiveReviewState,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == ArchiveReviewState.Idle) return
    val game = GameCatalog.byId(
        when (state) {
            ArchiveReviewState.Idle -> null
            is ArchiveReviewState.Inspecting -> state.gameId
            is ArchiveReviewState.VersionWarning -> state.gameId
            is ArchiveReviewState.Rejected -> state.gameId
        },
    )
    AlertDialog(
        onDismissRequest = {
            if (state !is ArchiveReviewState.Inspecting) onDismiss()
        },
        shape = RectangleShape,
        containerColor = LauncherPanelRaised,
        tonalElevation = 0.dp,
        title = {
            Text(
                when (state) {
                    ArchiveReviewState.Idle -> ""
                    is ArchiveReviewState.Inspecting -> "Inspecting game archive"
                    is ArchiveReviewState.VersionWarning -> "Untested game version"
                    is ArchiveReviewState.Rejected -> "Archive cannot be installed"
                },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                when (state) {
                    ArchiveReviewState.Idle -> Unit
                    is ArchiveReviewState.Inspecting -> {
                        Text(
                            "Checking the ZIP structure, critical files, and game version before setup begins.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ArchiveReviewState.VersionWarning -> {
                        Text(
                            "This game version has not been tested and may not work correctly with the " +
                                "dual-screen mod. For the best compatibility, use the recommended version.",
                            color = LauncherAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Recommended: ${game?.requiredBuild.orEmpty()}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        state.inspection.versionWarnings.forEach { warning ->
                            Text("- $warning", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "You can continue anyway. Native or address-sensitive parts of the mod may be " +
                                "skipped when no compatible patch exists.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ArchiveReviewState.Rejected -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Only corrupt archives, unsafe paths, or missing critical game files are blocked.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is ArchiveReviewState.VersionWarning -> Button(
                    onClick = onContinue,
                    shape = RectangleShape,
                ) { Text("Continue anyway") }
                is ArchiveReviewState.Rejected -> Button(
                    onClick = onDismiss,
                    shape = RectangleShape,
                ) { Text("Choose another ZIP") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state is ArchiveReviewState.VersionWarning) {
                OutlinedButton(onClick = onDismiss, shape = RectangleShape) { Text("Cancel") }
            }
        },
    )
}
