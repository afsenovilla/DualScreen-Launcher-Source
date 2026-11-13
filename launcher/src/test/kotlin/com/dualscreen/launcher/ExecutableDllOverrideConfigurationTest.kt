package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExecutableDllOverrideConfigurationTest {
    @Test
    fun audioAndPanelPoliciesAreAcceptedAndRemainExecutableScoped() {
        val root = Files.createTempDirectory("ac2-overrides").toFile()
        try {
            File(root, ".wine").mkdirs()
            val registry = File(root, ".wine/user.reg").apply {
                writeText("WINE REGISTRY Version 2\n\n[Software\\\\Wine\\\\DllOverrides] 1\n\"dsound\"=\"native\"\n")
            }
            repeat(2) {
                ExecutableDllOverrideConfiguration.ensure(
                    root,
                    "AssassinsCreedIIGame.exe",
                    mapOf("dinput8" to "native,builtin", "dsound" to "builtin,native"),
                )
            }
            val key = "Software\\Wine\\AppDefaults\\AssassinsCreedIIGame.exe\\DllOverrides"
            com.wemu.engine.core.WineRegistryEditor(registry).use { editor ->
                assertThat(editor.getStringValue(key, "dinput8")).isEqualTo("native,builtin")
                assertThat(editor.getStringValue(key, "dsound")).isEqualTo("builtin,native")
                assertThat(editor.getStringValue("Software\\Wine\\DllOverrides", "dsound")).isEqualTo("native")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidPoliciesAreRejectedBeforeRegistryWrites() {
        val root = Files.createTempDirectory("invalid-overrides").toFile()
        try {
            File(root, ".wine").mkdirs()
            val registry = File(root, ".wine/user.reg").apply { writeText("WINE REGISTRY Version 2\n") }
            val original = registry.readText()
            for ((dll, value) in listOf(
                "dsound" to "builtin,native\n\"other\"=\"native\"",
                "dsound" to "native,builtin,unknown",
                "../dsound" to "builtin,native",
            )) {
                var rejected = false
                try { ExecutableDllOverrideConfiguration.ensure(root, "AssassinsCreedIIGame.exe", mapOf(dll to value)) }
                catch (_: IllegalArgumentException) { rejected = true }
                assertThat(rejected).isTrue()
                assertThat(registry.readText()).isEqualTo(original)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overrideIsExecutableScopedAndIdempotent() {
        val root = Files.createTempDirectory("wfc-override").toFile()
        try {
            val prefix = File(root, ".wine").apply { mkdirs() }
            val registry = File(prefix, "user.reg").apply {
                writeText("WINE REGISTRY Version 2\n\n[Software\\Existing] 1\n#time=1\n\"Keep\"=\"yes\"\n")
            }
            ExecutableDllOverrideConfiguration.ensure(
                root,
                "TWFC.exe",
                mapOf("dinput8" to "native,builtin"),
            )
            ExecutableDllOverrideConfiguration.ensure(
                root,
                "TWFC.exe",
                mapOf("dinput8" to "native,builtin"),
            )
            val text = registry.readText()
            assertThat(text).contains("Software\\\\Wine\\\\AppDefaults\\\\TWFC.exe\\\\DllOverrides")
            assertThat(text).contains("\"dinput8\"=\"native,builtin\"")
            assertThat(text).contains("\"Keep\"=\"yes\"")
            assertThat(text).doesNotContain("Software\\\\Wine\\\\DllOverrides")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathLikeExecutableNames() {
        val root = Files.createTempDirectory("wfc-override-invalid").toFile()
        try {
            File(root, ".wine").mkdirs()
            File(root, ".wine/user.reg").writeText("WINE REGISTRY Version 2\n")
            ExecutableDllOverrideConfiguration.ensure(
                root,
                "..\\TWFC.exe",
                mapOf("dinput8" to "native,builtin"),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
