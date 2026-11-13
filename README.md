# DualScreen Launcher

DualScreen Launcher is a standalone Android launcher for games with tested
dual-screen mods. It is intentionally separate from WEMU and has a different
application ID (`com.dualscreen.launcher`), data directory, build, and UI.
Installing or removing it does not replace WEMU or alter WEMU's containers.

The same APK also embeds Eden DS as its Nintendo Switch backend. The Launcher
remains the only frontend: PC titles use the existing container engine, while
Switch titles run through Eden's native core and dual-display runtime without
requiring or launching a separate emulator application.

All supported-game implementation source is now kept inside this project:
PC mod sources live under [`pc/mods`](pc/mods), while Eden DS and its Switch
dual-screen implementations live under
[`switch/eden-upstream`](switch/eden-upstream). Generated payloads remain
separate from source under `launcher/src/main/assets/mods`. The build no longer
depends on the former external WEMU mod-source directory.

## Switch library

The Switch Games tab supports Mario Kart 8 Deluxe, The Legend of Zelda: Breath
of the Wild, Pokémon Legends: Z-A, and The Legend of Zelda: Skyward Sword HD.
Each title has separate base-NSP, update, DLC, mod, save, and per-game settings
controls. NSP metadata and title IDs are checked before any content is installed.
Existing Eden DS user data is migrated on the first Switch runtime initialization
when Android permits access to the previous app-specific directory.

This project was built from WEMU's production runtime/renderer modules. It does
not include or depend on the old prototype launcher. There are no Steam, Epic,
GOG, community, store, container-editor, or arbitrary-shortcut screens.

## Supported library

| Game | Required build/runtime | Lower display |
| --- | --- | --- |
| Skyrim Special Edition | `SkyrimSE.exe` 1.5.97.0, SHA-256 pinned | Native live game panel |
| Deus Ex: Human Revolution Director's Cut | `DXHRDC.exe` 2.0.66.0, SHA-256 pinned | Native live game panel |
| Dead Island Definitive Edition | The three verified 2016 executable/DLL sizes | Native live game panel |
| Valheim | Windows x64 game files; pinned BepInEx 5.4.23.5 is installed automatically | WEMU Valheim panel |
| Stardew Valley | Windows 1.6.15.24356 GOG or Steam game files | Native live game panel embedded into the supplied game assembly |

The player supplies their own game files as a ZIP. No game executable, asset,
mesh, texture, music, or other copyrighted game data is bundled.

## Exact predefined container

The default immutable preset is a direct capture of WEMU container `Games`
(`xuser-2`) on the AYN Thor on 2026-08-28. Among its pinned settings are:

- Proton `11.0-5-arm64ec-6`;
- FEXCore `2608-stable-0` and Box64 `0.4.2`;
- DXVK `3.0-1-gplasync-0` and VKD3D `2.9-0`;
- Turnip `26.2.0`, SurfaceFlinger presentation, mailbox mode;
- the original environment, CPU affinity, audio, DPI, FSR, direct scanout,
  adaptive refresh, and second-screen values.

The exact installed compatibility component trees were captured with the preset
and are pinned by version, byte length, and SHA-256. They are delivered from the
public payload release rather than embedded in the APK. Box64 and the graphics
driver are verified through the production runtime payload path. A
similar-looking public component is never silently substituted.

Stardew Valley is isolated in its own immutable capture of the working WEMU
`proton 9` container. Its real pinned runtime components and compatibility
settings are retained, while lower-desktop output is enabled so the embedded
`StardewValleySecondScreenPanel` window is scanned out on the Thor's lower
display. No other game's preset is changed by this Stardew-specific policy.

## Installation flow

1. The selected ZIP is extracted into a private staging directory with entry,
   expansion, free-space, and path-traversal limits.
2. The launcher finds exactly one supported game root and validates its required
   files and build gates.
3. Any game-specific framework still required by that game is downloaded from its pinned official GitHub release,
   checked by exact size and SHA-256, and overlaid in staging.
4. The matching dual-screen mod payload is copied from the verified first-boot
   payload store and every file is checked against its manifest hash. Stardew uses a verified binary delta for
   the detected GOG or Steam assembly, so the APK does not contain Stardew game
   code and never needs SMAPI.
5. Game, framework, launch executable, and mod files are verified again.
6. The old installation is retained as a rollback while the verified staging
   tree is atomically published. Only then is the direct launch entry saved.

The persistent lower-screen console exposes the active stage, overall progress,
and a bounded live log while the upper display remains on Games. A failed
install never replaces a working copy.

The Games page is always the initial page. It uses a compact horizontal shelf
with each complete 2:3 portrait cover isolated above its title, status, and
action area. Settings is a separate page exposing launcher
behavior and read-only preset/storage status; it never unlocks container
editing.

## Runtime behavior

Play starts only the installed game's shortcut. A full-window loading surface
covers the Wine environment until a mapped window with that game's verified
identity appears, so Explorer and the container desktop are never presented as
part of the launcher. The second display is selected with WEMU's production
Thor display-topology rule; virtual/private displays are rejected.

Native dual-screen games, including Stardew, use the zero-copy secondary
SurfaceFlinger output. Valheim retains its production local companion protocol and UI. The
session owns one runtime, one top renderer, and one lower presentation, and
releases the presentation, output surface, listeners, handlers, and runtime on
exit.

The lower display is active for the entire product experience: it is a dedicated
console in the launcher, showing readiness, ZIP validation/extraction progress,
the bounded live install log, and clear failures. It then hands off immediately
to a game-specific waiting panel before Wine starts. It never waits for the
Windows desktop or game window before claiming the lower screen.

## First-boot payloads

Large Wine/Proton components and dual-screen mod payloads are published at the
[DualScreen Launcher Payloads](https://github.com/JoeCorrell/DualScreen-Launcher-Payloads/releases/tag/launcher-payloads-v1)
release. The APK contains a small immutable bootstrap manifest with the exact
HTTPS URLs, byte lengths, and SHA-256 values. On first boot the launcher:

1. downloads each pinned pack with retry and resumable range support;
2. verifies its complete length and SHA-256 before extraction;
3. rejects absolute paths and traversal entries while extracting to staging;
4. atomically publishes the complete version, retaining the previous payload
   until the replacement is ready.

The library is not exposed until this succeeds, and a failed update cannot
replace a working payload set. Use `tools/build-launcher-payload-release.ps1`
to reproduce release packs and their bootstrap manifest.

## Build and verify

Use JDK 17 and an Android SDK configured in `local.properties`:

```powershell
.\gradlew.bat :launcher:testDebugUnitTest :launcher:assembleDebug
```

The APK is written to:

```text
launcher/build/outputs/apk/debug/launcher-debug.apk
```

For a locally signed release, run `tools/generate-release-keystore.ps1` once,
back up the generated keystore and ignored `keystore.properties`, then build:

```powershell
.\gradlew.bat :launcher:assembleRelease
```

The release APK is written to
`launcher/build/outputs/apk/release/launcher-release.apk`. Signing credentials,
keystores, and generated payload archives are ignored by Git.

The integrity tests validate the payload release pins, captured preset's
critical values, every mod manifest entry, the closed game catalog, and pinned
prerequisite metadata.

BepInEx is sourced from the official [BepInEx releases](https://github.com/BepInEx/BepInEx/releases).
See [NOTICE.md](NOTICE.md) for inherited notices.
