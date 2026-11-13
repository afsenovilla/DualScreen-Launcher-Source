package com.dualscreen.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.dualscreen.launcher.session.GameSessionActivity
import com.dualscreen.launcher.switch.SwitchGameDefinition
import com.thor.core.display.DisplayTopology
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {
    private val model: LauncherViewModel by viewModels()
    private var lowerPanel: LauncherPanelPresentation? = null
    private var storagePermissionPrompted = false
    private var launcherStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = FULLSCREEN_FLAGS
        setContent {
            LauncherTheme {
                LauncherScreen(
                    model = model,
                    onPlay = { game, installed -> launch(game, installed, configuration = false) },
                    onConfigure = { game, installed -> launch(game, installed, configuration = true) },
                    onPlaySwitch = ::launchSwitch,
                )
            }
        }
        startLauncher()
    }

    private fun startLauncher() {
        if (launcherStarted) {
            if (hasWindowFocus()) showLowerPanel()
            return
        }
        launcherStarted = true
        ensureSharedStorageAccess()
        lifecycleScope.launch {
            model.installed.collectLatest { ready ->
                lowerPanel?.update(ready, model.progress.value, model.saveProgress.value, model.launchStatus.value)
            }
        }
        lifecycleScope.launch {
            model.progress.collectLatest { progress ->
                lowerPanel?.update(model.installed.value, progress, model.saveProgress.value, model.launchStatus.value)
            }
        }
        lifecycleScope.launch {
            model.saveProgress.collectLatest { saves ->
                lowerPanel?.update(model.installed.value, model.progress.value, saves, model.launchStatus.value)
            }
        }
        lifecycleScope.launch {
            model.launchStatus.collectLatest { launch ->
                lowerPanel?.update(model.installed.value, model.progress.value, model.saveProgress.value, launch)
            }
        }
        model.refreshUpdates()
        showLowerPanel()
    }

    /**
     * The launcher deliberately exposes game files under /Dualscreen. Android
     * requires a one-time storage grant for that fixed user-visible location;
     * without it setup would silently fall back to the protected Wine prefix.
     */
    private fun ensureSharedStorageAccess() {
        if (storagePermissionPrompted) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                storagePermissionPrompted = true
                val appSettings = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                runCatching { startActivity(appSettings) }.onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                storagePermissionPrompted = true
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    STORAGE_PERMISSION_REQUEST,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = FULLSCREEN_FLAGS
        if (launcherStarted) {
            model.refreshUpdates()
            showLowerPanel()
        }
    }

    override fun onPause() {
        lowerPanel?.dismiss()
        lowerPanel = null
        super.onPause()
    }

    override fun onDestroy() {
        lowerPanel?.dismiss()
        lowerPanel = null
        super.onDestroy()
    }

    private fun showLowerPanel() {
        if (lowerPanel?.isShowing == true) return
        val manager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        @Suppress("DEPRECATION")
        val mainDisplayId = windowManager.defaultDisplay.displayId
        val lowerDisplay = manager.displays.firstOrNull { candidate ->
            candidate.displayId != mainDisplayId && DisplayTopology.isUsableSecondary(candidate)
        } ?: return
        lowerPanel = LauncherPanelPresentation(
            context = this,
            display = lowerDisplay,
        ).also {
            it.show()
            it.update(model.installed.value, model.progress.value, model.saveProgress.value, model.launchStatus.value)
        }
    }

    private fun launch(
        game: SupportedGame,
        installed: InstalledGame,
        configuration: Boolean,
    ) {
        model.clearSaveImport()
        startActivity(
            Intent(this, GameSessionActivity::class.java)
                .putExtra(GameSessionActivity.EXTRA_GAME_ID, game.id)
                .putExtra(GameSessionActivity.EXTRA_CONTAINER_ID, installed.containerId)
                .putExtra(GameSessionActivity.EXTRA_SHORTCUT_PATH, installed.shortcutPath)
                .putExtra(GameSessionActivity.EXTRA_CONFIGURATION_LAUNCHER, configuration),
        )
    }

    private fun launchSwitch(game: SwitchGameDefinition) {
        lifecycleScope.launch {
            val result = (application as DualScreenLauncherApplication)
                .switchBackend
                .createLaunchIntent(game)
            result.onSuccess(::startActivity).onFailure { failure ->
                Toast.makeText(
                    this@LauncherActivity,
                    failure.message ?: "Could not start ${game.title}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        private const val FULLSCREEN_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        private const val STORAGE_PERMISSION_REQUEST = 4401
    }
}

@Composable
internal fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFFF4AA43),
            onPrimary = Color(0xFF17120A),
            background = LauncherBackground,
            onBackground = Color(0xFFF1EEE6),
            surface = LauncherPanel,
            surfaceVariant = LauncherPanelRaised,
            onSurface = Color(0xFFF1EEE6),
            onSurfaceVariant = LauncherTextMuted,
            outline = LauncherOutline,
            error = Color(0xFFFF8A80),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp),
            extraLarge = RoundedCornerShape(0.dp),
        ),
        content = content,
    )
}
