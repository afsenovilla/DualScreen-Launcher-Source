package com.dualscreen.launcher

import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
internal fun GameDetailsPage(
    game: SupportedGame,
    installed: InstalledGame?,
    progress: InstallProgress?,
    saveProgress: SaveImportProgress?,
    operationBusy: Boolean,
    onBack: (() -> Unit)?,
    onInstall: () -> Unit,
    onImportSave: () -> Unit,
    onPlay: () -> Unit,
    onConfigure: () -> Unit,
    bundledGame: Boolean = false,
) {
    val state = resolveGameInstallState(installed, progress, saveProgress)
    val busy = operationBusy ||
        state == GameInstallState.INSTALLING ||
        state == GameInstallState.UPDATING_MOD ||
        state == GameInstallState.IMPORTING_SAVE

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 18.dp,
            top = 10.dp,
            bottom = 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RectangleShape)
                        .background(Color(0xFF202119))
                        .border(1.dp, Color(0xFF3B3C33), RectangleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", modifier = Modifier.size(19.dp))
                }
                }
                if (onBack != null) Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "JUEGO",
                        color = LauncherAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        letterSpacing = 1.6.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            gameShortTitle(game.id),
                            modifier = Modifier.weight(0.44f),
                            fontSize = 25.sp,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(
                            game.summary,
                            modifier = Modifier.weight(0.56f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                StatusPill(state)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                GameIdentityCard(game = game, state = state)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionPanel(
                        game = game,
                        installed = installed,
                        busy = busy,
                        onInstall = onInstall,
                        onImportSave = onImportSave,
                        onPlay = onPlay,
                        onConfigure = onConfigure,
                        bundledGame = bundledGame,
                    )
                    SetupFeedback(
                        game = game,
                        installed = installed,
                        progress = progress,
                        saveProgress = saveProgress,
                    )
                }
            }
        }

        item { DualScreenPreview(game) }

        item {
            LauncherCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("Configuración automática", "Se verifica antes de cada partida")
                    Text(
                        "SIN AJUSTES MANUALES",
                        color = LauncherSuccess,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        InformationRow("Ejecutable", game.executableCandidates.firstOrNull() ?: "Sin configurar")
                        InformationRow("Versión recomendada", game.requiredBuild, emphasized = true)
                        InformationRow(
                            "Lanzador del juego",
                            when {
                                game.configurationLauncherCandidates.isEmpty() -> "No necesario"
                                installed == null -> "Se detecta al instalar"
                                resolveConfigurationLauncherExecutable(File(installed.hostDirectory), game) != null -> "Available"
                                else -> "No incluido (opcional)"
                            },
                        )
                        InformationRow("Partidas guardadas", game.saveDirectory.ifBlank { "Las gestiona el juego" })
                        InformationRow("Carpeta compartida", "/Dualscreen/Games/${game.storageName}")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        InformationRow("Contenedor", "Preset verificado")
                        InformationRow("Mod de doble pantalla", installed?.payloadVersion ?: "Se instala al configurar")
                        InformationRow(
                            "Compatibilidad",
                            if (installed?.untestedBuild == true) "Versión no probada" else "Versión recomendada",
                            emphasized = installed != null && !installed.untestedBuild,
                        )
                        InformationRow("Datos instalados", installed?.let { formatBytes(it.installedBytes) } ?: "—")
                    }
                }
            }
        }
    }
}

@Composable
private fun GameIdentityCard(game: SupportedGame, state: GameInstallState) {
    LauncherCard(modifier = Modifier.width(164.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    when (state) {
                        GameInstallState.READY -> LauncherSuccess
                        GameInstallState.ERROR -> LauncherDanger
                        GameInstallState.INSTALLING,
                        GameInstallState.UPDATING_MOD,
                        GameInstallState.IMPORTING_SAVE,
                        -> LauncherAccent
                        GameInstallState.NOT_INSTALLED -> LauncherOutline
                    },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(202.dp)
                .clip(RectangleShape)
                .background(Color.Black),
        ) {
            Image(
                painter = painterResource(gameArtwork(game.id)),
                contentDescription = "${game.title} artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(45.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))),
            )
            if (game.isBeta) {
                BetaBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Text(
            when (state) {
                GameInstallState.NOT_INSTALLED -> "Añade el ZIP del juego para empezar."
                GameInstallState.INSTALLING -> "La instalación avanza en la pantalla de abajo."
                GameInstallState.UPDATING_MOD -> "Actualizando el mod de doble pantalla."
                GameInstallState.IMPORTING_SAVE -> "Save files are being imported."
                GameInstallState.READY -> if (game.panelMode == LowerPanelMode.DIAGNOSTICS)
                    "Installed for crash diagnosis. Native companion not enabled."
                else "Instalado, con el mod y listo para jugar."
                GameInstallState.ERROR -> "Mira el registro de abajo para ver el error."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
        Text(
            when (game.panelMode) {
                LowerPanelMode.NATIVE_WINDOW -> "PANEL INFERIOR NATIVO"
                LowerPanelMode.DIAGNOSTICS -> "CRASH DIAGNOSTICS / MOD IN DEVELOPMENT"
                LowerPanelMode.STARDEW -> "MANAGED LOWER PANEL"
                LowerPanelMode.VALHEIM -> "MANAGED LOWER PANEL"
            },
            color = LauncherAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
        )
    }
}

@Composable
private fun ActionPanel(
    game: SupportedGame,
    installed: InstalledGame?,
    busy: Boolean,
    onInstall: () -> Unit,
    onImportSave: () -> Unit,
    onPlay: () -> Unit,
    onConfigure: () -> Unit,
    bundledGame: Boolean = false,
) {
    val ready = installed != null
    val launcherAvailable = installed?.let { readyGame ->
        resolveConfigurationLauncherExecutable(File(readyGame.hostDirectory), game) != null
    } == true
    LauncherCard(modifier = Modifier.animateContentSize()) {
        SectionTitle(
            if (ready) "Jugar y gestionar" else "Instalar el juego",
            if (ready) "Juega, actualiza o importa partidas"
            else if (bundledGame) "El juego viene incluido en la app"
            else "Elige el ZIP con los archivos del juego",
        )
        if (!ready) {
            PrimaryAction(
                label = if (bundledGame) "Instalar juego" else "Elegir ZIP del juego",
                icon = Icons.Default.FolderZip,
                onClick = onInstall,
                enabled = !busy,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "RECOMENDADA  ${game.requiredBuild}",
                color = LauncherAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PrimaryAction(
                    label = "Jugar",
                    icon = Icons.Default.PlayArrow,
                    onClick = onPlay,
                    enabled = !busy,
                    primary = true,
                    modifier = Modifier.weight(1f),
                )
                if (launcherAvailable) {
                    PrimaryAction(
                        label = "Abrir lanzador del juego",
                        icon = Icons.Default.SettingsApplications,
                        onClick = onConfigure,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PrimaryAction(
                    label = "Importar partidas (ZIP)",
                    icon = Icons.Default.UploadFile,
                    onClick = onImportSave,
                    enabled = !busy && game.saveDirectory.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                PrimaryAction(
                    label = "Actualizar / reemplazar archivos",
                    icon = Icons.Default.FolderZip,
                    onClick = onInstall,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SetupFeedback(
    game: SupportedGame,
    installed: InstalledGame?,
    progress: InstallProgress?,
    saveProgress: SaveImportProgress?,
) {
    val state = resolveGameInstallState(installed, progress, saveProgress)
    val activeFraction = when (state) {
        GameInstallState.IMPORTING_SAVE -> saveProgress?.fraction ?: 0f
        GameInstallState.UPDATING_MOD -> modUpdateFraction(progress)
        else -> progress?.fraction ?: if (installed != null) 1f else 0f
    }
    val title = when (state) {
        GameInstallState.NOT_INSTALLED -> "Listo para tu ZIP"
        GameInstallState.INSTALLING -> progress?.detail ?: "Preparando el juego"
        GameInstallState.UPDATING_MOD -> progress?.detail ?: "Actualizando el mod"
        GameInstallState.IMPORTING_SAVE -> saveProgress?.detail ?: "Importando partidas"
        GameInstallState.READY -> "Instalación completa"
        GameInstallState.ERROR -> "La instalación necesita atención"
    }
    val error = progress?.error ?: saveProgress?.error
    val activeLog = when (state) {
        GameInstallState.IMPORTING_SAVE -> saveProgress?.log.orEmpty()
        else -> progress?.log.orEmpty()
    }

    LauncherCard(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        error != null -> error
                        state == GameInstallState.READY && installed?.untestedBuild == true ->
                            "Instalado con una versión no probada del juego. Puede que algo no funcione."
                        state == GameInstallState.READY -> "Mod de doble pantalla ${installed?.payloadVersion.orEmpty()} instalado."
                        state == GameInstallState.NOT_INSTALLED -> "Comprime la carpeta de ${game.title} (con isaac-ng.exe y resources) en un ZIP."
                        else -> "El progreso y el registro completo se ven en la pantalla de abajo."
                    },
                    color = if (error != null) LauncherDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state == GameInstallState.READY) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LauncherSuccess, modifier = Modifier.size(24.dp))
            } else if (
                state == GameInstallState.INSTALLING ||
                state == GameInstallState.UPDATING_MOD ||
                state == GameInstallState.IMPORTING_SAVE
            ) {
                Text(
                    "${installPercent(activeFraction)}%",
                    color = LauncherAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        if (
            state == GameInstallState.INSTALLING ||
            state == GameInstallState.UPDATING_MOD ||
            state == GameInstallState.IMPORTING_SAVE
        ) {
            LauncherProgress(
                fraction = activeFraction,
                modifier = Modifier.fillMaxWidth().height(5.dp),
            )
        }
        activeLog.takeLast(2).forEach { line ->
            Text(
                line,
                color = Color(0xFF918E83),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
