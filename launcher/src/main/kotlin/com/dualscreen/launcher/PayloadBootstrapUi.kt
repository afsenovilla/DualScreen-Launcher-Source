package com.dualscreen.launcher

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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PayloadBootstrapPage(
    state: PayloadBootstrapState,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF211F15), LauncherBackground, Color(0xFF12140F)),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .clip(RectangleShape)
                .background(LauncherPanel)
                .border(1.dp, LauncherOutline, RectangleShape)
                .padding(horizontal = 26.dp, vertical = 23.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(LauncherAccent))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RectangleShape)
                    .background(LauncherAccent.copy(alpha = 0.13f))
                    .border(1.dp, LauncherAccent.copy(alpha = 0.48f), RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state is PayloadBootstrapState.Failed) {
                        Icons.Default.ErrorOutline
                    } else {
                        Icons.Default.CloudDownload
                    },
                    contentDescription = null,
                    tint = if (state is PayloadBootstrapState.Failed) LauncherDanger else LauncherAccent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                "DUALSCREEN LAUNCHER",
                color = LauncherAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                when (state) {
                    PayloadBootstrapState.Checking -> CheckingPayloads()
                    is PayloadBootstrapState.Progress -> PayloadProgress(state)
                    is PayloadBootstrapState.Failed -> PayloadFailure(state.message, onRetry)
                    is PayloadBootstrapState.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun CheckingPayloads() {
    Text("Preparando el launcher", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(
        "Comprobando los componentes descargados…",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RectangleShape),
        color = LauncherAccent,
        trackColor = Color(0xFF34342C),
    )
}

@Composable
private fun PayloadProgress(state: PayloadBootstrapState.Progress) {
    Text("Primera configuración", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(
        state.detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
    )
    LauncherProgress(
        fraction = state.fraction,
        modifier = Modifier.fillMaxWidth().height(7.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "PAQUETE ${state.currentPack + 1} DE ${state.totalPacks}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Text(
            "${installPercent(state.fraction)}% OVERALL",
            color = LauncherAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
    }
    PayloadPhaseRail(state.phase)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            state.phase.label,
            color = LauncherAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
        Text(
            "${formatBytes(state.phaseCompletedBytes)} / ${formatBytes(state.phaseTotalBytes)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
    Text(
        "No cierres la app. Cada descarga se verifica antes de instalarse.",
        color = Color(0xFF817E74),
        fontSize = 10.sp,
    )
}

@Composable
private fun PayloadFailure(message: String, onRetry: () -> Unit) {
    Text("No se pudo completar", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text(message, color = LauncherDanger, fontSize = 12.sp)
    Button(
        onClick = onRetry,
        modifier = Modifier.height(44.dp),
        shape = RectangleShape,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("Reintentar descarga", fontWeight = FontWeight.Bold)
    }
}

private val PayloadBootstrapPhase.label: String
    get() = when (this) {
        PayloadBootstrapPhase.DOWNLOAD -> "DESCARGANDO"
        PayloadBootstrapPhase.VERIFY -> "VERIFICANDO"
        PayloadBootstrapPhase.INSTALL -> "INSTALANDO"
    }

@Composable
private fun PayloadPhaseRail(active: PayloadBootstrapPhase) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PayloadBootstrapPhase.entries.forEach { phase ->
            val complete = phase.ordinal < active.ordinal
            val selected = phase == active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RectangleShape)
                    .background(
                        when {
                            complete -> LauncherSuccess
                            selected -> LauncherAccent
                            else -> Color(0xFF34342C)
                        },
                    ),
            )
        }
    }
}
