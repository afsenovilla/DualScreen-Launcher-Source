package com.dualscreen.launcher.session

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.Display
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.dualscreen.launcher.CompanionTheme
import com.dualscreen.launcher.DualScreenLauncherApplication
import com.dualscreen.launcher.DualscreenStorage
import com.dualscreen.launcher.ExecutableDllOverrideConfiguration
import com.dualscreen.launcher.GameCatalog
import com.dualscreen.launcher.LaunchStatusStore
import com.dualscreen.launcher.LowerPanelMode
import com.dualscreen.launcher.PresetContainerManager
import com.dualscreen.launcher.SupportedGame
import com.dualscreen.launcher.resolveConfigurationLauncherExecutable
import com.dualscreen.launcher.supportedGameLaunchEnvironment
import com.thor.core.display.DisplayTopology
import com.wemu.engine.XServerHost
import com.wemu.engine.container.Container
import com.wemu.engine.container.Shortcut
import com.wemu.engine.core.Callback
import com.wemu.engine.core.ProcessHelper
import com.wemu.engine.renderer.ASurfaceRenderer
import com.wemu.engine.renderer.SecondScreenOutput
import com.wemu.engine.widget.InputControlsView
import com.wemu.engine.widget.TouchpadView
import com.wemu.engine.widget.XServerView
import com.wemu.engine.inputcontrols.ExternalController
import com.wemu.engine.xserver.Window
import com.wemu.engine.xserver.WindowManager
import com.wemu.engine.xserver.XServer
import com.wemu.runtime.RuntimeSession
import com.wemu.runtime.WemuRuntimeEngine
import com.wemu.session.secondscreen.SecondScreen
import com.wemu.session.secondscreen.StardewPanelControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Fullscreen game-only host. It has no path that launches a bare container desktop. */
class GameSessionActivity : AppCompatActivity(), XServerHost {
    private lateinit var root: FrameLayout
    private lateinit var loading: TextView
    private var runtime: RuntimeSession? = null
    private var serverView: XServerView? = null
    private var touchpad: TouchpadView? = null
    private var controlsView: InputControlsView? = null
    private var runtimeControls by mutableStateOf<CompanionRuntimeControls?>(null)
    private lateinit var runtimeMetrics: CompanionSessionMetrics
    private lateinit var runtimeLog: CompanionSessionLog
    private var secondaryDisplayId by mutableStateOf<Int?>(null)
    private var sessionTitle by mutableStateOf("Game")
    private var managedPanel by mutableStateOf(false)
    private var diagnosticsOnly by mutableStateOf(false)
    private var nativeTextBridge = false
    private var nativePanelModeModifierDown = false
    private var nativePanelModeModifierUsed = false
    private var nativePanelLeftTriggerDown = false
    private var nativePanelRightTriggerDown = false
    private var windowListener: WindowManager.OnWindowModificationListener? = null
    @Volatile private var keyboardTargetWindow: Window? = null
    @Volatile private var keyboardTargetScore = Int.MIN_VALUE
    @Volatile private var keyboardTargetIdentity = ""
    private val keyboardFocusLogged = AtomicBoolean(false)
    private val cursorShown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gameWindowVisible = AtomicBoolean(false)
    private val terminationHandled = AtomicBoolean(false)
    private lateinit var launchStatus: LaunchStatusStore
    private var guestOutputCallback: Callback<String>? = null
    private var diagnosticCapture: DiagnosticGuestCapture? = null
    private var launchStartedAt = 0L
    private var activityVisible = false
    private var isaacDeviceSleep = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshSecondaryDisplay()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSecondaryDisplay()
        override fun onDisplayRemoved(displayId: Int) = refreshSecondaryDisplay()
        override fun onDisplayChanged(displayId: Int) = refreshSecondaryDisplay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val app = application as DualScreenLauncherApplication
        if (app.settings.settings.value.keepScreenAwake) window.addFlags(FLAG_KEEP_SCREEN_ON)
        com.wemu.engine.inputcontrols.ExternalController.swapFaceButtons =
            app.settings.settings.value.swapFaceButtons
        hideSystemUi()
        root = FrameLayout(this)
        loading = TextView(this).apply {
            setBackgroundColor(0xFF090C0F.toInt())
            setTextColor(0xFFE7DFC9.toInt())
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            text = "PREPARANDO EL JUEGO"
        }
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        runtimeMetrics = CompanionSessionMetrics(this)
        runtimeLog = CompanionSessionLog().also { it.attach() }
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(
            displayListener,
            mainHandler,
        )
        refreshSecondaryDisplay()
        setContent {
            CompanionTheme {
                CompanionSessionHost(
                    gameRoot = root,
                    secondaryDisplayId = secondaryDisplayId,
                    title = sessionTitle,
                    managedPanel = managedPanel,
                    diagnosticsOnly = diagnosticsOnly,
                    controls = runtimeControls,
                    metrics = runtimeMetrics,
                    log = runtimeLog,
                    onExit = ::finish,
                )
            }
        }
        lifecycleScope.launch { runtimeLog.pumpForever() }
        lifecycleScope.launch { runtimeMetrics.sampleForever() }

        val game = GameCatalog.byId(intent.getStringExtra(EXTRA_GAME_ID))
        val containerId = intent.getIntExtra(EXTRA_CONTAINER_ID, -1)
        val shortcutPath = intent.getStringExtra(EXTRA_SHORTCUT_PATH)
        val configurationLauncher = intent.getBooleanExtra(EXTRA_CONFIGURATION_LAUNCHER, false)
        if (game == null || containerId <= 0 || shortcutPath.isNullOrBlank()) {
            finishWithMessage("This game installation is incomplete")
            return
        }
        sessionTitle = if (configurationLauncher) "${game.title} Launcher" else game.title
        managedPanel = !configurationLauncher &&
            game.panelMode in setOf(LowerPanelMode.STARDEW, LowerPanelMode.VALHEIM)
        diagnosticsOnly = game.panelMode == LowerPanelMode.DIAGNOSTICS
        nativeTextBridge = !configurationLauncher && game.id == "skyrim-se"
        isaacDeviceSleep = !configurationLauncher && game.id == "isaac-repentance"
        launchStatus = app.launchStatus
        val logFile = launchStatus.begin(game)
        launchStatus.record(
            "REQUEST",
            "container=$containerId shortcut=$shortcutPath mode=" +
                if (configurationLauncher) "configuration-launcher" else "direct-play",
        )
        launchStatus.record("LOG", "Persistent launch log: ${logFile.absolutePath}")
        launchStartedAt = android.os.SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            startGame(game, containerId, shortcutPath, configurationLauncher)
        }
    }

    private suspend fun startGame(
        game: SupportedGame,
        containerId: Int,
        shortcutPath: String,
        configurationLauncher: Boolean,
    ) {
        val app = application as DualScreenLauncherApplication
        updateStatus("VERIFICANDO EL MOD", "Comprobando el mod de doble pantalla")
        val payloadRefresh = app.installer.ensureCurrentPayload(game)
        if (payloadRefresh.isFailure) {
            val failure = payloadRefresh.exceptionOrNull()
            Log.e(TAG, "Could not update the dual-screen payload", failure)
            finishWithMessage("Dual-screen mod update failed: ${failure?.message ?: "unknown error"}")
            return
        }
        val currentInstall = payloadRefresh.getOrNull()
        currentInstall?.let { installed ->
            launchStatus.record("MOD", "Verified payload ${installed.payloadVersion}")
        }
        val effectiveContainerId = currentInstall?.containerId ?: containerId
        val effectiveShortcutPath = currentInstall?.shortcutPath ?: shortcutPath
        updateStatus("VERIFICANDO EL CONTENEDOR", "Contenedor $effectiveContainerId")
        val container = app.installerContainer(effectiveContainerId)
        val directShortcut = container?.let { loadShortcut(it, effectiveShortcutPath) }
        if (container == null || directShortcut == null) {
            finishWithMessage("The tested container or launch entry is missing")
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                DualscreenStorage().prepareSaveDirectory(container, game)
            }
        }.onSuccess { saves ->
            launchStatus.record("STORAGE", "Live saves: ${saves.absolutePath}")
            if (!configurationLauncher && game.id == "isaac-repentance") {
                runCatching {
                    com.dualscreen.launcher.IsaacOptions.ensureFullscreen(saves)
                }.onSuccess { changed ->
                    launchStatus.record("DISPLAY", "Isaac options.ini fullscreen enforced; changed=$changed")
                }
            }
        }.onFailure { failure ->
            launchStatus.record("STORAGE", "Public save exposure skipped safely: ${failure.message}")
        }
        val shortcut = if (configurationLauncher) {
            createConfigurationShortcut(container, game, directShortcut)
                ?: run {
                    finishWithMessage(
                        "Configuration launcher is missing. Expected one of: " +
                            game.configurationLauncherCandidates.joinToString(),
                    )
                    return
                }
        } else {
            directShortcut
        }

        val selectedResolution = app.settings.settings.value.resolution
        val resolutionChanged = withContext(Dispatchers.IO) {
            if (container.screenSize == selectedResolution.containerValue) {
                false
            } else {
                container.screenSize = selectedResolution.containerValue
                container.dpi = Container.suggestedDPI(selectedResolution.containerValue)
                check(container.saveData()) { "Could not save the selected game resolution" }
                true
            }
        }
        launchStatus.record(
            "DISPLAY",
            "fullscreen=borderless resolution=${selectedResolution.containerValue} " +
                "containerChanged=$resolutionChanged dpi=${container.dpi}",
        )

        logContainerPlan(container, shortcut)
        val preset = PresetContainerManager(this)
        val repairStaged = runCatching {
            preset.prepareForLaunch(container, game) { detail -> launchStatus.record("REPAIR", detail) }
        }.getOrElse { failure ->
            Log.e(TAG, "Could not stage the captured container", failure)
            finishWithMessage("Container verification failed: ${failure.message}")
            return
        }
        if (repairStaged) {
            updateStatus(
                "REPAIRING COPIED CONTAINER",
                "Materializing the captured WEMU runtime into Companion's private prefix",
            )
        } else {
            launchStatus.record("CONTAINER", "Runtime materialization revision is current")
        }

        val target = resolveGuestTarget(container, shortcut.path)
        if (target == null || !target.isFile) {
            val detail = "Launch executable is unavailable: guest=${shortcut.path} host=${target?.path ?: "unmapped"}"
            finishWithMessage(detail)
            return
        }
        launchStatus.record(
            "TARGET",
            "guest=${shortcut.path} host=${target.absolutePath} bytes=${target.length()} " +
                "cwd=${target.parentFile?.absolutePath} readable=${target.canRead()}",
        )

        if (!configurationLauncher && game.id == "isaac-repentance") {
            val gameRoot = target.parentFile
            if (gameRoot != null && !com.dualscreen.launcher.IsaacEid.installed(gameRoot)) {
                updateStatus("DESCARGANDO DESCRIPCIONES", "External Item Descriptions (solo la primera vez)")
            }
            val eid = gameRoot != null && withContext(Dispatchers.IO) {
                com.dualscreen.launcher.IsaacEid.ensure(gameRoot)
            }
            launchStatus.record("MOD", "External Item Descriptions available=$eid")
        }

        if (!configurationLauncher && game.nativeDllOverrides.isNotEmpty()) {
            val registry = withContext(Dispatchers.IO) {
                runCatching {
                    ExecutableDllOverrideConfiguration.ensure(
                        container.rootDir,
                        target.name,
                        game.nativeDllOverrides,
                    )
                }
            }.getOrElse { failure ->
                Log.e(TAG, "Could not apply the native panel proxy policy", failure)
                finishWithMessage("Native dual-screen setup failed: ${failure.message}")
                return
            }
            launchStatus.record(
                "COMPATIBILITY",
                "Executable-scoped overrides verified for ${target.name}: " +
                    game.nativeDllOverrides.entries.joinToString { "${it.key}=${it.value}" } +
                    " registry=${registry.absolutePath}",
            )
        }

        withContext(Dispatchers.IO) {
            DualscreenStorage().exposeConfigurationFiles(container, game)
        }

        // Migrate shortcuts created by earlier launcher builds as well as new
        // installs. This keeps Wine's optional first-boot add-on dialogs from
        // blocking invisibly in front of the game.
        val launchEnvironment = supportedGameLaunchEnvironment(game.id)
        if (shortcut.getExtra("envVars") != launchEnvironment) {
            shortcut.putExtra("envVars", launchEnvironment)
            shortcut.saveData()
            launchStatus.record("SHORTCUT", "Migrated launch-only DLL overrides: $launchEnvironment")
        }

        // Isaac enumerates both Wine's DirectInput HID view and its XInput view
        // when the generic pad is not explicitly hidden. That assigns one
        // physical controller two IDs and makes menu/gameplay bindings jump
        // between them. Keep only the XInput identity for this title.
        if (game.exclusiveXInput &&
            (shortcut.getExtra("inputType") != "4" ||
                shortcut.getExtra("exclusiveXInput") != "1")
        ) {
            shortcut.putExtra("inputType", "4")
            shortcut.putExtra("exclusiveXInput", "1")
            shortcut.saveData()
            launchStatus.record(
                "INPUT",
                "Exclusive XInput enabled; duplicate DirectInput controller suppressed",
            )
        }

        updateStatus("INICIANDO WINDOWS (WINE)", "Preparando Wine y el renderizador")
        val session = WemuRuntimeEngine.INSTANCE.createSession(this, this, container, shortcut)
        runtime = session
        refreshSecondaryDisplay()
        session.setTerminationListener { status ->
            val elapsed = android.os.SystemClock.elapsedRealtime() - launchStartedAt
            Log.i(TAG, "Runtime termination: status=$status elapsedMs=$elapsed gameWindow=${gameWindowVisible.get()}")
            runOnUiThread {
                if (!terminationHandled.compareAndSet(false, true) || isFinishing) return@runOnUiThread
                if (gameWindowVisible.get()) {
                    launchStatus.ended("Game process ended with status $status after ${elapsed}ms")
                    finish()
                } else {
                    showLaunchFailure(
                        "Guest exited before the game window was created\n" +
                            "Process status: $status  ·  elapsed: ${elapsed}ms",
                    )
                }
            }
        }

        val xServer = runCatching { session.prepare() }.getOrElse { failure ->
            Log.e(TAG, "Runtime prepare failed", failure)
            finishWithMessage("The game runtime could not be prepared: ${failure.message}")
            return
        }
        launchStatus.record(
            "PREPARE",
            "X server ready; renderer=${session.displayRenderer} wine=${session.wineInfo?.identifier()}",
        )
        attachRenderer(xServer, session, game)
        watchForGameWindow(xServer, game, configurationLauncher)
        updateStatus(
            if (configurationLauncher) {
                "STARTING ${game.title.uppercase(Locale.ROOT)} LAUNCHER"
            } else {
                "INICIANDO ${game.title.uppercase(Locale.ROOT)}"
            },
            "Arrancando el juego",
        )

        val started = withContext(Dispatchers.IO) { runCatching { session.start() } }
        if (started.isFailure) {
            val failure = started.exceptionOrNull()
            Log.e(TAG, "Runtime start failed", failure)
            finishWithMessage(failure?.message ?: "The game could not be started")
            return
        }
        runCatching { preset.markLaunchMaterialized(container) }
            .onFailure { Log.e(TAG, "Could not record runtime materialization", it) }
        launchStatus.record("PROCESS", "Process creation returned successfully; waiting for the game window")
    }

    private fun attachRenderer(xServer: XServer, session: RuntimeSession, game: SupportedGame) {
        val view = XServerView(this, xServer, session.displayRenderer)
        serverView = view
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        val renderer = view.renderer
        session.configureRenderer(renderer)
        if (game.id == "isaac-repentance") {
            // Fit/crop the guest client to the top screen so no desktop frame
            // or background shows around Isaac's window.
            renderer.setFitGuestWindow(true)
        }
        xServer.setRenderer(renderer)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val pointer = TouchpadView(this, xServer, mainHandler, Runnable { }).apply {
            setSensitivity(preferences.getFloat("cursor_speed", 1f))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        touchpad = pointer
        root.addView(pointer, 1, FrameLayout.LayoutParams(-1, -1))

        val overlay = InputControlsView(this, mainHandler, Runnable { }).apply {
            setTouchpadView(pointer)
            setXServer(xServer)
            visibility = View.GONE
        }
        controlsView = overlay
        root.addView(overlay, 2, FrameLayout.LayoutParams(-1, -1))

        val metrics = runtimeMetrics
        view.compositorRenderer?.setFrameListener(metrics.frameListener)
        (renderer as? ASurfaceRenderer)?.let { scanout ->
            scanout.setScanoutFrameListener { _, _, _ ->
                metrics.onFramePresented(scanout.isLastFrameZeroCopy)
            }
        }

        runtimeControls = CompanionRuntimeControls(
            context = this,
            compositor = view.compositorRenderer,
            activeRenderer = renderer,
            touchpad = pointer,
            inputControls = overlay,
            winHandler = session.winHandler,
            container = session.container,
            preferences = preferences,
            injectKey = { event ->
                // Keep the built-in keyboard on the same X11 input path as a
                // physical keyboard. The old Skyrim-only UDP branch discarded
                // name-entry keys whenever Wine reported a helper/panel window
                // as foreground (`[keyboard] ... sent=0`), even though Skyrim's
                // X window still owned keyboard focus. XServer already routes
                // these events to the focused guest window and correctly
                // reaches Scaleform text fields, DirectInput, and Win32 controls.
                focusGameWindowForKeyboard(xServer)
                xServer.keyboard.onKeyEvent(event)
            },
            injectCharacter = { character ->
                focusGameWindowForKeyboard(xServer)
                xServer.keyboard.injectCharacter(character.code)
            },
        )
        // Loading remains the last child and therefore completely covers Wine's
        // desktop until an actual mapped game window proves the target is ready.
        loading.bringToFront()
    }

    private fun watchForGameWindow(
        xServer: XServer,
        game: SupportedGame,
        configurationLauncher: Boolean,
    ) {
        fun observe(window: Window) {
            if (!window.attributes.isMapped) return
            // Native lower-panel titles deliberately include the game name, so
            // a loose game hint ("Fallout 4", "Grim Dawn", "Skyrim") also
            // matches the mod window. That window proves the mod initialized,
            // not that the playable upper window exists. Keep the launch cover
            // up until a non-panel game window is actually mapped.
            if (SecondScreenOutput.isEarlyClaimPanelWindow(window)) return
            val identity = "${window.name} ${window.className}".lowercase(Locale.ROOT)
            if (!matchesSessionWindow(identity, game, configurationLauncher)) return

            // A configuration launcher and the actual game are two different X
            // windows. Earlier code stopped observing after the launcher matched,
            // leaving the built-in keyboard focused on SkyrimSELauncher.exe even
            // after SkyrimSE.exe appeared. Prefer an exact direct-play executable
            // identity, but keep the launcher as a temporary target so its text
            // fields remain usable too.
            val directExecutableMatch = matchesDirectExecutableWindow(identity, game)
            val applicationBonus = if (window.isApplicationWindow) 10 else 0
            val targetScore = (if (directExecutableMatch) 100 else 20) + applicationBonus
            // Keep the first direct game target until it is unmapped. WFC's
            // native lower panel initially inherits the process WM_CLASS
            // (twfc.exe) before its private class/title properties settle. An
            // equal-score "newer window" replacement therefore promoted the
            // panel over the already-correct game window and every later focus
            // handoff explicitly focused the lower UI instead of gameplay.
            if (targetScore > keyboardTargetScore ||
                (targetScore == keyboardTargetScore &&
                    keyboardTargetWindow?.attributes?.isMapped != true)
            ) {
                keyboardTargetWindow = window
                keyboardTargetScore = targetScore
                keyboardTargetIdentity = identity
                keyboardFocusLogged.set(false)
                launchStatus.record(
                    "INPUT",
                    "Keyboard target updated: identity=$identity score=$targetScore " +
                        "directExecutable=$directExecutableMatch window=${window.id}",
                )
            }
            if (gameWindowVisible.compareAndSet(false, true)) {
                runOnUiThread {
                    // Diagnostic sessions must retain the crash, not just startup.
                    if (!diagnosticsOnly) stopGuestOutputCapture()
                    launchStatus.ready("Game window matched: $identity")
                    // The launch cover is correctness state, not decoration.
                    // A ViewPropertyAnimator needs a later Choreographer frame
                    // to run its end action; while Wine is mapping its first
                    // burst of windows that frame can be delayed long enough
                    // to leave an already-running game hidden until an app
                    // switch forces a redraw. Remove it atomically as soon as
                    // the playable window is authoritative.
                    loading.animate().cancel()
                    loading.alpha = 0f
                    loading.visibility = View.GONE
                    root.invalidate()
                }
            }
        }

        windowListener = object : WindowManager.OnWindowModificationListener {
            override fun onMapWindow(window: Window) = observe(window)
            override fun onUpdateWindowContent(window: Window) {
                // Match WEMU's proven cursor lifecycle. RendererOptions hides
                // the host cursor during empty Wine startup, then the first
                // real guest draw makes it available. The guest window's own
                // X cursor visibility still decides whether pixels are shown,
                // so games which hide their pointer do not gain a duplicate.
                if (cursorShown.compareAndSet(false, true) &&
                    touchpad?.isSimTouchScreen != true
                ) {
                    serverView?.renderer?.isCursorVisible = true
                    launchStatus.record("INPUT", "Guest cursor enabled after first window draw")
                }
                observe(window)
            }
            override fun onUpdateWindowGeometry(window: Window, resized: Boolean) =
                observe(window)
            override fun onModifyWindowProperty(window: Window, property: com.wemu.engine.xserver.Property) =
                observe(window)
        }.also(xServer.windowManager::addOnWindowModificationListener)
    }

    /**
     * Restores X11 and Win32 focus to the best mapped game window before a
     * lower-display keyboard event is injected. Android Presentation touches do
     * not update the guest pointer/focus, and a native second-screen HWND can be
     * the last Wine foreground window even though the player is typing into a
     * Scaleform field on the top screen.
     */
    private fun focusGameWindowForKeyboard(xServer: XServer): Boolean {
        var target = keyboardTargetWindow
            ?.takeIf { it.attributes.isMapped && it.attributes.isEnabled }
            ?: return false
        val rootWindow = xServer.windowManager.rootWindow
        while (target.parent != null && target.parent !== rootWindow) {
            target = target.parent
        }
        var changed = false
        xServer.lock(
            XServer.Lockable.WINDOW_MANAGER,
            XServer.Lockable.INPUT_DEVICE,
        ).use {
            changed = xServer.windowManager.focusedWindow !== target
            // Always refresh the X focus even when the cached WindowManager
            // value already points at the target. Wine's Win32 foreground
            // state can diverge after a non-activating companion HWND maps.
            xServer.windowManager.setFocus(
                target,
                if (target.parent === rootWindow) {
                    WindowManager.FocusRevertTo.POINTER_ROOT
                } else {
                    WindowManager.FocusRevertTo.PARENT
                },
            )
        }
        // Keep this paired with X11 focus. WEMU's working controller path does
        // both; conditioning this call on the X focus comparison caused WFC to
        // require a mouse click before it accepted controller input.
        xServer.winHandler.bringToFront(target.className, target.handle)
        if (keyboardFocusLogged.compareAndSet(false, true)) {
            launchStatus.record(
                "INPUT",
                "Keyboard injection focused identity=$keyboardTargetIdentity " +
                    "window=${target.id} win32Handle=${target.handle} changed=$changed",
            )
        }
        return xServer.windowManager.focusedWindow === target
    }

    private fun loadShortcut(container: Container, path: String): Shortcut? = runCatching {
        val file = File(path)
        val rootPath = container.rootDir.canonicalPath + File.separator
        if (!file.isFile || !file.canonicalPath.startsWith(rootPath)) null else Shortcut(container, file)
    }.getOrNull()

    /**
     * Builds a separate launch entry for the vendor settings launcher. The
     * installed direct-play shortcut is never rewritten, so PLAY continues to
     * target the game executable even after the settings launcher is used.
     */
    private suspend fun createConfigurationShortcut(
        container: Container,
        game: SupportedGame,
        directShortcut: Shortcut,
    ): Shortcut? = withContext(Dispatchers.IO) {
        val gameRoot = resolveGuestTarget(container, directShortcut.path)?.parentFile
            ?: return@withContext null
        val relative = resolveConfigurationLauncherExecutable(gameRoot, game)
            ?: return@withContext null
        val guestRoot = directShortcut.path.trim().trim('"')
            .substringBeforeLast('\\', missingDelimiterValue = "")
        if (guestRoot.isBlank()) return@withContext null
        val guestPath = "$guestRoot\\${relative.replace('/', '\\')}"
        val shortcutFile = File(
            directShortcut.file.parentFile,
            "${game.id}-configuration-launcher.desktop",
        )
        shortcutFile.writeText(
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Name=${game.title} Launcher")
                appendLine("Exec=$guestPath")
                appendLine("Type=Application")
                appendLine("StartupNotify=True")
            },
        )
        Shortcut(container, shortcutFile).also { launcherShortcut ->
            launcherShortcut.putExtra("envVars", supportedGameLaunchEnvironment(game.id))
            // A vendor launcher starts as a CPU/GDI window and may replace that
            // same X window with the game's DRI3/AHardwareBuffer swapchain when
            // PLAY is pressed. SurfaceFlinger cannot draw the initial GDI
            // window, while the GLES compositor accepts Fallout 3's imported
            // buffers but samples them as black after that in-place hand-off.
            // The Vulkan compositor supports both sources for the lifetime of
            // one X server, so it is the only renderer that can carry the
            // launcher -> game transition without recreating the session.
            // Direct PLAY shortcuts still retain the container's zero-copy
            // SurfaceFlinger renderer.
            launcherShortcut.putExtra("displayRenderer", XServerView.RENDERER_VULKAN)
            launcherShortcut.saveData()
            launchStatus.record(
                "TARGET",
                "configurationLauncher=$guestPath host=${File(gameRoot, relative).absolutePath} " +
                    "renderer=${XServerView.RENDERER_VULKAN}",
            )
        }
    }

    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        if (::launchStatus.isInitialized) launchStatus.failed(message)
        loading.text = "$message\n\nTOCA PARA VOLVER"
        loading.setTextColor(0xFFFF8A80.toInt())
        loading.visibility = View.VISIBLE
        loading.alpha = 1f
        loading.bringToFront()
        loading.setOnClickListener { if (!isFinishing) finish() }
    }

    private fun showLaunchFailure(detail: String) {
        finishWithMessage(detail)
    }

    private fun updateStatus(title: String, detail: String) {
        loading.text = title
        if (::launchStatus.isInitialized) launchStatus.record(title, detail)
    }

    private fun logContainerPlan(container: Container, shortcut: Shortcut) {
        launchStatus.record(
            "CONTAINER",
            "id=${container.id} name=${container.name} config=${container.configFile.absolutePath} " +
                "screen=${container.screenSize}",
        )
        launchStatus.record(
            "RUNTIME",
            "wine=${container.wineVersion} emulator=${container.emulator} " +
                "box64=${container.box64Version} fexcore=${container.fexCoreVersion}",
        )
        launchStatus.record(
            "GRAPHICS",
            "driver=${container.graphicsDriver}/${container.rendererDriverId} " +
                "renderer=${container.displayRenderer} directx=${container.dxWrapper} " +
                "config=${container.dxWrapperConfig}",
        )
        launchStatus.record(
            "ENVIRONMENT",
            "containerEnv=${container.envVars} shortcutEnv=${shortcut.getExtra("envVars")} " +
                "audio=${container.audioDriver} drives=${container.drives}",
        )
        launchStatus.record("SHORTCUT", "file=${shortcut.file.absolutePath} exec=${shortcut.path}")
    }

    private fun resolveGuestTarget(container: Container, guestPath: String): File? = runCatching {
        val clean = guestPath.trim().trim('"')
        val match = Regex("^([A-Za-z]):[\\\\/](.*)$").matchEntire(clean) ?: return@runCatching null
        val drive = match.groupValues[1].lowercase(Locale.ROOT)
        val relative = match.groupValues[2].replace('\\', File.separatorChar).replace('/', File.separatorChar)
        val root = when (drive) {
            "c" -> File(container.rootDir, ".wine/drive_c")
            "f" -> Environment.getExternalStorageDirectory()
            "d" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else -> File(container.rootDir, ".wine/dosdevices/$drive:").canonicalFile
        }
        File(root, relative).canonicalFile
    }.getOrNull()

    private fun stopGuestOutputCapture() {
        guestOutputCallback?.let(ProcessHelper::removeDebugCallback)
        guestOutputCallback = null
        diagnosticCapture?.close()
        diagnosticCapture = null
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        // A Presentation belongs only to a visible game activity. Recreate it
        // after returning from another app instead of leaving a stale waiting
        // panel obscuring that app's lower display.
        refreshSecondaryDisplay()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        refreshSecondaryDisplay()
    }

    override fun onStop() {
        activityVisible = false
        refreshSecondaryDisplay()
        // Setting the id to null disposes PresentationHost and its SurfaceView.
        // The guest can remain alive; onStart attaches a fresh lower Surface.
        secondaryDisplayId = null
        SecondScreenOutput.requestDetach()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dispatchKeyboardControllerKey(event)) return true
        if (dispatchNativePanelModeKey(event)) return true
        return dispatchGuestKeyEvent(event)
    }

    private fun isControllerEvent(event: KeyEvent): Boolean {
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 &&
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        ) {
            return false
        }
        if (ExternalController.isGameController(event.device)) return true
        val sources = event.source or (event.device?.sources ?: 0)
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    /** The controller belongs to the lower-screen keyboard while it is visible. */
    private fun dispatchKeyboardControllerKey(event: KeyEvent): Boolean {
        val keyboard = runtimeControls?.keyboard ?: return false
        if (!keyboard.visible || !isControllerEvent(event)) return false
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> keyboard.move(com.thor.core.model.NavDirection.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> keyboard.move(com.thor.core.model.NavDirection.RIGHT)
            KeyEvent.KEYCODE_DPAD_UP -> keyboard.move(com.thor.core.model.NavDirection.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> keyboard.move(com.thor.core.model.NavDirection.DOWN)
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> keyboard.pressFocused()
            KeyEvent.KEYCODE_BUTTON_B -> keyboard.press(com.thor.core.model.KeyboardKey.Backspace)
            KeyEvent.KEYCODE_BUTTON_X -> keyboard.press(com.thor.core.model.KeyboardKey.Space)
            KeyEvent.KEYCODE_BUTTON_Y -> keyboard.press(com.thor.core.model.KeyboardKey.Shift)
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            -> keyboard.press(com.thor.core.model.KeyboardKey.Layer)
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            -> keyboard.hide()
            else -> return false
        }
        return true
    }

    /** Normal WEMU route: touch overlay, Win32 controller bridge, then X keyboard. */
    private fun dispatchGuestKeyEvent(event: KeyEvent): Boolean {
        val overlay = controlsView
        val active = runtime
        val xServer = active?.xServer
        val winHandler = active?.winHandler
        if (overlay != null && winHandler != null && xServer != null) {
            if (overlay.onKeyEvent(event) ||
                winHandler.onKeyEvent(event) ||
                xServer.keyboard.onKeyEvent(event)
            ) {
                return true
            }
            if (isControllerEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Select scopes controller navigation to a visible lower panel. */
    private fun dispatchNativePanelModeKey(event: KeyEvent): Boolean {
        if (!isControllerEvent(event)) return false
        val managedStardew = isManagedStardewPanel()
        val nativePanel = SecondScreenOutput.isNativePanelControllerActive()
        if (!managedStardew && !nativePanel && !nativePanelModeModifierDown) {
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    nativePanelModeModifierDown = true
                    nativePanelModeModifierUsed = false
                    nativePanelLeftTriggerDown = false
                    nativePanelRightTriggerDown = false
                }
                return nativePanelModeModifierDown
            }
            if (event.action == KeyEvent.ACTION_UP && nativePanelModeModifierDown) {
                val used = nativePanelModeModifierUsed
                nativePanelModeModifierDown = false
                nativePanelModeModifierUsed = false
                nativePanelLeftTriggerDown = false
                nativePanelRightTriggerDown = false
                if (!used) {
                    dispatchGuestKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN))
                    dispatchGuestKeyEvent(event)
                }
                return true
            }
        }
        if (nativePanelModeModifierDown &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_R2)
        ) {
            nativePanelModeModifierUsed = true
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val next = event.keyCode == KeyEvent.KEYCODE_BUTTON_R2
                sendPanelPage(next, managedStardew)
            }
            return true
        }
        if (nativePanelModeModifierDown) {
            val action = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_BUTTON_A -> "activate"
                else -> null
            }
            if (action != null) {
                nativePanelModeModifierUsed = true
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    sendPanelNavigation(action, managedStardew)
                }
                return true
            }

            if (managedStardew || SecondScreenOutput.isStardewValleyPanelActive()) {
                val control = when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> StardewPanelControl.PREVIOUS_TOOLBAR
                    KeyEvent.KEYCODE_BUTTON_R1 -> StardewPanelControl.NEXT_TOOLBAR
                    KeyEvent.KEYCODE_BUTTON_X -> StardewPanelControl.SORT
                    KeyEvent.KEYCODE_BUTTON_B -> StardewPanelControl.USE
                    else -> null
                }
                if (control != null) {
                    nativePanelModeModifierUsed = true
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        sendStardewPanelControl(control, managedStardew)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun isManagedStardewPanel(): Boolean =
        managedPanel && sessionTitle == "Stardew Valley"

    private fun sendPanelPage(next: Boolean, managedStardew: Boolean) {
        if (managedStardew) {
            SecondScreen.controlStardewPanel(
                if (next) StardewPanelControl.NEXT_PAGE else StardewPanelControl.PREVIOUS_PAGE,
            )
        } else if (SecondScreenOutput.isStardewValleyPanelActive()) {
            NativePanelInput.page(next)
        } else {
            SecondScreenOutput.sendNativePanelPage(next)
        }
    }

    private fun sendPanelNavigation(action: String, managedStardew: Boolean) {
        if (managedStardew) {
            val control = when (action) {
                "left" -> StardewPanelControl.PREVIOUS_PAGE
                "right" -> StardewPanelControl.NEXT_PAGE
                "up" -> StardewPanelControl.PREVIOUS_SLOT
                "down" -> StardewPanelControl.NEXT_SLOT
                "activate" -> StardewPanelControl.USE
                else -> return
            }
            SecondScreen.controlStardewPanel(control)
        } else if (SecondScreenOutput.isStardewValleyPanelActive()) {
            NativePanelInput.navigation(action)
        } else {
            SecondScreenOutput.sendNativePanelNavigation(action)
        }
    }

    private fun sendStardewPanelControl(
        control: StardewPanelControl,
        managedStardew: Boolean,
    ) {
        if (managedStardew) {
            SecondScreen.controlStardewPanel(control)
            return
        }
        when (control) {
            StardewPanelControl.PREVIOUS_TOOLBAR -> NativePanelInput.toolbar(false)
            StardewPanelControl.NEXT_TOOLBAR -> NativePanelInput.toolbar(true)
            StardewPanelControl.SORT -> NativePanelInput.action("sort")
            StardewPanelControl.USE -> NativePanelInput.action("use")
            StardewPanelControl.PREVIOUS_PAGE -> NativePanelInput.page(false)
            StardewPanelControl.NEXT_PAGE -> NativePanelInput.page(true)
            StardewPanelControl.PREVIOUS_SLOT -> NativePanelInput.navigation("up")
            StardewPanelControl.NEXT_SLOT -> NativePanelInput.navigation("down")
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (dispatchNativePanelModeMotion(event)) return true
        // Route by the packet's source, not the device's combined capability
        // mask. The Thor exposes separate mouse and joystick packets; joystick
        // motion must reach XInput even if a future firmware combines devices.
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            if (runtime?.winHandler?.onGenericMotionEvent(event) == true) return true
        } else if (touchpad?.onExternalMouseEvent(event) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Handles controllers which expose L2/R2 as axes while Select is held. */
    private fun dispatchNativePanelModeMotion(event: MotionEvent): Boolean {
        val managedStardew = isManagedStardewPanel()
        if (!nativePanelModeModifierDown ||
            (!managedStardew && !SecondScreenOutput.isNativePanelControllerActive()) ||
            event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK
        ) return false

        val leftPressed = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        ) >= NATIVE_PANEL_TRIGGER_THRESHOLD
        val rightPressed = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        ) >= NATIVE_PANEL_TRIGGER_THRESHOLD
        if (leftPressed && !nativePanelLeftTriggerDown) {
            nativePanelModeModifierUsed = true
            sendPanelPage(false, managedStardew)
        }
        if (rightPressed && !nativePanelRightTriggerDown) {
            nativePanelModeModifierUsed = true
            sendPanelPage(true, managedStardew)
        }
        nativePanelLeftTriggerDown = leftPressed
        nativePanelRightTriggerDown = rightPressed
        return true
    }

    /**
     * The Thor's virtual mouse may deliver button down/up as touch events even
     * though its hover and wheel packets use generic motion.  Keep both paths
     * on the guest mouse bridge so launchers cannot lose clicks and look hung.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (touchpad?.onExternalMouseEvent(event) == true) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        CompanionDisplayCover.update(this, null)
        unregisterReceiver(screenReceiver)
        stopGuestOutputCapture()
        windowListener?.let { listener ->
            runtime?.xServer?.windowManager?.removeOnWindowModificationListener(listener)
        }
        windowListener = null
        // Retire the SurfaceView on the UI thread before the Activity exposes
        // the library again. SurfaceFlinger's child scanout layers outlive an
        // Android Activity unless XServerView.destroy() explicitly releases
        // them; stopping Wine alone left a full-screen black layer covering the
        // launcher after a vendor launcher or game exited.
        val retiredRuntime = runtime
        val retiredView = serverView
        val retiredTouchpad = touchpad
        val retiredControls = controlsView
        retiredRuntime?.setTerminationListener(null)
        retiredView?.detachForDestroy()
        (retiredControls?.parent as? android.view.ViewGroup)?.removeView(retiredControls)
        (retiredTouchpad?.parent as? android.view.ViewGroup)?.removeView(retiredTouchpad)
        (retiredView?.parent as? android.view.ViewGroup)?.removeView(retiredView)
        runtime = null
        serverView = null
        touchpad = null
        controlsView = null
        runtimeControls = null
        runtimeLog.stop()
        SecondScreen.shutdown()
        SecondScreenOutput.requestDetach()
        if (retiredRuntime != null || retiredView != null) {
            Thread({
                runCatching { retiredRuntime?.stop() }
                    .onFailure { Log.w(TAG, "Guest runtime teardown failed", it) }
                runCatching { retiredView?.destroy() }
                    .onFailure { Log.w(TAG, "Guest renderer teardown failed", it) }
            }, "DualScreenLauncherSessionCleanup").apply {
                isDaemon = true
                start()
            }
        }
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshSecondaryDisplay() {
        val manager = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val mainDisplayState = windowManager.defaultDisplay.state
        val awake = getSystemService(PowerManager::class.java)?.isInteractive != false &&
            mainDisplayState != Display.STATE_OFF && mainDisplayState != Display.STATE_DOZE_SUSPEND
        if (isaacDeviceSleep) runtime?.setDeviceSleeping(!awake)
        if (!activityVisible || !awake) {
            CompanionDisplayCover.update(this, null)
            if (secondaryDisplayId != null) {
                secondaryDisplayId = null
                SecondScreenOutput.requestDetach()
            }
            return
        }
        @Suppress("DEPRECATION")
        val current = windowManager.defaultDisplay.displayId
        secondaryDisplayId = manager?.displays?.firstOrNull { candidate ->
            candidate.displayId != current && candidate.state != Display.STATE_OFF &&
                candidate.state != Display.STATE_DOZE_SUSPEND && DisplayTopology.isUsableSecondary(candidate)
        }?.displayId
        // AC II-only until display lifecycle and controller focus are verified on device.
        CompanionDisplayCover.update(this, secondaryDisplayId.takeIf {
            intent.getStringExtra(EXTRA_GAME_ID) == "assassins-creed-ii"
        })
        Log.i(TAG, "Secondary display -> $secondaryDisplayId")
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun getHostContext(): Context = this
    override fun getXServer(): XServer? = runtime?.xServer
    override fun getXServerView(): XServerView? = serverView
    override fun getInputControlsView(): InputControlsView? = controlsView

    companion object {
        private const val TAG = "DualScreenGameLaunch"
        private const val NATIVE_PANEL_TRIGGER_THRESHOLD = 0.55f
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_CONTAINER_ID = "containerId"
        const val EXTRA_SHORTCUT_PATH = "shortcutPath"
        const val EXTRA_CONFIGURATION_LAUNCHER = "configurationLauncher"
    }
}

/**
 * Matches the executable class as well as a human-facing window title.
 *
 * Wine exposes Bethesda launcher windows by executable class (for example
 * `FalloutLauncherSteam.exe`). That intentionally does not match Fallout 3's
 * direct-play hints, and previously left the launch cover up forever even
 * though the utility was already running. Punctuation-insensitive matching also
 * handles launchers which publish "Fallout Launcher" as their title.
 */
internal fun matchesSessionWindow(
    identity: String,
    game: SupportedGame,
    configurationLauncher: Boolean,
): Boolean {
    val loweredIdentity = identity.lowercase(Locale.ROOT)
    val normalizedIdentity = loweredIdentity.filter(Char::isLetterOrDigit)
    val candidates = if (configurationLauncher) {
        game.windowHints + game.configurationLauncherCandidates
    } else {
        game.windowHints
    }
    return candidates.any { candidate ->
        val loweredCandidate = candidate.lowercase(Locale.ROOT)
        val normalizedCandidate = loweredCandidate
            .removeSuffix(".exe")
            .filter(Char::isLetterOrDigit)
        loweredIdentity.contains(loweredCandidate) ||
            (normalizedCandidate.isNotEmpty() && normalizedIdentity.contains(normalizedCandidate))
    }
}

/** Matches Wine's WM_CLASS executable name against a catalog path. */
internal fun matchesDirectExecutableWindow(identity: String, game: SupportedGame): Boolean {
    val loweredIdentity = identity.lowercase(Locale.ROOT)
    val normalizedIdentity = loweredIdentity.filter(Char::isLetterOrDigit)
    return game.executableCandidates.any { candidate ->
        val executableName = candidate
            .replace('\\', '/')
            .substringAfterLast('/')
            .lowercase(Locale.ROOT)
        val normalizedExecutable = executableName
            .removeSuffix(".exe")
            .filter(Char::isLetterOrDigit)
        loweredIdentity.contains(executableName) ||
            (normalizedExecutable.isNotEmpty() && normalizedIdentity.contains(normalizedExecutable))
    }
}

private suspend fun DualScreenLauncherApplication.installerContainer(id: Int): Container? =
    PresetContainerAccessor(this).find(id)

/** Narrow session lookup; keeping it here prevents the Activity from owning a manager. */
private class PresetContainerAccessor(context: Context) {
    private val manager = com.wemu.engine.container.ContainerManager(context)
    suspend fun find(id: Int): Container? = withContext(Dispatchers.IO) { manager.getContainerById(id) }
}
