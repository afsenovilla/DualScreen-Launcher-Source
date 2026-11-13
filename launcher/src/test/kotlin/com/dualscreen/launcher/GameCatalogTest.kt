package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GameCatalogTest {
    @Test
    fun catalogContainsOnlyTheBindingOfIsaac() {
        assertThat(GameCatalog.games.map { it.id }).containsExactly("isaac-repentance")
        val game = GameCatalog.games.single()
        assertThat(game.executableCandidates).isNotEmpty()
        assertThat(game.requiredFiles).isNotEmpty()
        assertThat(game.requiredBuild).contains("1.9.7.17.J460")
        assertThat(game.saveDirectory).isEqualTo("Documents/My Games/Binding of Isaac Repentance+")
    }

    @Test
    fun isaacUsesTheOfficialLuaApiAndNativeInProcessPanel() {
        val isaac = checkNotNull(GameCatalog.byId("isaac-repentance"))
        assertThat(isaac.executableCandidates).containsExactly("isaac-ng.exe")
        assertThat(isaac.panelMode).isEqualTo(LowerPanelMode.NATIVE_WINDOW)
        assertThat(isaac.requiredFiles).containsAtLeast(
            "resources/packed/repentance.a",
            "resources/scripts/socket/core.dll",
        )
        assertThat(isaac.nativeDllOverrides).isEmpty()
        assertThat(isaac.exclusiveXInput).isTrue()
    }

    @Test
    fun isaacAcceptsTheSteamAndDrmFreeJ460Executables() {
        val rule = checkNotNull(GameCatalog.byId("isaac-repentance")).fileRules.single()
        val steam = "e5faf6f513c6de9cc9ff1c335ef9c541e2b33fc623aa3b8ffbd2e6a6c0027bb0"
        val drmFree = "b4743967f3d31050f7b4222e42ac6beae6a0b7850b1f1adaac07ef7b94d01ec8"
        assertThat(rule.path).isEqualTo("isaac-ng.exe")
        assertThat(rule.sha256AnyOf).containsExactly(steam, drmFree)
        assertThat(compatibilityWarning(rule, 9_362_944L, steam)).isNull()
        assertThat(compatibilityWarning(rule, 9_176_064L, drmFree)).isNull()
        assertThat(compatibilityWarning(rule, 9_176_064L, "0".repeat(64))).isNotNull()
    }
}
