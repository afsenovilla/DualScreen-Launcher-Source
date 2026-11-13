package com.dualscreen.launcher

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PcDriverDownloaderPage(
    operation: PcGpuDriverOperationState,
    installedCount: Int,
    loader: suspend () -> Result<List<GpuDriverRelease>>,
    onInstall: (GpuDriverArtifact) -> Unit,
    onBack: () -> Unit,
) {
    DriverDownloaderPage(
        operationRunning = operation.running,
        operationError = operation.error,
        operationDetail = operation.detail,
        operationFraction = operation.fraction,
        installedCount = installedCount,
        platformLabel = "PC GAMES",
        sourceDescription = "AdrenoTools Turnip and Freedreno repositories - choose a source, then a ZIP",
        loadingLabel = "Loading the PC GPU driver catalog...",
        loadFailureLabel = "Could not load the PC driver catalog.",
        loader = loader,
        onInstall = onInstall,
        onBack = onBack,
    )
}

@Composable
private fun DriverDownloaderPage(
    operationRunning: Boolean,
    operationError: String?,
    operationDetail: String,
    operationFraction: Float,
    installedCount: Int,
    platformLabel: String,
    sourceDescription: String,
    loadingLabel: String,
    loadFailureLabel: String,
    loader: suspend () -> Result<List<GpuDriverRelease>>,
    onInstall: (GpuDriverArtifact) -> Unit,
    onBack: () -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var releases by remember { mutableStateOf<List<GpuDriverRelease>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSource by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, operationRunning) {
        if (operationRunning) return@LaunchedEffect
        loading = true
        loader().onSuccess { loaded ->
            releases = loaded
            error = null
            if (selectedSource !in loaded.map { it.source }) selectedSource = loaded.firstOrNull()?.source
        }.onFailure { error = it.message ?: loadFailureLabel }
        loading = false
    }

    val sources = releases.map(GpuDriverRelease::source).distinct()
    val visible = releases.filter { selectedSource == null || it.source == selectedSource }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp).border(1.dp, LauncherOutline, RectangleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$platformLabel  •  VERIFIED DRIVER SOURCES",
                        color = LauncherAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                    )
                    Text("GPU Driver Downloader", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "$installedCount INSTALLED",
                    color = LauncherAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.width(10.dp))
                IconButton(
                    enabled = !loading && !operationRunning,
                    onClick = { refreshKey++ },
                    modifier = Modifier.size(38.dp).border(1.dp, LauncherOutline, RectangleShape),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh driver catalog")
                }
            }
        }

        if (operationRunning || operationError != null) {
            item {
                LauncherCard(compact = true) {
                    Text(
                        operationError ?: operationDetail,
                        color = if (operationError == null) LauncherAccent else LauncherDanger,
                        fontSize = 10.sp,
                    )
                    if (operationRunning) {
                        Spacer(Modifier.height(5.dp))
                        LauncherProgress(operationFraction, Modifier.fillMaxWidth().height(5.dp))
                    }
                }
            }
        }

        item {
            LauncherCard(compact = true) {
                SectionTitle(
                    "Driver sources",
                    sourceDescription,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(sources, key = { it }) { source ->
                        DriverSourceButton(
                            label = source,
                            selected = source == selectedSource,
                            onClick = { selectedSource = source },
                        )
                    }
                }
            }
        }

        when {
            loading -> item {
                LauncherCard { Text(loadingLabel, color = LauncherTextMuted) }
            }
            error != null -> item {
                LauncherCard {
                    Text(error.orEmpty(), color = LauncherDanger)
                    Spacer(Modifier.height(7.dp))
                    PrimaryAction(
                        label = "Retry",
                        icon = Icons.Default.Refresh,
                        onClick = { refreshKey++ },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            visible.isEmpty() -> item {
                LauncherCard { Text("No compatible driver ZIPs were returned.", color = LauncherTextMuted) }
            }
            else -> items(
                visible,
                key = { "${it.source}:${it.tag}:${it.publishedAt}" },
            ) { release ->
                LauncherCard(compact = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    release.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (release.latest) {
                                    Text(
                                        "LATEST",
                                        color = LauncherAccent,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                    )
                                }
                            }
                            Text(
                                "${release.source}  •  ${release.tag}  •  ${release.publishedAt.take(10)}",
                                color = LauncherTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                            )
                            if (release.description.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    release.description.replace("\\r\\n", "\n").replace("\\n", "\n"),
                                    color = LauncherTextMuted,
                                    fontSize = 9.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(0.82f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            release.artifacts.forEach { artifact ->
                                PrimaryAction(
                                    label = if (artifact.installed) {
                                        "Installed • ${artifact.name}"
                                    } else {
                                        "${artifact.name} • ${formatDriverBytes(artifact.sizeBytes)}"
                                    },
                                    icon = if (artifact.installed) Icons.Default.Check else Icons.Default.Download,
                                    onClick = { onInstall(artifact) },
                                    enabled = !artifact.installed && !operationRunning,
                                    primary = !artifact.installed,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriverSourceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RectangleShape,
        modifier = Modifier
            .width(180.dp)
            .height(38.dp)
            .background(if (selected) LauncherAccent.copy(alpha = 0.16f) else LauncherPanelRaised)
            .border(1.dp, if (selected) LauncherAccent else LauncherOutline, RectangleShape),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            label,
            color = if (selected) LauncherAccent else LauncherTextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatDriverBytes(bytes: Long): String = when {
    bytes <= 0L -> "ZIP"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1_000L} KB"
}
