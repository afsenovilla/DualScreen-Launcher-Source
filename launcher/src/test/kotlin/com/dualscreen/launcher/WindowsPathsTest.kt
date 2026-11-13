package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsPathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesWindowsPathsWithoutAndroidCaseSensitivity() {
        val root = temporaryFolder.newFolder("game")
        val data = root.resolve("DATA").apply { mkdirs() }
        val executable = root.resolve("ISAAC-NG.EXE").apply { writeBytes(byteArrayOf(1)) }
        val master = data.resolve("repentance.A").apply { writeBytes(byteArrayOf(2)) }

        assertThat(resolveWindowsFile(root, "isaac-ng.exe")).isEqualTo(executable)
        assertThat(resolveWindowsFile(root, "Data/Repentance.a")).isEqualTo(master)
        assertThat(resolveLaunchExecutable(root, checkNotNull(GameCatalog.byId("isaac-repentance"))))
            .isEqualTo("ISAAC-NG.EXE")
    }

    @Test
    fun rejectsPathsThatCouldEscapeTheGameRoot() {
        val root = temporaryFolder.newFolder("game-root")

        assertThat(resolveWindowsFile(root, "../SkyrimSE.exe")).isNull()
    }
}
