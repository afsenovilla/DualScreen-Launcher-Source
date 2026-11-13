package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AssetIntegrityTest {
    private val assets = File("src/main/assets").canonicalFile
    private val engineAssets = File("../engine/src/main/assets").canonicalFile
    private val main = File("src/main").canonicalFile

    @Test
    fun loopbackBridgeIsAllowedWithoutEnablingGlobalCleartext() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val networkPolicy = File(main, "res/xml/network_security_config.xml").readText()
        assertThat(manifest).contains("android:networkSecurityConfig=\"@xml/network_security_config\"")
        assertThat(networkPolicy).contains("<base-config cleartextTrafficPermitted=\"false\"")
        assertThat(networkPolicy).contains(">127.0.0.1</domain>")
        assertThat(networkPolicy).contains(">localhost</domain>")
    }

    @Test
    fun capturedGamesPresetMatchesThorSnapshot() {
        val preset = JSONObject(File(assets, "presets/games.json").readText())
        assertThat(preset.getString("name")).isEqualTo("Games")
        assertThat(preset.getString("wineVersion")).isEqualTo("Proton-11.0-5-arm64ec-6")
        assertThat(preset.getString("fexcoreVersion")).isEqualTo("FEXCore-2608-stable-0")
        assertThat(preset.getString("emulator")).isEqualTo("FEXCore")
        assertThat(preset.getString("box64Version")).isEqualTo("0.4.2")
        assertThat(preset.getString("rendererDriverId")).isEqualTo("turnip26.2.0")
        assertThat(preset.getString("displayRenderer")).isEqualTo("surfaceflinger")
        assertThat(preset.getJSONObject("extraData").getString("displaySecondScreen")).isEqualTo("1")
        assertThat(preset.getJSONObject("extraData").getString("affinityCores")).isEqualTo("3,4,5,6,7")
        assertThat(preset.getJSONObject("extraData").getString("fallout3Compatibility"))
            .isEqualTo("4")
    }

    @Test
    fun capturedComponentManifestPinsEveryRemoteArchive() {
        val manifest = JSONObject(File(assets, "components/manifest.json").readText())
            .getJSONArray("components")
        // Includes the existing DXVK 2.6 compatibility archive alongside 3.0.
        assertThat(manifest.length()).isEqualTo(6)
        for (index in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(index)
            assertThat(entry.getString("source")).matches("[A-Za-z0-9._-]+\\.tgz")
            assertThat(entry.getLong("bytes")).isGreaterThan(0)
            assertThat(entry.getString("sha256")).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun everyRemoteModPayloadIsPinnedAndTargetsAPathInsideTheGame() {
        GameCatalog.games.forEach { game ->
            val root = File(assets, "mods/${game.id}").canonicalFile
            val manifestFile = File(root, "manifest.json")
            val bytes = manifestFile.readBytes()
            assertThat(bytes.take(3)).isNotEqualTo(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            val files = manifest.optJSONArray("files")
            val patches = manifest.optJSONArray("patches")
            assertThat((files?.length() ?: 0) + (patches?.length() ?: 0)).isGreaterThan(0)
            for (index in 0 until (files?.length() ?: 0)) {
                val entry = checkNotNull(files).getJSONObject(index)
                assertThat(entry.getString("source")).doesNotContain("..")
                assertThat(entry.getString("sha256")).matches("[0-9a-f]{64}")
                val target = entry.getString("target").replace('\\', '/')
                assertThat(target.startsWith("/")).isFalse()
                assertThat(target.split('/')).doesNotContain("..")
            }
            for (index in 0 until (patches?.length() ?: 0)) {
                val entry = checkNotNull(patches).getJSONObject(index)
                assertThat(entry.getString("source")).doesNotContain("..")
                assertThat(entry.getString("sha256")).matches("[0-9a-f]{64}")
                assertThat(entry.getString("inputSha256")).matches("[0-9a-f]{64}")
                assertThat(entry.getString("outputSha256")).matches("[0-9a-f]{64}")
                val target = entry.getString("target").replace('\\', '/')
                assertThat(target.startsWith("/")).isFalse()
                assertThat(target.split('/')).doesNotContain("..")
            }
            val removals = manifest.optJSONArray("remove")
            for (index in 0 until (removals?.length() ?: 0)) {
                val target = checkNotNull(removals).getString(index).replace('\\', '/')
                assertThat(target.startsWith("/")).isFalse()
                assertThat(target.split('/')).doesNotContain("..")
            }
        }
    }

    @Test
    fun isaacNativePatchAndAssetsCoverTheExactInstalledBuild() {
        val isaacAssets = File("../pc/mods/IsaacSecondScreen/assets").canonicalFile
        val index = JSONObject(File(isaacAssets, "index.json").readText())
        assertThat(index.getString("sourceBuild"))
            .isEqualTo("Binding of Isaac: Repentance+ v1.9.7.17.J460")
        val items = index.getJSONObject("items")
        assertThat(items.getJSONObject("collectibles").length()).isEqualTo(721)
        assertThat(items.getJSONObject("trinkets").length()).isEqualTo(188)
        assertThat(index.getJSONObject("players").length()).isEqualTo(41)
        assertThat(index.getJSONObject("pockets").length()).isEqualTo(98)
        assertThat(items.getJSONObject("collectibles").getJSONObject("118").getInt("quality"))
            .isEqualTo(4)

        listOf("collectibles", "trinkets").forEach { category ->
            val entries = items.getJSONObject(category)
            entries.keys().forEach { id ->
                val asset = entries.getJSONObject(id).getString("asset")
                    .removePrefix("isaac/")
                assertThat(File(isaacAssets, asset).isFile).isTrue()
            }
        }
        listOf(
            "game/font/upheaval.fnt",
            "game/gfx/ui/minimap_icons.png",
            "game/gfx/ui/ui_hearts.png",
            "game/gfx/items/pick ups/pickup_007_pill.png",
        ).forEach { relative -> assertThat(File(isaacAssets, relative).isFile).isTrue() }

        val mod = File(assets, "mods/isaac-repentance")
        val payload = File(mod, "payload").canonicalFile
        val manifest = JSONObject(File(mod, "manifest.json").readText()).getJSONArray("files")
        assertThat(manifest.length()).isEqualTo(1_309)
        val declared = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        for (entryIndex in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(entryIndex)
            val sourceName = entry.getString("source").replace('\\', '/')
            val targetName = entry.getString("target").replace('\\', '/')
            assertThat(declared.add(sourceName.lowercase())).isTrue()
            assertThat(targets.add(targetName.lowercase())).isTrue()
            assertThat(targetName.startsWith('/')).isFalse()
            assertThat(targetName.split('/')).doesNotContain("..")
            val source = File(payload, sourceName).canonicalFile
            assertThat(source.path.startsWith(payload.path + File.separator)).isTrue()
            assertThat(source.isFile).isTrue()
            assertThat(sha256(source))
                .isEqualTo(entry.getString("sha256"))
        }
        val bundled = payload.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(payload).invariantSeparatorsPath.lowercase() }
            .toSet()
        assertThat(declared).containsExactlyElementsIn(bundled)
        assertThat(targets).containsAtLeast(
            "winmm.dll",
            "resources/scripts/isaac_second_screen_assets/game/gfx/ui/minimap_icons.png",
            "resources/scripts/isaac_second_screen_assets/custom/item_description_panel.png",
            "resources/scripts/isaac_second_screen_assets/custom/item_slot_dark.png",
            "mods/dualscreenisaac/main.lua",
        )
        val mainLua = File(payload, "mod/main.lua").readText()
        assertThat(mainLua).contains("native winmm proxy")
        assertThat(mainLua).contains("luaopen_isaac_second_screen")
        assertThat(mainLua).doesNotContain("pcall(require, \"socket\")")
        assertThat(mainLua).doesNotContain("require, \"isaac_second_screen\"")
    }

    @Test
    fun firstBootPayloadReleaseIsPinnedAndPublic() {
        val manifest = JSONObject(File(assets, "payload-bootstrap.json").readText())
        assertThat(manifest.getInt("schema")).isEqualTo(1)
        assertThat(manifest.getString("version")).isNotEmpty()
        assertThat(manifest.getString("repository"))
            .isEqualTo("https://github.com/JoeCorrell/DualScreen-Launcher-Payloads")
        val packs = manifest.getJSONArray("packs")
        assertThat((0 until packs.length()).map { packs.getJSONObject(it).getString("root") })
            .containsExactly("components")
        for (index in 0 until packs.length()) {
            val pack = packs.getJSONObject(index)
            assertThat(pack.getLong("bytes")).isGreaterThan(0)
            assertThat(pack.getString("sha256")).matches("[0-9a-f]{64}")
            assertThat(pack.getString("url"))
                .startsWith("https://github.com/JoeCorrell/DualScreen-Launcher-Payloads/releases/download/")
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
