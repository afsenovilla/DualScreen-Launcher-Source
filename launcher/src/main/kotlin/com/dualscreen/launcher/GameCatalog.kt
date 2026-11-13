package com.dualscreen.launcher

enum class LowerPanelMode { NATIVE_WINDOW, STARDEW, VALHEIM, DIAGNOSTICS }

data class FileRule(
    val path: String,
    val bytes: Long? = null,
    val sha256: String? = null,
    val sha256AnyOf: Set<String> = emptySet(),
)

data class PrerequisitePackage(
    val name: String,
    val version: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val nestedZipSuffix: String? = null,
    val installedFiles: List<String>,
)

data class SupportedGame(
    val id: String,
    val title: String,
    /** Stable, Windows-safe directory name under /Dualscreen/Games. */
    val storageName: String = safeStorageSegment(title, "Game"),
    val summary: String,
    /** Player-facing build contract; validation below remains authoritative. */
    val requiredBuild: String,
    /** Visible until that game's complete dual-screen integration is release-ready. */
    val isBeta: Boolean = true,
    val executableCandidates: List<String>,
    /** Optional vendor configuration launcher; Play never uses this path. */
    val configurationLauncherCandidates: List<String> = emptyList(),
    /** Save root relative to Wine's drive_c/users/xuser profile; blank disables save management until verified. */
    val saveDirectory: String,
    /** Flat-save games may safely unwrap one arbitrary ZIP wrapper directory. */
    val stripSingleSaveArchiveRoot: Boolean = false,
    val requiredFiles: List<String>,
    val fileRules: List<FileRule> = emptyList(),
    val prerequisites: List<PrerequisitePackage> = emptyList(),
    val launchArguments: String = "",
    val panelMode: LowerPanelMode = LowerPanelMode.NATIVE_WINDOW,
    /** Hide the duplicate DirectInput view and expose only Wine's XInput pad. */
    val exclusiveXInput: Boolean = false,
    /** Executable-scoped Wine DLL policy; never promoted to prefix-wide env vars. */
    val nativeDllOverrides: Map<String, String> = emptyMap(),
    val windowHints: List<String> = executableCandidates,
)

/**
 * The complete, deliberately closed library supported by this product.
 * Adding a game requires an installer payload and validation rules in the APK;
 * users cannot create arbitrary entries or containers from the interface.
 */
object GameCatalog {
    val games: List<SupportedGame> = listOf(
        SupportedGame(
            id = "isaac-repentance",
            title = "The Binding of Isaac: Repentance+",
            storageName = "The Binding of Isaac - Repentance",
            summary = "Live dungeon map, stats, inventory, and run information.",
            requiredBuild = "Repentance+ v1.9.7.17.J460 (Windows 32-bit)",
            executableCandidates = listOf("isaac-ng.exe"),
            saveDirectory = "Documents/My Games/Binding of Isaac Repentance+",
            requiredFiles = listOf(
                "isaac-ng.exe",
                "resources/packed/repentance.a",
            ),
            fileRules = listOf(
                // Same J460 build: the Steam executable (with the SteamStub
                // .bind section) or the DRM-free executable without it.
                FileRule(
                    path = "isaac-ng.exe",
                    sha256AnyOf = setOf(
                        "e5faf6f513c6de9cc9ff1c335ef9c541e2b33fc623aa3b8ffbd2e6a6c0027bb0",
                        "b4743967f3d31050f7b4222e42ac6beae6a0b7850b1f1adaac07ef7b94d01ec8",
                    ),
                ),
            ),
            panelMode = LowerPanelMode.NATIVE_WINDOW,
            exclusiveXInput = true,
            windowHints = listOf("isaac-ng", "binding of isaac", "repentance"),
        ),
    )

    fun byId(id: String?): SupportedGame? = games.firstOrNull { it.id == id }
}
