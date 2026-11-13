package com.dualscreen.launcher

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ArchiveImportSafetyTest {
    @Test
    fun manifestDeclaredPatchOutputPassesPostInstallVersionValidation() {
        val outputs = setOf("verified-output")

        assertThat(payloadOutputIsAccepted("verified-output", outputs)).isTrue()
        assertThat(payloadOutputIsAccepted("different-output", outputs)).isFalse()
    }

    @Test
    fun skippedNativePatchIsRetriedWhenACompatibleInputAppears() {
        val inputs = setOf("supported-input")
        val outputs = setOf("patched-output")

        assertThat(nativePatchTargetIsCurrent(null, inputs, outputs, previouslySkipped = true))
            .isFalse()
        assertThat(nativePatchTargetIsCurrent("unknown-build", inputs, outputs, previouslySkipped = true))
            .isTrue()
        assertThat(nativePatchTargetIsCurrent("supported-input", inputs, outputs, previouslySkipped = true))
            .isFalse()
        assertThat(nativePatchTargetIsCurrent("patched-output", inputs, outputs, previouslySkipped = true))
            .isTrue()
        assertThat(nativePatchTargetIsCurrent("unknown-build", inputs, outputs, previouslySkipped = false))
            .isFalse()
    }

    @Test
    fun systemGpuDriverDoesNotRequireAProvisionedAdrenotoolsArchive() {
        assertThat(requiresAdrenotoolsProvisioning("System")).isFalse()
        assertThat(requiresAdrenotoolsProvisioning("system")).isFalse()
        assertThat(requiresAdrenotoolsProvisioning("turnip-25.1.0")).isTrue()
    }

    @Test
    fun gameArchivePathsAllowNestedWrappersAndWindowsSeparators() {
        assertThat(normalizeGameArchiveEntry("wrapper/one/two/Fallout3.exe"))
            .isEqualTo("wrapper/one/two/Fallout3.exe")
        assertThat(normalizeGameArchiveEntry("wrapper\\Data\\Fallout3.esm"))
            .isEqualTo("wrapper/Data/Fallout3.esm")
        assertThat(normalizeGameArchiveEntry("./Fallout 3/./Data/Fallout3.esm"))
            .isEqualTo("Fallout 3/Data/Fallout3.esm")
        assertThat(normalizeGameArchiveEntry("./")).isEmpty()
        assertThat(normalizeGameArchiveEntry(".")).isEmpty()
    }

    @Test
    fun gameArchivePathsRejectAbsoluteTraversalAndDrivePaths() {
        listOf("/Fallout3.exe", "../Fallout3.exe", "C:/Fallout3.exe", "a/../../b").forEach { path ->
            val failure = runCatching { normalizeGameArchiveEntry(path) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun extractionBudgetReservesSpaceWithoutOverflow() {
        assertThat(extractionByteBudget(10_000L, 3_000L)).isEqualTo(7_000L)
        assertThat(extractionByteBudget(Long.MAX_VALUE, 1L)).isEqualTo(Long.MAX_VALUE - 1L)

        val failure = runCatching { extractionByteBudget(3_000L, 3_000L) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun gameArchiveTargetCannotEscapeCanonicalExtractionRoot() {
        val root = Files.createTempDirectory("game-import-safety").toFile()
        try {
            assertThat(safeGameArchiveTarget(root, "wrapper/Data/Fallout3.esm"))
                .isEqualTo(File(root, "wrapper/Data/Fallout3.esm").canonicalFile)
            val failure = runCatching { safeGameArchiveTarget(root, "../outside.exe") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun untestedBuildMessageWarnsWithoutCallingTheArchiveInvalid() {
        val game = checkNotNull(GameCatalog.byId("isaac-repentance"))
        val message = untestedVersionMessage(
            game,
            listOf("isaac-ng.exe differs from the recommended build"),
        )
        assertThat(message).contains("has not been tested")
        assertThat(message).contains("For the best compatibility")
        assertThat(message).contains("1.9.7.17.J460")
    }

    @Test
    fun gameRootDetectionAcceptsDirectSingleWrapperAndNestedLayouts() {
        val required = listOf("Fallout3.exe", "Data/Fallout3.esm")
        assertThat(detectArchiveGameRoot(required, required)).isEqualTo("")
        assertThat(
            detectArchiveGameRoot(
                listOf("Fallout 3/Fallout3.exe", "Fallout 3/Data/Fallout3.esm"),
                required,
            ),
        ).isEqualTo("Fallout 3")
        assertThat(
            detectArchiveGameRoot(
                listOf("Backup/Games/Fallout 3/Fallout3.exe", "Backup/Games/Fallout 3/Data/Fallout3.esm"),
                required,
            ),
        ).isEqualTo("Backup/Games/Fallout 3")
    }

    @Test
    fun gameRootDetectionDoesNotCombineFilesFromDifferentFolders() {
        assertThat(
            detectArchiveGameRoot(
                listOf("one/Fallout3.exe", "two/Data/Fallout3.esm"),
                listOf("Fallout3.exe", "Data/Fallout3.esm"),
            ),
        ).isNull()
    }

    @Test
    fun saveArchiveTargetRejectsPathsThatCanEscapeTheProfile() {
        val root = Files.createTempDirectory("save-import-safety").toFile()
        try {
            assertThat(safeZipTarget(root, "Saves/Save 1.fos"))
                .isEqualTo(File(root, "Saves/Save 1.fos").canonicalFile)
            assertThat(safeZipTarget(root, "./Saves/Save 2.fos"))
                .isEqualTo(File(root, "Saves/Save 2.fos").canonicalFile)
            assertThat(safeZipTarget(root, "./")).isEqualTo(root.canonicalFile)
            listOf("/outside.fos", "../outside.fos", "C:/outside.fos").forEach { path ->
                val failure = runCatching { safeZipTarget(root, path) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savePayloadResolverFindsNestedBethesdaSavesDirectory() {
        val root = Files.createTempDirectory("save-import-layout").toFile()
        try {
            val saves = File(root, "export/Documents/My Games/Fallout3/Saves")
            assertThat(saves.mkdirs()).isTrue()
            File(saves, "Save 1.fos").writeText("save")
            assertThat(selectSavePayloadRoot(root, "Saves", stripSingleRoot = true))
                .isEqualTo(saves)
        } finally {
            root.deleteRecursively()
        }
    }
}
