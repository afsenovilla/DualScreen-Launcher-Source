package com.dualscreen.launcher

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Resolves payload assets from either the APK or first-boot storage.
 *
 * A locally-built APK can contain mod files newer than the last published
 * payload pack. In that case the downloaded store must not shadow the APK:
 * doing so made a new mod manifest and DLL look successfully installed while
 * the old downloaded copies were actually being read. A genuinely newer
 * remote payload catalog still takes precedence over the APK.
 */
internal object PayloadAssetStore {
    private const val DIRECTORY = "payload-assets/current"
    private const val VERSION_FILE = ".version"
    private const val BOOTSTRAP_MANIFEST = "payload-bootstrap.json"

    @Volatile
    private var embeddedCatalogVersion: String? = null

    fun root(context: Context): File = File(context.filesDir, DIRECTORY)

    fun open(context: Context, relativePath: String): InputStream {
        val normalized = normalize(relativePath)
        val root = root(context).canonicalFile
        val downloaded = File(root, normalized).canonicalFile
        check(downloaded.path.startsWith(root.path + File.separator)) {
            "Payload path escapes the payload store"
        }
        if (shouldPreferBundledModAsset(
                normalized,
                File(root, VERSION_FILE).readTextOrNull(),
                readEmbeddedCatalogVersion(context),
            )
        ) {
            runCatching { context.assets.open(normalized) }
                .getOrNull()
                ?.let { return it }
        }
        if (downloaded.isFile) return downloaded.inputStream().buffered()
        return runCatching { context.assets.open(normalized) }.getOrElse {
            throw FileNotFoundException(
                "Required launcher payload is unavailable: $normalized. " +
                    "Return to the launcher and retry first-boot setup.",
            )
        }
    }

    private fun readEmbeddedCatalogVersion(context: Context): String {
        embeddedCatalogVersion?.let { return it }
        return synchronized(this) {
            embeddedCatalogVersion ?: context.assets.open(BOOTSTRAP_MANIFEST)
                .bufferedReader()
                .use { PayloadBootstrapManifest.parse(it.readText()).version }
                .also { embeddedCatalogVersion = it }
        }
    }

    private fun File.readTextOrNull(): String? = runCatching {
        takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()

    internal fun normalize(relativePath: String): String {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Payload path must not be blank" }
        require(relativePath.firstOrNull() != '/' && !Regex("^[A-Za-z]:").containsMatchIn(relativePath)) {
            "Payload path must be relative"
        }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Payload path is unsafe"
        }
        return normalized
    }
}

/**
 * The only supported game's dual-screen mod ships inside the APK. A downloaded
 * payload catalog must never replace it (an upstream pack would carry a native
 * patch built for a different set of accepted executables).
 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldPreferBundledModAsset(
    normalizedPath: String,
    storedCatalogVersion: String?,
    embeddedCatalogVersion: String,
): Boolean = normalizedPath.startsWith("mods/")
