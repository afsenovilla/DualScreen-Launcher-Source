package com.dualscreen.launcher

import android.app.Application
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Fetches AdrenoTools-compatible Turnip/Freedreno driver releases for the PC runtime. */
internal class GpuDriverRepository(private val application: Application) {
    suspend fun fetch(
        installedNames: Set<String>,
    ): List<GpuDriverRelease> = withContext(Dispatchers.IO) {
        coroutineScope {
            val releases = SOURCES.map { source ->
                async { runCatching { fetchSource(source, installedNames) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
            require(releases.isNotEmpty()) {
                "GPU driver sources are currently unavailable. Check the network and try again."
            }
            releases
                .sortedWith(
                    compareBy<GpuDriverRelease> { sourceOrder(it.source) }
                        .thenByDescending { it.latest }
                        .thenByDescending { it.publishedAt },
                )
        }
    }

    suspend fun downloadAndInstall(
        artifact: GpuDriverArtifact,
        onProgress: (Float) -> Unit,
        installer: suspend (Uri) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val sourceUrl = URL(artifact.downloadUrl)
        require(sourceUrl.protocol == "https" && sourceUrl.host in ALLOWED_DOWNLOAD_HOSTS) {
            "Unsupported driver download URL."
        }
        val cacheRoot = File(application.cacheDir, "gpu-driver-downloads")
        require(cacheRoot.mkdirs() || cacheRoot.isDirectory) { "Could not create the driver cache." }
        val safeName = artifact.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        require(safeName.endsWith(".zip", ignoreCase = true)) { "The selected release is not a driver ZIP." }
        val target = File(cacheRoot, "${System.nanoTime()}-$safeName")
        try {
            val connection = (sourceUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            connection.connect()
            try {
                require(connection.responseCode in 200..299) {
                    "Driver download failed with HTTP ${connection.responseCode}."
                }
                val expected = connection.contentLengthLong
                require(expected <= 0L || expected <= MAX_DRIVER_DOWNLOAD_BYTES) {
                    "The driver download is unexpectedly large."
                }
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_DRIVER_DOWNLOAD_BYTES) {
                                "The driver download exceeded the safety limit."
                            }
                            output.write(buffer, 0, count)
                            onProgress(
                                if (expected > 0L) (copied.toFloat() / expected).coerceIn(0f, 1f)
                                else 0.5f,
                            )
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            require(target.length() > 0L) { "The driver download was empty." }
            installer(Uri.fromFile(target))
            onProgress(1f)
        } finally {
            target.delete()
        }
    }

    private fun fetchSource(
        source: DriverSource,
        installedNames: Set<String>,
    ): List<GpuDriverRelease> {
        val endpoint = URL("https://api.github.com/repos/${source.repository}/releases?per_page=20")
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            require(connection.responseCode in 200..299) {
                "${source.name}: HTTP ${connection.responseCode}"
            }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            var latestAssigned = false
            buildList {
                repeat(releases.length()) { index ->
                    val json = releases.optJSONObject(index) ?: return@repeat
                    val prerelease = json.optBoolean("prerelease")
                    val title = if (source.useTagName) json.optString("tag_name")
                    else json.optString("name").ifBlank { json.optString("tag_name") }
                    val assets = json.optJSONArray("assets") ?: JSONArray()
                    val artifacts = buildList {
                        repeat(assets.length()) { assetIndex ->
                            val asset = assets.optJSONObject(assetIndex) ?: return@repeat
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (name.endsWith(".zip", ignoreCase = true) && url.startsWith("https://")) {
                                add(
                                    GpuDriverArtifact(
                                        source = source.name,
                                        release = title,
                                        name = name,
                                        downloadUrl = url,
                                        sizeBytes = asset.optLong("size"),
                                        installed = name.lowercase() in installedNames,
                                    ),
                                )
                            }
                        }
                    }
                    if (artifacts.isEmpty()) return@repeat
                    val latest = !prerelease && !latestAssigned
                    if (latest) latestAssigned = true
                    add(
                        GpuDriverRelease(
                            source = source.name,
                            title = title.ifBlank { "Driver release" },
                            tag = json.optString("tag_name"),
                            description = json.optString("body").take(600),
                            publishedAt = json.optString("published_at"),
                            prerelease = prerelease,
                            latest = latest,
                            artifacts = artifacts,
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sourceOrder(name: String): Int = SOURCES.indexOfFirst { it.name == name }
        .takeIf { it >= 0 } ?: Int.MAX_VALUE

    private data class DriverSource(
        val name: String,
        val repository: String,
        val useTagName: Boolean = false,
    )

    private companion object {
        const val USER_AGENT = "Dualscreen-Launcher-Driver-Fetcher"
        const val MAX_DRIVER_DOWNLOAD_BYTES = 1_000_000_000L
        val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com")
        val SOURCES = listOf(
            DriverSource("Mr. Purple Turnip", "MrPurple666/purple-turnip"),
            DriverSource("GameHub Adreno 8xx", "crueter/GameHub-8Elite-Drivers"),
            DriverSource("KIMCHI Turnip", "K11MCH1/AdrenoToolsDrivers", useTagName = true),
            DriverSource("Weab-Chan Freedreno", "Weab-chan/freedreno_turnip-CI"),
            DriverSource("Whitebelyash Turnip", "whitebelyash/freedreno_turnip-CI"),
        )
    }
}

data class GpuDriverArtifact(
    val source: String,
    val release: String,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val installed: Boolean = false,
)

data class GpuDriverRelease(
    val source: String,
    val title: String,
    val tag: String,
    val description: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val latest: Boolean,
    val artifacts: List<GpuDriverArtifact>,
)
