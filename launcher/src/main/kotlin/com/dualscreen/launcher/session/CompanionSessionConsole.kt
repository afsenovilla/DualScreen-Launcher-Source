package com.dualscreen.launcher.session

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.display.SecondaryDisplay
import com.thor.core.model.ConsolePage
import com.dualscreen.launcher.CompanionButton
import com.dualscreen.launcher.CompanionConsoleShapeSet
import com.dualscreen.launcher.CompanionNavPill
import com.dualscreen.launcher.CompanionPanel
import com.dualscreen.launcher.CompanionTheme
import com.dualscreen.launcher.LocalCompanionShapes
import com.dualscreen.launcher.MonoLabel
import com.dualscreen.launcher.Size
import com.dualscreen.launcher.Tone
import com.wemu.engine.renderer.SecondScreenOutput
import com.wemu.engine.winhandler.ProcessInfo
import com.wemu.session.secondscreen.SecondScreen
import com.wemu.session.secondscreen.StardewScreen
import com.wemu.session.secondscreen.ValheimScreen

/** Hosts the game on the upper panel and WEMU's switchable console below it. */
@Composable
fun CompanionSessionHost(
    gameRoot: FrameLayout,
    secondaryDisplayId: Int?,
    title: String,
    managedPanel: Boolean,
    diagnosticsOnly: Boolean = false,
    controls: CompanionRuntimeControls?,
    metrics: CompanionSessionMetrics,
    log: CompanionSessionLog,
    onExit: () -> Unit,
) {
    val panelRouter = remember { CompanionGuestPanelMotionRouter() }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { gameRoot },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
        )
    }

    SecondaryDisplay(
        displayId = secondaryDisplayId,
        enabled = { true },
        takesFocus = { false },
        touchDispatcher = panelRouter::dispatch,
    ) {
        CompanionTheme {
            CompanionBottomPanel(
                title = title,
                managedPanel = managedPanel,
                diagnosticsOnly = diagnosticsOnly,
                controls = controls,
                metrics = metrics,
                log = log,
                router = panelRouter,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun CompanionBottomPanel(
    title: String,
    managedPanel: Boolean,
    diagnosticsOnly: Boolean,
    controls: CompanionRuntimeControls?,
    metrics: CompanionSessionMetrics,
    log: CompanionSessionLog,
    router: CompanionGuestPanelMotionRouter,
    onExit: () -> Unit,
) {
    var panelAvailable by remember { mutableStateOf(false) }
    var guestOwnsPanel by remember { mutableStateOf(false) }
    var consoleVisible by remember(diagnosticsOnly) { mutableStateOf(diagnosticsOnly) }

    DisposableEffect(router, managedPanel) {
        if (managedPanel) SecondScreen.start()
        SecondScreenOutput.setOnStateListener { available, claimed ->
            PANEL_HANDLER.post {
                panelAvailable = available
                guestOwnsPanel = claimed
            }
        }
        onDispose {
            router.reset()
            SecondScreenOutput.setOnStateListener(null)
            if (managedPanel) SecondScreen.shutdown()
        }
    }

    SideEffect {
        router.enabled = !diagnosticsOnly && guestOwnsPanel && !consoleVisible && controls?.keyboard?.visible != true
        metrics.consoleVisible = consoleVisible
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { router.resize(it.width, it.height) },
    ) {
        if (panelAvailable && !diagnosticsOnly) GuestPanelSurface()

        when {
            consoleVisible -> CompositionLocalProvider(
                LocalCompanionShapes provides CompanionConsoleShapeSet,
            ) {
                SessionConsole(
                    title = title,
                    diagnosticsOnly = diagnosticsOnly,
                    controls = controls,
                    metrics = metrics,
                    log = log,
                    onShowGame = { if (!diagnosticsOnly) consoleVisible = false },
                    onExit = onExit,
                )
            }

            managedPanel -> ThorTheme {
                if (title == "Stardew Valley") {
                    StardewScreen(Modifier.fillMaxSize())
                } else {
                    ValheimScreen(Modifier.fillMaxSize())
                }
            }
            guestOwnsPanel -> Unit
            else -> WaitingForGamePanel(title)
        }

        val keyboard = controls?.keyboard
        if (keyboard?.visible == true) {
            CompositionLocalProvider(LocalCompanionShapes provides CompanionConsoleShapeSet) {
                CompanionGuestKeyboardView(keyboard)
            }
        }

        PanelModeGesture(
            consoleVisible = consoleVisible,
            keyboardVisible = keyboard?.visible == true,
            onToggle = {
                if (keyboard?.visible == true) keyboard.hide()
                else if (!diagnosticsOnly) consoleVisible = !consoleVisible
            },
        )
    }
}

/** A parent surface for the guest's zero-copy lower-panel SurfaceControl. */
@Composable
private fun GuestPanelSurface() {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderMediaOverlay(true)
                val panelView = this
                holder.addCallback(object : SurfaceHolder.Callback {
                    private var attachedWidth = 0
                    private var attachedHeight = 0

                    private fun attachIfReady(
                        holder: SurfaceHolder,
                        offeredWidth: Int = 0,
                        offeredHeight: Int = 0,
                    ) {
                        val frame = holder.surfaceFrame
                        val width = listOf(offeredWidth, frame.width(), panelView.width)
                            .firstOrNull { it > 0 } ?: return
                        val height = listOf(offeredHeight, frame.height(), panelView.height)
                            .firstOrNull { it > 0 } ?: return
                        if (!holder.surface.isValid ||
                            width == attachedWidth && height == attachedHeight &&
                            SecondScreenOutput.isAttached()
                        ) return

                        attachedWidth = width
                        attachedHeight = height
                        SecondScreenOutput.requestAttach(holder.surface, width, height)
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Presentation-backed SurfaceViews do not always send a
                        // distinct surfaceChanged callback for their first,
                        // already-correctly-sized buffer.
                        attachIfReady(holder)
                        panelView.post { attachIfReady(holder) }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        attachIfReady(holder, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        SecondScreenOutput.requestDetach()
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun WaitingForGamePanel(title: String) {
    val colors = CompanionTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(colors.backdrop),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoLabel(text = title, color = colors.textMuted)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "ESPERANDO AL PANEL DEL JUEGO",
                color = colors.text,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            MonoLabel(text = "Desliza arriba para la consola", small = true)
        }
    }
}

/** Same bottom-center upward swipe used by WEMU to toggle console and game. */
@Composable
private fun BoxScope.PanelModeGesture(
    consoleVisible: Boolean,
    keyboardVisible: Boolean,
    onToggle: () -> Unit,
) {
    val colors = CompanionTheme.colors
    var travel = 0f
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .width(PANEL_HANDLE_WIDTH)
            .height(PANEL_HANDLE_HEIGHT)
            .pointerInput(consoleVisible, keyboardVisible) {
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragCancel = { travel = 0f },
                    onDragEnd = {
                        if (shouldToggleConsole(travel, PANEL_GESTURE_TRAVEL.toPx())) onToggle()
                        travel = 0f
                    },
                ) { change, delta ->
                    travel += delta
                    change.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 4.dp)
                .clip(CompanionTheme.shapes.control)
                .background(colors.text.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun SessionConsole(
    title: String,
    diagnosticsOnly: Boolean,
    controls: CompanionRuntimeControls?,
    metrics: CompanionSessionMetrics,
    log: CompanionSessionLog,
    onShowGame: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = CompanionTheme.colors
    val space = CompanionTheme.space
    var page by remember(diagnosticsOnly) { mutableStateOf(if (diagnosticsOnly) ConsolePage.LOGS else ConsolePage.STATS) }
    val ready = controls != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            // WEMU's console uses its 8dp small spacing as both the panel inset
            // and the rhythm between header, selector, page and actions.
            .padding(CONSOLE_INSET.dp),
        verticalArrangement = Arrangement.spacedBy(CONSOLE_INSET.dp),
    ) {
        ConsoleHeader(title, controls, metrics, onShowGame)
        ConsoleSelector(
            selected = page,
            enabled = ready,
            onSelect = { destination ->
                if (destination == ConsolePage.MODS) onShowGame() else page = destination
            },
        )

        CompanionPanel(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Crossfade(
                targetState = page,
                animationSpec = tween(CONSOLE_TRANSITION_MILLIS),
                label = "console page",
            ) { active ->
                when (active) {
                    ConsolePage.STATS -> StatsPage(metrics, controls, Modifier.fillMaxSize())
                    ConsolePage.PROGRAMS -> ProgramsPage(title, Modifier.fillMaxSize())
                    ConsolePage.INPUT -> ControlsRequired(controls) {
                        InputPage(it, Modifier.fillMaxSize())
                    }
                    ConsolePage.MOUSE -> ControlsRequired(controls) {
                        MousePage(it, Modifier.fillMaxSize())
                    }
                    ConsolePage.GRAPHICS -> ControlsRequired(controls) {
                        GraphicsPage(it, Modifier.fillMaxSize())
                    }
                    ConsolePage.SCREEN -> ControlsRequired(controls) {
                        ScreenPage(it, Modifier.fillMaxSize())
                    }
                    ConsolePage.PROCESSES -> ControlsRequired(controls) {
                        ProcessesPage(it.processes, Modifier.fillMaxSize())
                    }
                    ConsolePage.MODS -> Unit
                    ConsolePage.LOGS -> LogsPage(log, Modifier.fillMaxSize())
                }
            }
        }

        ConsoleActions(onExit)
    }
}

@Composable
private fun ConsoleHeader(
    title: String,
    controls: CompanionRuntimeControls?,
    metrics: CompanionSessionMetrics,
    onShowGame: () -> Unit,
) {
    val colors = CompanionTheme.colors
    val space = CompanionTheme.space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CompanionTheme.shapes.card)
            .background(colors.card)
            .border(1.dp, colors.outline, CompanionTheme.shapes.card)
            .padding(horizontal = CONSOLE_HEADER_HORIZONTAL.dp,
                vertical = CONSOLE_HEADER_VERTICAL.dp),
        horizontalArrangement = Arrangement.spacedBy(CONSOLE_HEADER_HORIZONTAL.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(space.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LIVE SESSION",
                    color = colors.accentText,
                    style = CompanionTheme.type.monoSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = title.ifEmpty { "Session" },
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MonoLabel(
                text = if (controls == null) {
                    "Starting…"
                } else {
                    "${metrics.fps} FPS · ${formatUptime(metrics.uptimeSeconds)}"
                },
                color = if (controls == null) colors.textMuted else colors.accentText,
                small = true,
            )
        }
        ConsoleCompactButton(
            label = if (controls?.keyboard?.visible == true) "Hide keyboard" else "Keyboard",
            icon = Icons.Rounded.Keyboard,
            onClick = { controls?.keyboard?.toggle() },
            enabled = controls != null,
        )
        ConsoleCompactButton(label = "Close", onClick = onShowGame)
    }
}

private fun consoleIcon(page: ConsolePage): ImageVector = when (page) {
    ConsolePage.STATS -> Icons.Rounded.BarChart
    ConsolePage.INPUT -> Icons.Rounded.SportsEsports
    ConsolePage.MOUSE -> Icons.Rounded.Mouse
    ConsolePage.GRAPHICS -> Icons.Rounded.Monitor
    ConsolePage.SCREEN -> Icons.Rounded.CropSquare
    ConsolePage.PROCESSES -> Icons.Rounded.FormatListBulleted
    ConsolePage.MODS -> Icons.Rounded.Extension
    ConsolePage.PROGRAMS -> Icons.Rounded.Apps
    ConsolePage.LOGS -> Icons.Rounded.Article
}

private fun consoleDescription(page: ConsolePage): String = when (page) {
    ConsolePage.STATS -> "Live performance and resource usage"
    ConsolePage.PROGRAMS -> "Browse files and launch another program"
    ConsolePage.INPUT -> "Keyboard, touch controls and gamepads"
    ConsolePage.MOUSE -> "Pointer behavior, speed and capture"
    ConsolePage.GRAPHICS -> "Frame pacing, scaling and screen effects"
    ConsolePage.SCREEN -> "Window fitting, magnification and PiP"
    ConsolePage.PROCESSES -> "Inspect and end guest processes"
    ConsolePage.MODS -> "What the running game's mod puts here"
    ConsolePage.LOGS -> "Startup checks and live guest output"
}

@Composable
private fun ConsoleSelector(
    selected: ConsolePage,
    enabled: Boolean,
    onSelect: (ConsolePage) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var selectorWidthPx by remember { mutableStateOf(0) }
    val colors = CompanionTheme.colors
    val space = CompanionTheme.space
    val density = LocalDensity.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = CONSOLE_SELECTOR_HEIGHT.dp)
                .onSizeChanged { selectorWidthPx = it.width }
                .clip(CompanionTheme.shapes.control)
                .background(colors.card)
                .border(1.dp, colors.outline, CompanionTheme.shapes.control)
                .clickable { open = !open }
                .padding(horizontal = CONSOLE_HEADER_HORIZONTAL.dp,
                    vertical = CONSOLE_SELECTOR_VERTICAL.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.tight),
        ) {
            Icon(
                imageVector = consoleIcon(selected),
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected.label,
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MonoLabel(consoleDescription(selected), small = true, maxLines = 1)
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Close page menu" else "Open page menu",
                tint = colors.textMuted,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }

        if (open && selectorWidthPx > 0) {
            Popup(
                popupPositionProvider = ConsoleMenuPositionProvider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(with(density) { selectorWidthPx.toDp() })
                        .heightIn(max = CONSOLE_MENU_MAX_HEIGHT.dp)
                        .clip(CompanionTheme.shapes.card)
                        .background(colors.card)
                        .border(1.dp, colors.outline, CompanionTheme.shapes.card)
                        .padding(vertical = CONSOLE_MENU_INSET.dp),
                    verticalArrangement = Arrangement.spacedBy(CONSOLE_MENU_GAP.dp),
                ) {
                    ConsolePage.entries.forEach { destination ->
                        val available = enabled || destination == ConsolePage.STATS ||
                            destination == ConsolePage.LOGS ||
                            destination == ConsolePage.PROGRAMS
                        val selectedRow = destination == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = CONSOLE_MENU_ROW_HEIGHT.dp)
                                .padding(horizontal = CONSOLE_MENU_INSET.dp)
                                .clip(CompanionTheme.shapes.control)
                                .background(
                                    if (selectedRow) colors.cardFooter else colors.card,
                                )
                                .clickable(enabled = available) {
                                    open = false
                                    onSelect(destination)
                                }
                                .focusable(enabled = available)
                                .padding(horizontal = CONSOLE_MENU_ROW_PAD.dp,
                                    vertical = CONSOLE_MENU_ROW_PAD.dp),
                            horizontalArrangement = Arrangement.spacedBy(CONSOLE_MENU_ROW_PAD.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = consoleIcon(destination),
                                contentDescription = null,
                                tint = when {
                                    !available -> colors.textMuted.copy(alpha = 0.5f)
                                    selectedRow -> colors.accentText
                                    else -> colors.text
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    destination.label,
                                    color = when {
                                        !available -> colors.textMuted.copy(alpha = 0.5f)
                                        selectedRow -> colors.accentText
                                        else -> colors.text
                                    },
                                    fontWeight = if (selectedRow) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                MonoLabel(
                                    consoleDescription(destination),
                                    color = colors.textMuted,
                                    small = true,
                                    maxLines = 1,
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
private fun ConsoleActions(onExit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(CONSOLE_INSET.dp),
    ) {
        CompanionButton(
            label = "Exit session",
            icon = Icons.AutoMirrored.Rounded.ExitToApp,
            onClick = onExit,
            tone = Tone.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConsoleCompactButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = CompanionTheme.colors
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = CONSOLE_COMPACT_BUTTON_HEIGHT.dp)
            .clip(CompanionTheme.shapes.control)
            .background(if (enabled) colors.cardFooter else colors.cardFooter.copy(alpha = 0.4f))
            .border(1.dp, colors.outline, CompanionTheme.shapes.control)
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled = enabled)
            .padding(horizontal = CONSOLE_COMPACT_BUTTON_HORIZONTAL.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) colors.text else colors.textMuted,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            color = if (enabled) colors.text else colors.textMuted,
            style = CompanionTheme.type.monoSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private object ConsoleMenuPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = CONSOLE_MENU_MARGIN
        val gap = CONSOLE_MENU_POPUP_GAP
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(margin)
        }
        val x = anchorBounds.left.coerceIn(
            margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
        )
        return IntOffset(x, y)
    }
}

@Composable
private fun StatsPage(
    metrics: CompanionSessionMetrics,
    controls: CompanionRuntimeControls?,
    modifier: Modifier = Modifier,
) {
    val colors = CompanionTheme.colors
    BoxWithConstraints(modifier = modifier.padding(8.dp)) {
        val compact = maxHeight < 290.dp || maxWidth < 650.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PerformanceSectionLabel("Performance")
            FrameRateCard(
                metrics = metrics,
                fpsLimit = controls?.fpsLimit ?: 0,
                compact = compact,
                modifier = Modifier.weight(0.44f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PerformanceSectionLabel("System resources")
                Box(Modifier.weight(1f).height(1.dp).background(colors.outline.copy(alpha = 0.7f)))
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(0.56f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ResourceStatCard(
                    icon = Icons.Rounded.Memory,
                    label = "CPU",
                    headline = metrics.cpuUsagePercent.takeIf { it >= 0 }?.let { "$it%" } ?: "—",
                    aside = metrics.cpuClocks.maxOrNull()?.let { "$it MHz" } ?: "Unavailable",
                    history = metrics.cpuHistory,
                    leading = "${metrics.cpuCoreCount}",
                    leadingLabel = "Cores",
                    trailing = metrics.cpuHistory.averageOrNull()?.let { "$it%" } ?: "—",
                    trailingLabel = "Avg usage",
                    compact = compact,
                    modifier = Modifier.weight(1f),
                )
                MemoryStatCard(metrics, compact, Modifier.weight(1f))
                ResourceStatCard(
                    icon = Icons.Rounded.Monitor,
                    label = "GPU",
                    headline = metrics.gpuLoadPercent.takeIf { it >= 0 }?.let { "$it%" } ?: "—",
                    aside = metrics.gpuClockMhz.takeIf { it >= 0 }?.let { "$it MHz" } ?: "Unavailable",
                    history = metrics.gpuHistory,
                    leading = metrics.gpuMaxClockMhz.takeIf { it > 0 }?.let { "$it MHz" } ?: "—",
                    leadingLabel = "Max clock",
                    trailing = metrics.gpuHistory.averageOrNull()?.let { "$it%" } ?: "—",
                    trailingLabel = "Avg load",
                    compact = compact,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FrameRateCard(
    metrics: CompanionSessionMetrics,
    fpsLimit: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CompanionTheme.colors
    PerformanceCard(modifier) {
        Row(
            modifier = Modifier.fillMaxSize().padding(if (compact) 9.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.width(if (compact) 108.dp else 138.dp)) {
                PerformanceCardHeading(Icons.AutoMirrored.Rounded.ShowChart, "FPS", compact)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        metrics.fps.toString(),
                        color = colors.text,
                        fontSize = if (compact) 28.sp else 36.sp,
                        lineHeight = if (compact) 30.sp else 38.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "fps",
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                Text(
                    when {
                        metrics.fps <= 0 -> "Idle"
                        metrics.onePercentLowFps > 0 -> "${metrics.onePercentLowFps} fps low"
                        else -> "Running"
                    },
                    color = colors.accentText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
                metrics.zeroCopy?.let { shared ->
                    Text(
                        if (shared) "Shared buffer" else "Copied frames",
                        color = if (shared) colors.textMuted else colors.danger,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
            PerformanceLineChart(
                values = metrics.fpsHistory.map(Int::toFloat),
                ceiling = chartCeiling(metrics.fpsHistory, fpsLimit),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ResourceStatCard(
    icon: ImageVector,
    label: String,
    headline: String,
    aside: String,
    history: List<Int>,
    leading: String,
    leadingLabel: String,
    trailing: String,
    trailingLabel: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CompanionTheme.colors
    PerformanceCard(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PerformanceCardHeading(icon, label, compact)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    headline,
                    color = colors.accentText,
                    fontSize = if (compact) 18.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (!compact) Text(aside, color = colors.text, fontSize = 9.sp, maxLines = 1)
            }
            if (compact) {
                UsageBar(
                    history.lastOrNull()?.div(100f) ?: 0f,
                    Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                PerformanceLineChart(
                    history.map(Int::toFloat),
                    100f,
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
            PerformanceFootnotes(
                if (compact) aside else leading,
                if (compact) "Core clock" else leadingLabel,
                trailing,
                trailingLabel,
            )
        }
    }
}

@Composable
private fun MemoryStatCard(
    metrics: CompanionSessionMetrics,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CompanionTheme.colors
    val total = metrics.totalMemoryMb
    val used = metrics.usedMemoryMb
    val fraction = if (total > 0) used.toFloat() / total else 0f
    PerformanceCard(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PerformanceCardHeading(Icons.Rounded.Storage, "Memory", compact)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (total > 0) "%.2f GB".format(used / 1024f) else "—",
                    color = colors.text,
                    fontSize = if (compact) 17.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (total > 0) "${(fraction * 100).toInt()}%" else "",
                    color = colors.accentText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            UsageBar(fraction, Modifier.fillMaxWidth())
            if (compact) {
                Spacer(Modifier.weight(1f))
            } else {
                PerformanceLineChart(
                    metrics.memoryHistory.map(Int::toFloat),
                    100f,
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
            PerformanceFootnotes(
                if (total > 0) "%.1f GB".format(total / 1024f) else "—",
                "Total",
                if (total > 0) "%.1f GB".format((total - used) / 1024f) else "—",
                "Free",
            )
        }
    }
}

@Composable
private fun PerformanceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = CompanionTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CompanionTheme.shapes.card)
            .background(colors.cardFooter)
            .border(1.dp, colors.outline, CompanionTheme.shapes.card),
    ) { content() }
}

@Composable
private fun PerformanceCardHeading(icon: ImageVector, label: String, compact: Boolean) {
    val colors = CompanionTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 19.dp else 23.dp)
                .clip(CompanionTheme.shapes.control)
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(if (compact) 12.dp else 15.dp),
            )
        }
        Text(
            label.uppercase(),
            color = colors.text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 8.sp else 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun PerformanceSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = CompanionTheme.colors.accentText,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
    )
}

@Composable
private fun PerformanceFootnotes(
    leading: String,
    leadingLabel: String,
    trailing: String,
    trailingLabel: String,
) {
    val colors = CompanionTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Column {
            Text(leading, color = colors.text, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(leadingLabel, color = colors.textMuted, fontSize = 7.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(trailing, color = colors.text, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(trailingLabel, color = colors.textMuted, fontSize = 7.sp, maxLines = 1)
        }
    }
}

@Composable
private fun UsageBar(progress: Float, modifier: Modifier = Modifier) {
    val colors = CompanionTheme.colors
    Box(
        modifier = modifier.height(5.dp).clip(CompanionTheme.shapes.control).background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .background(Brush.horizontalGradient(listOf(colors.accent, colors.accentText))),
        )
    }
}

@Composable
private fun PerformanceLineChart(
    values: List<Float>,
    ceiling: Float,
    modifier: Modifier = Modifier,
) {
    val colors = CompanionTheme.colors
    Canvas(modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = size.height * (1f - fraction)
            drawLine(
                colors.outline.copy(alpha = if (fraction == 0f) 0.8f else 0.55f),
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = if (fraction == 0f) null else dash,
            )
        }
        if (values.size >= 2) drawPerformanceTrace(values, ceiling.coerceAtLeast(1f), colors.accentText)
    }
}

private fun DrawScope.drawPerformanceTrace(values: List<Float>, ceiling: Float, color: Color) {
    val step = size.width / (values.size - 1).coerceAtLeast(1)
    fun y(value: Float) = size.height * (1f - (value / ceiling).coerceIn(0f, 1f))
    val line = Path().apply {
        moveTo(0f, y(values.first()))
        values.forEachIndexed { index, value -> if (index > 0) lineTo(index * step, y(value)) }
    }
    val area = Path().apply {
        addPath(line)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent)))
    drawPath(line, color, style = Stroke(width = 2f))
}

private fun chartCeiling(history: List<Int>, cap: Int): Float {
    if (cap > 0) return cap.toFloat()
    val peak = history.maxOrNull() ?: 0
    return listOf(30, 60, 90, 120, 144, 165, 240).firstOrNull { it > peak }?.toFloat() ?: 240f
}

private fun List<Int>.averageOrNull(): Int? =
    if (isEmpty()) null else (sum().toFloat() / size).toInt()

@Composable
private fun ProgramsPage(title: String, modifier: Modifier = Modifier) {
    PageList(modifier) {
        PageHeading("Programs", "Companion keeps the Windows desktop hidden during play")
        ConsoleInfoRow("Running preset", title)
        ConsoleInfoRow("Launch policy", "Game executable only")
        ConsoleInfoRow("Container", "Preconfigured and locked")
    }
}

@Composable
private fun InputPage(controls: CompanionRuntimeControls, modifier: Modifier = Modifier) {
    PageList(modifier) {
        PageHeading("Keyboard", "Input is delivered directly to the active game")
        item {
            CompanionButton(
                label = "Show full keyboard",
                icon = Icons.Rounded.Keyboard,
                onClick = controls.keyboard::show,
                modifier = Modifier.fillMaxWidth(),
                tone = Tone.PRIMARY,
            )
        }
        PageHeading("Controller", "Switch only when an older game requires DirectInput")
        ConsoleSwitchRow(
            "XInput",
            if (controls.xInput) "Reported as an Xbox controller" else "Reported as DirectInput",
            controls.xInput,
        ) { controls.xInput = it }
        controls.vibrationSlots.forEachIndexed { index, enabled ->
            ConsoleSwitchRow("Player ${index + 1} rumble", "Guest vibration feedback", enabled) {
                controls.setVibration(index, it)
            }
        }
    }
}

@Composable
private fun MousePage(controls: CompanionRuntimeControls, modifier: Modifier = Modifier) {
    PageList(modifier) {
        PageHeading("Pointer", "Live mouse behavior for the running game")
        ConsoleSwitchRow("Touch moves pointer", "Disable when using a real mouse", controls.mouseEnabled) {
            controls.mouseEnabled = it
        }
        ConsoleSliderRow(
            "Pointer speed",
            controls.cursorSpeed,
            0.25f..3f,
            { "%.2f×".format(it) },
        ) { controls.cursorSpeed = it }
        ConsoleSwitchRow("Two-finger right click", "Touchpad secondary click", controls.rightClick) {
            controls.rightClick = it
        }
        ConsoleSwitchRow("Capture pointer", "Raw relative motion for mouse-look", controls.pointerCapture) {
            controls.pointerCapture = it
        }
    }
}

@Composable
private fun GraphicsPage(controls: CompanionRuntimeControls, modifier: Modifier = Modifier) {
    PageList(modifier) {
        PageHeading("Frame pacing", "Changes apply to the running renderer")
        ConsoleChoiceRow(
            "Frame limit",
            listOf(0, 30, 40, 45, 60, 90, 120),
            controls.fpsLimit,
            label = { if (it == 0) "Uncapped" else "$it FPS" },
        ) { controls.fpsLimit = it }
        ConsoleChoiceRow(
            "Present mode",
            listOf("fifo", "mailbox", "immediate"),
            controls.presentMode,
            label = { it.replaceFirstChar(Char::uppercaseChar) },
        ) { controls.presentMode = it }

        if (controls.shaderControlsAvailable) {
            PageHeading("Image", "Upscaling and post-processing")
            ConsoleChoiceRow(
                "Upscaler",
                listOf<Int?>(null, 0, 1, 2),
                controls.upscaler,
                label = { it?.let { index -> "Mode ${index + 1}" } ?: "Off" },
            ) { controls.upscaler = it }
            ConsoleSliderRow("Sharpness", controls.sharpness, 0f..1f, { "${(it * 100).toInt()}%" }) {
                controls.sharpness = it
            }
            ConsoleChoiceRow(
                "Screen effect",
                RendererOptionsLabels.postFx,
                controls.postFxMode,
                label = { "Mode ${it + 1}" },
            ) { controls.postFxMode = it }
            ConsoleSwitchRow("Swap red and blue", "Compatibility correction", controls.swapRedBlue) {
                controls.swapRedBlue = it
            }
        }
    }
}

@Composable
private fun ScreenPage(controls: CompanionRuntimeControls, modifier: Modifier = Modifier) {
    PageList(modifier) {
        PageHeading("Fit", "How the guest picture meets the upper panel")
        ConsoleSwitchRow("Fit game to screen", "Expand a lower-resolution game window", controls.fitWindow) {
            controls.fitWindow = it
        }
        ConsoleSwitchRow("Stretch to fill", "Fill instead of preserving aspect ratio", controls.stretched) {
            if (it != controls.stretched) controls.toggleStretch()
        }
        ConsoleSwitchRow("Soft stretch", "Smooth pixels while stretching", controls.softStretch) {
            controls.softStretch = it
        }
    }
}

@Composable
private fun ProcessesPage(monitor: CompanionProcessMonitor, modifier: Modifier = Modifier) {
    LaunchedEffect(monitor) { monitor.pollWhileVisible() }
    LazyColumn(
        modifier = modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PageHeadingContent(
                "Processes",
                if (monitor.hasReported) "${monitor.processes.size} guest processes" else "Waiting for Wine",
            )
        }
        items(monitor.processes, key = ProcessInfo::pid) { process ->
            ConsoleRow {
                Column(modifier = Modifier.weight(1f)) {
                    Text(process.name, color = CompanionTheme.colors.text, maxLines = 1)
                    MonoLabel(
                        text = "PID ${process.pid}  ·  ${process.formattedMemoryUsage}",
                        small = true,
                    )
                }
                CompanionButton(
                    label = "End",
                    onClick = { monitor.kill(process) },
                    tone = Tone.DANGER,
                    size = Size.COMPACT,
                )
            }
        }
    }
}

@Composable
private fun LogsPage(log: CompanionSessionLog, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item { PageHeadingContent("Guest output", "The newest 500 runtime lines") }
        items(log.lines) { line ->
            Text(
                text = line,
                color = CompanionTheme.colors.textMuted,
                fontFamily = FontFamily.Monospace,
                style = CompanionTheme.type.monoSmall,
            )
        }
    }
}

@Composable
private fun ControlsRequired(
    controls: CompanionRuntimeControls?,
    content: @Composable (CompanionRuntimeControls) -> Unit,
) {
    if (controls == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MonoLabel("Waiting for the game engine")
        }
    } else {
        content(controls)
    }
}

@Composable
private fun PageList(
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.PageHeading(
    title: String,
    subtitle: String,
) {
    item { PageHeadingContent(title, subtitle) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ConsoleInfoRow(label: String, value: String) {
    item { ConsoleInfoRowContent(label, value) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ConsoleSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    item { ConsoleSwitchRowContent(title, subtitle, checked, onCheckedChange) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ConsoleSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    item { ConsoleSliderRowContent(title, value, range, valueLabel, onValueChange) }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.ConsoleChoiceRow(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    item { ConsoleChoiceRowContent(title, choices, selected, label, onSelect) }
}

@Composable
private fun PageHeadingContent(title: String, subtitle: String) {
    Column {
        Text(title, color = CompanionTheme.colors.text, fontWeight = FontWeight.Bold)
        MonoLabel(subtitle, small = true, maxLines = 2)
    }
}

@Composable
private fun ConsoleInfoRowContent(label: String, value: String) {
    ConsoleRow {
        Text(label, color = CompanionTheme.colors.text, modifier = Modifier.weight(1f))
        MonoLabel(value, color = CompanionTheme.colors.accentText)
    }
}

@Composable
private fun ConsoleSwitchRowContent(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ConsoleRow(onClick = { onCheckedChange(!checked) }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = CompanionTheme.colors.text)
            MonoLabel(subtitle, small = true, maxLines = 2)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConsoleSliderRowContent(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CompanionTheme.shapes.control)
            .background(CompanionTheme.colors.cardFooter)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row {
            Text(title, color = CompanionTheme.colors.text, modifier = Modifier.weight(1f))
            MonoLabel(valueLabel(value), color = CompanionTheme.colors.accentText)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun <T> ConsoleChoiceRowContent(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CompanionTheme.shapes.control)
            .background(CompanionTheme.colors.cardFooter)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = CompanionTheme.colors.text)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(choices) { choice ->
                CompanionNavPill(
                    label = label(choice),
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                )
            }
        }
    }
}

@Composable
private fun ConsoleRow(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(CompanionTheme.shapes.control)
        .background(CompanionTheme.colors.cardFooter)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun formatUptime(totalSeconds: Int): String {
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private object RendererOptionsLabels {
    val postFx: List<Int> = listOf(0, 1, 2, 3)
}

private const val CONSOLE_SELECTOR_HEIGHT = 58
private const val CONSOLE_TRANSITION_MILLIS = 180
private const val CONSOLE_INSET = 8
private const val CONSOLE_HEADER_HORIZONTAL = 16
private const val CONSOLE_HEADER_VERTICAL = 4
private const val CONSOLE_SELECTOR_VERTICAL = 8
private const val CONSOLE_COMPACT_BUTTON_HEIGHT = 32
private const val CONSOLE_COMPACT_BUTTON_HORIZONTAL = 8
private const val CONSOLE_MENU_MAX_HEIGHT = 360
private const val CONSOLE_MENU_ROW_HEIGHT = 42
private const val CONSOLE_MENU_ROW_PAD = 8
private const val CONSOLE_MENU_INSET = 6
private const val CONSOLE_MENU_GAP = 2
private const val CONSOLE_MENU_MARGIN = 8
private const val CONSOLE_MENU_POPUP_GAP = 6
private val PANEL_HANDLER = android.os.Handler(android.os.Looper.getMainLooper())
private val PANEL_HANDLE_WIDTH = 72.dp
private val PANEL_HANDLE_HEIGHT = 22.dp
private val PANEL_GESTURE_TRAVEL = 52.dp

/** Up is negative in Compose pointer coordinates. */
internal fun shouldToggleConsole(travelPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && travelPx <= -thresholdPx
