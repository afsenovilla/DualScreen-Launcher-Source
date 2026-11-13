package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CapturedRuntimeRequirementsTest {
    private val assets = File("src/main/assets")

    @Test
    fun pinnedTranslatorRequiresThePinnedManifestComponentWithoutFixedContainerAssets() {
        val request = CapturedRuntimeRequirements.fexCore("FEXCore-2608-stable-0")
        assertThat(request.fixedAssets).isNull()
        assertThat(request.componentIds).containsExactly("FEXCore-FEXCore-2608-stable-0")
        val manifest = JSONObject(File(assets, "components/manifest.json").readText()).getJSONArray("components")
        val component = (0 until manifest.length()).map(manifest::getJSONObject)
            .single { it.getString("id") == request.componentIds.single() }
        assertThat(component.getString("sha256")).matches("[0-9a-f]{64}")
        assertThat(component.getLong("bytes")).isGreaterThan(0)
    }

    @Test
    fun alreadyQualifiedTranslatorIdIsNotPrefixedAgain() {
        assertThat(CapturedRuntimeRequirements.fexCore("FEXCore-FEXCore-2608-stable-0").componentIds)
            .containsExactly("FEXCore-FEXCore-2608-stable-0")
    }

    @Test
    fun incompleteTranslatorOverrideCannotSilentlySkipProvisioning() {
        listOf("", "2608", "FEXCore-latest", "Proton-10.0-arm64ec-0").forEach { value ->
            assertThrows(IllegalStateException::class.java) { CapturedRuntimeRequirements.fexCore(value) }
        }
    }

    @Test
    fun fullCapturedPresetsStillRequireTheirFixedAssetsAndDoNotChange() {
        listOf("games.json").forEach { name ->
            val profile = JSONObject(File(assets, "presets/$name").readText())
            val before = profile.toString()
            val request = CapturedRuntimeRequirements.preset(profile)
            assertThat(request.fixedAssets?.box64Archive).isEqualTo("box64/box64-0.4.2.tzst")
            assertThat(request.fixedAssets?.rendererDriverId).isEqualTo("turnip26.2.0")
            assertThat(request.componentIds).contains(profile.getString("wineVersion"))
            assertThat(profile.toString()).isEqualTo(before)
        }
    }

    @Test
    fun missingBox64InAFullPresetStillFails() {
        val failure = assertThrows(IllegalStateException::class.java) {
            CapturedRuntimeRequirements.preset(JSONObject().put("rendererDriverId", "turnip26.2.0"))
        }
        assertThat(failure).hasMessageThat().isEqualTo("The captured preset has no Box64 version")
    }

    @Test
    fun missingRendererInAFullPresetStillFails() {
        val failure = assertThrows(IllegalStateException::class.java) {
            CapturedRuntimeRequirements.preset(JSONObject().put("box64Version", "0.4.2"))
        }
        assertThat(failure).hasMessageThat().isEqualTo("The captured preset has no renderer driver")
    }
}
