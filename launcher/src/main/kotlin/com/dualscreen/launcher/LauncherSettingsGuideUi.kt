package com.dualscreen.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsPage(
    settings: LauncherSettings,
    installed: Map<String, InstalledGame>,
    gpuDrivers: List<LauncherGpuDriver>,
    gpuDriverImportState: GpuDriverImportState,
    gpuDriverMessage: String?,
    onKeepAwake: (Boolean) -> Unit,
    onResolution: (GameResolution) -> Unit,
    onGpuDriver: (String) -> Unit,
    onImportGpuDriver: () -> Unit,
    onOpenPcDriverDownloader: () -> Unit,
) {
    val selectedDriver = gpuDrivers.firstOrNull { it.id == settings.gpuDriverId }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "LAUNCHER SETTINGS",
                    color = LauncherAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    letterSpacing = 1.6.sp,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Display, graphics, storage, and the verified game runtime.",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "${installed.size} READY  •  VERIFIED PRESETS",
                color = if (installed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else LauncherSuccess,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
            )
        }
        Spacer(Modifier.height(9.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                LauncherCard(modifier = Modifier.height(230.dp), compact = true) {
                    SettingsSectionHeading(
                        icon = Icons.Default.Smartphone,
                        title = "Display & session",
                        detail = "Applied automatically before the game starts",
                    )
                    CompactSwitch(
                        title = "Keep displays awake",
                        detail = "Prevent either screen from sleeping during play.",
                        checked = settings.keepScreenAwake,
                        onCheckedChange = onKeepAwake,
                    )
                    Text("TOP-SCREEN GAME RESOLUTION", style = settingLabelStyle())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GameResolution.entries.forEach { resolution ->
                            CompactChoice(
                                label = resolution.label,
                                selected = settings.resolution == resolution,
                                onClick = { onResolution(resolution) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        InformationRow(
                            "Top output",
                            settings.resolution.label,
                            emphasized = true,
                            modifier = Modifier.weight(1f),
                        )
                        InformationRow("Lower UI", "1240 × 1080", modifier = Modifier.weight(1f))
                    }
                }
            }

            item {
                LauncherCard(modifier = Modifier.height(230.dp), compact = true) {
                    SettingsSectionHeading(
                        icon = Icons.Default.SettingsApplications,
                        title = "PC GPU driver",
                        detail = "Applied to the predefined Windows game containers",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LauncherAccent.copy(alpha = 0.08f), RectangleShape)
                            .border(1.dp, LauncherAccent.copy(alpha = 0.26f), RectangleShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ACTIVE DRIVER", style = settingLabelStyle())
                        Text(
                            selectedDriver?.label ?: "Loading…",
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            color = LauncherAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (gpuDrivers.isEmpty()) {
                        Text("Reading installed drivers…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(gpuDrivers, key = { it.id }) { driver ->
                                val suffix = when (driver.source) {
                                    LauncherGpuDriver.Source.SYSTEM -> ""
                                    LauncherGpuDriver.Source.BUNDLED -> " • bundled"
                                    LauncherGpuDriver.Source.UPLOADED -> " • uploaded"
                                }
                                CompactChoice(
                                    label = driver.label + suffix,
                                    selected = settings.gpuDriverId == driver.id,
                                    onClick = { onGpuDriver(driver.id) },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        PrimaryAction(
                            label = "Download drivers",
                            icon = Icons.Default.CloudDownload,
                            onClick = onOpenPcDriverDownloader,
                            enabled = gpuDriverImportState !is GpuDriverImportState.Importing,
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = onImportGpuDriver,
                            enabled = gpuDriverImportState !is GpuDriverImportState.Importing,
                            modifier = Modifier.weight(1f).height(39.dp),
                            shape = RectangleShape,
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (gpuDriverImportState is GpuDriverImportState.Importing) {
                                    "Validating…"
                                } else {
                                    "Upload ZIP"
                                },
                                fontSize = 11.sp,
                            )
                        }
                    }
                    DriverMessage(gpuDriverImportState, gpuDriverMessage)
                }
            }

            item {
                LauncherCard(modifier = Modifier.height(334.dp), compact = true) {
                    SettingsSectionHeading(
                        icon = Icons.Default.Archive,
                        title = "Library & payloads",
                        detail = "Game data you can reach from any file manager",
                    )
                    InformationRow("Ready to play", "${installed.size} of ${GameCatalog.games.size}", emphasized = installed.isNotEmpty())
                    InformationRow("Managed game data", formatBytes(installed.values.sumOf { it.installedBytes }))
                    InformationRow("Shared root", "/Dualscreen")
                    InformationRow("Game folders", "Game / Mods / Saves / Config / Logs")
                    InformationRow("Runtime delivery", "Protected and verified")
                    InformationRow("Game mods", "SHA-256 verified")
                    InformationRow("Save imports", "Live public Saves folders")
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                LauncherCard(compact = true) {
                    SettingsSectionHeading(
                        icon = Icons.Default.CheckCircle,
                        title = "Verified environment",
                        detail = "Game-tested settings stay locked per preset",
                    )
                    InformationRow("Proton", "11.0-5 ARM64EC 6")
                    InformationRow("FEX", "2608 stable 0")
                    InformationRow("DirectX", "DXVK 3.0-1 • VKD3D 2.9")
                    InformationRow("Graphics", "Turnip 26.2.0")
                    InformationRow("Presentation", "SurfaceFlinger • dual display")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeading(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF191B15))
            .border(1.dp, LauncherOutline, RectangleShape)
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(31.dp)
                .background(LauncherAccent.copy(alpha = 0.12f), RectangleShape)
                .border(1.dp, LauncherAccent.copy(alpha = 0.35f), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = LauncherAccent, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
    }
}

@Composable
private fun CompactSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val track by animateColorAsState(
        if (checked) LauncherAccent.copy(alpha = 0.24f) else Color(0xFF24251E),
        animationSpec = tween(160),
        label = "switch-track",
    )
    val outline by animateColorAsState(
        if (checked) LauncherAccent else Color(0xFF555449),
        animationSpec = tween(160),
        label = "switch-outline",
    )
    val thumb by animateColorAsState(
        if (checked) LauncherAccent else Color(0xFF77756C),
        animationSpec = tween(160),
        label = "switch-thumb",
    )
    val thumbOffset by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "switch-offset",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 24.dp)
                .background(track)
                .border(1.dp, outline)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                    .background(thumb),
            )
        }
    }
}

@Composable
private fun CompactChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val background by animateColorAsState(
        if (selected) LauncherAccent else Color(0xFF191B15),
        animationSpec = tween(150),
        label = "choice-background",
    )
    val foreground by animateColorAsState(
        if (selected) Color(0xFF17120A) else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(150),
        label = "choice-foreground",
    )
    val border by animateColorAsState(
        if (selected) LauncherAccent else LauncherOutline,
        animationSpec = tween(150),
        label = "choice-border",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground),
        border = BorderStroke(1.dp, border),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DriverMessage(state: GpuDriverImportState, message: String?) {
    val text: String?
    val color: Color
    when (state) {
        is GpuDriverImportState.Failed -> {
            text = state.message
            color = MaterialTheme.colorScheme.error
        }
        is GpuDriverImportState.Installed -> {
            text = "Imported ${state.driver.label}."
            color = LauncherSuccess
        }
        GpuDriverImportState.Importing -> {
            text = "Inspecting driver package…"
            color = LauncherAccent
        }
        GpuDriverImportState.Idle -> {
            text = message
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    if (!text.isNullOrBlank()) Text(text, color = color, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun settingLabelStyle() = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 8.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp,
)

@Composable
internal fun GuidePage(onBrowseGames: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                PageHeading(
                    eyebrow = "Quick start",
                    title = "Guide",
                    subtitle = "From your game archive to a working dual-screen session.",
                )
                Button(
                    onClick = onBrowseGames,
                    modifier = Modifier.height(40.dp),
                    shape = RectangleShape,
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Browse Games", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.Top,
            ) {
                GuideStep(
                    number = "01",
                    icon = Icons.Default.Archive,
                    title = "Prepare your ZIP",
                    body = "ZIP the game folder you legally own. Files may be at the ZIP root or inside a top-level game folder; the launcher finds the real root automatically.",
                    modifier = Modifier.weight(1f),
                )
                GuideStep(
                    number = "02",
                    icon = Icons.Default.FolderOpen,
                    title = "Add the game",
                    body = "Choose a grey game card, open Game Details, then select Install Game. Pick the matching ZIP from device storage.",
                    modifier = Modifier.weight(1f),
                )
                GuideStep(
                    number = "03",
                    icon = Icons.Default.RocketLaunch,
                    title = "Let setup finish",
                    body = "The launcher validates critical files, warns instead of blocking an untested version, extracts, configures, installs the mod, and verifies the result.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.Top,
            ) {
                GuideStep(
                    number = "04",
                    icon = Icons.Default.PlayArrow,
                    title = "Launch",
                    body = "Use Play Game from Game Details. The top display runs gameplay while the lower display hosts the mod UI and launcher console.",
                    modifier = Modifier.weight(1f),
                )
                GuideStep(
                    number = "05",
                    icon = Icons.Default.SettingsApplications,
                    title = "Game launcher",
                    body = "Use Open Game Launcher only when a title needs its own settings tool. Return to Game Details afterward and use Play Game.",
                    modifier = Modifier.weight(1f),
                )
                GuideStep(
                    number = "06",
                    icon = Icons.Default.Save,
                    title = "Import saves",
                    body = "Choose Import Save ZIP from Game Details. Common wrapper layouts are detected, existing data is backed up, and live saves remain under /Dualscreen.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            LauncherCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Troubleshoot, contentDescription = null, tint = LauncherAccent, modifier = Modifier.size(22.dp))
                    SectionTitle("Troubleshooting", "The lower-screen console always shows the current setup or launch stage")
                }
                InformationRow("Archive rejected", "Confirm critical files exist; version differences only warn")
                InformationRow("Game returns immediately", "Open the console and read the final launch error")
                InformationRow("Wrong resolution", "Choose a tested resolution in Settings")
                InformationRow("Graphics problem", "Select another installed GPU driver")
                InformationRow("No lower-screen UI", "Wait for world/save initialization, then check mod status")
            }
        }
    }
}

@Composable
private fun GuideStep(
    number: String,
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    LauncherCard(modifier.height(140.dp), compact = true) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(LauncherAccent.copy(alpha = 0.65f)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(31.dp)
                    .background(LauncherAccent.copy(alpha = 0.13f), RectangleShape)
                    .border(1.dp, LauncherAccent.copy(alpha = 0.4f), RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = LauncherAccent, modifier = Modifier.size(17.dp))
            }
            Text(number, color = Color(0xFF5E5C53), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
